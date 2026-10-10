import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import addFormats from "ajv-formats";
import Ajv2020 from "ajv/dist/2020";
import type { AnySchema, ValidateFunction } from "ajv";
import { schemaRegistry, type ContractName } from "../src/registry.ts";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../../..");
const contractsPath = resolve(repoRoot, "docs/plan/contracts.md");
const sectionSchemas: Record<number, ContractName> = {
  2: "session.create.request.v1",
  3: "analysis.event.v1",
  5: "window_result.v1",
  7: "match.v1",
};

export type DocExample = {
  section: number;
  schemaName: ContractName;
  value: unknown;
  block: number;
};

export function replaceDocPlaceholders(value: unknown, key = ""): unknown {
  if (typeof value === "string" && value.includes("…")) {
    if (key === "sha256") return "a".repeat(64);
    if (key === "source_uri") return "content://media/doc";
    if (key.endsWith("_at") || key === "at") return "2026-01-01T00:00:00Z";
    if (/^(?:[A-Za-z]+_)+…$/.test(value)) return value.replaceAll("…", "doc");
    return value.replaceAll("…", "doc");
  }
  if (Array.isArray(value)) {
    return value.map((item) => replaceDocPlaceholders(item, key));
  }
  if (value !== null && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value).map(([childKey, childValue]) => [
        childKey,
        replaceDocPlaceholders(childValue, childKey),
      ]),
    );
  }
  return value;
}

export function getDocExamples(): DocExample[] {
  const text = readFileSync(contractsPath, "utf8");
  const headings = [...text.matchAll(/^##\s+(\d+)\./gm)].map((match) => ({
    section: Number(match[1]),
    index: match.index ?? 0,
  }));
  const blocks = [...text.matchAll(/```json\s*\n([\s\S]*?)\n```/g)];

  return blocks.map((block, blockIndex) => {
    const index = block.index ?? 0;
    const heading = headings.filter((item) => item.index < index).at(-1);
    if (!heading) throw new Error(`JSON block ${blockIndex + 1} has no numbered section`);

    const parsed: unknown = JSON.parse(block[1]);
    const replaced = replaceDocPlaceholders(parsed);
    const embeddedName =
      replaced !== null &&
      typeof replaced === "object" &&
      "schema" in replaced &&
      typeof replaced.schema === "string"
        ? replaced.schema
        : undefined;
    const name = embeddedName ?? sectionSchemas[heading.section];
    if (!name || !(name in schemaRegistry)) {
      throw new Error(
        `JSON block ${blockIndex + 1} in §${heading.section} has no registered schema mapping`,
      );
    }

    return {
      section: heading.section,
      schemaName: name as ContractName,
      value: replaced,
      block: blockIndex + 1,
    };
  });
}

const ajv = new Ajv2020({ allErrors: true, strict: true });
addFormats(ajv);
const validators = new Map<ContractName, ValidateFunction>();

export function getGeneratedValidator(name: ContractName): ValidateFunction {
  const existing = validators.get(name);
  if (existing) return existing;

  const entry = schemaRegistry[name];
  const schemaPath = resolve(repoRoot, "packages/contracts/schemas", entry.file);
  const schema = JSON.parse(readFileSync(schemaPath, "utf8")) as AnySchema;
  const validator = ajv.compile(schema);
  validators.set(name, validator);
  return validator;
}

export function describeZodFailure(result: { success: boolean; error?: { issues: unknown[] } }): string {
  if (result.success || !result.error) return "";
  return JSON.stringify(result.error.issues);
}

export function cloneExample<T>(value: T): T {
  return structuredClone(value);
}
