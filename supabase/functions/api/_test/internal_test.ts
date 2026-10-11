import assert from "node:assert/strict";
import { fieldsToRpc, rowToWire } from "../lib/events.ts";
import { type Call, type Handler, makeApp, ok, type Res } from "./fakes.ts";
import { conforms } from "./jsonschema.ts";

function build(
  o: {
    append?: Res;
    existing?: unknown;
    insert?: Res[];
    ingest?: Res;
    segments?: unknown[];
  } = {},
) {
  const log: Call[] = [];
  let storedEvent: Record<string, unknown> | null = null;
  let existingCalls = 0;
  let currentRun = {
    id: "a_1",
    session_id: "s_1",
    match_id: "m_1",
    owner_id: "u1",
    state: "processing",
    last_seq: 1,
  };
  const inserts = o.insert ?? [ok({ id: "art_new", analysis_id: "a_1" })];
  const admin: Handler = (c) => {
    if (c.kind === "rpc" && c.name === "append_event") {
      const fields = c.args.p_fields as Record<string, unknown>;
      const result = o.append ?? ok({
        event_id: "e_1",
        analysis_id: "a_1",
        match_id: "m_1",
        seq: 1,
        schema_version: 1,
        type: c.args.p_type,
        window_seq: 0,
        decode_start_us: 10,
        decode_end_us: 20,
        emit_start_us: null,
        emit_end_us: null,
        artifact_id: null,
        reason: null,
        engine_version: fields.engine_version ?? null,
        created_at: "2026-01-01T00:00:00Z",
      });
      if (result.error === null) {
        storedEvent = result.data as Record<string, unknown>;
      }
      return result;
    }
    if (c.kind === "rpc" && c.name === "ingest_vision_result") {
      if (o.ingest) return o.ingest;
      if (currentRun.state === "completed") {
        return {
          data: null,
          error: { code: "P0001", message: "already_finalized" },
        };
      }
      currentRun = { ...currentRun, state: "completed", last_seq: 3 };
      return ok(null);
    }
    if (c.kind === "from" && c.table === "analysis_runs") {
      return ok(c.filters.id === "a_missing" ? null : currentRun);
    }
    if (c.kind === "from" && c.table === "segments") {
      return ok(o.segments ?? []);
    }
    if (c.kind === "from" && c.table === "artifacts") {
      if (c.op === "select") {
        existingCalls++;
        const e = o.existing;
        return ok(Array.isArray(e) ? e[existingCalls - 1] ?? null : e ?? null);
      }
      return inserts.shift() ?? ok(null);
    }
    return ok(null);
  };
  const user: Handler = (c) => {
    if (c.kind === "from" && c.table === "analysis_runs") {
      return ok({ id: "a_1", state: "processing" });
    }
    if (c.kind === "from" && c.table === "analysis_events") {
      return ok(storedEvent ? [storedEvent] : []);
    }
    return ok(null);
  };
  return { app: makeApp(user, admin, undefined, [], log), log };
}

function call(
  app: ReturnType<typeof build>["app"],
  path: string,
  body: unknown,
  token: string | null = "tok",
  id = "a_1",
) {
  return app.fetch(
    new Request(`http://localhost/api/internal/analyses/${id}/${path}`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(token ? { "X-Internal-Token": token } : {}),
      },
      body: JSON.stringify(body),
    }),
  );
}

function getSegments(
  app: ReturnType<typeof build>["app"],
  query = "",
  token: string | null = "tok",
) {
  return app.fetch(
    new Request(
      `http://localhost/api/internal/analyses/a_1/segments${query}`,
      { headers: token ? { "X-Internal-Token": token } : {} },
    ),
  );
}

const segmentRow = (seq: number, completed: boolean, final = false) => ({
  seq,
  pts_start_us: seq * 1_000_000,
  pts_end_us: (seq + 1) * 1_000_000,
  starts_on_sync_sample: true,
  bytes: 100,
  sha256: "a".repeat(64),
  final,
  r2_key: `seg/s_1/${seq}.mp4`,
  completed_at: completed ? "2026-10-11T10:00:00Z" : null,
});

const finalizeBody = {
  vision_result: {
    schema: "match.v1",
    match_id: "m_1",
    session_ids: ["s_1"],
    sport: "badminton",
    format: "singles",
    ruleset: { id: "bwf_3x21", version: "2025-04" },
    players: [
      {
        player_id: "p_me",
        track_ids: ["t_me"],
        label: "you",
        identity: "user_confirmed",
      },
    ],
    calibration: {
      calibration_id: "cal_1",
      plane: "court",
      template: "badminton_singles",
      method: "tap",
      H: [1, 0, 0, 0, 1, 0, 0, 0, 1],
      H_cov: null,
      residual_px: 0,
      visible_fraction: 1,
      tier: "A",
    },
    analysis: {
      analysis_id: "a_1",
      engine_version: "0.3.0",
      state: "processing",
      models: [],
      artifacts: { ball_track: "art_ball", player_tracks: "art_players" },
    },
    capabilities: {
      court: { tier: "core", status: "unknown" },
      ball: { tier: "core", status: "unknown" },
      reviews: { tier: "unsupported", status: "unknown" },
      score: { tier: "unsupported", status: "unknown" },
    },
    rallies: [],
    shots: [],
    events: [],
    reviews: [],
    confirmed_points: [],
    score: { timeline: [], games: [] },
    metrics: [],
    moments: [],
    clips: [],
    corrections: [],
  },
  clip_artifact_ids: [],
};

Deno.test("internal routes need the right token", async () => {
  const t = build();
  assert.equal((await call(t.app, "events", {}, null)).status, 401);
  assert.equal((await call(t.app, "events", {}, "bad")).status, 401);
  assert.equal((await call(t.app, "artifacts", {}, "bad")).status, 401);
  assert.equal((await call(t.app, "finalize", {}, "bad")).status, 401);
});

Deno.test("internal segments requires the token and returns only the completed prefix", async () => {
  const t = build({
    segments: [
      segmentRow(0, true),
      segmentRow(1, true),
      segmentRow(2, false),
      segmentRow(3, true, true),
    ],
  });
  assert.equal((await getSegments(t.app, "?after_seq=0", null)).status, 401);
  const res = await getSegments(t.app, "?after_seq=0");
  assert.equal(res.status, 200);
  const body = await res.json();
  conforms("internal.segments.page.v1", body);
  assert.deepEqual(
    body.segments.map((segment: { seq: number }) => segment.seq),
    [1],
  );
  assert.equal(body.final_seq, 3);
});

Deno.test("finalize bad body -> 422 and marks the run failed", async () => {
  const t = build();
  const res = await call(t.app, "finalize", { vision_result: {} });
  assert.equal(res.status, 422);
  assert.equal((await res.json()).error.code, "validation_failed");
  assert.ok(
    t.log.some((entry) =>
      entry.kind === "rpc" && entry.name === "append_event" &&
      entry.args.p_type === "analysis.failed" &&
      (entry.args.p_fields as Record<string, unknown>).reason ===
        "invalid_vision_result"
    ),
  );
});

Deno.test("finalize unknown analysis -> 404", async () => {
  const t = build();
  const res = await call(t.app, "finalize", finalizeBody, "tok", "a_missing");
  assert.equal(res.status, 404);
  assert.equal((await res.json()).error.code, "not_found");
});

Deno.test("finalize persists summary; a retry -> 409 conflict", async () => {
  const t = build();
  const first = await call(t.app, "finalize", finalizeBody);
  assert.equal(first.status, 200);
  assert.deepEqual(await first.json(), {
    analysis_id: "a_1",
    state: "completed",
    last_seq: 3,
  });
  const second = await call(t.app, "finalize", finalizeBody);
  assert.equal(second.status, 409);
  assert.equal((await second.json()).error.code, "conflict");
});

const estimateReview = {
  review_id: "rv_estimate",
  match_id: "m_1",
  rally_id: "r_1",
  event_id: "e_1",
  t_ms: 100,
  evidence_frames: [90, 100, 110],
  contact_xy_m: [1, 2],
  cov_m2: [[0, 0], [0, 0]],
  line_id: "left_sideline_singles",
  margin_m: 0.1,
  sigma_m: 0.02,
  estimate: "IN",
  visibility: "visible",
  model_ids: [],
  calibration_id: "cal_1",
  confirmed_point_winner: null,
  confirmed_by: null,
  confirmed_at: null,
};

Deno.test("finalize forwards engine review estimates with null confirmations", async () => {
  const t = build();
  const body = {
    ...finalizeBody,
    vision_result: { ...finalizeBody.vision_result, reviews: [estimateReview] },
  };
  const res = await call(t.app, "finalize", body);
  assert.equal(res.status, 200);
  const ingest = t.log.find((entry) =>
    entry.kind === "rpc" && entry.name === "ingest_vision_result"
  );
  assert.ok(ingest && ingest.kind === "rpc");
  const result = ingest.args.p_result as { reviews: unknown[] };
  assert.deepEqual(result.reviews, [estimateReview]);
});

Deno.test("finalize forwards reviews with all player confirmations", async () => {
  const t = build();
  const review = {
    ...estimateReview,
    review_id: "rv_confirmed",
    confirmed_point_winner: "p_me",
    confirmed_by: "p_me",
    confirmed_at: "2026-10-11T12:00:00Z",
  };
  const body = {
    ...finalizeBody,
    vision_result: { ...finalizeBody.vision_result, reviews: [review] },
  };
  const res = await call(t.app, "finalize", body);
  assert.equal(res.status, 200);
  const ingest = t.log.find((entry) =>
    entry.kind === "rpc" && entry.name === "ingest_vision_result"
  );
  assert.ok(ingest && ingest.kind === "rpc");
  const result = ingest.args.p_result as { reviews: unknown[] };
  assert.deepEqual(result.reviews, [review]);
});

Deno.test("internal event type whitelist -> 422", async () => {
  const t = build();
  for (
    const type of [
      "analysis.queued",
      "analysis.finalizing",
      "analysis.completed",
      "analysis.partial",
      "point.confirmed",
    ]
  ) {
    const res = await call(t.app, "events", { type });
    assert.equal(res.status, 422, type);
    assert.equal((await res.json()).error.code, "validation_failed");
  }
  assert.equal(t.log.length, 0);
});

Deno.test("window.completed/failed require window_seq", async () => {
  const t = build();
  assert.equal(
    (await call(t.app, "events", { type: "window.completed" })).status,
    422,
  );
  assert.equal(
    (await call(t.app, "events", { type: "window.failed" })).status,
    422,
  );
  assert.equal(
    (await call(t.app, "events", { type: "analysis.started" })).status,
    200,
  );
});

Deno.test("event arrays map to 4 columns and back", async () => {
  const t = build();
  const res = await call(t.app, "events", {
    type: "window.completed",
    window_seq: 0,
    decode_window_us: [10, 20],
    emit_range_us: [12, 18],
  });
  assert.equal(res.status, 200);
  const rpc = t.log[0] as Call & { args: { p_fields: Record<string, number> } };
  assert.deepEqual(rpc.args.p_fields, {
    window_seq: 0,
    decode_start_us: 10,
    decode_end_us: 20,
    emit_start_us: 12,
    emit_end_us: 18,
  });
  const body = await res.json();
  assert.deepEqual(body.decode_window_us, [10, 20]);
  assert.ok(!("emit_range_us" in body));
  assert.ok(!("owner_id" in body));
});

Deno.test("engine_version is stored by append_event and returned by the cursor", async () => {
  const t = build();
  const res = await call(t.app, "events", {
    type: "analysis.started",
    engine_version: "0.3.0",
  });
  assert.equal(res.status, 200);
  const rpc = t.log[0] as Call & {
    args: { p_fields: Record<string, unknown> };
  };
  assert.deepEqual(rpc.args.p_fields, { engine_version: "0.3.0" });
  assert.equal((await res.json()).engine_version, "0.3.0");

  const cursor = await t.app.fetch(
    new Request("http://localhost/api/v1/analyses/a_1/events", {
      headers: { Authorization: "Bearer jwt" },
    }),
  );
  assert.equal(cursor.status, 200);
  const page = await cursor.json();
  assert.equal(page.events[0].engine_version, "0.3.0");
});

Deno.test("fieldsToRpc/rowToWire helpers", () => {
  assert.deepEqual(fieldsToRpc({ type: "analysis.started" }), {});
  const w = rowToWire({
    event_id: "e",
    analysis_id: "a",
    match_id: "m",
    seq: 1,
    schema_version: 1,
    type: "t",
    window_seq: null,
    decode_start_us: "5",
    decode_end_us: null,
    emit_start_us: 1,
    emit_end_us: 2,
    artifact_id: null,
    reason: null,
    engine_version: null,
    created_at: "x",
  });
  assert.equal(w.decode_window_us, undefined);
  assert.deepEqual(w.emit_range_us, [1, 2]);
});

Deno.test("append P0001 -> 409 conflict, P0002 -> 404", async () => {
  const bad = build({
    append: {
      data: null,
      error: {
        code: "P0001",
        message: "invalid_transition",
        details: "failed is terminal",
      },
    },
  });
  const r1 = await call(bad.app, "events", { type: "analysis.started" });
  assert.equal(r1.status, 409);
  assert.equal((await r1.json()).error.code, "conflict");
  const gone = build({
    append: {
      data: null,
      error: { code: "P0002", message: "analysis_not_found" },
    },
  });
  const r2 = await call(gone.app, "events", { type: "analysis.started" });
  assert.equal(r2.status, 404);
});

const art = (r2_key: string) => ({
  kind: "window_result",
  r2_key,
  bytes: 10,
  sha256: "a".repeat(64),
});

Deno.test("artifact accepts analysis/{id}/ and clips/{match_id}/ prefixes", async () => {
  for (
    const key of ["analysis/a_1/0.3.0/windows/0.json", "clips/m_1/c_1.mp4"]
  ) {
    const t = build();
    const res = await call(t.app, "artifacts", art(key));
    assert.equal(res.status, 200, key);
    assert.deepEqual(await res.json(), { artifact_id: "art_new" });
  }
});

Deno.test("artifact rejects other prefixes and bad sha", async () => {
  const t = build();
  const res = await call(t.app, "artifacts", art("analysis/a_other/x.json"));
  assert.equal(res.status, 422);
  const res2 = await call(t.app, "artifacts", {
    ...art("analysis/a_1/x"),
    sha256: "zz",
  });
  assert.equal(res2.status, 422);
});

Deno.test("artifact repeated r2_key returns the same id; foreign owner -> 422", async () => {
  const same = build({ existing: { id: "art_old", analysis_id: "a_1" } });
  const r = await call(
    same.app,
    "artifacts",
    art("analysis/a_1/0.3.0/windows/0.json"),
  );
  assert.deepEqual(await r.json(), { artifact_id: "art_old" });
  const foreign = build({ existing: { id: "art_x", analysis_id: "a_2" } });
  assert.equal(
    (await call(
      foreign.app,
      "artifacts",
      art("analysis/a_1/0.3.0/windows/0.json"),
    )).status,
    422,
  );
});

Deno.test("artifact insert race (23505) re-selects the winner", async () => {
  const t = build({
    existing: [null, { id: "art_win", analysis_id: "a_1" }],
    insert: [{ data: null, error: { code: "23505", message: "dup" } }],
  });
  const res = await call(
    t.app,
    "artifacts",
    art("analysis/a_1/0.3.0/windows/0.json"),
  );
  assert.deepEqual(await res.json(), { artifact_id: "art_win" });
});

Deno.test("G5: a newly registered artifact counts one Class A op; a re-registration does not", async () => {
  const fresh = build();
  await call(fresh.app, "artifacts", art("analysis/a_1/0.3.0/windows/0.json"));
  const usage = fresh.log.filter((c) =>
    c.kind === "rpc" && c.name === "r2_add_usage"
  );
  assert.equal(usage.length, 1);
  assert.deepEqual((usage[0] as { args: unknown }).args, {
    p_class_a: 1,
    p_class_b: 0,
  });
  const again = build({ existing: { id: "art_old", analysis_id: "a_1" } });
  await call(again.app, "artifacts", art("analysis/a_1/0.3.0/windows/0.json"));
  assert.equal(
    again.log.filter((c) => c.kind === "rpc" && c.name === "r2_add_usage")
      .length,
    0,
  );
});
