import assert from "node:assert/strict";
import { fieldsToRpc, rowToWire } from "../lib/events.ts";
import { type Call, type Handler, makeApp, ok, type Res } from "./fakes.ts";

const RUN = { id: "a_1", match_id: "m_1", owner_id: "u1" };

function build(
  o: { append?: Res; existing?: unknown; insert?: Res[] } = {},
) {
  const log: Call[] = [];
  let existingCalls = 0;
  const inserts = o.insert ?? [ok({ id: "art_new", analysis_id: "a_1" })];
  const admin: Handler = (c) => {
    if (c.kind === "rpc" && c.name === "append_event") {
      return o.append ?? ok({
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
        engine_version: null,
        created_at: "2026-01-01T00:00:00Z",
      });
    }
    if (c.kind === "from" && c.table === "analysis_runs") return ok(RUN);
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
  const user: Handler = () => ok(null);
  return { app: makeApp(user, admin, undefined, [], log), log };
}

function call(
  app: ReturnType<typeof build>["app"],
  path: string,
  body: unknown,
  token: string | null = "tok",
) {
  return app.fetch(
    new Request(`http://localhost/api/internal/analyses/a_1/${path}`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(token ? { "X-Internal-Token": token } : {}),
      },
      body: JSON.stringify(body),
    }),
  );
}

Deno.test("internal routes need the right token", async () => {
  const t = build();
  assert.equal((await call(t.app, "events", {}, null)).status, 401);
  assert.equal((await call(t.app, "events", {}, "bad")).status, 401);
  assert.equal((await call(t.app, "artifacts", {}, "bad")).status, 401);
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

Deno.test("append P0001 -> 409 invalid_transition, P0002 -> 404", async () => {
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
  assert.equal((await r1.json()).error.code, "invalid_transition");
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
