// Packs the plugin as a ZIP for the OpenAI Plugin directory (desktop-only plugin with a local stdio MCP server):
// the portable plugin.json and mcp.json at the root, without the Claude manifests.
// Usage: node scripts/openai-plugin.mjs [output dir], writes <output dir>/headlessmc-mcp-<version>.zip (default build/openai)
// See https://developers.openai.com/plugins/deploy/desktop-only

import { execFileSync } from 'node:child_process';
import { cpSync, mkdirSync, readFileSync, rmSync } from 'node:fs';
import { join, resolve } from 'node:path';

const out = resolve(process.argv[2] ?? 'build/openai');
const files = ['plugin.json', 'mcp.json', 'README.md', 'LICENSE', 'assets'];

const manifest = JSON.parse(readFileSync('plugin/plugin.json', 'utf8'));
JSON.parse(readFileSync('plugin/mcp.json', 'utf8'));

// the submission limits of the listing, see https://developers.openai.com/plugins/deploy/submission#listing-metadata
const ui = manifest.extensions['com.openai'].interface;
const limits = { displayName: 30, shortDescription: 30, longDescription: 4000, developerName: 80 };
for (const [field, max] of Object.entries(limits)) {
    if (!ui[field] || ui[field].length > max) {
        throw new Error(`interface.${field} must have 1 to ${max} characters`);
    }
}
for (const prompt of ui.defaultPrompt ?? []) {
    if (prompt.length > 128) {
        throw new Error(`defaultPrompt "${prompt}" is longer than 128 characters`);
    }
}

const staging = join(out, 'headlessmc-mcp');
rmSync(staging, { recursive: true, force: true });
mkdirSync(staging, { recursive: true });
for (const file of files) {
    cpSync(join('plugin', file), join(staging, file), { recursive: true });
}

const zip = join(out, `headlessmc-mcp-${manifest.version}.zip`);
rmSync(zip, { force: true });
// -X leaves out extra file attributes, -D directory entries
execFileSync('zip', ['-r', '-X', '-D', zip, ...files], { cwd: staging, stdio: 'inherit' });
rmSync(staging, { recursive: true });
console.log(zip);
