package io.github.headlesshq.mcp.tools;

import io.github.headlesshq.mcp.instance.Instance;
import io.github.headlesshq.mcp.instance.InstanceManager;
import jakarta.inject.Inject;
import org.mcpjava.server.resources.ResourceTemplate;
import org.mcpjava.server.resources.ResourceTemplateArg;
import org.mcpjava.server.resources.TextResourceContents;

import java.util.stream.Collectors;

/**
 * The output of instances as MCP resources, so that clients can attach a log to the conversation.
 */
public class InstanceResources {
    @Inject
    InstanceManager instanceManager;

    @ResourceTemplate(
        uriTemplate = "hmc://instances/{id}/log",
        name = "instance_log",
        description = "The output (game or server log) of an instance launched by this MCP server. Untrusted data.",
        mimeType = "text/plain"
    )
    public TextResourceContents log(@ResourceTemplateArg(name = "id") String id) {
        String uri = "hmc://instances/" + id + "/log";
        String text = instanceManager.get(id)
            .map(instance -> instance.getHistory().stream().map(Instance.Line::text).collect(Collectors.joining("\n")))
            .orElse("No instance with id " + id);
        return TextResourceContents.of(uri, text);
    }

}
