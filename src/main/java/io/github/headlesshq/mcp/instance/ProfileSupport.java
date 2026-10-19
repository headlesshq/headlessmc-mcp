package io.github.headlesshq.mcp.instance;

import io.github.headlesshq.headlessmc.files.AppFiles;
import io.github.headlesshq.headlessmc.launcher.profile.Profile;
import io.github.headlesshq.headlessmc.launcher.profile.ProfileService;
import io.github.headlesshq.headlessmc.version.arg.Side;
import io.github.headlesshq.headlessmc.version.arg.VersionArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * HeadlessMc profiles give a client its own game directory (mods, config, worlds, options).
 * Without a profile, all clients of the same version share a game directory, so two of them can not run at the same time.
 * The MCP server creates a profile in {@code <data dir>/client/<name>} whenever that would happen,
 * and agents can create and reuse named profiles, e.g. one per test setup.
 */
@ApplicationScoped
public class ProfileSupport {
    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private final ProfileService profileService;
    private final InstanceManager instanceManager;
    private final AppFiles appFiles;

    @Inject
    public ProfileSupport(ProfileService profileService, InstanceManager instanceManager, AppFiles appFiles) {
        this.profileService = profileService;
        this.instanceManager = instanceManager;
        this.appFiles = appFiles;
    }

    /**
     * @return the client profile with the name, HeadlessMc stores servers as profiles as well.
     */
    public Optional<Profile> get(String name) {
        return profileService.getProfile(name).filter(profile -> profile.side() == Side.CLIENT);
    }

    /**
     * @return all client profiles.
     */
    public List<Profile> list() {
        return profileService.getProfiles().stream().filter(profile -> profile.side() == Side.CLIENT).toList();
    }

    public void remove(Profile profile) {
        profileService.remove(profile);
    }

    /**
     * @return the directory for the game files of a profile created by the MCP server.
     */
    public Path managedDir(String name) {
        return getManagedRoot().resolve(name.toLowerCase(Locale.ROOT));
    }

    public Path getManagedRoot() {
        // next to HeadlessMc's <data dir>/server, the profile json files are in <config dir>/profiles
        return appFiles.getDataDir().resolve("client").toAbsolutePath().normalize();
    }

    /**
     * @return the running instance that uses the directory as its game directory.
     */
    public Optional<Instance> usedBy(Path gameDir) {
        return instanceManager.findRunning(gameDir);
    }

    /**
     * @return a running client without a profile for the same loader and version, which uses the default game directory.
     */
    public Optional<Instance> defaultDirUsedBy(@Nullable String loader, String version) {
        return instanceManager.list().stream()
            .filter(instance -> instance.getStatus() == Instance.Status.RUNNING)
            .filter(instance -> instance.getRequest().kind() == Instance.Kind.CLIENT && instance.getRequest().profile() == null)
            .filter(instance -> Objects.equals(instance.getRequest().loader(), loader)
                && version.equals(instance.getRequest().mcVersion()))
            .findFirst();
    }

    /**
     * @return the name of a profile for the loader and version that is not in use, it might not exist yet.
     */
    public String nextFreeName(@Nullable String loader, String version) {
        String base = (loader == null ? "vanilla" : loader) + "-" + version;
        for (int i = 2; ; i++) {
            String name = base + "-" + i;
            Optional<Profile> profile = get(name);
            if (profile.isEmpty() || usedBy(profile.get().path()).isEmpty()) {
                return name;
            }
        }
    }

    /**
     * @return the modloader of a profile, {@code null} for vanilla.
     */
    public static @Nullable String loaderOf(Profile profile) {
        String platform = profile.version().platform();
        return platform == null || VersionArg.PLATFORM_VANILLA.equalsIgnoreCase(platform)
            ? null
            : platform.toLowerCase(Locale.ROOT);
    }

}
