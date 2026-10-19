package io.github.headlesshq.hmcmcp;

import io.github.headlesshq.hmcmcp.bridge.Bridge;
import io.github.headlesshq.hmcmcp.bridge.Registration;
import me.earth.headlessmc.mc.Minecraft;
import me.earth.headlessmc.mc.metrics.MetricsService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Lets the HeadlessMc MCP server control this client.
 * HMC-Specifics calls {@link HmcMcpProvider} with the {@link Minecraft} instance when it initializes,
 * which starts the {@link Bridge}. The modloader entrypoints only record which modloader loaded the mod,
 * see {@link #onModConstructed(String)}.
 */
public final class HmcMcp {
    public static final String ID = "hmc_mcp_bridge";
    public static final String NAME = "HMC-MCP-Bridge";

    private static final Logger LOGGER = Logger.getLogger(HmcMcp.class.getName());
    private static final HmcMcp INSTANCE = new HmcMcp();

    private volatile String loader = "unknown";
    private volatile Minecraft mc;
    private volatile Bridge bridge;

    public static HmcMcp getInstance() {
        return INSTANCE;
    }

    /**
     * Called by the modloader entrypoints.
     * Forge before 1.13 adds mods to the classpath after HMC-Specifics has been initialized,
     * so HMC-Specifics could not find our {@link HmcMcpProvider}.
     * In that case we look for the Minecraft instance ourselves and let HMC-Specifics load our provider.
     *
     * @param loader the name of the modloader.
     */
    public static void onModConstructed(String loader) {
        INSTANCE.loader = loader;
        if (INSTANCE.mc == null) {
            Minecraft mc = findMinecraft();
            if (mc != null) {
                MetricsService.getInstance().loadProviders(mc, HmcMcp.class.getClassLoader());
            }
        }
    }

    /**
     * Called once HMC-Specifics has initialized, starts the bridge if it is configured.
     *
     * @param mc the Minecraft instance.
     */
    synchronized void init(Minecraft mc) {
        if (this.mc != null) {
            return;
        }

        this.mc = mc;
        McpConfig config = McpConfig.fromSystemProperties();
        if (!config.isEnabled()) {
            LOGGER.info(NAME + " is disabled by " + McpConfig.ENABLED + "=false.");
            return;
        }

        // not launched by an MCP server: register the game, so that MCP servers can attach to it
        boolean attach = config.getToken() == null;
        if (attach) {
            config = config.withToken(newToken());
        }

        try {
            Bridge bridge = Bridge.start(config, loader, getVersion());
            this.bridge = bridge;
            Registration registration = attach ? Registration.register(bridge.getPort(), config.getToken(), bridge.getInfo(), getMcVersion(mc)) : null;
            Runnable stop = () -> {
                if (registration != null) {
                    registration.delete();
                }

                bridge.stop();
            };

            Runtime.getRuntime().addShutdownHook(new Thread(stop, "hmc-mcp-bridge-shutdown"));
            stopWhenGameStops(Thread.currentThread(), stop);
            LOGGER.info(NAME + " bridge listening on " + bridge.getUrl());
            if (registration != null) {
                LOGGER.info(NAME + ": agents can attach to this game with the HeadlessMc MCP server (list_instances), registered in "
                                + registration.getFile() + ". Set " + McpConfig.ENABLED + "=false to turn this off.");
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to start the " + NAME + " bridge", e);
        }
    }

    /**
     * HMC-Specifics initializes on the game thread, which ends when the game stops.
     * Shutdown hooks alone are not enough: on some modloaders the JVM does not exit after the game has stopped,
     * e.g. when the shutdown watchdog of Minecraft fails, because the modloader has already closed the Minecraft classes.
     */
    private static void stopWhenGameStops(Thread gameThread, Runnable stop) {
        Thread watcher = new Thread(() -> {
            try {
                gameThread.join();
            } catch (InterruptedException e) {
                return;
            }

            LOGGER.info("Game thread " + gameThread.getName() + " has ended, stopping the " + NAME + " bridge.");
            stop.run();
        }, "hmc-mcp-bridge-watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String getMcVersion(Minecraft mc) {
        try {
            return mc.getMetricsSource().getMinecraftVersion();
        } catch (Throwable t) { // HMC-Specifics versions without metrics
            return null;
        }
    }

    public String getLoader() {
        return loader;
    }

    /**
     * @return the running bridge, or {@code null} if it has not been started.
     */
    public Bridge getBridge() {
        return bridge;
    }

    /**
     * Read from a resource, because not every modloader exposes the manifest of the jar through the Package.
     */
    public static String getVersion() {
        try (InputStream is = HmcMcp.class.getResourceAsStream("/hmc_mcp_bridge.version")) {
            if (is != null) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                String version = reader.readLine();
                if (version != null && !version.trim().isEmpty()) {
                    return version.trim();
                }
            }
        } catch (IOException ignored) {
            // fall back to the manifest
        }

        String version = HmcMcp.class.getPackage().getImplementationVersion();
        return version == null ? "unknown" : version;
    }

    /**
     * HMC-Specifics makes net.minecraft.client.Minecraft implement its {@link Minecraft} interface.
     * The static field holding the instance has a different name in every version and mapping, so we check the type.
     */
    private static Minecraft findMinecraft() {
        try {
            Class<?> type = Class.forName("net.minecraft.client.Minecraft", false, HmcMcp.class.getClassLoader());
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == type) {
                    field.setAccessible(true);
                    Object value = field.get(null);
                    if (value instanceof Minecraft) {
                        return (Minecraft) value;
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.log(Level.FINE, "Failed to find the Minecraft instance", t);
        }

        return null;
    }

}
