package io.github.headlesshq.mcp.instance;

import io.github.headlesshq.headlessmc.config.Holder;
import io.github.headlesshq.headlessmc.console.Console;
import io.github.headlesshq.headlessmc.launcher.LauncherConfig;
import io.github.headlesshq.headlessmc.launcher.LifecycleService;
import io.github.headlesshq.headlessmc.launcher.ProcessLifecycle;
import io.github.headlesshq.headlessmc.launcher.process.ProcessLauncher;
import io.github.headlesshq.headlessmc.test.CommandTestService;
import io.github.headlesshq.headlessmc.test.TestConfig;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.Optional;

/**
 * Replaces HeadlessMc's {@link LifecycleService}: HeadlessMc launches the game with inherited IO and
 * blocks until it exits. Inherited IO would write the game log into the MCP channel, and the MCP server
 * has to stay responsive while games run, so processes are launched with piped IO and handed to the
 * {@link InstanceManager}. Before launching a client, the {@link ModInstaller} copies hmc-specifics and hmc-mcp-bridge
 * into its mods folder.
 */
@Alternative
@Priority(1)
@Singleton // LifecycleService has no no-args constructor, so it cannot be proxied
public class McpLifecycleService extends LifecycleService {
    private final InstanceManager instanceManager;
    private final ModInstaller modInstaller;
    private final CommandTestService testService;
    private final Holder<LauncherConfig> config;
    private final Holder<TestConfig> testConfig;
    private final Console console;

    @Inject
    public McpLifecycleService(
        CommandTestService testService,
        Holder<LauncherConfig> config,
        Holder<TestConfig> testConfig,
        Console console,
        InstanceManager instanceManager,
        ModInstaller modInstaller
    ) {
        super(testService, config, testConfig, console);
        this.instanceManager = instanceManager;
        this.modInstaller = modInstaller;
        this.testService = testService;
        this.config = config;
        this.testConfig = testConfig;
        this.console = console;
    }

    @Override
    public ProcessLifecycle wrap(ProcessLauncher launcher, int retries) {
        ProcessLifecycle lifecycle = new ProcessLifecycle(
            launcher, testService, config.get(), testConfig.get(), console, launcher.getGameDir()
        ) {
            @Override
            public Integer call() {
                LaunchRequest request = instanceManager.consumeExpected();
                Optional<Instance> running = instanceManager.findRunning(launcher.getGameDir());
                if (running.isPresent()) {
                    // two games in one directory overwrite each other's files, and the mods folder is shared
                    throw new IllegalStateException("Instance " + running.get().getId() + " (" + running.get().getName()
                        + ") is running in " + launcher.getGameDir() + " already. Launch with another profile.");
                }

                modInstaller.install(request, launcher.getGameDir()).forEach(console::write);
                Instance instance = instanceManager.register(request, launcher, launcher.launch(true));
                console.write("Launched " + instance.getName() + " as instance " + instance.getId()
                                  + " (pid " + instance.getPid() + ")");
                return 0;
            }
        };

        lifecycle.setRetries(retries);
        return lifecycle;
    }

}
