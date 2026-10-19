package io.github.headlesshq.hmcmcp.bridge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.headlesshq.hmcmcp.McpConfig;
import me.earth.headlessmc.mc.commands.MinecraftContext;
import me.earth.headlessmc.mc.console.CapturingConsole;
import me.earth.headlessmc.mc.metrics.MetricsFormatter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A small HTTP server on the loopback interface, through which the HeadlessMc MCP server executes
 * HMC-Specifics commands and gets their output, separated from the game log:
 * <ul>
 *     <li>{@code GET /health}: always answers {@code ok}, without authentication.</li>
 *     <li>{@code GET /info}: version of the mod and the modloader, as JSON.</li>
 *     <li>{@code POST /command?timeout=<seconds>}: executes the command line in the body, answers
 *     {@code {"output": "...", "timedOut": false, "error": null}}.</li>
 * </ul>
 * Every request except {@code /health} needs the header {@code Authorization: Bearer <token>}.
 * Browsers can not send that header to another origin without a CORS preflight, which is never allowed.
 */
public final class Bridge {
    public static final int PROTOCOL_VERSION = 1;
    private static final Logger LOGGER = Logger.getLogger(Bridge.class.getName());
    private static final int MAX_COMMAND_LENGTH = 64 * 1024;
    private static final long DEFAULT_TIMEOUT_SECONDS = 30L;
    private static final long MAX_TIMEOUT_SECONDS = 600L;

    private final HttpServer server;
    private final ExecutorService executor;
    private final byte[] token;
    private final Map<String, Object> info;
    private final AtomicBoolean stopped = new AtomicBoolean();

    private Bridge(HttpServer server, ExecutorService executor, String token, Map<String, Object> info) {
        this.server = server;
        this.executor = executor;
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.info = info;
    }

    public static Bridge start(McpConfig config, String loader, String version) throws IOException {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", "hmc-mcp-bridge");
        info.put("version", version);
        info.put("loader", loader);
        info.put("protocol", PROTOCOL_VERSION);

        HttpServer server = HttpServer.create(new InetSocketAddress(config.getHost(), config.getPort()), 0);
        ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "hmc-mcp-bridge");
            thread.setDaemon(true);
            return thread;
        });

        Bridge bridge = new Bridge(server, executor, config.getToken(), info);
        server.setExecutor(executor);
        server.createContext("/health", exchange -> bridge.handle(exchange, false, bridge::health));
        server.createContext("/info", exchange -> bridge.handle(exchange, true, bridge::info));
        server.createContext("/command", exchange -> bridge.handle(exchange, true, bridge::command));
        startDaemon(server);
        if (config.getPortFile() != null) {
            writePortFile(Paths.get(config.getPortFile()), server.getAddress().getPort());
        }

        return bridge;
    }

    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }

        server.stop(0);
        executor.shutdownNow();
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    /**
     * @return name, version and loader of the mod, and the protocol version of the bridge.
     */
    public Map<String, Object> getInfo() {
        return info;
    }

    public String getUrl() {
        InetSocketAddress address = server.getAddress();
        return "http://" + address.getHostString() + ":" + address.getPort();
    }

    private void health(HttpExchange exchange) throws IOException {
        send(exchange, 200, "text/plain; charset=utf-8", "ok\n");
    }

    private void info(HttpExchange exchange) throws IOException {
        sendJson(exchange, 200, info);
    }

    private void command(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            send(exchange, 405, "text/plain; charset=utf-8", "Use POST\n");
            return;
        }

        String line = readBody(exchange).trim();
        Map<String, Object> result = new LinkedHashMap<>();
        MinecraftContext context = getContext();
        if (context == null) {
            result.put("error", "HMC-Specifics has not been initialized yet.");
            sendJson(exchange, 503, result);
            return;
        }

        CapturingConsole output = new CapturingConsole();
        boolean timedOut = false;
        String error = null;
        if (!line.isEmpty()) {
            try {
                context.execute(line, output).get(getTimeout(exchange), TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                timedOut = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                error = "Interrupted";
            } catch (ExecutionException e) {
                error = String.valueOf(e.getCause());
            }
        }

        result.put("output", output.getOutput());
        result.put("timedOut", timedOut);
        result.put("error", error);
        sendJson(exchange, 200, result);
    }

    private void handle(HttpExchange exchange, boolean authenticate, Handler handler) throws IOException {
        try {
            if (authenticate && !isAuthorized(exchange.getRequestHeaders().getFirst("Authorization"))) {
                send(exchange, 401, "text/plain; charset=utf-8", "Unauthorized\n");
                return;
            }

            handler.handle(exchange);
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Failed to handle " + exchange.getRequestURI(), t);
            try {
                send(exchange, 500, "text/plain; charset=utf-8", t + "\n");
            } catch (IOException ignored) {
                // response has already been sent
            }
        } finally {
            exchange.close();
        }
    }

    private boolean isAuthorized(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }

        byte[] given = header.substring("Bearer ".length()).trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(given, token);
    }

    private static long getTimeout(HttpExchange exchange) {
        String query = exchange.getRequestURI().getQuery();
        if (query != null) {
            for (String parameter : query.split("&")) {
                if (parameter.startsWith("timeout=")) {
                    try {
                        long timeout = Long.parseLong(parameter.substring("timeout=".length()));
                        return Math.max(1L, Math.min(timeout, MAX_TIMEOUT_SECONDS));
                    } catch (NumberFormatException ignored) {
                        // use the default
                    }
                }
            }
        }

        return DEFAULT_TIMEOUT_SECONDS;
    }

    private static MinecraftContext getContext() {
        try {
            return MinecraftContext.getInstance();
        } catch (LinkageError e) { // an HMC-Specifics version without the method
            return null;
        }
    }

    /**
     * The HTTP-Dispatcher thread of the server inherits the daemon status of the thread calling {@link HttpServer#start()}.
     * It must be a daemon, otherwise it keeps the JVM alive after the game has stopped, and shutdown hooks never run.
     */
    private static void startDaemon(HttpServer server) throws IOException {
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        Thread starter = new Thread(() -> {
            try {
                server.start();
            } catch (RuntimeException e) {
                failure.set(e);
            }
        }, "hmc-mcp-bridge-start");
        starter.setDaemon(true);
        starter.start();
        try {
            starter.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            server.stop(0);
            throw new IOException("Interrupted while starting the bridge", e);
        }

        if (failure.get() != null) {
            throw new IOException("Failed to start the bridge", failure.get());
        }
    }

    private static void writePortFile(Path file, int port) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        // the MCP server polls for the file, it must never see a half written one
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, String.valueOf(port).getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try (InputStream is = exchange.getRequestBody()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = is.read(buffer)) != -1 && body.size() <= MAX_COMMAND_LENGTH) {
                body.write(buffer, 0, read);
            }
        }

        return new String(body.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void sendJson(HttpExchange exchange, int code, Map<String, Object> body) throws IOException {
        send(exchange, code, "application/json; charset=utf-8", MetricsFormatter.toJson(body));
    }

    private static void send(HttpExchange exchange, int code, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

}
