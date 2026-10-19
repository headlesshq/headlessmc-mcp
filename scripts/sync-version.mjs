// Writes the version of gradle.properties (bumped by release-please) into the npm package, the plugin manifests
// and server.json, which pin the npm package to the version of the release.
// Runs on the release-please pull request, see .github/workflows/lifecycle.yml.

import { readFileSync, writeFileSync } from 'node:fs';

const version = readFileSync('gradle.properties', 'utf8').match(/^project_version=(\S+)$/m)[1];

const files = [
    'npm/headlessmc-mcp/package.json',
    'npm/headlessmc-mcp/README.md',
    'README.md',
    'plugin/.claude-plugin/plugin.json',
    'plugin/plugin.json',
    'plugin/.mcp.json',
    'plugin/mcp.json',
    'gemini-extension.json',
    'server.json',
];

for (const file of files) {
    const text = readFileSync(file, 'utf8');
    // text replacements instead of JSON.stringify, to keep the formatting of the files
    const updated = text
        .replace(/("version":\s*")[^"]*(")/g, `$1${version}$2`)
        .replace(/("@headlesshq\/headlessmc-mcp-[a-z0-9-]+":\s*")[^"]*(")/g, `$1${version}$2`)
        .replace(/(@headlesshq\/headlessmc-mcp@)[0-9A-Za-z.+-]+/g, `$1${version}`);
    if (updated !== text) {
        writeFileSync(file, updated);
        console.log(`${file}: ${version}`);
    }
}
