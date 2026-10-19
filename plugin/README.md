# HeadlessMc MCP

A plugin that adds the [HeadlessMc MCP server](https://github.com/headlesshq/headlessmc-mcp) to your agent
(Claude Code, Codex, Cursor, GitHub Copilot and other clients that support plugins). With it, the agent can install,
launch and control Minecraft: Java Edition: vanilla, Fabric, Forge and NeoForge clients and servers, several at once and
optionally headless (no display or GPU), mods from Modrinth, and the running game through
[HMC-Specifics](https://github.com/headlesshq/hmc-specifics): screens, buttons, text fields, chat, commands and state.

Install it in Claude Code with:

```
/plugin install headlessmc-mcp --marketplace headlesshq/headlessmc-mcp
```

See the [repository](https://github.com/headlesshq/headlessmc-mcp) for the list of tools and how to install the plugin
in other agents.

## What the plugin runs

The plugin starts one local MCP server over stdio:

```
npx -y @headlesshq/headlessmc-mcp@<version>
```

The [`@headlesshq/headlessmc-mcp`](https://www.npmjs.com/package/@headlesshq/headlessmc-mcp) npm package starts the native
executable of the MCP server for your platform, which npm installs from a platform package
(`@headlesshq/headlessmc-mcp-linux-x64`, ...). Node.js 18 or newer is required. The executables are built from this
repository by GitHub Actions and published with npm provenance.

## What the MCP server downloads and connects to

When the agent asks for it, the MCP server downloads Minecraft, modloaders and Java from Mojang/Microsoft, the modloader
repositories and Java distributors, mods from Modrinth, and HMC-Specifics from GitHub. It logs in with Microsoft only when you
sign in on Microsoft's own page, and it connects to the Minecraft servers you choose to join. It sends no telemetry.
Game files are stored in HeadlessMc's directory, shared with the `headlessmc` command line launcher. See the
[Privacy Policy](https://github.com/headlesshq/headlessmc-mcp/blob/main/PRIVACY.md) and the
[Terms of Use](https://github.com/headlesshq/headlessmc-mcp/blob/main/TERMS.md).

HeadlessMc is not an official Minecraft product and is not approved by or associated with Mojang or Microsoft.

## License

[MIT](LICENSE)
