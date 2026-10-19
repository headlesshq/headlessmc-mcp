package io.github.headlesshq.mcp.instance;

import io.github.headlesshq.headlessmc.files.AppFiles;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Copies the mods that make a client controllable into its mods folder:
 * hmc-specifics for the Minecraft version and modloader, downloaded from its GitHub releases,
 * and the version independent hmc-mcp-bridge mod bundled with the server.
 */
@ApplicationScoped
public class ModInstaller {
    private static final Logger LOG = Logger.getLogger(ModInstaller.class);
    private static final String BUNDLED_MOD = "/hmc-mcp-bridge/hmc-mcp-bridge.jar";
    /**
     * Asset names of hmc-specifics use lexforge for Forge.
     */
    private static final Map<String, String> LOADERS = Map.of("fabric", "fabric", "forge", "lexforge", "neoforge", "neoforge");

    private final HttpClient http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private final AppFiles appFiles;
    private final String specificsVersion;
    private final String specificsUrl;

    @Inject
    public ModInstaller(
        AppFiles appFiles,
        @ConfigProperty(name = "hmc.mcp.specifics.version") String specificsVersion,
        @ConfigProperty(name = "hmc.mcp.specifics.url") String specificsUrl
    ) {
        this.appFiles = appFiles;
        this.specificsVersion = specificsVersion;
        this.specificsUrl = specificsUrl;
    }

    /**
     * @param loader a modloader name.
     * @return whether hmc-specifics exists for the modloader.
     */
    public static boolean supports(String loader) {
        return loader != null && LOADERS.containsKey(loader.toLowerCase(Locale.ROOT));
    }

    /**
     * Installs the mods if the request is for a controllable client.
     *
     * @return messages for the user, e.g. why the client will not be controllable.
     */
    public List<String> install(LaunchRequest request, Path gameDir) {
        List<String> messages = new ArrayList<>();
        if (request.kind() != Instance.Kind.CLIENT || request.token() == null) {
            return messages;
        }

        if (!supports(request.loader()) || request.mcVersion() == null) {
            messages.add("hmc-specifics needs a modloader (fabric, forge, neoforge), this client can not be controlled.");
            return messages;
        }

        try {
            Path mods = gameDir.resolve("mods");
            Files.createDirectories(mods);
            removeOldJars(mods);
            Path specifics = downloadSpecifics(request.mcVersion(), request.loader().toLowerCase(Locale.ROOT));
            Files.copy(specifics, mods.resolve(specifics.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            try (InputStream is = ModInstaller.class.getResourceAsStream(BUNDLED_MOD)) {
                if (is == null) {
                    throw new IOException("The hmc-mcp-bridge mod is not bundled with this server, build it with Gradle");
                }

                Files.copy(is, mods.resolve("hmc-mcp-bridge.jar"), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.warn("Failed to install hmc-specifics and hmc-mcp-bridge", e);
            messages.add("Failed to install hmc-specifics and hmc-mcp-bridge, this client can not be controlled: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            messages.add("Interrupted while installing hmc-specifics and hmc-mcp-bridge");
        }

        return messages;
    }

    private Path downloadSpecifics(String mcVersion, String loader) throws IOException, InterruptedException {
        String assetLoader = LOADERS.get(loader);
        String url = specificsUrl
            .replace("{tag}", specificsVersion)
            .replace("{mc}", mcVersion)
            .replace("{loader}", assetLoader);
        Path cache = appFiles.getDataDir().resolve("mcp").resolve("hmc-specifics");
        Path file = cache.resolve(url.substring(url.lastIndexOf('/') + 1));
        if (Files.exists(file)) {
            return file;
        }

        Files.createDirectories(cache);
        LOG.infof("Downloading %s", url);
        Path tmp = Files.createTempFile(cache, "download", ".jar");
        try {
            HttpResponse<Path> response = http.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2)).GET().build(),
                HttpResponse.BodyHandlers.ofFile(tmp)
            );
            if (response.statusCode() != 200) {
                throw new IOException("No hmc-specifics " + specificsVersion + " for " + loader + " " + mcVersion
                                          + " (HTTP " + response.statusCode() + " for " + url + ")");
            }

            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            return file;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Removes hmc-specifics and hmc-mcp-bridge jars of other versions, which could be left over from earlier launches.
     */
    private static void removeOldJars(Path mods) throws IOException {
        try (Stream<Path> files = Files.list(mods)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (name.endsWith(".jar") && (name.startsWith("hmc-specifics-") || name.startsWith("hmc-mcp"))) {
                    Files.delete(file);
                }
            }
        }
    }

}
