import assert from "node:assert/strict";
import analysisCreateResponse from "../../../../packages/contracts/schemas/analysis.create.response.v1.schema.json" with {
  type: "json",
};
import analysisEvent from "../../../../packages/contracts/schemas/analysis.event.v1.schema.json" with {
  type: "json",
};
import analysisEventsPage from "../../../../packages/contracts/schemas/analysis.events.page.v1.schema.json" with {
  type: "json",
};
import analysisGetResponse from "../../../../packages/contracts/schemas/analysis.get.response.v1.schema.json" with {
  type: "json",
};
import errorEnvelope from "../../../../packages/contracts/schemas/error.envelope.v1.schema.json" with {
  type: "json",
};
import healthResponse from "../../../../packages/contracts/schemas/health.response.v1.schema.json" with {
  type: "json",
};
import internalArtifactRequest from "../../../../packages/contracts/schemas/internal.artifact.request.v1.schema.json" with {
  type: "json",
};
import internalArtifactResponse from "../../../../packages/contracts/schemas/internal.artifact.response.v1.schema.json" with {
  type: "json",
};
import internalEventRequest from "../../../../packages/contracts/schemas/internal.event.request.v1.schema.json" with {
  type: "json",
};
import internalSegmentsPage from "../../../../packages/contracts/schemas/internal.segments.page.v1.schema.json" with {
  type: "json",
};
import matchV1 from "../../../../packages/contracts/schemas/match.v1.schema.json" with {
  type: "json",
};
import sessionCreateResponse from "../../../../packages/contracts/schemas/session.create.response.v1.schema.json" with {
  type: "json",
};
import sessionGetResponse from "../../../../packages/contracts/schemas/session.get.response.v1.schema.json" with {
  type: "json",
};
import uploadAbortResponse from "../../../../packages/contracts/schemas/upload.abort.response.v1.schema.json" with {
  type: "json",
};
import uploadCompleteResponse from "../../../../packages/contracts/schemas/upload.complete.response.v1.schema.json" with {
  type: "json",
};
import confirmedPoint from "../../../../packages/contracts/schemas/confirmed.point.v1.schema.json" with {
  type: "json",
};
import comparisonsResponse from "../../../../packages/contracts/schemas/comparisons.response.v1.schema.json" with {
  type: "json",
};
import reviewConfirmRequest from "../../../../packages/contracts/schemas/review.confirm.request.v1.schema.json" with {
  type: "json",
};
import segmentCompleteResponse from "../../../../packages/contracts/schemas/segment.complete.response.v1.schema.json" with {
  type: "json",
};
import segmentRegisterRequest from "../../../../packages/contracts/schemas/segment.register.request.v1.schema.json" with {
  type: "json",
};
import segmentRegisterResponse from "../../../../packages/contracts/schemas/segment.register.response.v1.schema.json" with {
  type: "json",
};
import uploadPartsResponse from "../../../../packages/contracts/schemas/upload.parts.response.v1.schema.json" with {
  type: "json",
};

// Minimal JSON Schema (draft 2020-12 subset used by packages/contracts/schemas) checker.
type Schema = Record<string, unknown>;

const schemas: Record<string, Schema> = {
  "analysis.create.response.v1": analysisCreateResponse as Schema,
  "analysis.event.v1": analysisEvent as Schema,
  "analysis.events.page.v1": analysisEventsPage as Schema,
  "analysis.get.response.v1": analysisGetResponse as Schema,
  "confirmed.point.v1": confirmedPoint as Schema,
  "comparisons.response.v1": comparisonsResponse as Schema,
  "error.envelope.v1": errorEnvelope as Schema,
  "health.response.v1": healthResponse as Schema,
  "internal.artifact.request.v1": internalArtifactRequest as Schema,
  "internal.artifact.response.v1": internalArtifactResponse as Schema,
  "internal.event.request.v1": internalEventRequest as Schema,
  "internal.segments.page.v1": internalSegmentsPage as Schema,
  "match.v1": matchV1 as Schema,
  "review.confirm.request.v1": reviewConfirmRequest as Schema,
  "segment.complete.response.v1": segmentCompleteResponse as Schema,
  "segment.register.request.v1": segmentRegisterRequest as Schema,
  "segment.register.response.v1": segmentRegisterResponse as Schema,
  "session.create.response.v1": sessionCreateResponse as Schema,
  "session.get.response.v1": sessionGetResponse as Schema,
  "upload.abort.response.v1": uploadAbortResponse as Schema,
  "upload.complete.response.v1": uploadCompleteResponse as Schema,
  "upload.parts.response.v1": uploadPartsResponse as Schema,
};

export function load(name: string): Schema {
  const schema = schemas[name];
  if (!schema) throw new Error(`unknown contract schema: ${name}`);
  return schema;
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
