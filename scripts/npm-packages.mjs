// Creates the npm packages from the release assets: one package per platform with the native executable,
// and the main package @headlesshq/headlessmc-mcp that starts it.
// Usage: node scripts/npm-packages.mjs <dir with the release assets> <output dir>
// Publish the platform packages before the main package, which depends on them.

import { chmodSync, copyFileSync, cpSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const [dist, out] = process.argv.slice(2);
if (!dist || !out) {
    console.error('Usage: node scripts/npm-packages.mjs <dist> <out>');
    process.exit(1);
}

const main = JSON.parse(readFileSync('npm/headlessmc-mcp/package.json', 'utf8'));

// npm platform (process.platform-process.arch[-libc]) -> name of the release asset and the libc of Linux executables.
// The musl executable is fully static, bin/headlessmc-mcp.js also uses it where the glibc executable cannot run.
const platforms = {
    'linux-x64': { asset: 'headlessmc-mcp-linux-x64', libc: 'glibc' },
    'linux-x64-musl': { asset: 'headlessmc-mcp-linux-x64-musl', libc: 'musl' },
    'linux-arm64': { asset: 'headlessmc-mcp-linux-arm64', libc: 'glibc' },
    'darwin-x64': { asset: 'headlessmc-mcp-macos-x64' },
    'darwin-arm64': { asset: 'headlessmc-mcp-macos-arm64' },
    'win32-x64': { asset: 'headlessmc-mcp-windows-x64.exe' },
};

for (const [platform, { asset, libc }] of Object.entries(platforms)) {
    const [os, cpu] = platform.split('-');
    const name = `@headlesshq/headlessmc-mcp-${platform}`;
    if (main.optionalDependencies[name] !== main.version) {
        throw new Error(`${name} is not an optional dependency of version ${main.version}`);
    }

    const source = join(dist, asset);
    if (!existsSync(source)) {
        throw new Error(`Missing release asset ${source}`);
    }

    const dir = join(out, `headlessmc-mcp-${platform}`);
    mkdirSync(join(dir, 'bin'), { recursive: true });
    const executable = join(dir, 'bin', os === 'win32' ? 'headlessmc-mcp.exe' : 'headlessmc-mcp');
    copyFileSync(source, executable);
    chmodSync(executable, 0o755);

    writeFileSync(join(dir, 'package.json'), JSON.stringify({
        name,
        version: main.version,
        description: libc === 'musl'
            ? `The statically linked ${os}-${cpu} executable (musl) of ${main.name}.`
            : `The ${platform} executable of ${main.name}.`,
        homepage: main.homepage,
        repository: main.repository,
        license: main.license,
        os: [os],
        cpu: [cpu],
        // npm only installs the musl executable on musl systems automatically
        ...(libc ? { libc: [libc] } : {}),
        files: ['bin'],
    }, null, 2) + '\n');
    writeFileSync(join(dir, 'README.md'), `# ${name}\n\nThe ${platform} executable of [${main.name}](https://www.npmjs.com/package/${main.name}), use that package instead.\n`);
    copyFileSync('LICENSE', join(dir, 'LICENSE'));
}

cpSync('npm/headlessmc-mcp', join(out, 'headlessmc-mcp'), { recursive: true });
// npm always packs a LICENSE file, regardless of "files"
copyFileSync('LICENSE', join(out, 'headlessmc-mcp', 'LICENSE'));
console.log(`Created the npm packages of version ${main.version} in ${out}`);
