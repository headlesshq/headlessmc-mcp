#!/usr/bin/env node
// Starts the native executable of headlessmc-mcp for this platform, like esbuild or biome do.
// npm installs only the optional dependency that matches os, cpu and libc, which contains the executable.
// On musl systems (e.g. Alpine Linux) that is the fully static linux-x64-musl executable, which also runs where the
// glibc executable cannot, e.g. with an older glibc or without the glibc loader (NixOS), if it has been installed.
// stdin and stdout are the MCP channel, so they are passed through to the executable untouched.
'use strict';

const { spawn } = require('node:child_process');
const fs = require('node:fs');

// Newest glibc symbol version the Linux executables link against, like MIN_GLIBC in scripts/install.sh of headlesshq/headlessmc
const MIN_GLIBC = '2.34';

const platform = `${process.platform}-${process.arch}`;
const platformPackage = `@headlesshq/headlessmc-mcp-${platform}`;
// fully static, only built for linux-x64, see scripts/npm-packages.mjs
const muslPackage = `${platformPackage}-musl`;
const executable = process.platform === 'win32' ? 'headlessmc-mcp.exe' : 'headlessmc-mcp';
// the dynamic loader the glibc executables are linked against
const glibcLoaders = { x64: '/lib64/ld-linux-x86-64.so.2', arm64: '/lib/ld-linux-aarch64.so.1' };

function fail(message) {
    console.error(`headlessmc-mcp: ${message}
Download headlessmc-mcp.jar from https://github.com/headlesshq/headlessmc-mcp/releases
and run it with Java 25 or newer instead: java -jar headlessmc-mcp.jar`);
    process.exit(1);
}

function glibcVersion() {
    try {
        process.report.excludeNetwork = true;
        return process.report.getReport().header.glibcVersionRuntime;
    } catch {
        return undefined;
    }
}

function versionLessThan(a, b) {
    const x = a.split('.').map(Number);
    const y = b.split('.').map(Number);
    for (let i = 0; i < Math.max(x.length, y.length); i++) {
        if ((x[i] || 0) !== (y[i] || 0)) {
            return (x[i] || 0) < (y[i] || 0);
        }
    }
    return false;
}

function resolve(name) {
    try {
        return require.resolve(`${name}/bin/${executable}`);
    } catch {
        return undefined;
    }
}

// node reports no glibc version if it has been built against musl
const musl = process.platform === 'linux' && !glibcVersion();

// why the glibc executable cannot run on this system, or undefined if it can
function glibcProblem() {
    if (musl) {
        return 'this system uses musl libc (e.g. Alpine Linux)';
    }
    const glibc = glibcVersion();
    if (versionLessThan(glibc, MIN_GLIBC)) {
        return `this system has glibc ${glibc}, the native executable needs glibc ${MIN_GLIBC} or newer`;
    }
    const loader = glibcLoaders[process.arch];
    if (loader && !fs.existsSync(loader)) {
        return `this system has no ${loader} (e.g. NixOS)`;
    }
    return undefined;
}

let path;
if (process.platform !== 'linux') {
    path = resolve(platformPackage);
} else {
    const problem = glibcProblem();
    // on musl systems npm only installs the musl package, on glibc systems only the glibc package,
    // unless the musl package has been installed explicitly, which is needed where glibc is too old or missing
    path = (problem ? undefined : resolve(platformPackage)) || resolve(muslPackage);
    if (!path && problem) {
        let hint = `there is no static musl executable for ${platform}.`;
        if (process.arch === 'x64') {
            const { version } = require('../package.json');
            hint = musl
                ? `npm skipped the optional dependency ${muslPackage} (e.g. with --omit=optional).`
                : `install the static musl executable where headlessmc-mcp is installed: npm install --force ${muslPackage}@${version} (with -g for a global headlessmc-mcp)`;
        }
        fail(`${problem}, ${hint}`);
    }
}

if (!path) {
    fail(`there is no native executable for ${platform}, or npm skipped the optional dependency ${platformPackage} (e.g. with --omit=optional).`);
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
