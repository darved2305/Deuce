import { readFile, readdir } from "node:fs/promises";
import { dirname, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import addFormats from "ajv-formats";
import Ajv2020 from "ajv/dist/2020";
import type { AnySchema, ValidateFunction } from "ajv";
import { schemaRegistry } from "../src/registry.ts";

const packageRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const repoRoot = resolve(packageRoot, "../..");
const fixtureRoot = resolve(repoRoot, "fixtures");
const generatedRoot = resolve(packageRoot, "schemas");
const modelsPath = resolve(repoRoot, "engine/elevate_engine/models.json");

const ajv = new Ajv2020({ allErrors: true, strict: true });
addFormats(ajv);

const validators = new Map<string, ValidateFunction>();
for (const [name, entry] of Object.entries(schemaRegistry)) {
  const schemaText = await readFile(resolve(generatedRoot, entry.file), "utf8");
  validators.set(name, ajv.compile(JSON.parse(schemaText) as AnySchema));
}

const failures: string[] = [];
let validated = 0;

function validationErrors(validator: ValidateFunction): string {
  return (validator.errors ?? [])
    .map((error) => `${error.instancePath || "/"} ${error.message ?? "invalid"}`)
    .join("; ");
}

function validate(name: string, value: unknown, path: string): void {
  const validator = validators.get(name);
  if (!validator) {
    failures.push(`${path}: no registered schema named ${name}`);
    return;
  }
  if (!validator(value)) {
    failures.push(`${path}: ${validationErrors(validator)}`);
    return;
  }
  validated += 1;
  console.log(`validated ${path} (${name})`);
}

async function listFiles(directory: string): Promise<string[]> {
  let entries;
  try {
    entries = await readdir(directory, { withFileTypes: true });
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return [];
    throw error;
  }

  const files = await Promise.all(
    entries.map(async (entry) => {
      const path = resolve(directory, entry.name);
      return entry.isDirectory() ? listFiles(path) : [path];
    }),
  );
  return files.flat();
}

function fixtureSchemaName(file: string, value: unknown): string | undefined {
  if (
    value !== null &&
    typeof value === "object" &&
    "schema" in value &&
    typeof value.schema === "string"
  ) {
    return value.schema;
  }

  const name = file.split(/[\\/]/).at(-1) ?? "";
  if (name === "fixture_match.json") return "match.v1";
  if (name === "fixture_events.jsonl") return "analysis.event.v1";
  if (/^fixture_window_.*\.json$/.test(name)) return "window_result.v1";
  if (name === "fixture_trail.json") return "elevate.trail_labels.v1";
  return undefined;
}

const fixtureFiles = (await listFiles(fixtureRoot)).filter((file) =>
  /\.jsonl?$/.test(file),
);

const expectedFixtures = [
  "fixture_trail.json",
  "fixture_match.json",
  "fixture_events.jsonl",
  "fixture_window_0003.json",
];
for (const name of expectedFixtures) {
  if (!fixtureFiles.some((file) => file.endsWith(`/${name}`) || file.endsWith(`\\${name}`))) {
    console.log(`OWED by Person 4 (contracts.md §11): fixtures/${name} does not exist yet.`);
  }
}

for (const file of fixtureFiles) {
  const shortPath = relative(repoRoot, file);
  const text = await readFile(file, "utf8");
  if (file.endsWith(".jsonl")) {
    for (const [index, line] of text.split(/\r?\n/).entries()) {
      if (!line.trim()) continue;
      const path = `${shortPath}:${index + 1}`;
      try {
        const value: unknown = JSON.parse(line);
        const name = fixtureSchemaName(file, value);
        if (!name) failures.push(`${path}: no schema field or §11 filename mapping`);
        else validate(name, value, path);
      } catch (error) {
        failures.push(`${path}: invalid JSON (${String(error)})`);
      }
    }
    continue;
  }

  try {
    const value: unknown = JSON.parse(text);
    const name = fixtureSchemaName(file, value);
    if (!name) failures.push(`${shortPath}: no schema field or §11 filename mapping`);
    else validate(name, value, shortPath);
  } catch (error) {
    failures.push(`${shortPath}: invalid JSON (${String(error)})`);
  }
}

try {
  const models: unknown = JSON.parse(await readFile(modelsPath, "utf8"));
  if (models === null || typeof models !== "object" || Array.isArray(models)) {
    failures.push("engine/elevate_engine/models.json: expected an object of model entries");
  } else {
    for (const [id, model] of Object.entries(models)) {
      if (
        model === null ||
        typeof model !== "object" ||
        !("descriptor" in model)
      ) {
        failures.push(`engine/elevate_engine/models.json#${id}.descriptor: missing descriptor`);
        continue;
      }
      validate(
        "model.descriptor.v1",
        model.descriptor,
        `engine/elevate_engine/models.json#${id}.descriptor`,
      );
    }
  }
} catch (error) {
  failures.push(`engine/elevate_engine/models.json: ${String(error)}`);
}

if (failures.length > 0) {
  for (const failure of failures) console.error(failure);
  process.exitCode = 1;
} else {
  console.log(`Validation passed for ${validated} data records.`);
}
