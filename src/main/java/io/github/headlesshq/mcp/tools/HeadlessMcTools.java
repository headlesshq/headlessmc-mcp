package io.github.headlesshq.mcp.tools;

import io.github.headlesshq.mcp.hmc.CommandJob;
import io.github.headlesshq.mcp.hmc.CommandService;
import io.github.headlesshq.mcp.hmc.Interaction;
import io.quarkiverse.mcp.server.Elicitation;
import jakarta.inject.Inject;
import org.mcpjava.server.Cancellation;
import org.mcpjava.server.progress.Progress;
import org.mcpjava.server.tools.Tool;
import org.mcpjava.server.tools.ToolArg;
import org.mcpjava.server.tools.ToolResponse;

import java.time.Duration;

/**
 * Generic access to the HeadlessMc command line: everything HeadlessMc can do, e.g. installing versions,
 * managing accounts, Java, mods from Modrinth, profiles and servers.
 */
public class HeadlessMcTools {
    @Inject
    CommandService commandService;

    @Tool(name = "hmc", description = """
        Runs a HeadlessMc command line and returns its output, like the `headlessmc` CLI, without the leading `headlessmc`.
        Examples: `help`, `version list --remote`, `version install fabric 1.21.5`, `mod search sodium`,
        `mod add mod fabric-api fabric 1.21.5`, `server add paper 1.21.5`, `java list`, `account list`, `debug`.
        Append -h to any command for its usage. If a command asks for input, the user is asked through the MCP client,
        if the client supports it, otherwise pass all arguments. `login` logs into a Microsoft account in the browser of the user.
        Prefer the launch_client and start_server tools for launching, they keep the game controllable.
        If the command takes longer than timeoutSeconds it keeps running, poll it with hmc_job.""")
    public ToolResponse hmc(
        @ToolArg(description = "The command line, e.g. `version list`.") String command,
        @ToolArg(description = "How long to wait for the command.", defaultValue = "120") int timeoutSeconds,
        Elicitation elicitation,
        Progress progress,
        Cancellation cancellation
    ) {
        Interaction interaction = new Interaction(elicitation, progress, cancellation);
        return respond(commandService.execute(command, Duration.ofSeconds(timeoutSeconds), interaction));
    }

    @Tool(name = "hmc_job", description = "Returns the output and status of a HeadlessMc command started by the hmc tool.",
        annotations = @Tool.Annotations(readOnlyHint = true))
    public ToolResponse hmcJob(@ToolArg(description = "The job id returned by the hmc tool.") String jobId) {
        return commandService.get(jobId)
            .map(HeadlessMcTools::respond)
            .orElseGet(() -> ToolResponse.ofError("No job with id " + jobId));
    }

    static ToolResponse respond(CommandJob job) {
        String output = job.getOutput().strip();
        if (!job.isDone()) {
            return ToolResponse.ofText("Job " + job.getId() + " is still running, poll it with hmc_job. Output so far:\n" + output);
        }

        Integer exitCode = job.getExitCode();
        String text = (output.isEmpty() ? "(no output)" : output) + "\n[exit code " + exitCode + "]";
        return exitCode != null && exitCode == 0 ? ToolResponse.ofText(text) : ToolResponse.ofError(text);
    }

}
