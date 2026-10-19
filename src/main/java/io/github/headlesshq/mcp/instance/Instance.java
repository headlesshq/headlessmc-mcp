package io.github.headlesshq.mcp.instance;

import io.github.headlesshq.headlessmc.launcher.process.McProcess;
import io.github.headlesshq.mcp.bridge.BridgeClient;
import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A Minecraft client or server launched by the MCP server, with piped IO.
 * Keeps a bounded history of its output, allows writing to its stdin (e.g. server console commands)
 * and, for clients with hmc-specifics and hmc-mcp-bridge, executes commands inside the game through the bridge.
 * <p>
 * An attached instance is a client that another launcher started, with hmc-specifics and hmc-mcp-bridge installed,
 * which registered its bridge (see {@link InstanceManager}). It has no stdin, its output is read from {@code logs/latest.log}.
 */
public final class Instance {
    private static final Logger LOG = Logger.getLogger(Instance.class);
    static final int MAX_HISTORY = 10_000;

    public enum Kind {
        CLIENT, SERVER, UNKNOWN
    }

    public enum Status {
        RUNNING, EXITED, STOPPED
    }

    private final Deque<Line> history = new ArrayDeque<>();
    private final String id;
    private final String name;
    private final Path gameDir;
    private final LaunchRequest request;
    private final @Nullable Process process;
    private final ProcessHandle handle;
    private final boolean attached;
    private final long started;

    private volatile Status status = Status.RUNNING;
    private volatile @Nullable Integer exitCode;
    private volatile @Nullable BridgeClient bridge;
    private long lineCounter;

    Instance(String id, String name, Path gameDir, LaunchRequest request, McProcess mcProcess) {
        this.id = id;
        this.name = name;
        this.gameDir = gameDir;
        this.request = request;
        Process process = mcProcess.process()
            .orElseThrow(() -> new IllegalStateException("Process " + mcProcess.id() + " did not start a process"));
        this.process = process;
        this.handle = process.toHandle();
        this.attached = false;
        this.started = System.currentTimeMillis();
        pump(process.getInputStream(), "out");
        pump(process.getErrorStream(), "err");
        process.onExit().thenAccept(exited -> onExit(exited.exitValue()));
    }

    Instance(String id, String name, Path gameDir, LaunchRequest request, ProcessHandle handle, BridgeClient bridge) {
        this.id = id;
        this.name = name;
        this.gameDir = gameDir;
        this.request = request;
        this.process = null;
        this.handle = handle;
        this.attached = true;
        this.bridge = bridge;
        this.started = handle.info().startInstant().map(Instant::toEpochMilli).orElseGet(System::currentTimeMillis);
        tail(gameDir.resolve("logs").resolve("latest.log"));
        // the exit code of a process we did not start is not available
        handle.onExit().thenAccept(exited -> onExit(null));
    }

    private void pump(InputStream stream, String type) {
        Thread.ofVirtual().name("hmc-mcp-" + id + "-" + type).start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    addLine(type, line);
                }
            } catch (IOException e) {
                LOG.debugf(e, "Output stream %s of %s closed", type, id);
            }
        });
    }

    /**
     * Follows the log file of an attached instance, like {@code tail -f}.
     */
    private void tail(Path log) {
        Thread.ofVirtual().name("hmc-mcp-" + id + "-log").start(() -> {
            long position = 0;
            ByteArrayOutputStream pending = new ByteArrayOutputStream();
            while (status == Status.RUNNING) {
                try {
                    if (Files.isRegularFile(log)) {
                        long size = Files.size(log);
                        if (size < position) { // the log has been replaced
                            position = 0;
                            pending.reset();
                        }

                        if (size > position) {
                            try (SeekableByteChannel channel = Files.newByteChannel(log)) {
                                channel.position(position);
                                ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
                                int read;
                                while ((read = channel.read(buffer)) > 0) {
                                    pending.write(buffer.array(), 0, read);
                                    position += read;
                                    buffer.clear();
                                }
                            }

                            // only complete lines, the rest of a line might be written later
                            byte[] data = pending.toByteArray();
                            int start = 0;
                            for (int i = 0; i < data.length; i++) {
                                if (data[i] == '\n') {
                                    addLine("log", new String(data, start, i - start, StandardCharsets.UTF_8).stripTrailing());
                                    start = i + 1;
                                }
                            }

                            pending.reset();
                            pending.write(data, start, data.length - start);
                        }
                    }

                    Thread.sleep(500);
                } catch (IOException e) {
                    LOG.debugf(e, "Failed to read %s", log);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
    }

    private void onExit(@Nullable Integer code) {
        exitCode = code;
        if (status == Status.RUNNING) {
            status = Status.EXITED;
        }

        addLine("hmc", code == null ? "Process exited" : "Process exited with code " + code);
        if (request.portFile() != null) {
            try {
                Files.deleteIfExists(request.portFile());
            } catch (IOException e) {
                LOG.debugf(e, "Failed to delete %s", request.portFile());
            }
        }
    }

    void addLine(String type, String text) {
        synchronized (history) {
            history.addLast(new Line(++lineCounter, type, text));
            while (history.size() > MAX_HISTORY) {
                history.removeFirst();
            }

            history.notifyAll();
        }
    }

    /**
     * Writes a line to the stdin of the process, e.g. a server console command.
     * For clients this reaches hmc-specifics as well, but {@link #bridge(Duration)} separates the output of commands.
     */
    public void sendInput(String input) throws IOException {
        if (status != Status.RUNNING) {
            throw new IllegalStateException("Instance " + id + " is not running");
        }

        if (process == null) {
            throw new IllegalStateException("Instance " + id + " has been launched by another launcher, it has no console");
        }

        OutputStream stdin = process.getOutputStream();
        synchronized (this) {
            stdin.write((input + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        }

        addLine("in", input);
    }

    /**
     * Waits for the bridge of the hmc-mcp-bridge mod, which starts once hmc-specifics has initialized.
     *
     * @param timeout how long to wait for the bridge.
     * @return the bridge, or empty if this instance can not be controlled or the bridge did not start in time.
     */
    public Optional<BridgeClient> bridge(Duration timeout) throws InterruptedException {
        BridgeClient bridge = this.bridge;
        if (bridge != null || request.portFile() == null || request.token() == null) {
            return Optional.ofNullable(bridge);
        }

        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            if (Files.exists(request.portFile())) {
                try {
                    int port = Integer.parseInt(Files.readString(request.portFile()).trim());
                    bridge = new BridgeClient(port, request.token());
                    this.bridge = bridge;
                    return Optional.of(bridge);
                } catch (IOException | NumberFormatException e) {
                    LOG.debugf(e, "Failed to read %s", request.portFile());
                }
            }

            if (status != Status.RUNNING) {
                return Optional.empty();
            }

            Thread.sleep(250);
        } while (System.nanoTime() < deadline);
        return Optional.empty();
    }

    void useBridge(BridgeClient bridge) {
        this.bridge = bridge;
    }

    /**
     * Waits until a line of output matches the pattern.
     *
     * @param pattern  the pattern to find in a line.
     * @param afterLine only lines with a number greater than this are considered.
     * @param timeout  how long to wait.
     * @return the matching line, or empty if none appeared in time or the process exited.
     */
    public Optional<Line> waitForLine(Pattern pattern, long afterLine, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        long checked = afterLine;
        synchronized (history) {
            while (true) {
                for (Line line : history) {
                    if (line.n() > checked) {
                        checked = line.n();
                        if (pattern.matcher(line.text()).find()) {
                            return Optional.of(line);
                        }
                    }
                }

                long remaining = deadline - System.nanoTime();
                if (remaining <= 0 || status != Status.RUNNING) {
                    return Optional.empty();
                }

                history.wait(Math.max(1, remaining / 1_000_000));
            }
        }
    }

    /**
     * Asks the process to exit (SIGTERM), Minecraft servers save the world on shutdown.
     */
    public void stop() {
        status = Status.STOPPED;
        if (process == null) {
            handle.destroy();
        } else {
            process.destroy();
        }
    }

    public void kill() {
        status = Status.STOPPED;
        if (process == null) {
            handle.destroyForcibly();
        } else {
            process.destroyForcibly();
        }
    }

    /**
     * @return the newest crash report written since this instance was started.
     */
    public Optional<Path> findCrashReport() {
        Path dir = gameDir.resolve("crash-reports");
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }

        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(file -> file.toFile().lastModified() >= started)
                .max(Comparator.comparingLong(file -> file.toFile().lastModified()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public List<Line> getHistory() {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    public long getLastLineNumber() {
        synchronized (history) {
            return lineCounter;
        }
    }

    public boolean isControllable() {
        return bridge != null || request.token() != null && request.portFile() != null;
    }

    /**
     * @return whether another launcher started this instance, see {@link InstanceManager}.
     */
    public boolean isAttached() {
        return attached;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Path getGameDir() {
        return gameDir;
    }

    public LaunchRequest getRequest() {
        return request;
    }

    public long getStarted() {
        return started;
    }

    public long getPid() {
        return handle.pid();
    }

    public Status getStatus() {
        return status;
    }

    public @Nullable Integer getExitCode() {
        return exitCode;
    }

    /**
     * A line of output.
     *
     * @param n    sequence number of the line within the instance.
     * @param type {@code out}, {@code err}, {@code in} (input written by the MCP server), {@code log} (latest.log of an
     *             attached instance) or {@code hmc} (status messages).
     * @param text the text of the line.
     */
    public record Line(long n, String type, String text) {
    }

}
