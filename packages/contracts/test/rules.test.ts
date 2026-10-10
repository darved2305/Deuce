import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import {
  analysisEventTypeSchema,
  analysisStateSchema,
  reviewEstimateSchema,
} from "../src/api.ts";
import {
  canonicalModelIdSchema,
  capabilityStatusSchema,
  capabilityTierSchema,
  idSchemas,
  provenanceStateSchema,
} from "../src/common.ts";
import { schemaRegistry, type ContractName } from "../src/registry.ts";
import { cloneExample, getDocExamples, getGeneratedValidator } from "./doc-examples.ts";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../../..");
const spec = readFileSync(resolve(repoRoot, "docs/plan/contracts.md"), "utf8");

function example(name: ContractName): Record<string, any> {
  const found = getDocExamples().find((item) => item.schemaName === name);
  assert.ok(found, `missing contracts.md example for ${name}`);
  return cloneExample(found.value as Record<string, any>);
}

function zodAccepts(name: ContractName, value: unknown): boolean {
  return schemaRegistry[name].schema.safeParse(value).success;
}

function jsonAccepts(name: ContractName, value: unknown): boolean {
  return getGeneratedValidator(name)(value) as boolean;
}

// Pulls a list written in the spec's prose, e.g. "`a | b | c`" or "`a`, `b`, `c`".
function proseList(pattern: RegExp, separator: RegExp): string[] {
  const match = spec.match(pattern);
  assert.ok(match, `contracts.md no longer contains ${pattern}; update this test with the spec`);
  return match[1]
    .split(separator)
    .map((item) => item.replace(/`/g, "").trim())
    .filter(Boolean);
}

function backticked(pattern: RegExp): string[] {
  const match = spec.match(pattern);
  assert.ok(match, `contracts.md no longer contains ${pattern}; update this test with the spec`);
  return [...match[0].matchAll(/`([^`]+)`/g)].map((m) => m[1]);
}

test("enums written in contracts.md prose match the schemas", () => {
  assert.deepEqual(
    proseList(/sibling state `([^`]+)`/, /\|/),
    provenanceStateSchema.options,
    "§0 provenance states",
  );
  assert.deepEqual(
    proseList(/\*\*Capability tier\*\* \(target\): `([^`]+)`/, /\|/),
    capabilityTierSchema.options,
    "§0 capability tiers",
  );
  assert.deepEqual(
    proseList(/\*\*Status\*\*\s+\(verified\): `([^`]+)`/, /\|/),
    capabilityStatusSchema.options,
    "§0 capability statuses",
  );
  assert.deepEqual(
    proseList(/States: `([^`]+)`/, /,/),
    analysisStateSchema.options,
    "§3 analysis states",
  );
  assert.deepEqual(
    // Event types always contain a dot; this skips the "(with `reason`)" aside.
    backticked(/Types: [\s\S]*?event `point\.confirmed`/).filter((item) => item.includes(".")),
    analysisEventTypeSchema.options,
    "§3 event types",
  );
  assert.deepEqual(
    backticked(/Canonical IDs: [\s\S]*?\./),
    canonicalModelIdSchema.options,
    "§6 canonical model IDs",
  );
  assert.deepEqual(
    proseList(/Review estimate enum `([^`]+)`/, /\|/),
    reviewEstimateSchema.options,
    "§7 review estimates",
  );
  const prefixes = backticked(/\*\*IDs:\*\* [\s\S]*?\n- \*\*Provenance/).map((id) => id.replace(/…$/, ""));
  assert.ok(prefixes.length >= 16, "§0 ID prefix list not found");
  for (const prefix of prefixes) {
    assert.ok(prefix in idSchemas, `§0 lists ID prefix ${prefix} but the schemas do not`);
  }
});

test("the engine may post only its own four event types to /internal (§3, D-261010-NP1)", () => {
  const owned = { type: "window.completed", window_seq: 3, emit_range_us: [12000000, 18000000] };
  assert.equal(zodAccepts("internal.event.request.v1", owned), true);
  assert.equal(jsonAccepts("internal.event.request.v1", owned), true);
  for (const type of ["analysis.completed", "analysis.finalizing", "analysis.queued", "point.confirmed"]) {
    assert.equal(zodAccepts("internal.event.request.v1", { type }), false, `Zod accepted ${type}`);
    assert.equal(jsonAccepts("internal.event.request.v1", { type }), false, `JSON Schema accepted ${type}`);
  }
});

test("times are never negative (§0)", () => {
  const session = example("session.create.request.v1");
  session.source.duration_ms = -1;
  assert.equal(zodAccepts("session.create.request.v1", session), false);
  assert.equal(jsonAccepts("session.create.request.v1", session), false);
});

test("the court template matches the format (§2)", () => {
  const session = example("session.create.request.v1");
  session.format = "doubles";
  assert.equal(zodAccepts("session.create.request.v1", session), false);
  session.calibration_input.template = "badminton_doubles";
  assert.equal(zodAccepts("session.create.request.v1", session), true);
});

test("a free-play floor rectangle needs its four tapped corners (§2)", () => {
  const session = example("session.create.request.v1");
  session.sport = "free_play";
  session.free_play_seed = { t_ms: 2400, image_xy: [812, 431] };
  session.calibration_input = { method: "tap", plane: "floor", frame_ms: 1200 };
  assert.equal(zodAccepts("session.create.request.v1", session), false);
  assert.equal(jsonAccepts("session.create.request.v1", session), false);
  session.calibration_input.points_px = example("session.create.request.v1").calibration_input.points_px;
  assert.equal(zodAccepts("session.create.request.v1", session), true);
  assert.equal(jsonAccepts("session.create.request.v1", session), true);
  session.calibration_input = null;
  assert.equal(zodAccepts("session.create.request.v1", session), true);
});

test("airborne match events stay in image space; surface events need their plane (§0, §7)", () => {
  const match = example("match.v1");
  const landing = match.events[0];
  const hit = { ...landing, type: "hit", image_xy: [790, 210] };
  delete hit.plane;
  delete hit.contact_xy_m;
  delete hit.cov_m2;
  match.events = [landing, hit];
  assert.equal(zodAccepts("match.v1", match), true);
  assert.equal(jsonAccepts("match.v1", match), true);

  const noPlane = example("match.v1");
  delete noPlane.events[0].plane;
  assert.equal(zodAccepts("match.v1", noPlane), false);
});
