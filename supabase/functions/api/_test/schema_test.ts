import assert from "node:assert/strict";
import { type Handler, makeApp, ok } from "./fakes.ts";
import { check, conforms, load } from "./jsonschema.ts";

const wireRow = (type: string, extra: Record<string, unknown> = {}) => ({
  event_id: "e_1",
  analysis_id: "a_1",
  match_id: "m_1",
  seq: 2,
  schema_version: 1,
  type,
  window_seq: 0,
  decode_start_us: 10,
  decode_end_us: 20,
  emit_start_us: 12,
  emit_end_us: 18,
  artifact_id: "art_1",
  reason: null,
  engine_version: "0.3.0",
  created_at: "2026-10-10T10:00:00Z",
  ...extra,
});

const admin: Handler = (c) => {
  if (c.kind === "rpc" && c.name === "append_event") {
    return ok(wireRow(String(c.args.p_type)));
  }
  if (c.kind === "from" && c.table === "analysis_runs") {
    return ok({ id: "a_1", match_id: "m_1", owner_id: "u1" });
  }
  if (c.kind === "from" && c.table === "artifacts") {
    return c.op === "select"
      ? ok(null)
      : ok({ id: "art_9", analysis_id: "a_1" });
  }
  return ok(null);
};
const user: Handler = (c) => {
  if (c.kind === "from" && c.table === "analysis_runs") {
    return ok({ id: "a_1", state: "partial" });
  }
  if (c.kind === "from" && c.table === "analysis_events") {
    return ok([
      wireRow("window.completed"),
      wireRow("analysis.started", { seq: 3 }),
    ]);
  }
  return ok(null);
};
const app = makeApp(user, admin);

function internal(path: string, body: unknown) {
  return app.fetch(
    new Request(`http://localhost/api/internal/analyses/a_1/${path}`, {
      method: "POST",
      headers: {
        "X-Internal-Token": "tok",
        "Content-Type": "application/json",
      },
      body: JSON.stringify(body),
    }),
  );
}

Deno.test("internal event: request and response conform to the contract schemas", async () => {
  const req = {
    type: "window.completed",
    window_seq: 0,
    decode_window_us: [10, 20],
    emit_range_us: [12, 18],
    artifact_id: "art_1",
  };
  conforms("internal.event.request.v1", req);
  const res = await internal("events", req);
  assert.equal(res.status, 200);
  conforms("analysis.event.v1", await res.json());
});

Deno.test("internal artifact: request and response conform", async () => {
  const req = {
    kind: "window_result",
    r2_key: "analysis/a_1/0.3.0/windows/0.json",
    bytes: 10,
    sha256: "a".repeat(64),
  };
  conforms("internal.artifact.request.v1", req);
  const res = await internal("artifacts", req);
  assert.equal(res.status, 200);
  conforms("internal.artifact.response.v1", await res.json());
});

Deno.test("events page conforms to analysis.events.page.v1", async () => {
  const res = await app.fetch(
    new Request("http://localhost/api/v1/analyses/a_1/events", {
      headers: { Authorization: "Bearer jwt" },
    }),
  );
  assert.equal(res.status, 200);
  conforms("analysis.events.page.v1", await res.json());
});

Deno.test("error envelope conforms to error.envelope.v1", async () => {
  const res = await internal("events", { type: "analysis.queued" });
  assert.equal(res.status, 422);
  conforms("error.envelope.v1", await res.json());
  conforms("analysis.create.response.v1", {
    analysis_id: "a_1",
    state: "queued",
  });
});

Deno.test("checker rejects a body the schema forbids", () => {
  assert.notDeepEqual(
    check(load("internal.event.request.v1"), { type: "analysis.queued" }),
    [],
  );
  assert.notDeepEqual(
    check(load("analysis.create.response.v1"), {
      analysis_id: "a_1",
      state: "x",
    }),
    [],
  );
});
