package io.github.headlesshq.hmcmcp;

/**
 * The configuration of the bridge, read from SystemProperties.
 * The HeadlessMc MCP server passes these to the game when it launches it.
 */
public final class McpConfig {
    /**
     * The secret the MCP server has to send as bearer token. If it is not set, the game was not launched by the MCP server:
     * the bridge then creates a token itself and registers the game, so that MCP servers can attach to it,
     * see {@link io.github.headlesshq.hmcmcp.bridge.Registration}.
     */
    public static final String TOKEN = "hmc.mcp.token";
    public static final String HOST = "hmc.mcp.host";
    /**
     * The port to listen on, {@code 0} picks a free port.
     */
    public static final String PORT = "hmc.mcp.port";
    /**
     * A file to write the port to once the bridge listens, so that the MCP server finds it.
     */
    public static final String PORT_FILE = "hmc.mcp.portFile";
    /**
     * {@code false} turns the bridge off.
     */
    public static final String ENABLED = "hmc.mcp.enabled";

    private final boolean enabled;
    private final String token;
    private final String host;
    private final int port;
    private final String portFile;

    public McpConfig(boolean enabled, String token, String host, int port, String portFile) {
        this.enabled = enabled;
        this.token = token;
        this.host = host;
        this.port = port;
        this.portFile = portFile;
    }

    public static McpConfig fromSystemProperties() {
        String token = System.getProperty(TOKEN);
        if (token != null && token.isEmpty()) {
            token = null;
        }

        boolean enabled = Boolean.parseBoolean(System.getProperty(ENABLED, "true"));
        int port;
        try {
            port = Integer.parseInt(System.getProperty(PORT, "0"));
        } catch (NumberFormatException e) {
            port = 0;
        }

        return new McpConfig(enabled, token, System.getProperty(HOST, "127.0.0.1"), port, System.getProperty(PORT_FILE));
    }

    public McpConfig withToken(String token) {
        return new McpConfig(enabled, token, host, port, portFile);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @return the token passed by the MCP server, or {@code null} if the game has not been launched by an MCP server.
     */
    public String getToken() {
        return token;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    /**
     * @return the file to write the port to, or {@code null}.
     */
    public String getPortFile() {
        return portFile;
    }

}
