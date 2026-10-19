package io.github.headlesshq.mcp.tools;

import io.github.headlesshq.headlessmc.launcher.profile.Profile;
import io.github.headlesshq.mcp.hmc.CommandJob;
import io.github.headlesshq.mcp.hmc.CommandService;
import io.github.headlesshq.mcp.hmc.Interaction;
import io.github.headlesshq.mcp.instance.Instance;
import io.github.headlesshq.mcp.instance.ProfileSupport;
import io.quarkiverse.mcp.server.Elicitation;
import jakarta.inject.Inject;
import org.mcpjava.server.Cancellation;
import org.mcpjava.server.progress.Progress;
import org.mcpjava.server.tools.Tool;
import org.mcpjava.server.tools.ToolArg;
import org.mcpjava.server.tools.ToolResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Lists, creates and deletes HeadlessMc profiles, which give clients their own game directories.
 */
public class ProfileTools {
    @Inject
    CommandService commandService;

    @Inject
    ProfileSupport profileSupport;

    @Tool(name = "list_profiles", description = """
        Lists the HeadlessMc profiles: name, version, game directory and the instance running it, if any.
        A profile is a client setup with its own game directory (mods, config, worlds), launch it with launch_client.
        Servers are listed with the hmc tool: `server list`.""",
        annotations = @Tool.Annotations(readOnlyHint = true))
    public String listProfiles() {
        List<Profile> profiles = profileSupport.list();
        if (profiles.isEmpty()) {
            return "No profiles. Create one with create_profile, or pass a profile name to launch_client.";
        }

        return profiles.stream()
            .sorted(Comparator.comparing(Profile::name))
            .map(profile -> profile.name() + " [" + profile.version().toString(" ") + "] " + profile.path()
                + profileSupport.usedBy(profile.path()).map(instance -> " (running as instance " + instance.getId() + ")").orElse(""))
            .collect(Collectors.joining("\n"));
    }

    @Tool(name = "create_profile", description = """
        Creates a profile with its own game directory and installs its version.
        Add mods with the hmc tool: `mod add mod <slug> <profile>`, then launch it with launch_client.""")
    public ToolResponse createProfile(
        @ToolArg(description = "Name of the profile: letters, digits, '.', '_' and '-'.") String name,
        @ToolArg(description = "Minecraft version, e.g. 1.21.5.") String version,
        @ToolArg(description = "Modloader: fabric, forge, neoforge or vanilla.", defaultValue = "fabric") String loader,
        @ToolArg(description = "How long to wait for installing the version.", defaultValue = "600") int timeoutSeconds,
        Elicitation elicitation,
        Progress progress,
        Cancellation cancellation
    ) {
        if (!ProfileSupport.NAME.matcher(name).matches()) {
            return ToolResponse.ofError("Invalid profile name " + name + ", use letters, digits, '.', '_' and '-'.");
        }

        if (profileSupport.get(name).isPresent()) {
            return ToolResponse.ofError("Profile " + name + " exists already.");
        }

        String modloader = loader == null || loader.isBlank() || "vanilla".equalsIgnoreCase(loader) ? null : loader.toLowerCase(Locale.ROOT);
        String line = InstanceTools.createProfileLine(name, profileSupport.managedDir(name), modloader, version);
        CommandJob job = commandService.execute(line, Duration.ofSeconds(timeoutSeconds), new Interaction(elicitation, progress, cancellation));
        return HeadlessMcTools.respond(job);
    }

    @Tool(name = "delete_profile", description = """
        Deletes a profile. Its game directory (worlds, mods, config) is only deleted if deleteFiles is true,
        and only if the MCP server created it. Ask the user before deleting files.""",
        annotations = @Tool.Annotations(destructiveHint = true))
    public ToolResponse deleteProfile(
        @ToolArg(description = "Name of the profile.") String name,
        @ToolArg(description = "Also delete the game directory of the profile.", defaultValue = "false") boolean deleteFiles,
        Elicitation elicitation
    ) throws IOException {
        Optional<Profile> profile = profileSupport.get(name);
        if (profile.isEmpty()) {
            return ToolResponse.ofError("No profile named " + name);
        }

        Optional<Instance> running = profileSupport.usedBy(profile.get().path());
        if (running.isPresent()) {
            return ToolResponse.ofError("Profile " + name + " is used by the running instance " + running.get().getId() + ", stop it first.");
        }

        Path dir = profile.get().path().toAbsolutePath().normalize();
        boolean managed = dir.startsWith(profileSupport.getManagedRoot()) && !dir.equals(profileSupport.getManagedRoot());
        if (deleteFiles && !managed) {
            return ToolResponse.ofError("The game directory " + dir + " was not created by the MCP server, it is not deleted."
                                            + " Delete the profile without deleteFiles.");
        }

        if (deleteFiles) {
            Optional<Boolean> confirmed = new Interaction(elicitation, null, null)
                .confirm("Delete the profile " + name + " and all of its files (worlds, mods, config) in " + dir + "?", "Delete");
            if (confirmed.isPresent() && !confirmed.get()) {
                return ToolResponse.ofError("The user did not confirm deleting the files of profile " + name + ", nothing was deleted.");
            }
        }

        profileSupport.remove(profile.get());
        if (deleteFiles && Files.exists(dir)) {
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }

            return ToolResponse.ofText("Deleted profile " + name + " and " + dir);
        }

        return ToolResponse.ofText("Deleted profile " + name + (Files.exists(dir) ? ", its files are still in " + dir : ""));
    }

}
