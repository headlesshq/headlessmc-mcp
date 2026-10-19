package io.github.headlesshq.mcp.hmc;

import jakarta.inject.Qualifier;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Qualifies the beans the MCP server plugs into HeadlessMc's SPI ({@link McpConsoleProvider}),
 * so they do not clash with HeadlessMc's own {@code @Default} beans.
 */
@Qualifier
@Documented
@Retention(RetentionPolicy.RUNTIME)
public @interface Mcp {
}
