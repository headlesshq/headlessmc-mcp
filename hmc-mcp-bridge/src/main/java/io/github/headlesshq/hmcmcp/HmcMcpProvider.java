package io.github.headlesshq.hmcmcp;

import me.earth.headlessmc.mc.Minecraft;
import me.earth.headlessmc.mc.metrics.MetricsProvider;
import me.earth.headlessmc.mc.metrics.MetricsService;

/**
 * Called by HMC-Specifics when it initializes, via {@link java.util.ServiceLoader}.
 * HMC-MCP-Bridge does not register metrics of its own, it uses this hook to get the {@link Minecraft} instance.
 */
public class HmcMcpProvider implements MetricsProvider {
    @Override
    public void register(MetricsService service, Minecraft mc) {
        HmcMcp.getInstance().init(mc);
    }

}
