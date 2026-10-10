import assert from "node:assert/strict";
import {
  type Call,
  type Handler,
  jsonRes,
  makeApp,
  ok,
  type Res,
} from "./fakes.ts";

const RUN = { id: "a_1", state: "uploaded" };

type Opts = {
  session?: unknown;
  upload?: unknown;
  idem?: Res;
  run?: Res;
  claim?: unknown;
  curState?: string;
  spec?: unknown;
  modal?: Array<() => Response>;
  events?: unknown[];
};

function build(o: Opts = {}) {
  const log: Call[] = [];
  const sleeps: number[] = [];
  let modalCalls = 0;
  const user: Handler = (c) => {
    if (c.kind === "from" && c.table === "sessions") {
      return ok("session" in o ? o.session : { id: "s_1" });
    }
    if (c.kind === "from" && c.table === "uploads") {
      return ok(
        "upload" in o
          ? o.upload
          : { r2_key: "orig/s_1/source.mp4", status: "completed" },
      );
    }
    if (c.kind === "from" && c.table === "analysis_runs") {
      return ok({ id: "a_1", state: "partial" });
    }
    if (c.kind === "from" && c.table === "analysis_events") {
      const after = c.filters["seq>"] as number;
      const lim = c.filters["limit"] as number;
      return ok(
        (o.events ?? []).filter((e) => (e as { seq: number }).seq > after)
          .slice(0, lim),
      );
    }
    return ok(null);
  };
  const admin: Handler = (c) => {
    if (c.kind === "from" && c.table === "idempotency") {
      return c.op === "select" ? (o.idem ?? ok(null)) : ok(null);
    }
    if (c.kind === "rpc") {
      if (c.name === "create_analysis_run") return o.run ?? ok(RUN);
      if (c.name === "append_event") return ok({});
      if (c.name === "claim_dispatch") {
        return ok("claim" in o ? o.claim : { id: "a_1", state: "queued" });
      }
      if (c.name === "session_spec") {
        return ok("spec" in o ? o.spec : { sport: "badminton" });
      }
    }
    if (c.kind === "from" && c.table === "analysis_runs") {
      return ok({ id: "a_1", state: o.curState ?? "queued" });
    }
    return ok(null);
  };
  const fetchImpl = (() => {
    const r = (o.modal ?? [() => jsonRes(200, { modal_call_id: "fc-1" })])[
      modalCalls++
    ];
    return Promise.resolve(r());
  }) as typeof fetch;
  const app = makeApp(user, admin, fetchImpl, sleeps, log);
  return { app, log, sleeps, modalCalls: () => modalCalls };
}

function post(app: ReturnType<typeof build>["app"], headers: HeadersInit = {
  "Idempotency-Key": "k1",
}) {
  return app.fetch(
    new Request("http://localhost/api/v1/analyses", {
      method: "POST",
      headers: {
        Authorization: "Bearer jwt",
        "Content-Type": "application/json",
        ...headers,
      },
      body: JSON.stringify({ session_id: "s_1" }),
    }),
  );
}

const names = (log: Call[]) =>
  log.filter((c) => c.kind === "rpc").map((c) => (c as { name: string }).name);

Deno.test("dispatch happy path -> 202, ordered steps, modal_call_id stored", async () => {
  const t = build();
  const res = await post(t.app);
  assert.equal(res.status, 202);
  assert.deepEqual(await res.json(), { analysis_id: "a_1", state: "queued" });
  assert.deepEqual(names(t.log), [
    "create_analysis_run",
    "append_event",
    "claim_dispatch",
    "session_spec",
  ]);
  const upd = t.log.find((c) => c.kind === "from" && c.op === "update") as
    & Call
    & { payload: Record<string, unknown> };
  assert.equal(upd.payload.modal_call_id, "fc-1");
  assert.equal(upd.payload.dispatching, false);
  const stored = t.log.find((c) => c.kind === "from" && c.op === "upsert") as
    & Call
    & { payload: Record<string, unknown> };
  assert.equal(stored.payload.status_code, 202);
  assert.equal(t.modalCalls(), 1);
});

Deno.test("modal 4xx -> 502, one call, analysis.failed dispatch_failed, no idem row", async () => {
  const t = build({ modal: [() => jsonRes(422, { error: { code: "x" } })] });
  const res = await post(t.app);
  assert.equal(res.status, 502);
  assert.equal((await res.json()).error.code, "engine_unavailable");
  assert.equal(t.modalCalls(), 1);
  const failed = t.log.find((c) =>
    c.kind === "rpc" && c.name === "append_event" &&
    c.args.p_type === "analysis.failed"
  ) as Call & { args: { p_fields: { reason: string } } };
  assert.equal(failed.args.p_fields.reason, "dispatch_failed");
  assert.ok(!t.log.some((c) => c.kind === "from" && c.op === "upsert"));
});

Deno.test("modal 5xx x3 then 200 -> 202 with 4 calls and backoff", async () => {
  const f = () => jsonRes(503, {});
  const t = build({
    modal: [f, f, f, () => jsonRes(200, { modal_call_id: "fc" })],
  });
  const res = await post(t.app);
  assert.equal(res.status, 202);
  assert.equal(t.modalCalls(), 4);
  assert.deepEqual(t.sleeps, [500, 1000, 2000]);
});

Deno.test("modal 5xx x4 -> 502 and analysis.failed", async () => {
  const f = () => jsonRes(500, {});
  const t = build({ modal: [f, f, f, f] });
  const res = await post(t.app);
  assert.equal(res.status, 502);
  assert.equal(t.modalCalls(), 4);
  assert.ok(t.log.some((c) =>
    c.kind === "rpc" && c.name === "append_event" &&
    c.args.p_type === "analysis.failed"
  ));
});

Deno.test("idempotency replay returns stored 202 without touching Modal", async () => {
  const hash = await (await import("../lib/idempotency.ts")).canonicalHash({
    session_id: "s_1",
  });
  const t = build({
    idem: ok({
      request_hash: hash,
      status_code: 202,
      response: { analysis_id: "a_9", state: "queued" },
      created_at: new Date().toISOString(),
    }),
  });
  const res = await post(t.app);
  assert.equal(res.status, 202);
  assert.deepEqual(await res.json(), { analysis_id: "a_9", state: "queued" });
  assert.equal(t.modalCalls(), 0);
  assert.deepEqual(names(t.log), []);
});

Deno.test("idempotency key reused with different body -> 409", async () => {
  const t = build({
    idem: ok({
      request_hash: "other",
      status_code: 202,
      response: {},
      created_at: new Date().toISOString(),
    }),
  });
  const res = await post(t.app);
  assert.equal(res.status, 409);
  assert.equal((await res.json()).error.code, "idempotency_conflict");
});

Deno.test("expired idempotency row (>24h) is treated as new", async () => {
  const t = build({
    idem: ok({
      request_hash: "other",
      status_code: 202,
      response: {},
      created_at: new Date(Date.now() - 25 * 3600 * 1000).toISOString(),
    }),
  });
  assert.equal((await post(t.app)).status, 202);
});

Deno.test("missing Idempotency-Key -> 422", async () => {
  const t = build();
  const res = await post(t.app, {});
  assert.equal(res.status, 422);
  assert.equal((await res.json()).error.code, "validation_failed");
});

Deno.test("live run under another key (claim miss, still queued) -> 202, no dispatch", async () => {
  const t = build({ claim: null });
  const res = await post(t.app);
  assert.equal(res.status, 202);
  assert.deepEqual(await res.json(), { analysis_id: "a_1", state: "queued" });
  assert.equal(t.modalCalls(), 0);
  const stored = t.log.find((c) => c.kind === "from" && c.op === "upsert") as
    & Call
    & { payload: Record<string, unknown> };
  assert.equal(stored.payload.status_code, 202);
});

Deno.test("claim miss, run already processing -> 200 and idem row stored", async () => {
  const t = build({ claim: null, curState: "processing" });
  const res = await post(t.app, { "Idempotency-Key": "k2" });
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), {
    analysis_id: "a_1",
    state: "processing",
  });
  assert.equal(t.modalCalls(), 0);
  const stored = t.log.find((c) => c.kind === "from" && c.op === "upsert") as
    & Call
    & { payload: Record<string, unknown> };
  assert.equal(stored.payload.status_code, 200);
  assert.equal(stored.payload.key, "k2");
  assert.deepEqual(stored.payload.response, {
    analysis_id: "a_1",
    state: "processing",
  });
});

Deno.test("replay of a stored 200 returns the stored body, no rpc, no modal", async () => {
  const hash = await (await import("../lib/idempotency.ts")).canonicalHash({
    session_id: "s_1",
  });
  const t = build({
    idem: ok({
      request_hash: hash,
      status_code: 200,
      response: { analysis_id: "a_1", state: "processing" },
      created_at: new Date().toISOString(),
    }),
  });
  const res = await post(t.app, { "Idempotency-Key": "k2" });
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), {
    analysis_id: "a_1",
    state: "processing",
  });
  assert.ok(!names(t.log).includes("create_analysis_run"));
  assert.equal(t.modalCalls(), 0);
});

Deno.test("session_spec null -> 502, analysis.failed, no modal call", async () => {
  const t = build({ spec: null });
  const res = await post(t.app);
  assert.equal(res.status, 502);
  assert.equal((await res.json()).error.code, "engine_unavailable");
  assert.ok(t.log.some((c) =>
    c.kind === "rpc" && c.name === "append_event" &&
    c.args.p_type === "analysis.failed"
  ));
  assert.equal(t.modalCalls(), 0);
});

Deno.test("terminal run -> 200 without append or dispatch", async () => {
  const t = build({ run: ok({ id: "a_1", state: "failed" }) });
  const res = await post(t.app);
  assert.equal(res.status, 200);
  assert.deepEqual(names(t.log), ["create_analysis_run"]);
});

Deno.test("analysis_run_conflict -> 409", async () => {
  const t = build({
    run: {
      data: null,
      error: { code: "P0001", message: "analysis_run_conflict" },
    },
  });
  assert.equal((await post(t.app)).status, 409);
});

Deno.test("upload not complete -> 409; no upload row -> 409", async () => {
  for (const upload of [{ r2_key: "k", status: "initiated" }, null]) {
    const res = await post(build({ upload }).app);
    assert.equal(res.status, 409);
    assert.equal((await res.json()).error.code, "upload_not_complete");
  }
});

Deno.test("session not visible to caller (other owner) -> 404", async () => {
  const res = await post(build({ session: null }).app);
  assert.equal(res.status, 404);
});

function getEvents(app: ReturnType<typeof build>["app"], qs: string) {
  return app.fetch(
    new Request(`http://localhost/api/v1/analyses/a_1/events${qs}`, {
      headers: { Authorization: "Bearer jwt" },
    }),
  );
}

const ev = (seq: number) => ({
  event_id: `e_${seq}`,
  analysis_id: "a_1",
  match_id: "m_1",
  seq,
  schema_version: 1,
  type: "window.completed",
  window_seq: seq,
  decode_start_us: 1,
  decode_end_us: 5,
  emit_start_us: 2,
  emit_end_us: 4,
  artifact_id: null,
  reason: null,
  engine_version: null,
  created_at: "2026-01-01T00:00:00Z",
});

Deno.test("events cursor: has_more via limit+1, arrays rebuilt", async () => {
  const t = build({ events: [ev(1), ev(2), ev(3)] });
  const body = await (await getEvents(t.app, "?limit=2")).json();
  assert.equal(body.events.length, 2);
  assert.equal(body.has_more, true);
  assert.equal(body.next_after_seq, 2);
  assert.deepEqual(body.events[0].decode_window_us, [1, 5]);
  assert.deepEqual(body.events[0].emit_range_us, [2, 4]);
  assert.equal(body.state, "partial");
});

Deno.test("events cursor: empty page echoes input cursor", async () => {
  const t = build({ events: [ev(1)] });
  const body = await (await getEvents(t.app, "?after_seq=7")).json();
  assert.deepEqual(body.events, []);
  assert.equal(body.next_after_seq, 7);
  assert.equal(body.has_more, false);
});

Deno.test("events limit=501 -> 422", async () => {
  const res = await getEvents(build().app, "?limit=501");
  assert.equal(res.status, 422);
});
