import assert from "node:assert/strict";
import { createR2 } from "../lib/r2.ts";
import { type Call, type Handler, makeApp, ok, type Res } from "./fakes.ts";
import { conforms } from "./jsonschema.ts";

const SEGMENT = {
  seq: 0,
  pts_start_us: 0,
  pts_end_us: 1_000_000,
  starts_on_sync_sample: true,
  bytes: 1024,
  sha256: "a".repeat(64),
  final: false,
};

type SegmentRow = typeof SEGMENT & {
  session_id: string;
  owner_id: string;
  r2_key: string;
  url_requests: number;
  completed_at: string | null;
};

type Options = {
  rows?: SegmentRow[];
  registerError?: Res["error"];
  urlLimit?: boolean;
  headBytes?: number;
  headMissing?: boolean;
};

const segmentRow = (
  seq: number,
  completed_at: string | null = null,
  final = false,
): SegmentRow => ({
  ...SEGMENT,
  seq,
  pts_start_us: seq * 1_000_000,
  pts_end_us: (seq + 1) * 1_000_000,
  final,
  session_id: "s_1",
  owner_id: "u1",
  r2_key: `seg/s_1/${seq}.mp4`,
  url_requests: 0,
  completed_at,
});

function build(options: Options = {}) {
  const adminLog: Call[] = [];
  const userLog: Call[] = [];
  const r2Calls: string[] = [];
  const rows = new Map<number, SegmentRow>(
    (options.rows ?? []).map((row) => [row.seq, row]),
  );
  const user: Handler = (call) => {
    userLog.push(call);
    if (call.kind === "from" && call.table === "sessions") {
      return ok({ id: "s_1", upload_mode: "segments" });
    }
    if (call.kind === "from" && call.table === "segments") {
      return ok(rows.get(Number(call.filters.seq)) ?? null);
    }
    return ok(null);
  };
  const admin: Handler = (call) => {
    if (call.kind === "rpc") {
      if (call.name === "r2_budget_snapshot") {
        return ok({
          sessions_today: 0,
          uploads_today: 0,
          storage_bytes: 0,
          class_a: 0,
          class_b: 0,
        });
      }
      if (call.name === "register_segment") {
        if (options.registerError) {
          return { data: null, error: options.registerError };
        }
        const args = call.args;
        const seq = Number(args.p_seq);
        const existing = rows.get(seq);
        if (existing) return ok(existing);
        const row: SegmentRow = {
          seq,
          pts_start_us: Number(args.p_pts_start_us),
          pts_end_us: Number(args.p_pts_end_us),
          starts_on_sync_sample: Boolean(args.p_starts_on_sync_sample),
          bytes: Number(args.p_bytes),
          sha256: String(args.p_sha256).toLowerCase(),
          final: Boolean(args.p_final),
          session_id: String(args.p_session_id),
          owner_id: String(args.p_owner_id),
          r2_key: `seg/${args.p_session_id}/${seq}.mp4`,
          url_requests: 0,
          completed_at: null,
        };
        rows.set(seq, row);
        return ok(row);
      }
      if (call.name === "r2_take_segment_url") {
        if (options.urlLimit) return ok(null);
        const row = rows.get(Number(call.args.p_seq));
        if (!row) {
          return { data: null, error: { code: "P0002", message: "missing" } };
        }
        row.url_requests++;
        return ok(row.url_requests);
      }
      return ok(null);
    }
    if (call.table === "analysis_runs") return ok({ session_id: "s_1" });
    if (call.table === "segments" && call.op === "select") {
      if (call.filters.seq !== undefined) {
        return ok(rows.get(Number(call.filters.seq)) ?? null);
      }
      return ok([...rows.values()].sort((a, b) => a.seq - b.seq));
    }
    if (call.table === "segments" && call.op === "update") {
      const row = rows.get(Number(call.filters.seq));
      if (row && row.completed_at === null) {
        Object.assign(row, call.payload);
        return ok(row);
      }
      return ok(null);
    }
    return ok(null);
  };
  const r2 = createR2({
    accountId: "acc",
    accessKeyId: "AK",
    secretAccessKey: "SK",
    bucket: "bkt",
    fetchImpl: ((request: Request) => {
      r2Calls.push(request.method);
      if (options.headMissing) {
        return Promise.resolve(new Response(null, { status: 404 }));
      }
      return Promise.resolve(
        new Response(null, {
          status: 200,
          headers: {
            "content-length": String(options.headBytes ?? SEGMENT.bytes),
          },
        }),
      );
    }) as typeof fetch,
  });
  const app = makeApp(user, admin, undefined, [], adminLog, r2);
  return { app, adminLog, userLog, r2Calls, rows };
}

function post(
  app: ReturnType<typeof build>["app"],
  path: string,
  body?: unknown,
) {
  return app.fetch(
    new Request(`http://localhost/api${path}`, {
      method: "POST",
      headers: {
        Authorization: "Bearer jwt",
        "Content-Type": "application/json",
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    }),
  );
}

const register = (
  app: ReturnType<typeof build>["app"],
  body: unknown = SEGMENT,
) => post(app, "/v1/sessions/s_1/segments", body);
const complete = (app: ReturnType<typeof build>["app"], seq = 0) =>
  post(app, `/v1/sessions/s_1/segments/${seq}/complete`);

Deno.test("segment registration rejects a sequence gap with 409 segment_gap", async () => {
  const t = build({
    registerError: { code: "P0001", message: "segment_gap" },
  });
  const res = await register(t.app, { ...SEGMENT, seq: 2 });
  assert.equal(res.status, 409);
  const body = await res.json();
  assert.equal(body.error.code, "segment_gap");
  conforms("error.envelope.v1", body);
});

Deno.test("segment registration replay is idempotent and returns a 600-second PUT URL", async () => {
  const t = build();
  const first = await register(t.app);
  const replay = await register(t.app);
  assert.equal(first.status, 200);
  assert.equal(replay.status, 200);
  const one = await first.json();
  const two = await replay.json();
  conforms("segment.register.request.v1", SEGMENT);
  conforms("segment.register.response.v1", one);
  conforms("segment.register.response.v1", two);
  assert.equal(one.seq, two.seq);
  assert.equal(one.r2_key, "seg/s_1/0.mp4");
  assert.equal(new URL(one.put_url).searchParams.get("X-Amz-Expires"), "600");
  assert.equal(t.rows.size, 1);
  assert.equal(t.rows.get(0)?.url_requests, 2);
});

Deno.test("segment registration persists the final flag", async () => {
  const t = build();
  const res = await register(t.app, { ...SEGMENT, final: true });
  assert.equal(res.status, 200);
  conforms("segment.register.response.v1", await res.json());
  assert.equal(t.rows.get(0)?.final, true);
  const rpc = t.adminLog.find((call) =>
    call.kind === "rpc" && call.name === "register_segment"
  );
  assert.equal(rpc?.kind === "rpc" && rpc.args.p_final, true);
});

Deno.test("segment registration enforces the cumulative G1 byte cap", async () => {
  const t = build({
    registerError: { code: "P0001", message: "upload_too_large" },
  });
  const res = await register(t.app);
  assert.equal(res.status, 413);
  const body = await res.json();
  assert.equal(body.error.code, "upload_too_large");
  conforms("error.envelope.v1", body);
});

Deno.test("segment registration enforces the G4 URL cap", async () => {
  const t = build({ urlLimit: true });
  const res = await register(t.app);
  assert.equal(res.status, 429);
  const body = await res.json();
  assert.equal(body.error.code, "too_many_part_requests");
  assert.equal(body.retry_after_s, 600);
  conforms("error.envelope.v1", body);
});

Deno.test("segment completion rejects a stored size mismatch", async () => {
  const t = build({ rows: [segmentRow(0)], headBytes: SEGMENT.bytes - 1 });
  const res = await complete(t.app);
  assert.equal(res.status, 409);
  const body = await res.json();
  assert.equal(body.error.code, "upload_parts_invalid");
  conforms("error.envelope.v1", body);
  assert.equal(t.rows.get(0)?.completed_at, null);
  assert.deepEqual(t.r2Calls, ["HEAD"]);
});

Deno.test("segment completion stores and returns the verified manifest", async () => {
  const t = build({ rows: [segmentRow(0)] });
  const res = await complete(t.app);
  assert.equal(res.status, 200);
  const body = await res.json();
  conforms("segment.complete.response.v1", body);
  assert.equal(body.r2_key, "seg/s_1/0.mp4");
  assert.equal(typeof body.completed_at, "string");
  assert.deepEqual(t.r2Calls, ["HEAD"]);
});

Deno.test("segment completion replay returns the stored manifest without another HEAD", async () => {
  const completedAt = "2026-10-11T10:00:00Z";
  const t = build({ rows: [segmentRow(0, completedAt)] });
  const res = await complete(t.app);
  assert.equal(res.status, 200);
  const body = await res.json();
  conforms("segment.complete.response.v1", body);
  assert.equal(body.completed_at, completedAt);
  assert.deepEqual(t.r2Calls, []);
});
