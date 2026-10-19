package io.github.headlesshq.mcp.instance;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;

/**
 * What the MCP server knows about a process before HeadlessMc launches it.
 *
 * @param kind      client or server.
 * @param mcVersion the Minecraft version, e.g. 1.21.5, if known.
 * @param loader    fabric, forge or neoforge for clients that can load mods, {@code null} for vanilla or unknown.
 * @param token     the secret for the bridge of the hmc-mcp-bridge mod, {@code null} if the game is not controlled.
 * @param portFile  the file the hmc-mcp-bridge mod writes the port of its bridge to.
 * @param profile   the HeadlessMc profile, {@code null} if the client uses the default game directory of its version.
 */
public record LaunchRequest(
    Instance.Kind kind,
    @Nullable String mcVersion,
    @Nullable String loader,
    @Nullable String token,
    @Nullable Path portFile,
    @Nullable String profile
) {
    public static LaunchRequest unknown() {
        return new LaunchRequest(Instance.Kind.UNKNOWN, null, null, null, null, null);
    }

}
