import { createHash } from "node:crypto";
import { mkdir, readdir, rm, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import * as z from "zod";
import { schemaRegistry } from "../src/registry.ts";

const packageRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const schemasDirectory = resolve(packageRoot, "schemas");

function sortKeys(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sortKeys);
  if (value === null || typeof value !== "object") return value;

  return Object.fromEntries(
    Object.entries(value)
      .sort(([left], [right]) => (left < right ? -1 : left > right ? 1 : 0))
      .map(([key, nested]) => [key, sortKeys(nested)]),
  );
}

function serialize(value: unknown): string {
  return `${JSON.stringify(sortKeys(value), null, 2)}\n`;
}

await mkdir(schemasDirectory, { recursive: true });

const names = Object.keys(schemaRegistry).sort();
const expectedFiles = new Set([
  "index.json",
  ...names.map((name) => schemaRegistry[name as keyof typeof schemaRegistry].file),
]);

for (const file of await readdir(schemasDirectory)) {
  if (!expectedFiles.has(file)) {
    await rm(resolve(schemasDirectory, file), { recursive: true, force: true });
  }
}

const index: Array<{
  name: string;
  version: string;
  file: string;
  sha256: string;
}> = [];

for (const name of names) {
  const entry = schemaRegistry[name as keyof typeof schemaRegistry];
  const generated = z.toJSONSchema(entry.schema, {
    target: "draft-2020-12",
    io: "output",
    unrepresentable: "throw",
  });
  const contents = serialize(generated);
  await writeFile(resolve(schemasDirectory, entry.file), contents, "utf8");
  index.push({
    name,
    version: entry.version,
    file: entry.file,
    sha256: createHash("sha256").update(contents).digest("hex"),
  });
}

await writeFile(resolve(schemasDirectory, "index.json"), serialize(index), "utf8");
console.log(`Generated ${index.length} schemas in ${schemasDirectory}`);
