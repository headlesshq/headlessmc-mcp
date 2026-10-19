package io.github.headlesshq.hmcmcp.command;

import me.earth.headlessmc.mc.commands.AbstractMinecraftCommand;
import me.earth.headlessmc.mc.commands.MinecraftContext;
import me.earth.headlessmc.mc.commands.ScheduledCommand;
import me.earth.headlessmc.mc.gui.GuiButton;
import me.earth.headlessmc.mc.gui.GuiElement;
import me.earth.headlessmc.mc.gui.GuiScreen;
import me.earth.headlessmc.mc.gui.TextField;
import me.earth.headlessmc.mc.metrics.MetricsFormatter;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Like the {@code gui} command of HMC-Specifics, but as JSON, so that the MCP server does not have to parse tables.
 */
@CommandLine.Command(name = "screen", description = "Dumps the currently displayed screen as JSON.")
public class McpScreenCommand extends AbstractMinecraftCommand implements ScheduledCommand {
    public McpScreenCommand(MinecraftContext ctx) {
        super(ctx);
    }

    @Override
    protected void execute() {
        ctx.log(MetricsFormatter.toJson(describe(mc.getScreen())));
    }

    static Map<String, Object> describe(GuiScreen screen) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (screen == null) {
            result.put("screen", null);
            return result;
        }

        result.put("screen", screen.getHandle().getClass().getSimpleName());
        result.put("class", screen.getHandle().getClass().getName());
        List<GuiElement> elements = new ArrayList<>(screen.getAllElements());
        elements.sort(Comparator.comparingInt(GuiElement::getId));
        List<Map<String, Object>> list = new ArrayList<>();
        for (GuiElement element : elements) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", element.getId());
            map.put("kind", element instanceof TextField ? "textField" : element instanceof GuiButton ? "button" : "other");
            map.put("text", element.getText());
            if (element instanceof GuiButton) {
                map.put("enabled", ((GuiButton) element).isEnabled());
            }

            map.put("x", element.getX());
            map.put("y", element.getY());
            map.put("w", element.getWidth());
            map.put("h", element.getHeight());
            map.put("type", element.getHandle().getClass().getSimpleName());
            list.add(map);
        }

        result.put("elements", list);
        return result;
    }

}
