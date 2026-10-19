package io.github.headlesshq.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.headlesshq.mcp.bridge.BridgeClient;
import io.github.headlesshq.mcp.instance.Instance;
import io.github.headlesshq.mcp.instance.InstanceManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.mcpjava.server.tools.ToolResponse;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * Executes HMC-Specifics commands inside a client through the bridge of the hmc-mcp-bridge mod.
 */
@ApplicationScoped
public class GameSupport {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration BRIDGE_TIMEOUT = Duration.ofSeconds(5);

    @Inject
    InstanceManager instanceManager;

    /**
     * Executes a command and turns the result into a tool response.
     */
    public ToolResponse execute(String instanceId, String command, long timeoutSeconds) throws InterruptedException {
        Optional<Instance> instance = instanceManager.get(instanceId);
        if (instance.isEmpty()) {
            return ToolResponse.ofError("No instance with id " + instanceId);
        }

        try {
            BridgeClient.Result result = bridge(instance.get()).command(command, timeoutSeconds);
            if (result.error() != null) {
                return ToolResponse.ofError(result.error() + "\n" + result.output());
            }

            String output = result.output().strip();
            if (result.timedOut()) {
                output += "\n(still running after " + timeoutSeconds + "s, later output only appears in instance_log)";
            }

            return ToolResponse.ofText(output.isEmpty() ? "(no output)" : output);
        } catch (IOException | IllegalStateException e) {
            return ToolResponse.ofError(e.getMessage());
        }
    }

    /**
     * Executes {@code mcp state} and parses the JSON it prints.
     */
    public JsonNode state(Instance instance) throws IOException, InterruptedException {
        BridgeClient.Result result = bridge(instance).command("mcp state", 10);
        if (result.error() != null) {
            throw new IOException(result.error());
        }

        return MAPPER.readTree(result.output().strip());
    }

    /**
     * Executes {@code mcp screen} and parses the JSON it prints.
     */
    public JsonNode screen(Instance instance) throws IOException, InterruptedException {
        BridgeClient.Result result = bridge(instance).command("mcp screen", 10);
        if (result.error() != null) {
            throw new IOException(result.error());
        }

        return MAPPER.readTree(result.output().strip());
    }

    /**
     * @return whether the client is in a world, or shows a screen with buttons, i.e. not a loading screen.
     */
    public boolean isInteractive(Instance instance, JsonNode state) throws IOException, InterruptedException {
        if (state.path("inWorld").asBoolean()) {
            return true;
        }

        if (state.path("screen").isNull()) {
            return false;
        }

        for (JsonNode element : screen(instance).path("elements")) {
            if ("button".equals(element.path("kind").asText())) {
                return true;
            }
        }

        return false;
    }

    /**
     * Waits until the client shows a menu with buttons (e.g. the main menu) or is in a world.
     *
     * @return a description of the state for the user.
     */
    public String awaitMainMenuOrWorld(Instance instance, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        if (instance.bridge(timeout).isEmpty()) {
            return instance.getStatus() == Instance.Status.RUNNING
                ? "The hmc-mcp-bridge did not start in time, check instance_log."
                : "The client exited before the hmc-mcp-bridge started, check instance_log.";
        }

        String last = "unknown";
        while (System.nanoTime() < deadline && instance.getStatus() == Instance.Status.RUNNING) {
            try {
                JsonNode state = state(instance);
                last = state.toString();
                if (isInteractive(instance, state)) {
                    return "Ready: " + last;
                }
            } catch (IOException e) {
                last = e.getMessage();
            }

            Thread.sleep(1000);
        }

        return "Not ready yet, last state: " + last;
    }

    private static BridgeClient bridge(Instance instance) throws InterruptedException {
        if (!instance.isControllable()) {
            throw new IllegalStateException("Instance " + instance.getId() + " can not be controlled, "
                                                + "only clients launched with launch_client and a modloader can, "
                                                + "or clients with HMC-Specifics and HMC-MCP-Bridge started by another launcher.");
        }

        if (instance.getStatus() != Instance.Status.RUNNING) {
            throw new IllegalStateException("Instance " + instance.getId() + " is not running.");
        }

        return instance.bridge(BRIDGE_TIMEOUT).orElseThrow(() -> new IllegalStateException(
            "The hmc-mcp-bridge of instance " + instance.getId() + " is not available (yet), check instance_log."));
    }

}
