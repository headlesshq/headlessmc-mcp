#!/usr/bin/env node
// Starts the native executable of headlessmc-mcp for this platform, like esbuild or biome do.
// npm installs only the optional dependency that matches os and cpu, which contains the executable.
// stdin and stdout are the MCP channel, so they are passed through to the executable untouched.
'use strict';

const { spawn } = require('node:child_process');
const fs = require('node:fs');

const platform = `${process.platform}-${process.arch}`;
const executable = process.platform === 'win32' ? 'headlessmc-mcp.exe' : 'headlessmc-mcp';

let path;
try {
    path = require.resolve(`@headlesshq/headlessmc-mcp-${platform}/bin/${executable}`);
} catch {
    console.error(`headlessmc-mcp: there is no native executable for ${platform}.
Download headlessmc-mcp.jar from https://github.com/headlesshq/headlessmc-mcp/releases
and run it with Java 25 or newer instead: java -jar headlessmc-mcp.jar`);
    process.exit(1);
}

// the executable bit can get lost, e.g. when a package is extracted by another tool
if (process.platform !== 'win32') {
    try {
        fs.accessSync(path, fs.constants.X_OK);
    } catch {
        fs.chmodSync(path, 0o755);
    }
}

const child = spawn(path, process.argv.slice(2), { stdio: 'inherit', windowsHide: true });
for (const signal of ['SIGINT', 'SIGTERM', 'SIGHUP']) {
    process.on(signal, () => child.kill(signal));
}

child.on('error', error => {
    console.error(`headlessmc-mcp: could not start ${path}: ${error.message}`);
    process.exit(1);
});

child.on('exit', (code, signal) => process.exit(code ?? (signal ? 1 : 0)));
