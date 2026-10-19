package io.github.headlesshq.hmcmcp.command;

import me.earth.headlessmc.mc.commands.AbstractMinecraftCommand;
import me.earth.headlessmc.mc.commands.MinecraftContext;
import me.earth.headlessmc.mc.commands.ScheduledCommand;
import me.earth.headlessmc.mc.gui.GuiScreen;
import me.earth.headlessmc.mc.metrics.Metric;
import me.earth.headlessmc.mc.metrics.MetricsFormatter;
import me.earth.headlessmc.mc.metrics.MetricsService;
import me.earth.headlessmc.mc.player.Player;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The state of the client as JSON: whether it is in a world, the current screen and the HMC-Specifics metrics,
 * e.g. position, health, dimension, server address and players.
 */
@CommandLine.Command(name = "state", description = "Dumps the state of the client as JSON.")
public class McpStateCommand extends AbstractMinecraftCommand implements ScheduledCommand {
    @CommandLine.Option(names = "--jvm", description = "Also include the jvm metrics.")
    private boolean jvm;

    public McpStateCommand(MinecraftContext ctx) {
        super(ctx);
    }

    @Override
    protected void execute() {
        Map<String, Object> result = new LinkedHashMap<>();
        Player player = mc.getPlayer();
        GuiScreen screen = mc.getScreen();
        result.put("inWorld", player != null);
        result.put("dead", player != null && player.isDead());
        result.put("screen", screen == null ? null : screen.getHandle().getClass().getSimpleName());

        MetricsService service = ctx.getMetricsService();
        service.loadProviders(mc, McpStateCommand.class.getClassLoader());
        List<Metric<?>> metrics = new ArrayList<>();
        for (Metric<?> metric : service.getMetrics()) {
            if (jvm || !"jvm".equals(metric.getGroup())) {
                metrics.add(metric);
            }
        }

        result.put("metrics", service.collect(metrics));
        ctx.log(MetricsFormatter.toJson(result));
    }

}
