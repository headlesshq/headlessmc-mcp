# Privacy Policy

_Last updated: 2026-10-17_

This privacy policy describes how **HeadlessMc - MCP** ("the MCP server", "we") handles data. The MCP server is open source
software published by the HeadlessHQ project at <https://github.com/headlesshq/headlessmc-mcp>. It lets AI assistants that
support the Model Context Protocol (MCP), such as ChatGPT, Codex or Claude, install, launch and control Minecraft: Java Edition
on your computer.

## Summary

- The MCP server runs **on your own computer**. We do not operate servers for it and **we do not receive any of your data**.
- There is **no telemetry, analytics, advertising or tracking**.
- The MCP server connects to third party services (Mojang/Microsoft, modloader and mod repositories, GitHub) to download
  and run Minecraft, and to the Minecraft servers you choose to join.
- What the MCP server returns to your AI assistant (logs, screen contents, chat) is processed by the provider of that
  assistant under its own privacy policy.

## Data the MCP server processes

All of this data stays on your computer, unless it is sent to one of the recipients listed below.

| Data | Purpose | Where it is stored |
|---|---|---|
| Microsoft/Minecraft account name, UUID and access tokens, if you log in | Launching Minecraft with your account | HeadlessMc's files directory on your computer |
| Minecraft versions, libraries, assets, mods, Java runtimes | Running the game | HeadlessMc's files directory and Minecraft directory |
| Profiles, worlds, game settings, server configurations | Running the game and servers | The game directories of your profiles and servers |
| Game and server logs, including chat messages and names of other players | Showing the AI assistant what happens in the game | In memory while the MCP server runs, and in the log files the game writes |
| Screen contents and game state (buttons, text fields, position, health, server address, players online) | Letting the AI assistant control the game | In memory while the MCP server runs |
| Answers you give when the MCP server asks you something through your AI client | Completing the command that asked | In memory, and in the output of that command |

The MCP server **never asks for passwords or other secrets** through your AI client. To log in to a Microsoft account,
you sign in on Microsoft's website in your own browser (device code login), and the MCP server only receives the resulting
tokens from Microsoft.

To control a running game, the MCP server talks to a small mod inside the game (`hmc-mcp-bridge`) over the loopback
network interface of your computer (`127.0.0.1`), protected by a random secret that is created for each launch.
It is not reachable from other computers.

## Who receives data

The MCP server sends data only where it is needed to do what you or your AI assistant asked:

- **Your AI client and its provider** (e.g. OpenAI for ChatGPT or Codex, Anthropic for Claude): the results of tool calls,
  which can contain log lines, chat messages, names of other players, server addresses, screen contents, file paths
  on your computer and account names. The provider processes this data under its own privacy policy. Do not use the
  MCP server with game sessions that contain information you do not want to share with your AI provider.
- **Microsoft and Mojang**: when you log in, when the game authenticates with your account, and when versions,
  libraries and assets are downloaded. See the [Microsoft Privacy Statement](https://privacy.microsoft.com/privacystatement).
- **Modloader and mod services**, when you install them: Fabric, Forge, NeoForge, PaperMC, Purpur and
  [Modrinth](https://modrinth.com/legal/privacy) receive standard HTTP request data (such as your IP address) when files are downloaded or mods are searched.
- **GitHub**, when the MCP server downloads HMC-Specifics releases. See the [GitHub Privacy Statement](https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement).
- **Java distribution providers**, when HeadlessMc downloads a Java runtime.
- **Minecraft servers you join**: they receive your player name, UUID, IP address and everything you or the AI assistant do and say on them.

We do not sell or share data, and we have no access to any of it.

## Your choices and control

- You decide which AI client the MCP server is connected to and which tools it may use, in the settings of that client.
- You can log out and remove accounts with the `account` commands of HeadlessMc, and delete profiles with `delete_profile`.
- You can delete all data by deleting HeadlessMc's files directory (configurable with `-Dhmc.files.location`) and the game directories.
- Uninstalling the MCP server is as simple as removing it from your AI client's configuration and deleting the executable.

## Children

The MCP server is not directed at children under 13 (or the minimum age in your country). Use of Minecraft and of AI
assistants is subject to the age requirements of their providers.

## Changes

We publish changes to this policy in the repository, with a new "Last updated" date.
The history of this file shows all previous versions.

## Contact

Questions about this policy: open an issue at <https://github.com/headlesshq/headlessmc-mcp/issues>.
