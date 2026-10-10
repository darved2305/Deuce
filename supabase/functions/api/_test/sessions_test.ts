import assert from "node:assert/strict";
import { canonicalHash } from "../lib/idempotency.ts";
import { createR2 } from "../lib/r2.ts";
import { type Call, type Handler, makeApp, ok, type Res } from "./fakes.ts";
import { conforms } from "./jsonschema.ts";

const MIB = 1024 * 1024;

const BODY = {
  sport: "badminton",
  format: "singles",
  ruleset: { id: "bwf_3x21", version: "2025-04" },
  mode: "match",
  source: {
    width: 1920,
    height: 1080,
    rotation: 0,
    fps_nominal: 60,
    duration_ms: 20480,
    pts: {
      median_interval_us: 16667,
      min_us: 16600,
      max_us: 33400,
      gaps: 0,
      vfr: false,
    },
    codec: "avc1",
    has_audio: true,
    audio_rate_hz: 48000,
    device: "SM-S711B",
    bytes: 51203344,
    sha256: "a".repeat(64),
  },
  calibration_input: {
    method: "tap",
    plane: "court",
    template: "badminton_singles",
    points_px: {
      near_left: [16, 196],
      near_right: [334, 196],
      far_right: [248, 34],
      far_left: [102, 34],
    },
    frame_ms: 1200,
  },
  who_is_me: { image_xy: [128, 150], t_ms: 1200 },
  free_play_seed: null,
  rally_candidates: [
    { start_ms: 4200, end_ms: 11800, onsets: 9, state: "candidate" },
  ],
  upload_mode: "multipart",
};

type UpRow = Partial<{
  session_id: string;
  r2_key: string;
  r2_upload_id: string;
  part_size: number;
  bytes: number;
  status: string;
  completed_at: string | null;
}>;

const upRow = (o: UpRow = {}) => ({
  session_id: "s_1",
  r2_key: "orig/s_1/source.mp4",
  r2_upload_id: "UP1",
  part_size: 8 * MIB,
  bytes: 20 * MIB, // 3 parts
  status: "initiated",
  completed_at: null,
  ...o,
});

const xml = (status: number, body: string, headers: HeadersInit = {}) =>
  new Response(body, { status, headers });
const errXml = (status: number, code: string, msg = "m") =>
  xml(status, `<Error><Code>${code}</Code><Message>${msg}</Message></Error>`);

type Opts = {
  upload?: unknown; // result of the user client's uploads select
  idem?: Res;
  r2?: (req: Request) => Response;
  adminUploadUpdate?: unknown; // result of the admin update ... returning
  insertSessionError?: boolean;
  snap?: Partial<Record<string, number>>;
  takeParts?: unknown; // r2_take_part_urls result (null refuses)
};

function build(o: Opts = {}) {
  const r2Seen: Array<{ method: string; url: string; body: string }> = [];
  const adminLog: Call[] = [];
  const userLog: Call[] = [];
  const user: Handler = (c) => {
    userLog.push(c);
    if (c.kind === "from" && c.table === "matches") return ok({ id: "m_1" });
    if (c.kind === "from" && c.table === "sessions") {
      if (c.op === "insert") {
        return o.insertSessionError
          ? {
            data: null,
            error: Object.assign(new Error("rls"), { code: "42501" }),
          }
          : ok({ id: "s_1" });
      }
      return ok({
        id: "s_1",
        match_id: "m_1",
        mode: "match",
        upload_mode: "multipart",
        source: BODY.source,
        created_at: "2026-10-10T10:00:00Z",
      });
    }
    if (c.kind === "from" && c.table === "uploads") {
      return ok("upload" in o ? o.upload : upRow());
    }
    return ok(null);
  };
  const admin: Handler = (c) => {
    if (c.kind === "from" && c.table === "idempotency") {
      return c.op === "select" ? (o.idem ?? ok(null)) : ok(null);
    }
    if (c.kind === "from" && c.table === "uploads" && c.op === "update") {
      return ok("adminUploadUpdate" in o ? o.adminUploadUpdate : { bytes: 1 });
    }
    if (c.kind === "rpc" && c.name === "r2_budget_snapshot") {
      return ok({
        sessions_today: 0,
        uploads_today: 0,
        storage_bytes: 0,
        class_a: 0,
        class_b: 0,
        ...o.snap,
      });
    }
    if (c.kind === "rpc" && c.name === "r2_take_part_urls") {
      return ok("takeParts" in o ? o.takeParts : 3);
    }
    return ok(null);
  };
  const r2 = createR2({
    accountId: "acc",
    accessKeyId: "AK",
    secretAccessKey: "SK",
    bucket: "bkt",
    fetchImpl: (async (req: Request) => {
      r2Seen.push({
        method: req.method,
        url: req.url,
        body: await req.clone().text(),
      });
      if (o.r2) return o.r2(req);
      if (req.method === "POST" && req.url.includes("?uploads")) {
        return xml(200, "<R><UploadId>UP1</UploadId></R>");
      }
      if (req.method === "HEAD") {
        return xml(200, "", { "content-length": String(20 * MIB) });
      }
      return xml(200, "<Ok/>");
    }) as typeof fetch,
    sleep: () => Promise.resolve(),
  });
  const app = makeApp(user, admin, undefined, [], adminLog, r2);
  return { app, adminLog, userLog, r2Seen };
}

function call(
  app: ReturnType<typeof build>["app"],
  method: string,
  path: string,
  body?: unknown,
  headers: HeadersInit = {},
) {
  return app.fetch(
    new Request(`http://localhost/api${path}`, {
      method,
      headers: {
        Authorization: "Bearer jwt",
        "Content-Type": "application/json",
        ...headers,
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    }),
  );
}

const create = (
  app: ReturnType<typeof build>["app"],
  body: unknown = BODY,
  headers: HeadersInit = { "Idempotency-Key": "k1" },
) => call(app, "POST", "/v1/sessions", body, headers);

const errCode = async (res: Response) =>
  ((await res.json()) as { error: { code: string } }).error.code;

Deno.test("POST /v1/sessions creates match, session, R2 upload and uploads row", async () => {
  const { app, adminLog, userLog, r2Seen } = build();
  const res = await create(app);
  assert.equal(res.status, 201);
  const out = await res.json();
  conforms("session.create.response.v1", out);
  assert.deepEqual(out, {
    session_id: "s_1",
    match_id: "m_1",
    upload: {
      upload_id: "UP1",
      r2_key: "orig/s_1/source.mp4",
      part_size: 8388608,
      max_concurrency: 2,
    },
  });
  // RLS-governed inserts go through the user client with the verified owner.
  const match = userLog.find((c) => c.kind === "from" && c.table === "matches");
  assert.equal(
    (match as { payload: { owner_id: string } }).payload.owner_id,
    "u1",
  );
  assert.ok(
    userLog.some((c) =>
      c.kind === "from" && c.table === "sessions" && c.op === "insert"
    ),
  );
  // R2 create was the only storage call; the key is exactly orig/{session_id}/source.mp4.
  assert.equal(r2Seen.length, 1);
  assert.equal(r2Seen[0].method, "POST");
  assert.match(r2Seen[0].url, /\/bkt\/orig\/s_1\/source\.mp4\?uploads=?$/);
  const ins = adminLog.find((c) =>
    c.kind === "from" && c.table === "uploads" && c.op === "insert"
  );
  assert.deepEqual((ins as { payload: unknown }).payload, {
    session_id: "s_1",
    owner_id: "u1",
    r2_key: "orig/s_1/source.mp4",
    r2_upload_id: "UP1",
    part_size: 8388608,
    bytes: 51203344,
    sha256: "a".repeat(64),
    status: "initiated",
  });
  const stored = adminLog.find((c) =>
    c.kind === "from" && c.table === "idempotency" && c.op === "upsert"
  );
  assert.equal(
    (stored as { payload: { status_code: number } }).payload.status_code,
    201,
  );
});

Deno.test("POST /v1/sessions without Idempotency-Key is 422 validation_failed", async () => {
  const { app } = build();
  const res = await create(app, BODY, {});
  assert.equal(res.status, 422);
  assert.equal(await errCode(res), "validation_failed");
});

Deno.test("same Idempotency-Key and body replays the stored 201 without touching R2", async () => {
  const stored = {
    session_id: "s_1",
    match_id: "m_1",
    upload: {
      upload_id: "UP1",
      r2_key: "orig/s_1/source.mp4",
      part_size: 8388608,
      max_concurrency: 2,
    },
  };
  const hash = await canonicalHash(BODY);
  const { app, r2Seen, userLog } = build({
    idem: ok({
      request_hash: hash,
      status_code: 201,
      response: stored,
      created_at: new Date().toISOString(),
    }),
  });
  const res = await create(app);
  assert.equal(res.status, 201);
  assert.deepEqual(await res.json(), stored);
  assert.equal(r2Seen.length, 0);
  assert.equal(userLog.length, 0);
});

Deno.test("same Idempotency-Key with a different body is 409 idempotency_conflict", async () => {
  const { app, r2Seen } = build({
    idem: ok({
      request_hash: "different",
      status_code: 201,
      response: {},
      created_at: new Date().toISOString(),
    }),
  });
  const res = await create(app);
  assert.equal(res.status, 409);
  assert.equal(await errCode(res), "idempotency_conflict");
  assert.equal(r2Seen.length, 0);
});

Deno.test("source.bytes over 300 MiB is 413 upload_too_large", async () => {
  const { app, r2Seen } = build();
  const res = await create(app, {
    ...BODY,
    source: { ...BODY.source, bytes: 300 * MIB + 1 },
  });
  assert.equal(res.status, 413);
  assert.equal(await errCode(res), "upload_too_large");
  assert.equal(r2Seen.length, 0);
});

Deno.test("exactly 300 MiB is accepted", async () => {
  const { app } = build();
  const res = await create(app, {
    ...BODY,
    source: { ...BODY.source, bytes: 300 * MIB },
  });
  assert.equal(res.status, 201);
});

Deno.test("court template that does not match format is 422 calibration_template_mismatch", async () => {
  const { app } = build();
  const res = await create(app, { ...BODY, format: "doubles" });
  assert.equal(res.status, 422);
  assert.equal(await errCode(res), "calibration_template_mismatch");
});

Deno.test("other schema violations stay validation_failed", async () => {
  const { app } = build();
  const res = await create(app, { ...BODY, upload_mode: "segments" });
  assert.equal(res.status, 422);
  assert.equal(await errCode(res), "validation_failed");
});

Deno.test("an RLS rejection on session insert rolls back the match and creates no R2 upload", async () => {
  const { app, adminLog, r2Seen } = build({ insertSessionError: true });
  const res = await create(app);
  assert.equal(res.status, 500);
  assert.equal(r2Seen.length, 0);
  const del = adminLog.find((c) =>
    c.kind === "from" && c.table === "matches" && c.op === "delete"
  );
  assert.deepEqual((del as { filters: unknown }).filters, {
    id: "m_1",
    owner_id: "u1",
  });
});

Deno.test("R2 create failure rolls back the match", async () => {
  const { app, adminLog } = build({ r2: () => errXml(500, "InternalError") });
  const res = await create(app);
  assert.equal(res.status, 500);
  assert.ok(
    adminLog.some((c) =>
      c.kind === "from" && c.table === "matches" && c.op === "delete"
    ),
  );
  assert.ok(
    !adminLog.some((c) =>
      c.kind === "from" && c.table === "uploads" && c.op === "insert"
    ),
  );
});

Deno.test("persistent R2 429 on create is 503 storage_busy", async () => {
  const { app } = build({ r2: () => errXml(429, "SlowDown") });
  const res = await create(app);
  assert.equal(res.status, 503);
  assert.equal(await errCode(res), "storage_busy");
});

Deno.test("upload/parts returns 600 s presigned PUT URLs in request order", async () => {
  const { app, r2Seen } = build();
  const res = await call(app, "POST", "/v1/sessions/s_1/upload/parts", {
    part_numbers: [3, 1],
  });
  assert.equal(res.status, 200);
  const urls = (await res.json()) as string[];
  assert.equal(urls.length, 2);
  const u0 = new URL(urls[0]);
  assert.equal(u0.searchParams.get("partNumber"), "3");
  assert.equal(u0.searchParams.get("uploadId"), "UP1");
  assert.equal(u0.searchParams.get("X-Amz-Expires"), "600");
  assert.equal(new URL(urls[1]).searchParams.get("partNumber"), "1");
  assert.equal(r2Seen.length, 0); // presigning is local
});

Deno.test("upload/parts rejects numbers outside 1..count with 400 invalid_part_number", async () => {
  const { app } = build();
  for (const n of [0, 4]) {
    const res = await call(app, "POST", "/v1/sessions/s_1/upload/parts", {
      part_numbers: [1, n],
    });
    assert.equal(res.status, 400, `part ${n}`);
    assert.equal(await errCode(res), "invalid_part_number");
  }
});

Deno.test("upload/parts takes at most 50 numbers", async () => {
  const { app } = build({ upload: upRow({ bytes: 400 * MIB }) });
  const fifty = Array.from({ length: 50 }, (_, i) => i + 1);
  const okRes = await call(app, "POST", "/v1/sessions/s_1/upload/parts", {
    part_numbers: fifty,
  });
  assert.equal(okRes.status, 200);
  const over = await call(app, "POST", "/v1/sessions/s_1/upload/parts", {
    part_numbers: [...fifty, 51],
  });
  assert.equal(over.status, 422);
  assert.equal(await errCode(over), "validation_failed");
});

Deno.test("another user's (or missing) session is 404 not_found on every upload route", async () => {
  const { app, adminLog, r2Seen } = build({ upload: null });
  for (
    const [m, p, b] of [
      ["POST", "/v1/sessions/s_x/upload/parts", { part_numbers: [1] }],
      ["POST", "/v1/sessions/s_x/upload/complete", { parts: [] }],
      ["POST", "/v1/sessions/s_x/upload/abort", undefined],
    ] as const
  ) {
    const res = await call(app, m, p, b);
    assert.equal(res.status, 404, p);
    assert.equal(await errCode(res), "not_found");
  }
  assert.equal(r2Seen.length, 0);
  assert.ok(!adminLog.some((c) => c.kind === "from" && c.table === "uploads"));
});

Deno.test("upload/parts on a non-initiated upload is 409 invalid_transition", async () => {
  const { app } = build({ upload: upRow({ status: "completed" }) });
  const res = await call(app, "POST", "/v1/sessions/s_1/upload/parts", {
    part_numbers: [1],
  });
  assert.equal(res.status, 409);
  assert.equal(await errCode(res), "invalid_transition");
});

const PARTS = [
  { n: 1, etag: '"a"' },
  { n: 2, etag: '"b"' },
  { n: 3, etag: '"c"' },
];

Deno.test("upload/complete completes in R2, records parts and returns the stored size", async () => {
  const { app, adminLog, r2Seen } = build();
  const res = await call(app, "POST", "/v1/sessions/s_1/upload/complete", {
    parts: [PARTS[2], PARTS[0], PARTS[1]],
  });
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { status: "completed", bytes: 20 * MIB });
  assert.equal(r2Seen[0].method, "POST");
  assert.match(r2Seen[0].url, /uploadId=UP1/);
  // Parts go to R2 in ascending order.
  assert.equal(
    r2Seen[0].body,
    '<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>"a"</ETag></Part><Part><PartNumber>2</PartNumber><ETag>"b"</ETag></Part><Part><PartNumber>3</PartNumber><ETag>"c"</ETag></Part></CompleteMultipartUpload>',
  );
  assert.equal(r2Seen[1].method, "HEAD");
  const upd = adminLog.find((c) =>
    c.kind === "from" && c.table === "uploads" && c.op === "update"
  ) as {
    filters: Record<string, unknown>;
    payload: Record<string, unknown>;
  };
  assert.deepEqual(upd.filters, {
    session_id: "s_1",
    owner_id: "u1",
    status: "initiated",
  });
  assert.equal(upd.payload.status, "completed");
  assert.deepEqual(upd.payload.parts, PARTS);
});

Deno.test("upload/complete on an already completed upload returns the same body without R2", async () => {
  const { app, r2Seen, adminLog } = build({
    upload: upRow({ status: "completed", bytes: 123 }),
  });
  const res = await call(app, "POST", "/v1/sessions/s_1/upload/complete", {
    parts: PARTS,
  });
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { status: "completed", bytes: 123 });
  assert.equal(r2Seen.length, 0);
  assert.equal(adminLog.length, 0);
});

Deno.test("losing the complete race returns the winner's completed body", async () => {
  let reads = 0;
  const user: Handler = (c) => {
    if (c.kind === "from" && c.table === "uploads") {
      return ok(
        upRow({
          status: ++reads === 1 ? "initiated" : "completed",
          ...(reads === 1 ? {} : { bytes: 999 }),
        }),
      );
    }
    return ok(null);
  };
  const a = makeApp(
    user,
    (c) => (c.kind === "from" && c.table === "uploads" ? ok(null) : ok(null)),
    undefined,
    [],
    [],
    createR2({
      accountId: "a",
      accessKeyId: "k",
      secretAccessKey: "s",
      bucket: "b",
      fetchImpl: (() =>
        Promise.resolve(
          xml(200, "<Ok/>", { "content-length": "20971520" }),
        )) as typeof fetch,
    }),
  );
  const res = await call(a, "POST", "/v1/sessions/s_1/upload/complete", {
    parts: PARTS,
  });
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { status: "completed", bytes: 999 });
});

Deno.test("R2 InvalidPart is 409 upload_parts_invalid with R2's message; status stays initiated", async () => {
  const { app, adminLog } = build({
    r2: () => errXml(400, "InvalidPart", "etag mismatch part 2"),
  });
  const res = await call(app, "POST", "/v1/sessions/s_1/upload/complete", {
    parts: PARTS,
  });
  assert.equal(res.status, 409);
  const body = (await res.json()) as {
    error: { code: string; message: string };
  };
  assert.equal(body.error.code, "upload_parts_invalid");
  assert.equal(body.error.message, "etag mismatch part 2");
  assert.ok(!adminLog.some((c) => c.kind === "from" && c.table === "uploads"));
});

Deno.test("InvalidPartOrder maps the same way", async () => {
  const { app } = build({ r2: () => errXml(400, "InvalidPartOrder") });
  const res = await call(app, "POST", "/v1/sessions/s_1/upload/complete", {
    parts: PARTS,
  });
  assert.equal(res.status, 409);
  assert.equal(await errCode(res), "upload_parts_invalid");
});

Deno.test("complete with missing, duplicate or out-of-range parts never reaches R2", async () => {
  const { app, r2Seen } = build();
  const cases: Array<[unknown, number, string]> = [
    [[PARTS[0], PARTS[1]], 409, "upload_parts_invalid"],
    [[PARTS[0], PARTS[0], PARTS[1], PARTS[2]], 409, "upload_parts_invalid"],
    [[...PARTS, { n: 4, etag: "x" }], 400, "invalid_part_number"],
    [[], 409, "upload_parts_invalid"],
  ];
  for (const [parts, status, code] of cases) {
    const res = await call(app, "POST", "/v1/sessions/s_1/upload/complete", {
      parts,
    });
    assert.equal(res.status, status);
    assert.equal(await errCode(res), code);
  }
  assert.equal(r2Seen.length, 0);
});

Deno.test("complete after abort is 409 invalid_transition", async () => {
  const { app } = build({ upload: upRow({ status: "aborted" }) });
  const res = await call(app, "POST", "/v1/sessions/s_1/upload/complete", {
    parts: PARTS,
  });
  assert.equal(res.status, 409);
  assert.equal(await errCode(res), "invalid_transition");
});

Deno.test("upload/abort aborts in R2 and marks the row, owner-scoped", async () => {
  const { app, adminLog, r2Seen } = build();
  const res = await call(app, "POST", "/v1/sessions/s_1/upload/abort");
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { status: "aborted" });
  assert.equal(r2Seen[0].method, "DELETE");
  const upd = adminLog.find((c) =>
    c.kind === "from" && c.table === "uploads"
  ) as {
    filters: unknown;
    payload: unknown;
  };
  assert.deepEqual(upd.filters, {
    session_id: "s_1",
    owner_id: "u1",
    status: "initiated",
  });
  assert.deepEqual(upd.payload, { status: "aborted" });
});

Deno.test("abort is idempotent and refuses a completed upload", async () => {
  const again = build({ upload: upRow({ status: "aborted" }) });
  const r1 = await call(again.app, "POST", "/v1/sessions/s_1/upload/abort");
  assert.equal(r1.status, 200);
  assert.equal(again.r2Seen.length, 0);
  const done = build({ upload: upRow({ status: "completed" }) });
  const r2 = await call(done.app, "POST", "/v1/sessions/s_1/upload/abort");
  assert.equal(r2.status, 409);
});

Deno.test("abort tolerates NoSuchUpload from R2", async () => {
  const { app } = build({ r2: () => errXml(404, "NoSuchUpload") });
  const res = await call(app, "POST", "/v1/sessions/s_1/upload/abort");
  assert.equal(res.status, 200);
});

Deno.test("GET /v1/sessions/{id} returns the session with upload status (proposed shape)", async () => {
  const { app } = build();
  const res = await call(app, "GET", "/v1/sessions/s_1");
  assert.equal(res.status, 200);
  const out = await res.json();
  assert.equal(out.session_id, "s_1");
  assert.equal(out.match_id, "m_1");
  assert.deepEqual(out.upload, {
    status: "initiated",
    part_size: 8388608,
    bytes: 20 * MIB,
    part_count: 3,
    completed_at: null,
  });
});

Deno.test("GET /v1/sessions/{id} for a session the caller cannot see is 404", async () => {
  const user: Handler = () => ok(null);
  const app = makeApp(user, () => ok(null));
  const res = await call(app, "GET", "/v1/sessions/s_other");
  assert.equal(res.status, 404);
  assert.equal(await errCode(res), "not_found");
});

// ---- R2 budget guards (docs/ops/cloudflare-budget.md, D-261011-HW2) ----

const GIB = 1024 * MIB;
const rpcs = (log: Call[], name: string) =>
  log.filter((c) => c.kind === "rpc" && c.name === name) as Array<
    { args: Record<string, unknown> }
  >;

async function withEnv<T>(value: string, fn: () => Promise<T>): Promise<T> {
  Deno.env.set("R2_WRITES_ENABLED", value);
  try {
    return await fn();
  } finally {
    Deno.env.delete("R2_WRITES_ENABLED");
  }
}

Deno.test("G3: 5 sessions per user per day, 429 daily_upload_limit with retry_after_s", async () => {
  const { app, r2Seen, userLog } = build({ snap: { sessions_today: 5 } });
  const res = await create(app);
  assert.equal(res.status, 429);
  const body = await res.json();
  assert.equal(body.error.code, "daily_upload_limit");
  assert.ok(body.retry_after_s > 0 && body.retry_after_s <= 86400);
  assert.equal(res.headers.get("retry-after"), String(body.retry_after_s));
  assert.equal(r2Seen.length, 0);
  assert.equal(userLog.length, 0);
  const ok4 = build({ snap: { sessions_today: 4 } });
  assert.equal((await create(ok4.app)).status, 201);
});

Deno.test("G3: 30 uploads per day in total", async () => {
  const over = build({ snap: { uploads_today: 30 } });
  const res = await create(over.app);
  assert.equal(res.status, 429);
  assert.equal(await errCode(res), "daily_upload_limit");
  const under = build({ snap: { uploads_today: 29 } });
  assert.equal((await create(under.app)).status, 201);
});

Deno.test("G10: storage at 1 GiB pauses uploads with 503 uploads_paused", async () => {
  const at = build({ snap: { storage_bytes: GIB } });
  const res = await create(at.app);
  assert.equal(res.status, 503);
  assert.equal(await errCode(res), "uploads_paused");
  assert.equal(at.r2Seen.length, 0);
  const below = build({ snap: { storage_bytes: GIB - 1 } });
  assert.equal((await create(below.app)).status, 201);
});

Deno.test("G10: Class A at 100,000 pauses uploads", async () => {
  const at = build({ snap: { class_a: 100_000 } });
  const res = await create(at.app);
  assert.equal(res.status, 503);
  assert.equal(await errCode(res), "uploads_paused");
  const below = build({ snap: { class_a: 99_999 } });
  assert.equal((await create(below.app)).status, 201);
});

Deno.test("G10 also pauses part URLs", async () => {
  const { app, adminLog } = build({ snap: { class_a: 100_000 } });
  const res = await call(app, "POST", "/v1/sessions/s_1/upload/parts", {
    part_numbers: [1],
  });
  assert.equal(res.status, 503);
  assert.equal(await errCode(res), "uploads_paused");
  assert.equal(rpcs(adminLog, "r2_take_part_urls").length, 0);
});

Deno.test("G9: R2_WRITES_ENABLED=false pauses create and parts without touching R2 or the DB", async () => {
  await withEnv("false", async () => {
    const { app, r2Seen, adminLog, userLog } = build();
    const c1 = await create(app);
    assert.equal(c1.status, 503);
    assert.equal(await errCode(c1), "uploads_paused");
    const c2 = await call(app, "POST", "/v1/sessions/s_1/upload/parts", {
      part_numbers: [1],
    });
    assert.equal(c2.status, 503);
    assert.equal(await errCode(c2), "uploads_paused");
    assert.equal(r2Seen.length, 0);
    assert.equal(rpcs(adminLog, "r2_budget_snapshot").length, 0);
    assert.equal(
      userLog.filter((c) => c.kind === "from" && c.table === "matches").length,
      0,
    );
  });
});

Deno.test("G9: anything but false leaves uploads on; complete and abort stay available", async () => {
  await withEnv("true", async () => {
    assert.equal((await create(build().app)).status, 201);
  });
  await withEnv("false", async () => {
    const res = await call(
      build().app,
      "POST",
      "/v1/sessions/s_1/upload/complete",
      { parts: PARTS },
    );
    assert.equal(res.status, 200);
    const ab = await call(build().app, "POST", "/v1/sessions/s_1/upload/abort");
    assert.equal(ab.status, 200);
  });
});

Deno.test("G4: part URL allowance is 3 x part count, refusal is 429 too_many_part_requests", async () => {
  const { app, adminLog } = build({ takeParts: null });
  const res = await call(app, "POST", "/v1/sessions/s_1/upload/parts", {
    part_numbers: [1, 2],
  });
  assert.equal(res.status, 429);
  assert.equal(await errCode(res), "too_many_part_requests");
  assert.deepEqual(rpcs(adminLog, "r2_take_part_urls")[0].args, {
    p_session: "s_1",
    p_owner: "u1",
    p_count: 2,
    p_limit: 9, // 20 MiB = 3 parts, 3 x 3
  });
});

Deno.test("Class A is counted when part URLs are issued (inside r2_take_part_urls)", async () => {
  const { app, adminLog } = build();
  const res = await call(app, "POST", "/v1/sessions/s_1/upload/parts", {
    part_numbers: [1, 2, 3],
  });
  assert.equal(res.status, 200);
  assert.equal(rpcs(adminLog, "r2_take_part_urls")[0].args.p_count, 3);
});

Deno.test("Class A is counted for CreateMultipartUpload; Class A and B for complete and HEAD", async () => {
  const created = build();
  await create(created.app);
  assert.deepEqual(rpcs(created.adminLog, "r2_add_usage").map((r) => r.args), [
    { p_class_a: 1, p_class_b: 0 },
  ]);
  const done = build();
  await call(done.app, "POST", "/v1/sessions/s_1/upload/complete", {
    parts: PARTS,
  });
  assert.deepEqual(rpcs(done.adminLog, "r2_add_usage").map((r) => r.args), [
    { p_class_a: 1, p_class_b: 0 },
    { p_class_a: 0, p_class_b: 1 },
  ]);
});

Deno.test("an idempotent replay is not blocked by the daily limits", async () => {
  const stored = {
    session_id: "s_1",
    match_id: "m_1",
    upload: {
      upload_id: "UP1",
      r2_key: "orig/s_1/source.mp4",
      part_size: 8388608,
      max_concurrency: 2,
    },
  };
  const { app } = build({
    snap: { sessions_today: 5 },
    idem: ok({
      request_hash: await canonicalHash(BODY),
      status_code: 201,
      response: stored,
      created_at: new Date().toISOString(),
    }),
  });
  assert.equal((await create(app)).status, 201);
});

Deno.test("complete recovers when R2 already completed but the row is still initiated", async () => {
  let reads = 0;
  const user: Handler = (c) =>
    c.kind === "from" && c.table === "uploads"
      ? ok(upRow({ status: ++reads <= 2 ? "initiated" : "completed" }))
      : ok(null);
  let first = true;
  const app = makeApp(
    user,
    (c) =>
      c.kind === "from" && c.table === "uploads"
        ? ok({ bytes: 20 * MIB })
        : ok(null),
    undefined,
    [],
    [],
    createR2({
      accountId: "a",
      accessKeyId: "k",
      secretAccessKey: "s",
      bucket: "b",
      fetchImpl: ((req: Request) => {
        if (req.method === "POST" && first) {
          first = false;
          return Promise.resolve(errXml(404, "NoSuchUpload"));
        }
        return Promise.resolve(
          xml(200, "", { "content-length": String(20 * MIB) }),
        );
      }) as typeof fetch,
    }),
  );
  const res = await call(app, "POST", "/v1/sessions/s_1/upload/complete", {
    parts: PARTS,
  });
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { status: "completed", bytes: 20 * MIB });
});

Deno.test("complete rejects an object larger than the declared parts can hold, deletes it and closes the upload", async () => {
  const { app, r2Seen, adminLog } = build({
    // 20 MiB declared = 3 parts = 24 MiB at most
    r2: (req) =>
      req.method === "HEAD"
        ? xml(200, "", { "content-length": String(200 * MIB) })
        : req.method === "DELETE"
        ? new Response(null, { status: 204 })
        : xml(200, "<Ok/>"),
  });
  const res = await call(app, "POST", "/v1/sessions/s_1/upload/complete", {
    parts: PARTS,
  });
  assert.equal(res.status, 413);
  assert.equal(await errCode(res), "upload_too_large");
  assert.deepEqual(r2Seen.map((r) => r.method), ["POST", "HEAD", "DELETE"]);
  const closed = adminLog.find((c) =>
    c.kind === "from" && c.table === "uploads" && c.op === "update"
  ) as { payload: unknown };
  assert.deepEqual(closed.payload, { status: "aborted" });
});

Deno.test("an object within the declared parts is accepted", async () => {
  const { app } = build({
    r2: (req) =>
      req.method === "HEAD"
        ? xml(200, "", { "content-length": String(24 * MIB) })
        : xml(200, "<Ok/>"),
  });
  const res = await call(app, "POST", "/v1/sessions/s_1/upload/complete", {
    parts: PARTS,
  });
  assert.equal(res.status, 200);
});
