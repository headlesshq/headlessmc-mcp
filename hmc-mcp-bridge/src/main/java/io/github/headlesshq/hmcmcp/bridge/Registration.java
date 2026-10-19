package io.github.headlesshq.hmcmcp.bridge;

import me.earth.headlessmc.mc.metrics.MetricsFormatter;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Announces a bridge that was not started for the HeadlessMc MCP server, e.g. because the game was launched by another
 * launcher, so that MCP servers can attach to the game later.
 * <p>
 * The registration is the file {@code <registry>/<pid>.json} with the port of the bridge and its token, readable only
 * by the user. The registry is {@code headlessmc-mcp/instances} in the XDG state directory, which is also what the
 * MCP server uses: {@code $XDG_STATE_HOME}, else {@code %LOCALAPPDATA%\state} on Windows,
 * {@code ~/Library/Application Support} on macOS and {@code ~/.local/state} elsewhere.
 * Set {@link #REGISTRY} on both sides to use another directory.
 */
public final class Registration {
    public static final String REGISTRY = "hmc.mcp.registry";

    private final Path file;

    private Registration(Path file) {
        this.file = file;
    }

    /**
     * Writes the registration of a bridge.
     *
     * @param port      the port the bridge listens on.
     * @param token     the token of the bridge.
     * @param info      the info of the bridge (mod version, loader, protocol).
     * @param mcVersion the Minecraft version, or {@code null} if unknown.
     */
    public static Registration register(int port, String token, Map<String, Object> info, String mcVersion) throws IOException {
        long pid = pid();
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("protocol", info.get("protocol"));
        json.put("pid", pid);
        json.put("port", port);
        json.put("token", token);
        json.put("bridgeVersion", info.get("version"));
        json.put("loader", info.get("loader"));
        json.put("mcVersion", mcVersion);
        json.put("gameDir", gameDir().toString());
        json.put("started", System.currentTimeMillis());

        Path dir = directory();
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir);
            if (posix) {
                Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
            }
        }

        // the token must never be readable by other users, and MCP servers must never see a half written file
        Path file = dir.resolve(pid + ".json");
        Path tmp = dir.resolve(pid + ".json.tmp");
        Files.deleteIfExists(tmp);
        if (posix) {
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }

        Files.write(tmp, MetricsFormatter.toJson(json).getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return new Registration(file);
    }

    public void delete() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // MCP servers delete registrations of processes that no longer exist
        }
    }

    public Path getFile() {
        return file;
    }

    public static Path directory() {
        String registry = System.getProperty(REGISTRY);
        if (registry != null && !registry.trim().isEmpty()) {
            return Paths.get(registry).toAbsolutePath();
        }

        return stateHome().resolve("headlessmc-mcp").resolve("instances");
    }

    /**
     * The same as HeadlessMc's XdgBaseDirectoriesFactory, which the MCP server uses.
     */
    private static Path stateHome() {
        String xdg = System.getenv("XDG_STATE_HOME");
        if (xdg != null && !xdg.trim().isEmpty() && Paths.get(xdg).isAbsolute()) {
            return Paths.get(xdg);
        }

        Path home = Paths.get(System.getProperty("user.home"));
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String local = System.getenv("LOCALAPPDATA");
            Path base = local == null || local.trim().isEmpty() ? home.resolve("AppData").resolve("Local") : Paths.get(local);
            return base.resolve("state");
        }

        if (os.contains("mac")) {
            return home.resolve("Library").resolve("Application Support");
        }

        return home.resolve(".local").resolve("state");
    }

    /**
     * Launchers pass {@code --gameDir}, and most of them also start the game in it.
     * {@code sun.java.command} joins the arguments with spaces, so the argument is only used if it is a directory.
     */
    private static Path gameDir() {
        String command = System.getProperty("sun.java.command", "");
        String[] args = command.split(" ");
        for (int i = 0; i < args.length - 1; i++) {
            if ("--gameDir".equals(args[i])) {
                Path dir = Paths.get(args[i + 1]);
                if (Files.isDirectory(dir)) {
                    return dir.toAbsolutePath().normalize();
                }
            }
        }

        return Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    /**
     * ProcessHandle needs Java 9.
     */
    private static long pid() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        try {
            return Long.parseLong(name.substring(0, name.indexOf('@')));
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to get the pid from " + name, e);
        }
    }

}
