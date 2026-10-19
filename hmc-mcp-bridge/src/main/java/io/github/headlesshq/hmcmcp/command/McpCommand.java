package io.github.headlesshq.hmcmcp.command;

import io.github.headlesshq.hmcmcp.HmcMcp;
import io.github.headlesshq.hmcmcp.bridge.Bridge;
import me.earth.headlessmc.mc.commands.AbstractMinecraftCommand;
import me.earth.headlessmc.mc.commands.MinecraftContext;
import picocli.CommandLine;

@CommandLine.Command(name = "mcp", description = "Shows the status of the HeadlessMc MCP bridge.")
public class McpCommand extends AbstractMinecraftCommand {
    public McpCommand(MinecraftContext ctx) {
        super(ctx);
    }

    @Override
    protected void execute() {
        Bridge bridge = HmcMcp.getInstance().getBridge();
        ctx.log(HmcMcp.NAME + " " + HmcMcp.getVersion() + " (" + HmcMcp.getInstance().getLoader() + "): "
                    + (bridge == null ? "bridge not running" : "bridge listening on " + bridge.getUrl()));
    }

}
