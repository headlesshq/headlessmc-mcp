# @headlesshq/headlessmc-mcp

<img src="https://raw.githubusercontent.com/headlesshq/headlessmc-mcp/main/docs/logo.png" alt="HeadlessMc MCP logo" width="160">

An [MCP](https://modelcontextprotocol.io) server for [HeadlessMc](https://github.com/headlesshq/headlessmc):
install, launch and control Minecraft: Java Edition from AI agents.

This package starts the native executable of [headlessmc-mcp](https://github.com/headlesshq/headlessmc-mcp) for your
platform (Windows x64, Linux x64/arm64, macOS x64/arm64), which npm installs as an optional dependency. No Java
installation is needed for the MCP server itself.

```json
{
  "mcpServers": {
    "headlessmc": {
      "command": "npx",
      "args": ["-y", "@headlesshq/headlessmc-mcp@0.1.0"]
    }
  }
}
```

See the [repository](https://github.com/headlesshq/headlessmc-mcp) for the tools, configuration and the
[privacy policy](https://github.com/headlesshq/headlessmc-mcp/blob/main/PRIVACY.md).
