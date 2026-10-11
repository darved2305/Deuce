import { mkdir, readFile, readdir, rm, writeFile } from "node:fs/promises";
import { dirname, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const sourceRoot = fileURLToPath(
  new URL("../../packages/contracts/src/", import.meta.url),
);
const destinationRoot = fileURLToPath(
  new URL("../src/contracts/", import.meta.url),
);
const entrypoints = ["api.ts", "common.ts"];
const header = "// GENERATED from packages/contracts/src - do not edit.\n" +
  "// Regenerate with node mcp/scripts/sync-contracts.mjs\n";
const localImportPattern = /(?:\bfrom\s*|\bimport\s*)["'](\.{1,2}\/[^"']+)["']/g;

function importedPaths(source) {
  return [...source.matchAll(localImportPattern)].map((match) => match[1]);
}

function sourceName(importer, specifier) {
  const sourceSpecifier = specifier.endsWith(".ts")
    ? specifier
    : specifier.endsWith(".js")
    ? `${specifier.slice(0, -3)}.ts`
    : `${specifier}.ts`;
  const sourcePath = resolve(sourceRoot, dirname(importer), sourceSpecifier);
  const name = relative(sourceRoot, sourcePath);
  if (name === ".." || name.startsWith(`..${sep}`)) {
    throw new Error(`Contract import escapes packages/contracts/src: ${specifier}`);
  }
  return name.split(sep).join("/");
}

function transform(source) {
  return source.replace(/\r\n/g, "\n").replace(
    /((?:\bfrom\s*|\bimport\s*)["'])(\.{1,2}\/[^"']+)(["'])/g,
    (_match, prefix, specifier, quote) => {
      const runtimeSpecifier = specifier.endsWith(".ts")
        ? `${specifier.slice(0, -3)}.js`
        : /\.[^/]+$/.test(specifier)
        ? specifier
        : `${specifier}.js`;
      return `${prefix}${runtimeSpecifier}${quote}`;
    },
  );
}

async function expectedFiles() {
  const files = new Map();
  const visit = async (name) => {
    if (files.has(name)) return;
    const source = await readFile(resolve(sourceRoot, name), "utf8");
    files.set(name, `${header}${transform(source)}`);
    for (const specifier of importedPaths(source)) {
      await visit(sourceName(name, specifier));
    }
  };
  for (const entrypoint of entrypoints) await visit(entrypoint);
  return new Map([...files].sort(([a], [b]) => a.localeCompare(b)));
}

async function listFiles(directory, prefix = "") {
  let entries;
  try {
    entries = await readdir(resolve(directory, prefix), { withFileTypes: true });
  } catch (error) {
    if (error.code === "ENOENT") return [];
    throw error;
  }
  const files = [];
  for (const entry of entries) {
    const name = prefix ? `${prefix}/${entry.name}` : entry.name;
    if (entry.isDirectory()) files.push(...await listFiles(directory, name));
    else files.push(name);
  }
  return files.sort();
}

const expected = await expectedFiles();
const existing = await listFiles(destinationRoot);
if (process.argv.includes("--check")) {
  const stale = [];
  for (const [name, content] of expected) {
    let current;
    try {
      current = await readFile(resolve(destinationRoot, name), "utf8");
    } catch (error) {
      if (error.code !== "ENOENT") throw error;
    }
    if (current !== content) stale.push(name);
  }
  for (const name of existing) if (!expected.has(name)) stale.push(`${name} (extra)`);
  if (stale.length) {
    console.error(`MCP contract vendor is stale: ${stale.join(", ")}`);
    process.exitCode = 1;
  } else {
    console.log(`MCP contract vendor is up to date (${expected.size} files)`);
  }
} else {
  await mkdir(destinationRoot, { recursive: true });
  for (const name of existing) {
    if (!expected.has(name)) await rm(resolve(destinationRoot, name));
  }
  for (const [name, content] of expected) {
    const target = resolve(destinationRoot, name);
    await mkdir(dirname(target), { recursive: true });
    await writeFile(target, content, "utf8");
  }
  console.log(`Synced ${expected.size} MCP contract files`);
}
