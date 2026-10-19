package io.github.headlesshq.mcp;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

/**
 * Explicit main, otherwise quarkus-picocli (needed to make HeadlessMc's commands CDI beans)
 * would run the HeadlessMc command line as application and exit, instead of serving MCP on stdio.
 */
@QuarkusMain
public class McpServerMain {
    public static void main(String... args) {
        Quarkus.run(args);
    }

}
