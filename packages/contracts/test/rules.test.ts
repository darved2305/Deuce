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
  artifactKindSchema,
  builtSportSchema,
  matchFormatSchema,
  sessionModeSchema,
  canonicalModelIdSchema,
  capabilityStatusSchema,
  capabilityTierSchema,
  idSchemas,
  provenanceStateSchema,
} from "../src/common.ts";
import { readdirSync } from "node:fs";
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
  session.format = "none";
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

// Person 3's migrations hold the database's CHECK constraints. The contracts must accept exactly
// what the database accepts, or the API returns a 500 instead of a clear 4xx.
function databaseChecks(): Record<string, string[]> {
  const dir = resolve(repoRoot, "supabase/migrations");
  const sql = readdirSync(dir).filter((f) => f.endsWith(".sql")).sort()
    .map((f) => readFileSync(resolve(dir, f), "utf8")).join("\n");
  const checks: Record<string, string[]> = {};
  const re = /\b(\w+)\s+text\s+not\s+null(?:\s+default\s+'[^']*')?\s+check\s*\(\s*\1\s+in\s*\(([^)]*)\)\s*\)/g;
  for (const m of sql.matchAll(re)) {
    checks[m[1]] = [...m[2].matchAll(/'([^']*)'/g)].map((v) => v[1]);
  }
  return checks;
}

test("enums match the database CHECK constraints in supabase/migrations", () => {
  const db = databaseChecks();
  const same: Array<[string, readonly string[]]> = [
    ["sport", builtSportSchema.options],
    ["format", matchFormatSchema.options],
    ["mode", sessionModeSchema.options],
    ["kind", artifactKindSchema.options],
    ["state", analysisStateSchema.options],
    ["type", analysisEventTypeSchema.options],
  ];
  for (const [column, options] of same) {
    assert.ok(db[column], `no CHECK constraint found for ${column}; update this test with the migration`);
    assert.deepEqual([...options].sort(), [...db[column]].sort(), `${column}: contracts vs database`);
  }
  assert.deepEqual(["multipart", "segments"], [...(db.upload_mode ?? [])].sort(), "upload_mode: contracts vs database");
});

test("free play has format none and racket sports never do", () => {
  const session = example("session.create.request.v1");
  session.format = "none";
  assert.equal(zodAccepts("session.create.request.v1", session), false);
  session.sport = "squash";
  session.format = "singles";
  assert.equal(zodAccepts("session.create.request.v1", session), false, "squash is not a built pack");
  assert.equal(jsonAccepts("session.create.request.v1", session), false);
});

// fixture_trail.json (D-261011-HW4): Person 2's real hand labels, one shape for app and CI.
function trailFixture(): Record<string, any> {
  return JSON.parse(readFileSync(resolve(repoRoot, "fixtures/fixture_trail.json"), "utf8"));
}

test("fixture_trail.json validates as elevate.trail_labels.v1, and broken copies do not", () => {
  const doc = trailFixture();
  assert.equal(zodAccepts("elevate.trail_labels.v1", doc), true);
  assert.equal(jsonAccepts("elevate.trail_labels.v1", doc), true);

  const machineAsHand = cloneExampleJson(doc);
  machineAsHand.provenance.engine_version = "0.3.0";
  assert.equal(zodAccepts("elevate.trail_labels.v1", machineAsHand), false, "user state with engine output");

  const offFrame = cloneExampleJson(doc);
  offFrame.frames.find((f: any) => f.ball).ball = [doc.clip.width + 5, 10];
  assert.equal(zodAccepts("elevate.trail_labels.v1", offFrame), false, "ball outside the frame");

  const outOfRange = cloneExampleJson(doc);
  outOfRange.frames[0].t_us = doc.range_us[1] + 20000;
  assert.equal(zodAccepts("elevate.trail_labels.v1", outOfRange), false, "frame outside range_us");

  const unknownField = cloneExampleJson(doc);
  unknownField.frames[0].confidence = 0.9;
  assert.equal(zodAccepts("elevate.trail_labels.v1", unknownField), false);
  assert.equal(jsonAccepts("elevate.trail_labels.v1", unknownField), false);
});

test("the app's bundled trail labels are identical to fixtures/fixture_trail.json", () => {
  const dir = resolve(repoRoot, "android/app/src/main/assets/fixtures");
  let bundled: string[] = [];
  try {
    bundled = readdirSync(dir).filter((f) => f.startsWith("trail_") && f.endsWith(".json"));
  } catch {
    return; // no Android assets in this checkout
  }
  for (const file of bundled) {
    const app = JSON.parse(readFileSync(resolve(dir, file), "utf8"));
    if (app.clip?.id !== trailFixture().clip.id) continue;
    assert.deepEqual(app, trailFixture(), `${file} differs from fixtures/fixture_trail.json`);
  }
});

function cloneExampleJson<T>(value: T): T {
  return JSON.parse(JSON.stringify(value));
}

// Issue #20 (contracts amendment, D-261011-HW6).
test("#20: read events carry every §3 type; engine_version is optional on them", () => {
  const queued = {
    event_id: "e_doc", analysis_id: "a_doc", match_id: "m_doc", seq: 1, schema_version: 1,
    type: "analysis.queued", created_at: "2026-10-11T00:00:00Z",
  };
  for (const type of ["analysis.queued", "analysis.finalizing", "analysis.completed", "point.confirmed"]) {
    assert.equal(zodAccepts("analysis.event.v1", { ...queued, type }), true, `read event ${type}`);
    assert.equal(jsonAccepts("analysis.event.v1", { ...queued, type }), true, `read event ${type} (JSON Schema)`);
  }
  assert.equal(zodAccepts("analysis.event.v1", { ...queued, engine_version: null }), true);
});

test("#20: the engine may send engine_version; window events need window_seq", () => {
  const started = { type: "analysis.started", engine_version: "0.3.0" };
  assert.equal(zodAccepts("internal.event.request.v1", started), true);
  assert.equal(jsonAccepts("internal.event.request.v1", started), true);
  for (const type of ["window.completed", "window.failed"]) {
    const noSeq = { type, reason: "decode_error", emit_range_us: [0, 6000000] };
    assert.equal(zodAccepts("internal.event.request.v1", noSeq), false, `${type} without window_seq`);
    assert.equal(jsonAccepts("internal.event.request.v1", noSeq), false, `${type} without window_seq (JSON Schema)`);
  }
});

test("#20: upload part URLs come with their part number and expiry", () => {
  const ok = { parts: [{ n: 1, url: "https://example.r2.cloudflarestorage.com/x?X-Amz-Signature=doc", expires_at: "2026-10-11T00:10:00Z" }] };
  assert.equal(zodAccepts("upload.parts.response.v1", ok), true);
  assert.equal(jsonAccepts("upload.parts.response.v1", ok), true);
  assert.equal(zodAccepts("upload.parts.response.v1", ["https://example.com/a"]), false, "bare URL list is the old shape");
});

test("segments: register needs no gaps in shape, keys are typed, the engine page is ordered data", async () => {
  const { buildSegmentKey, segmentKeyRegex } = await import("../src/keys.ts");
  const key = buildSegmentKey("s_doc", 3);
  assert.match(key, segmentKeyRegex);
  const reg = { seq: 0, pts_start_us: 0, pts_end_us: 6000000, starts_on_sync_sample: true, bytes: 10485760, sha256: "0".repeat(64), final: false };
  assert.equal(zodAccepts("segment.register.request.v1", reg), true);
  assert.equal(jsonAccepts("segment.register.request.v1", reg), true);
  assert.equal(zodAccepts("segment.register.request.v1", { ...reg, final: undefined }), false, "final is required");
  const page = { segments: [{ ...reg, r2_key: buildSegmentKey("s_doc", 0) }], final_seq: null };
  assert.equal(zodAccepts("internal.segments.page.v1", page), true);
  assert.equal(zodAccepts("internal.segments.page.v1", { ...page, segments: [{ ...reg, r2_key: "orig/s_doc/source.mp4" }] }), false, "segment keys only");
});

test("1.7.0: engine reviews are unconfirmed; confirmations are all-or-none; match-level metrics", () => {
  const m = example("match.v1");
  const engine = { ...m, reviews: [{ ...m.reviews[0], confirmed_point_winner: null, confirmed_by: null, confirmed_at: null }] };
  assert.equal(zodAccepts("match.v1", engine), true, "engine estimate with null confirmation");
  assert.equal(jsonAccepts("match.v1", engine), true);
  const half = { ...m, reviews: [{ ...m.reviews[0], confirmed_by: null }] };
  assert.equal(zodAccepts("match.v1", half), false, "partial confirmation");
  const matchLevel = { ...m, metrics: [{ ...m.metrics[0], player_id: null }] };
  assert.equal(zodAccepts("match.v1", matchLevel), true, "match-level metric");
});

test("1.8.0: uncalibrated match, hits as events, withheld feet, segments session, windows_expected", () => {
  const m = example("match.v1");
  assert.equal(zodAccepts("match.v1", { ...m, calibration: null }), true, "uncalibrated match");
  assert.equal(jsonAccepts("match.v1", { ...m, calibration: null }), true);
  const hit = { event_id: "e_hit", type: "hit", t_ms: 21430, t_sigma_ms: 10, image_xy: [922.5, 723.8], cues: ["audio", "trajectory"], state: "candidate", evidence: [{ t_ms: 21430 }], track_id: "t_1" };
  assert.equal(zodAccepts("match.v1", { ...m, events: [hit] }), true, "hit candidate without rally");
  assert.equal(zodAccepts("match.v1", { ...m, calibration: { ...m.calibration, tier: "D" } }), false, "tier A|B|C");
  assert.equal(zodAccepts("match.v1", { ...m, calibration: { ...m.calibration, H_cov: [1, 2] } }), false, "H_cov is 81 numbers");
  const w = example("window_result.v1");
  w.players = [[12000, "t_1", 410, 520, 470, 700, null, null, 0.97]];
  assert.equal(zodAccepts("window_result.v1", w), true, "withheld foot metres");
  assert.equal(jsonAccepts("window_result.v1", w), true);
  const seg = { session_id: "s_doc", match_id: "m_doc", upload_mode: "segments", segments: { max_segment_bytes: 20971520, max_concurrency: 2, url_ttl_s: 600 } };
  assert.equal(zodAccepts("session.create.response.v1", seg), true, "segments session");
  assert.equal(zodAccepts("internal.event.request.v1", { type: "analysis.started", engine_version: "0.1.0", windows_expected: 81 }), true);
  const fin = { vision_result: { ...m, calibration: null, rallies: [], shots: [], events: [], reviews: [], confirmed_points: [], score: { timeline: [], games: [] }, metrics: [], moments: [], clips: [], corrections: [] }, clip_artifact_ids: [] };
  delete (fin.vision_result as any).insights;
  assert.equal(zodAccepts("analysis.finalize.request.v1", fin), true, "minimal finalize body");
});

test("1.9.0 (#77): the engine can build finalize from what dispatch hands it", () => {
  const m = example("match.v1");
  const sub = { analysis_id: "a_doc", r2_key: "orig/s_doc/source.mp4", session_spec: example("session.create.request.v1") };
  assert.equal(zodAccepts("modal.submit.request.v1", { ...sub, match_id: "m_doc", session_id: "s_doc" }), true, "ids at dispatch");
  assert.equal(jsonAccepts("modal.submit.request.v1", { ...sub, match_id: "m_doc", session_id: "s_doc" }), true);
  assert.equal(zodAccepts("modal.submit.request.v1", sub), true, "old engine request still valid");
  assert.equal(zodAccepts("modal.submit.request.v1", { ...sub, match_id: "x_doc" }), false, "typed match id");
  const fin = { vision_result: { ...m, players: [], calibration: null, analysis: { ...m.analysis, artifacts: { ball_track: null, player_tracks: null } }, rallies: [], shots: [], events: [], reviews: [], confirmed_points: [], score: { timeline: [], games: [] }, metrics: [], moments: [], clips: [], corrections: [] }, clip_artifact_ids: [] };
  delete (fin.vision_result as any).insights;
  assert.equal(zodAccepts("analysis.finalize.request.v1", fin), true, "no players, no whole-match tracks");
  assert.equal(jsonAccepts("analysis.finalize.request.v1", fin), true);
});

test("1.10.0 (#65): read pages reuse match.v1 pieces", () => {
  const m = example("match.v1");
  const rally = m.rallies[0];
  assert.equal(zodAccepts("rallies.page.v1", { items: m.rallies, next_cursor: null }), true, "rallies page");
  assert.equal(jsonAccepts("rallies.page.v1", { items: m.rallies, next_cursor: "c1" }), true);
  assert.equal(zodAccepts("rally.detail.v1", { ...rally, shots: m.shots }), true, "rally plus shots");
  assert.equal(zodAccepts("moments.page.v1", { items: m.moments.map((x: any) => ({ ...x, match_id: m.match_id })), next_cursor: null }), true);
  assert.equal(zodAccepts("moments.page.v1", { items: m.moments, next_cursor: null }), m.moments.length === 0, "moments need match_id");
  const item = { match_id: m.match_id, sport: m.sport, format: m.format, ruleset: m.ruleset, created_at: "2026-10-11T05:00:00+05:30", state: null, score: { games: m.score.games } };
  assert.equal(zodAccepts("matches.page.v1", { items: [item], next_cursor: null }), true, "matches page");
  assert.equal(jsonAccepts("matches.page.v1", { items: [item], next_cursor: null }), true);
  assert.equal(zodAccepts("capabilities.v1", { capabilities: m.capabilities }), true, "capabilities");
  assert.equal(zodAccepts("matches.page.v1", { items: [], next_cursor: "" }), false, "empty cursor is not a cursor");
});

test("1.11.0 (D-261011-HW31): friends and challenges shapes", () => {
  const uid = "11111111-1111-4111-8111-111111111111";
  const round = { hit_event_id: "e_1", landing_event_id: "e_2", contact_ms: 4200, contact_sigma_ms: 8, ladder_us: [3950000, 4050000, 4130000] };
  const result = { hit_event_id: "e_1", pick: "B2", reaction_ms: 640, verdict: "exact", points: 120 };
  const rounds = [round, { ...round, hit_event_id: "e_3" }, { ...round, hit_event_id: "e_5" }];
  const create = { match_id: "m_doc", display_name: "Hetansh", rounds, score: 120, round_results: [result] };
  assert.equal(zodAccepts("challenge.create.request.v1", create), true);
  assert.equal(jsonAccepts("challenge.create.request.v1", create), true);
  assert.equal(zodAccepts("challenge.create.request.v1", { ...create, rounds: rounds.slice(0, 2) }), false, "at least three rounds");
  assert.equal(zodAccepts("challenge.create.request.v1", { ...create, extra: 1 }), false, "producers reject unknown fields");
  assert.equal(zodAccepts("challenge.create.request.v1", { ...create, display_name: "x".repeat(25) }), false, "name is at most 24");
  assert.equal(zodAccepts("challenge.create.request.v1", { ...create, match_id: "s_doc" }), false, "typed match id");
  assert.equal(zodAccepts("challenge.entry.request.v1", { score: 90, round_results: [{ ...result, verdict: "wrong", pick: null, reaction_ms: null, points: 0 }] }), true);
  assert.equal(zodAccepts("challenge.entry.request.v1", { score: 90, round_results: [{ ...result, verdict: "lucky" }] }), false, "verdict enum");
  assert.equal(zodAccepts("challenge.accept.request.v1", {}), true);
  const entry = { player_id: uid, display_name: "Asha", score: null, round_results: null, outcome: null, accepted_at: "2026-10-11T10:00:00Z", played_at: null };
  const view = {
    id: "ch_doc", match_id: "m_doc", status: "open", video_sha256: "a".repeat(64), rematch_of: null, rounds,
    creator: { player_id: "22222222-2222-4222-8222-222222222222", display_name: "Hetansh", score: 120, round_results: [result] },
    entries: [entry], viewer: { player_id: uid, role: "opponent" }, created_at: "2026-10-11T09:00:00Z",
  };
  assert.equal(zodAccepts("challenge.v1", view), true);
  assert.equal(jsonAccepts("challenge.v1", view), true);
  assert.equal(zodAccepts("challenge.v1", { ...view, entries: [{ ...entry, score: 100, round_results: [result], outcome: "creator", played_at: "2026-10-11T10:05:00Z" }] }), true);
  assert.equal(zodAccepts("challenge.v1", { ...view, status: "done" }), false, "status enum");
  assert.equal(zodAccepts("challenge.v1", { ...view, id: "m_doc" }), false, "typed challenge id");
  const friends = { items: [{ player_id: uid, display_name: "Asha", challenges: 3, played: 2, wins: 1, losses: 1, ties: 0, last_challenge_at: "2026-10-11T10:00:00Z" }] };
  assert.equal(zodAccepts("friends.response.v1", friends), true);
  assert.equal(jsonAccepts("friends.response.v1", friends), true);
  assert.equal(zodAccepts("friends.response.v1", { items: [{ ...friends.items[0], wins: -1 }] }), false);
});

test("1.12.0 (D-261011-HW34): match post and feed shapes", () => {
  const uid = "11111111-1111-4111-8111-111111111111";
  const request = { caption: "Match summary" };
  assert.equal(zodAccepts("post.create.request.v1", request), true);
  assert.equal(jsonAccepts("post.create.request.v1", request), true);
  assert.equal(zodAccepts("post.create.request.v1", {}), true);
  assert.equal(zodAccepts("post.create.request.v1", { caption: "x".repeat(141) }), false);
  assert.equal(zodAccepts("post.create.request.v1", { caption: "x", extra: true }), false);

  const summary = {
    title: null,
    duration_ms: null,
    rally_count: null,
    hit_count: null,
    landings_in: null,
    landings_out: null,
    longest_rally_ms: null,
    top_coach_points: [],
    landing_points_m: [],
    owner_display_name: "Asha",
  };
  const post = {
    post_id: "po_doc",
    match_id: "m_doc",
    owner: { player_id: uid, display_name: "Asha" },
    created_at: "2026-10-11T10:00:00Z",
    caption: null,
    summary,
  };
  assert.equal(zodAccepts("post.v1", post), true);
  assert.equal(jsonAccepts("post.v1", post), true);
  assert.equal(zodAccepts("post.v1", { ...post, post_id: "m_doc" }), false);
  assert.equal(zodAccepts("post.summary.v1", { ...summary, top_coach_points: ["a", "b", "c", "d"] }), false);
  const feed = { items: [post], next_cursor: "cursor" };
  assert.equal(zodAccepts("feed.page.v1", feed), true);
  assert.equal(jsonAccepts("feed.page.v1", feed), true);
  assert.equal(zodAccepts("feed.page.v1", { items: [], next_cursor: null }), true);
});
