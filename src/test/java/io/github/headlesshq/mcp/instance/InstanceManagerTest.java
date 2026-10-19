package io.github.headlesshq.mcp.instance;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InstanceManagerTest {
    @TempDir
    Path tmp;

    private HttpServer bridge;
    private Process game;

    @AfterEach
    void stop() {
        if (bridge != null) {
            bridge.stop(0);
        }

        if (game != null) {
            game.destroyForcibly();
        }
    }

    @Test
    void attachesToRegisteredClient() throws Exception {
        Path gameDir = Files.createDirectories(tmp.resolve("game"));
        Files.createDirectories(gameDir.resolve("logs"));
        Files.writeString(gameDir.resolve("logs").resolve("latest.log"), "[main/INFO]: Loading Minecraft\n[main/INFO]: Sound engine st");
        startBridge("secret");

        Path registry = Files.createDirectories(tmp.resolve("registry"));
        long pid = startGame();
        Files.writeString(registry.resolve(pid + ".json"), """
            {"protocol":1,"pid":%d,"port":%d,"token":"secret","bridgeVersion":"0.1.0","loader":"Fabric","mcVersion":"1.21.8","gameDir":"%s"}"""
            .formatted(pid, bridge.getAddress().getPort(), gameDir.toString().replace("\\", "\\\\")));

        InstanceManager manager = new InstanceManager();
        manager.registry = registry;
        List<Instance> instances = manager.list();
        assertEquals(1, instances.size());
        Instance instance = instances.getFirst();
        assertTrue(instance.isAttached());
        assertTrue(instance.isControllable());
        assertEquals(pid, instance.getPid());
        assertEquals("fabric-1.21.8", instance.getName());
        assertEquals(gameDir.toAbsolutePath().normalize(), instance.getGameDir());
        assertEquals("1.21.8", instance.getRequest().mcVersion());
        assertThrows(IllegalStateException.class, () -> instance.sendInput("stop"));

        // registered once, not again on every refresh
        assertEquals(1, manager.list().size());
        assertSame(instance, manager.get(instance.getId()).orElseThrow());

        assertEquals("pong", instance.bridge(Duration.ZERO).orElseThrow().command("ping", 5).output());

        // complete lines only, the rest of the second line follows later
        assertTrue(instance.waitForLine(java.util.regex.Pattern.compile("Loading Minecraft"), 0, Duration.ofSeconds(5)).isPresent());
        Files.writeString(gameDir.resolve("logs").resolve("latest.log"), "arted\n", java.nio.file.StandardOpenOption.APPEND);
        assertEquals("[main/INFO]: Sound engine started",
            instance.waitForLine(java.util.regex.Pattern.compile("Sound engine"), 0, Duration.ofSeconds(5)).orElseThrow().text());
    }

    @Test
    void deletesRegistrationsOfExitedProcesses() throws Exception {
        Process exited = new ProcessBuilder("java", "-version").redirectErrorStream(true).start();
        exited.getInputStream().transferTo(OutputStream.nullOutputStream());
        exited.waitFor();

        Path registry = Files.createDirectories(tmp.resolve("registry"));
        Path file = registry.resolve(exited.pid() + ".json");
        Files.writeString(file, """
            {"protocol":1,"pid":%d,"port":1,"token":"secret","gameDir":"."}""".formatted(exited.pid()));

        InstanceManager manager = new InstanceManager();
        manager.registry = registry;
        assertTrue(manager.list().isEmpty());
        assertFalse(Files.exists(file));
    }

    @Test
    void ignoresRegistrationsWithoutBridge() throws Exception {
        Path registry = Files.createDirectories(tmp.resolve("registry"));
        long pid = startGame();
        Path file = registry.resolve(pid + ".json");
        // nothing listens on port 1, e.g. a reused pid
        Files.writeString(file, """
            {"protocol":1,"pid":%d,"port":1,"token":"secret","gameDir":"."}""".formatted(pid));

        InstanceManager manager = new InstanceManager();
        manager.registry = registry;
        assertTrue(manager.list().isEmpty());
        assertTrue(Files.exists(file));
    }

    /**
     * Stands in for the game process of the registration.
     */
    private long startGame() throws IOException {
        Path sleep = tmp.resolve("Sleep.java");
        Files.writeString(sleep, "class Sleep { public static void main(String[] args) throws Exception { Thread.sleep(60_000); } }");
        String java = ProcessHandle.current().info().command().orElse("java");
        game = new ProcessBuilder(java, sleep.toString()).start();
        return game.pid();
    }

    private void startBridge(String token) throws IOException {
        bridge = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        bridge.createContext("/health", exchange -> respond(exchange, 200, "ok\n"));
        bridge.createContext("/command", exchange -> {
            if (!("Bearer " + token).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                respond(exchange, 401, "Unauthorized\n");
                return;
            }

            String line = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            respond(exchange, 200, "{\"output\":\"" + ("ping".equals(line) ? "pong" : "") + "\",\"timedOut\":false,\"error\":null}");
        });
        bridge.start();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

}
