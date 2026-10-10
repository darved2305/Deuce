import assert from "node:assert/strict";
import { ApiFailure, errorBody } from "../lib/errors.ts";
import { type Handler, makeApp, ok } from "./fakes.ts";

// Minimal JSON Schema (draft 2020-12 subset used by packages/contracts/schemas) checker.
type Schema = Record<string, unknown>;

function load(name: string): Schema {
  const url = new URL(
    `../../../../packages/contracts/schemas/${name}.schema.json`,
    import.meta.url,
  );
  return JSON.parse(Deno.readTextFileSync(url));
}

function check(s: Schema, v: unknown, path = "$"): string[] {
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

function conforms(name: string, value: unknown) {
  assert.deepEqual(check(load(name), value), [], name);
}

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
    return ok({
      id: "a_1",
      session_id: "s_1",
      match_id: "m_1",
      state: "partial",
      engine_version: "0.3.0",
      last_seq: 2,
      failure_reason: null,
      created_at: "2026-10-10T10:00:00Z",
      updated_at: "2026-10-10T10:01:00Z",
      finalized_at: null,
    });
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

Deno.test("1.4 response schemas conform", async () => {
  const analysis = {
    analysis_id: "a_1",
    session_id: "s_1",
    match_id: "m_1",
    state: "partial",
    engine_version: "0.3.0",
    last_seq: 2,
    failure_reason: null,
    created_at: "2026-10-10T10:00:00Z",
    updated_at: "2026-10-10T10:01:00Z",
    finalized_at: null,
  };
  conforms("analysis.get.response.v1", analysis);
  const getAnalysis = await app.fetch(
    new Request("http://localhost/api/v1/analyses/a_1", {
      headers: { Authorization: "Bearer jwt" },
    }),
  );
  assert.equal(getAnalysis.status, 200);
  conforms("analysis.get.response.v1", await getAnalysis.json());

  const at = "2026-10-10T10:00:00Z";
  conforms("session.get.response.v1", {
    session_id: "s_1",
    match_id: "m_1",
    mode: "match",
    upload_mode: "multipart",
    source: {
      width: 1920,
      height: 1080,
      rotation: 0,
      fps_nominal: 30,
      duration_ms: 1000,
      pts: {
        median_interval_us: 33333,
        min_us: 33333,
        max_us: 33334,
        gaps: 0,
        vfr: false,
      },
      codec: "h264",
      has_audio: false,
      audio_rate_hz: 0,
      device: "test",
      bytes: 1000,
      sha256: "a".repeat(64),
    },
    created_at: at,
    upload: {
      status: "completed",
      part_size: 8388608,
      bytes: 1000,
      part_count: 1,
      completed_at: at,
    },
  });
  conforms("upload.complete.response.v1", { status: "completed", bytes: 1000 });
  conforms("upload.abort.response.v1", { status: "aborted" });
  conforms("upload.parts.response.v1", {
    parts: [{ n: 1, url: "https://r2.example/part", expires_at: at }],
  });
  conforms("health.response.v1", { status: "ok" });

  const health = await app.fetch(
    new Request("http://localhost/api/v1/health"),
  );
  assert.equal(health.status, 200);
  conforms("health.response.v1", await health.json());
});

Deno.test("1.4 event schemas cover every read event type", () => {
  const types = [
    "analysis.queued",
    "analysis.started",
    "window.completed",
    "window.failed",
    "analysis.partial",
    "analysis.finalizing",
    "analysis.completed",
    "analysis.failed",
    "point.confirmed",
  ];
  const events = types.map((type, index) => ({
    event_id: `e_${index + 1}`,
    analysis_id: "a_1",
    match_id: "m_1",
    seq: index + 1,
    schema_version: 1,
    type,
    created_at: "2026-10-10T10:00:00Z",
    engine_version: index === 0 ? null : "0.3.0",
    ...(type === "window.completed" || type === "window.failed"
      ? { window_seq: index }
      : {}),
    ...(type === "window.failed" ? { reason: "decode_failed" } : {}),
  }));
  for (const event of events) conforms("analysis.event.v1", event);
  conforms("analysis.events.page.v1", {
    events,
    next_after_seq: events.length,
    has_more: false,
    state: "processing",
  });
  conforms("internal.event.request.v1", {
    type: "analysis.started",
    engine_version: "0.3.0",
  });
  conforms("internal.event.request.v1", {
    type: "window.completed",
    window_seq: 3,
  });
  assert.notDeepEqual(
    check(load("internal.event.request.v1"), { type: "window.completed" }),
    [],
  );
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

Deno.test("known error statuses match 1.4 and 429 retry_after_s is top-level", () => {
  assert.throws(() => new ApiFailure(409, "upload_too_large", "bad status"));
  const failure = new ApiFailure(
    429,
    "storage_budget_reached",
    "storage budget reached",
    undefined,
    60,
  );
  const body = errorBody(
    failure.code,
    failure.message,
    failure.details,
    failure.retry_after_s,
  );
  assert.equal(body.retry_after_s, 60);
  conforms("error.envelope.v1", body);
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
