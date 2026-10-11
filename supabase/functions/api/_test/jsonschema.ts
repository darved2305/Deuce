import assert from "node:assert/strict";

// Minimal JSON Schema (draft 2020-12 subset used by packages/contracts/schemas) checker.
type Schema = Record<string, unknown>;

export function load(name: string): Schema {
  const url = new URL(
    `../../../../packages/contracts/schemas/${name}.schema.json`,
    import.meta.url,
  );
  return JSON.parse(Deno.readTextFileSync(url));
}

export function check(s: Schema, v: unknown, path = "$"): string[] {
  const errs: string[] = [];
  if (Array.isArray(s.anyOf)) {
    const branches = s.anyOf.map((b) => check(b as Schema, v, path));
    return branches.some((e) => e.length === 0) ? [] : [
      `${path}: no anyOf branch matched (${branches[0].join("; ")})`,
    ];
  }
  if (Array.isArray(s.oneOf)) {
    const matches = s.oneOf.filter((b) =>
      check(b as Schema, v, path).length === 0
    ).length;
    return matches === 1
      ? []
      : [`${path}: expected one oneOf branch, got ${matches}`];
  }
  if ("const" in s && s.const !== v) errs.push(`${path}: const`);
  if (Array.isArray(s.enum) && !s.enum.includes(v)) errs.push(`${path}: enum`);
  const schemaType = s.type;
  if (Array.isArray(schemaType)) {
    if (v === null && schemaType.includes("null")) return errs;
    const nonNullTypes = schemaType.filter((type) => type !== "null");
    if (nonNullTypes.length !== 1) return [`${path}: unsupported type union`];
    return [
      ...errs,
      ...check({ ...s, type: nonNullTypes[0] } as Schema, v, path),
    ];
  }
  const t = schemaType as string | undefined;
  if (t === "string") {
    if (typeof v !== "string") return [`${path}: not string`];
    if (typeof s.pattern === "string" && !new RegExp(s.pattern).test(v)) {
      errs.push(`${path}: pattern ${s.pattern}`);
    }
  } else if (t === "integer" || t === "number") {
    if (typeof v !== "number" || (t === "integer" && !Number.isInteger(v))) {
      return [`${path}: not ${t}`];
    }
    if (typeof s.minimum === "number" && v < s.minimum) {
      errs.push(`${path}: min`);
    }
    if (typeof s.maximum === "number" && v > s.maximum) {
      errs.push(`${path}: max`);
    }
  } else if (t === "boolean") {
    if (typeof v !== "boolean") errs.push(`${path}: not boolean`);
  } else if (t === "array") {
    if (!Array.isArray(v)) return [`${path}: not array`];
    if (typeof s.minItems === "number" && v.length < s.minItems) {
      errs.push(`${path}: minItems`);
    }
    if (typeof s.maxItems === "number" && v.length > s.maxItems) {
      errs.push(`${path}: maxItems`);
    }
    if (Array.isArray(s.prefixItems)) {
      if (v.length !== s.prefixItems.length) errs.push(`${path}: length`);
      s.prefixItems.forEach((p, i) =>
        errs.push(...check(p as Schema, v[i], `${path}[${i}]`))
      );
    } else if (s.items) {
      v.forEach((x, i) =>
        errs.push(...check(s.items as Schema, x, `${path}[${i}]`))
      );
    }
  } else if (t === "object") {
    if (typeof v !== "object" || v === null || Array.isArray(v)) {
      return [`${path}: not object`];
    }
    const o = v as Record<string, unknown>;
    const props = (s.properties ?? {}) as Record<string, Schema>;
    for (const k of (s.required ?? []) as string[]) {
      if (!(k in o)) errs.push(`${path}.${k}: missing`);
    }
    for (const [k, x] of Object.entries(o)) {
      if (props[k]) errs.push(...check(props[k], x, `${path}.${k}`));
      else if (s.additionalProperties === false) {
        errs.push(`${path}.${k}: unexpected`);
      }
    }
  }
  return errs;
}

export function conforms(name: string, value: unknown) {
  assert.deepEqual(check(load(name), value), [], name);
}
