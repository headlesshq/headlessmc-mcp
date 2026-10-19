package io.github.headlesshq.mcp.instance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.headlesshq.headlessmc.files.XdgBaseDirectories;
import io.github.headlesshq.headlessmc.launcher.process.McProcess;
import io.github.headlesshq.headlessmc.launcher.process.ProcessLauncher;
import io.github.headlesshq.mcp.bridge.BridgeClient;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Registry of the Minecraft processes launched by the MCP server.
 * <p>
 * It also attaches to clients that other launchers started with hmc-specifics and hmc-mcp-bridge installed.
 * Their bridge writes a registration ({@code <pid>.json} with port and token) to {@link #registry}, which
 * is checked whenever instances are listed or looked up, see {@link #refreshAttached()}.
 * <p>
 * Tools that launch something register a {@link LaunchRequest} with {@link #expect(LaunchRequest)} before they run
 * the HeadlessMc command. HeadlessMc commands run one after another on a single thread, so the
 * {@link McpLifecycleService} can pick up the request when HeadlessMc launches the process.
 */
@ApplicationScoped
public class InstanceManager {
    private static final Logger LOG = Logger.getLogger(InstanceManager.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /**
     * The version of the bridge protocol, see {@code io.github.headlesshq.hmcmcp.bridge.Bridge.PROTOCOL_VERSION}.
     */
    private static final int BRIDGE_PROTOCOL = 1;

    private final Map<String, Instance> instances = new LinkedHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private @Nullable LaunchRequest expected;
    private @Nullable Instance lastLaunched;
    /**
     * Where hmc-mcp-bridge registers games that have not been launched by an MCP server,
     * see {@code io.github.headlesshq.hmcmcp.bridge.Registration}.
     */
    @Nullable Path registry;

    @Inject
    void init(XdgBaseDirectories dirs, @ConfigProperty(name = "hmc.mcp.registry") Optional<String> configured) {
        registry = configured.filter(dir -> !dir.isBlank())
            .map(Path::of)
            .orElseGet(() -> dirs.stateHome().resolve("headlessmc-mcp").resolve("instances"))
            .toAbsolutePath();
    }

    /**
     * Announces that the next HeadlessMc command will launch a process described by the request.
     */
    public synchronized void expect(LaunchRequest request) {
        this.expected = request;
        this.lastLaunched = null;
    }

    /**
     * @return the instance launched for the request passed to {@link #expect(LaunchRequest)}, if it has been launched.
     */
    public synchronized Optional<Instance> takeLaunched() {
        Instance instance = lastLaunched;
        lastLaunched = null;
        expected = null;
        return Optional.ofNullable(instance);
    }

    synchronized LaunchRequest consumeExpected() {
        LaunchRequest request = expected == null ? LaunchRequest.unknown() : expected;
        expected = null;
        return request;
    }

    synchronized Instance register(LaunchRequest request, ProcessLauncher launcher, McProcess process) {
        Instance instance = new Instance(
            String.valueOf(ids.incrementAndGet()), launcher.getId(), launcher.getGameDir().toAbsolutePath(), request, process
        );
        instances.put(instance.getId(), instance);
        lastLaunched = instance;
        return instance;
    }

    public synchronized List<Instance> list() {
        refreshAttached();
        return new ArrayList<>(instances.values());
    }

    /**
     * @return the running instance with the given game directory.
     */
    public synchronized Optional<Instance> findRunning(Path gameDir) {
        refreshAttached();
        Path normalized = gameDir.toAbsolutePath().normalize();
        return instances.values().stream()
            .filter(instance -> instance.getStatus() == Instance.Status.RUNNING)
            .filter(instance -> instance.getGameDir().normalize().equals(normalized))
            .findFirst();
    }

    public synchronized Optional<Instance> get(String id) {
        if (!instances.containsKey(id)) {
            refreshAttached();
        }

        return Optional.ofNullable(instances.get(id));
    }

    /**
     * Attaches to the clients that registered their bridge since the last call, and deletes registrations of processes
     * that no longer exist.
     */
    synchronized void refreshAttached() {
        if (registry == null || !Files.isDirectory(registry)) {
            return;
        }

        try (DirectoryStream<Path> files = Files.newDirectoryStream(registry, "*.json")) {
            for (Path file : files) {
                attach(file);
            }
        } catch (IOException e) {
            LOG.debugf(e, "Failed to list %s", registry);
        }
    }

    private void attach(Path file) {
        JsonNode json;
        try {
            json = MAPPER.readTree(file.toFile());
        } catch (IOException e) {
            LOG.debugf(e, "Failed to read %s", file);
            return;
        }

        long pid = json.path("pid").asLong(-1);
        if (pid == ProcessHandle.current().pid()) {
            return;
        }

        Optional<ProcessHandle> handle = ProcessHandle.of(pid).filter(ProcessHandle::isAlive);
        if (handle.isEmpty()) {
            // the game crashed or was killed before it could delete its registration
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                LOG.debugf(e, "Failed to delete %s", file);
            }

            return;
        }

        Optional<Instance> known = instances.values().stream()
            .filter(instance -> instance.getPid() == pid && instance.getStatus() == Instance.Status.RUNNING)
            .findFirst();
        if (known.isPresent() && known.get().isControllable() || json.path("protocol").asInt() != BRIDGE_PROTOCOL) {
            return;
        }

        String token = json.path("token").asText("");
        BridgeClient bridge = new BridgeClient(json.path("port").asInt(), token);
        if (token.isEmpty() || !bridge.isAlive()) {
            return;
        }

        if (known.isPresent()) {
            // launched by us without a token, e.g. with the hmc tool, into a game directory that has the mods
            known.get().useBridge(bridge);
            return;
        }

        String mcVersion = text(json, "mcVersion");
        // the modloader as HeadlessMc names it
        String loader = Optional.ofNullable(text(json, "loader")).map(l -> l.toLowerCase(Locale.ROOT)).orElse(null);
        Path gameDir = Path.of(json.path("gameDir").asText(".")).toAbsolutePath().normalize();
        String name = (loader == null ? "client" : loader) + (mcVersion == null ? "" : "-" + mcVersion);
        Instance instance = new Instance(String.valueOf(ids.incrementAndGet()), name, gameDir,
            new LaunchRequest(Instance.Kind.CLIENT, mcVersion, loader, token, null, null), handle.get(), bridge);
        instances.put(instance.getId(), instance);
        LOG.infof("Attached to %s (pid %d) as instance %s", name, pid, instance.getId());
    }

    private static @Nullable String text(JsonNode json, String field) {
        JsonNode value = json.path(field);
        return value.isTextual() && !value.asText().isBlank() && !"unknown".equals(value.asText()) ? value.asText() : null;
    }

    /**
     * Removes an instance that is no longer running.
     *
     * @return {@code true} if the instance has been removed.
     */
    public synchronized boolean remove(String id) {
        Instance instance = instances.get(id);
        if (instance == null || instance.getStatus() == Instance.Status.RUNNING) {
            return false;
        }

        instances.remove(id);
        return true;
    }

    private synchronized List<Instance> instances() {
        return new ArrayList<>(instances.values());
    }

    void onShutdown(@Observes ShutdownEvent event) {
        // the processes would lose their stdout/stdin with us anyway, attached games belong to the user
        for (Instance instance : instances()) {
            if (instance.getStatus() == Instance.Status.RUNNING && !instance.isAttached()) {
                instance.stop();
            }
        }
    }

}
