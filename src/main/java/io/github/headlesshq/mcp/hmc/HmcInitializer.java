package io.github.headlesshq.mcp.hmc;

import io.github.headlesshq.headlessmc.config.ConfigFileProvider;
import io.github.headlesshq.headlessmc.files.AppFiles;
import io.github.headlesshq.headlessmc.files.FileService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Creates HeadlessMc's directories and config file on startup,
 * like {@code HeadlessMcApplication} does for the command line application.
 */
@ApplicationScoped
public class HmcInitializer {
    private static final Logger LOG = Logger.getLogger(HmcInitializer.class);

    private final ConfigFileProvider configFileProvider;
    private final FileService fileService;
    private final AppFiles appFiles;

    @Inject
    public HmcInitializer(ConfigFileProvider configFileProvider, FileService fileService, AppFiles appFiles) {
        this.configFileProvider = configFileProvider;
        this.fileService = fileService;
        this.appFiles = appFiles;
    }

    void onStart(@Observes StartupEvent event) {
        try {
            Files.createDirectories(appFiles.getDataDir());
            Files.createDirectories(appFiles.getConfigDir());
            fileService.ensureFileExists(
                configFileProvider.getConfigFile(),
                ("# === HeadlessMc Config ===" + System.lineSeparator()).getBytes(StandardCharsets.UTF_8)
            );
        } catch (IOException e) {
            LOG.error("Failed to initialize HeadlessMc files", e);
        }

        LOG.infof("HeadlessMc data directory: %s", appFiles.getDataDir().toAbsolutePath());
    }

}
