import assert from "node:assert/strict";
import type { R2 } from "../lib/r2.ts";
import type { Call, Handler } from "./fakes.ts";
import { makeApp, ok, testSessionSpec } from "./fakes.ts";
import { conforms } from "./jsonschema.ts";

const OWNER_A = "11111111-1111-1111-1111-111111111111";
const CREATED_AT = "2026-10-11T15:00:00.000Z";
const SIGNED_URL =
  "https://r2.test/artifact?X-Amz-Date=20300102T030405Z&X-Amz-Expires=300";

function makeKey(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  const base64 = btoa(String.fromCharCode(...bytes));
  return `k_${
    base64.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
  }`;
}

async function hashKey(key: string): Promise<string> {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(key),
  );
  return Array.from(
    new Uint8Array(digest),
    (b) => b.toString(16).padStart(2, "0"),
  ).join("");
}

async function build(
  handler: Handler,
  options: { ownerId?: string; revoked?: boolean; r2?: R2 } = {},
) {
  const key = makeKey();
  const keyHash = await hashKey(key);
  const ownerId = options.ownerId ?? OWNER_A;
  const adminLog: Call[] = [];
  const admin: Handler = async (call) => {
    if (
      call.kind === "from" && call.table === "developer_keys" &&
      call.op === "select"
    ) {
      return ok(
        !options.revoked && call.filters.key_hash === keyHash &&
          call.filters["revoked_at.is"] === null
          ? { owner_id: ownerId, scope: "read" }
          : null,
      );
    }
    return await handler(call);
  };
  return {
    app: makeApp(() => ok(null), admin, undefined, [], adminLog, options.r2),
    key,
    adminLog,
  };
}

function send(
  app: ReturnType<typeof makeApp>,
  path: string,
  method = "GET",
  headers: Record<string, string> = {},
) {
  return app.fetch(
    new Request(`http://localhost/api${path}`, { method, headers }),
  );
}

Deno.test("API key is rejected on caller-scoped challenge routes (HW31)", async () => {
  const t = await build(() => ok(null));
  for (
    const path of [
      "/v1/challenges",
      "/v1/challenges/ch_any",
      "/v1/challenges/ch_any/entries",
    ]
  ) {
    const res = await send(t.app, path, "GET", { "X-API-Key": t.key });
    assert.equal(res.status, 403, path);
    assert.equal((await res.json()).error.code, "read_only_key", path);
  }
});

function matchV1(matchId: string) {
  return {
    schema: "match.v1",
    match_id: matchId,
    session_ids: ["s_owned"],
    sport: "badminton",
    format: "singles",
    ruleset: { id: "bwf_3x21", version: "2025-04" },
    players: [],
    calibration: null,
    analysis: {
      analysis_id: "a_owned",
      engine_version: "0.3.0",
      state: "completed",
      models: [],
      artifacts: { ball_track: "art_ball", player_tracks: "art_players" },
    },
    capabilities: {
      court: { tier: "core", status: "unknown" },
      ball: { tier: "core", status: "unknown" },
      reviews: { tier: "core", status: "unknown" },
      score: { tier: "core", status: "unknown" },
    },
    rallies: [],
    shots: [],
    events: [],
    reviews: [],
    confirmed_points: [],
    score: { timeline: [], games: [] },
    metrics: [],
    insights: [],
    moments: [],
    clips: [],
    corrections: [],
  };
}

Deno.test("API key reads analysis events for its owner only", async () => {
  const t = await build((call) => {
    if (call.kind !== "from") return ok(null);
    if (call.table === "analysis_runs") {
      return ok(
        call.filters.id === "a_owned" && call.filters.owner_id === OWNER_A
          ? { id: "a_owned", state: "completed" }
          : null,
      );
    }
    if (call.table === "analysis_events") return ok([]);
    return ok(null);
  });
  const own = await send(t.app, "/v1/analyses/a_owned/events", "GET", {
    "X-API-Key": t.key,
  });
  assert.equal(own.status, 200);
  const body = await own.json();
  assert.deepEqual(body, {
    events: [],
    next_after_seq: 0,
    has_more: false,
    state: "completed",
  });
  conforms("analysis.events.page.v1", body);
  assert.ok(
    t.adminLog.some((call) =>
      call.kind === "from" && call.table === "analysis_runs" &&
      call.filters.owner_id === OWNER_A
    ),
  );
  assert.ok(
    t.adminLog.some((call) =>
      call.kind === "from" && call.table === "analysis_events" &&
      call.filters.owner_id === OWNER_A
    ),
  );

  const before =
    t.adminLog.filter((call) =>
      call.kind === "from" && call.table === "analysis_events"
    ).length;
  const other = await send(t.app, "/v1/analyses/a_other/events", "GET", {
    "X-API-Key": t.key,
  });
  assert.equal(other.status, 404);
  assert.equal((await other.json()).error.code, "not_found");
  assert.equal(
    t.adminLog.filter((call) =>
      call.kind === "from" && call.table === "analysis_events"
    ).length,
    before,
  );
});

Deno.test("API key signs only an owner's artifact and keeps the download cap", async () => {
  const signedKeys: string[] = [];
  const artifact = {
    id: "art_owned",
    r2_key: "analysis/a_owned/engine/windows/0.json",
    match_id: "m_owned",
  };
  const r2 = {
    presignGet: (key: string) => {
      signedKeys.push(key);
      return Promise.resolve(SIGNED_URL);
    },
  } as unknown as R2;
  const t = await build((call) => {
    if (call.kind === "rpc" && call.name === "r2_take_download") {
      return ok(1);
    }
    if (call.kind === "rpc" && call.name === "r2_add_usage") return ok(true);
    if (call.kind === "from" && call.table === "artifacts") {
      return ok(
        call.filters.id === artifact.id && call.filters.owner_id === OWNER_A
          ? artifact
          : null,
      );
    }
    return ok(null);
  }, { r2 });
  const own = await send(t.app, "/v1/artifacts/art_owned", "GET", {
    "X-API-Key": t.key,
  });
  assert.equal(own.status, 200);
  assert.deepEqual(await own.json(), {
    url: SIGNED_URL,
    expires_at: "2030-01-02T03:09:05.000Z",
  });
  assert.deepEqual(signedKeys, [artifact.r2_key]);
  assert.ok(
    t.adminLog.some((call) =>
      call.kind === "rpc" && call.name === "r2_take_download" &&
      call.args.p_owner === OWNER_A && call.args.p_limit === 1000
    ),
  );
  assert.ok(
    t.adminLog.some((call) =>
      call.kind === "from" && call.table === "artifacts" &&
      call.filters.owner_id === OWNER_A
    ),
  );

  const other = await send(t.app, "/v1/artifacts/art_other", "GET", {
    "X-API-Key": t.key,
  });
  assert.equal(other.status, 404);
  assert.equal((await other.json()).error.code, "not_found");
  assert.deepEqual(signedKeys, [artifact.r2_key]);
});

Deno.test("API key reads only owner match.v1", async () => {
  const t = await build((call) => {
    if (call.kind === "from" && call.table === "matches") {
      return ok(
        call.filters.id === "m_owned" && call.filters.owner_id === OWNER_A
          ? { id: "m_owned" }
          : null,
      );
    }
    if (call.kind === "rpc" && call.name === "get_match_v1_for_owner") {
      return ok(
        call.args.p_owner === OWNER_A && call.args.p_match_id === "m_owned"
          ? matchV1("m_owned")
          : null,
      );
    }
    return ok(null);
  });
  const own = await send(t.app, "/v1/matches/m_owned", "GET", {
    "X-API-Key": t.key,
  });
  assert.equal(own.status, 200);
  const body = await own.json();
  assert.equal(body.match_id, "m_owned");
  conforms("match.v1", body);
  assert.ok(
    t.adminLog.some((call) =>
      call.kind === "from" && call.table === "matches" &&
      call.filters.owner_id === OWNER_A
    ),
  );
  assert.ok(
    t.adminLog.some((call) =>
      call.kind === "rpc" && call.name === "get_match_v1_for_owner" &&
      call.args.p_owner === OWNER_A
    ),
  );

  const other = await send(t.app, "/v1/matches/m_other", "GET", {
    "X-API-Key": t.key,
  });
  assert.equal(other.status, 404);
  assert.equal((await other.json()).error.code, "not_found");
});

Deno.test("API key comparisons are owner-scoped and do not read shared grants", async () => {
  const t = await build((call) => {
    if (call.kind === "from" && call.table === "matches") {
      return ok(
        call.filters.id === "m_owned" && call.filters.owner_id === OWNER_A
          ? { id: "m_owned", owner_id: OWNER_A }
          : null,
      );
    }
    if (call.kind === "from" && call.table === "shots") {
      return ok(
        call.filters.match_id === "m_owned" && call.filters.id === "sh_owned" &&
          call.filters.owner_id === OWNER_A
          ? { id: "sh_owned" }
          : null,
      );
    }
    if (call.kind === "rpc" && call.name === "compare_shot_for_owner") {
      return ok([]);
    }
    return ok(null);
  });
  const own = await send(
    t.app,
    "/v1/matches/m_owned/shots/sh_owned/comparisons?scope=own",
    "GET",
    { "X-API-Key": t.key },
  );
  assert.equal(own.status, 200);
  const ownBody = await own.json();
  assert.deepEqual(ownBody, { query_shot_id: "sh_owned", candidates: [] });
  conforms("comparisons.response.v1", ownBody);
  const ownRpc = t.adminLog.find((call) =>
    call.kind === "rpc" && call.name === "compare_shot_for_owner"
  );
  assert.ok(ownRpc && ownRpc.kind === "rpc");
  assert.deepEqual(ownRpc.args, {
    p_owner: OWNER_A,
    p_match_id: "m_owned",
    p_shot_id: "sh_owned",
    p_scope: "own",
    p_limit: 3,
  });
  assert.ok(
    t.adminLog.some((call) =>
      call.kind === "from" && call.table === "shots" &&
      call.filters.owner_id === OWNER_A
    ),
  );

  const shared = await send(
    t.app,
    "/v1/matches/m_owned/shots/sh_owned/comparisons?scope=shared",
    "GET",
    { "X-API-Key": t.key },
  );
  assert.equal(shared.status, 200);
  assert.deepEqual(await shared.json(), {
    query_shot_id: "sh_owned",
    candidates: [],
  });
  const sharedRpc = t.adminLog.filter((call) =>
    call.kind === "rpc" && call.name === "compare_shot_for_owner"
  ).at(-1);
  assert.ok(sharedRpc && sharedRpc.kind === "rpc");
  assert.equal(sharedRpc.args.p_scope, "shared");

  const other = await send(
    t.app,
    "/v1/matches/m_other/shots/sh_other/comparisons?scope=own",
    "GET",
    { "X-API-Key": t.key },
  );
  assert.equal(other.status, 404);
  assert.equal((await other.json()).error.code, "not_found");
});

Deno.test("API key reads only owner session and owner upload status", async () => {
  const session = {
    id: "s_owned",
    match_id: "m_owned",
    mode: "match",
    upload_mode: "segments",
    source: testSessionSpec.source,
    created_at: CREATED_AT,
  };
  const t = await build((call) => {
    if (call.kind === "from" && call.table === "sessions") {
      return ok(
        call.filters.id === session.id && call.filters.owner_id === OWNER_A
          ? session
          : null,
      );
    }
    if (call.kind === "from" && call.table === "uploads") return ok(null);
    return ok(null);
  });
  const own = await send(t.app, "/v1/sessions/s_owned", "GET", {
    "X-API-Key": t.key,
  });
  assert.equal(own.status, 200);
  const body = await own.json();
  assert.equal(body.session_id, "s_owned");
  assert.equal(body.match_id, "m_owned");
  assert.equal(body.upload, null);
  conforms("session.get.response.v1", body);
  assert.ok(
    t.adminLog.some((call) =>
      call.kind === "from" && call.table === "sessions" &&
      call.filters.owner_id === OWNER_A
    ),
  );
  assert.ok(
    t.adminLog.some((call) =>
      call.kind === "from" && call.table === "uploads" &&
      call.filters.owner_id === OWNER_A
    ),
  );

  const other = await send(t.app, "/v1/sessions/s_other", "GET", {
    "X-API-Key": t.key,
  });
  assert.equal(other.status, 404);
  assert.equal((await other.json()).error.code, "not_found");
});

Deno.test("API key is read-only on every public mutation route", async () => {
  const t = await build(() => ok(null));
  const mutations = [
    ["POST", "/v1/analyses"],
    ["POST", "/v1/sessions"],
    ["POST", "/v1/sessions/s_owned/upload/parts"],
    ["POST", "/v1/sessions/s_owned/upload/complete"],
    ["POST", "/v1/sessions/s_owned/upload/abort"],
    ["POST", "/v1/sessions/s_owned/segments"],
    ["POST", "/v1/sessions/s_owned/segments/0/complete"],
    ["POST", "/v1/developer-keys"],
    ["DELETE", "/v1/developer-keys/k_owned"],
    ["DELETE", "/v1/matches/m_owned"],
    ["POST", "/v1/matches/m_owned/reviews/rv_owned/confirm"],
    ["POST", "/v1/matches/m_owned/corrections"],
  ] as const;
  for (const [method, path] of mutations) {
    const response = await send(t.app, path, method, { "X-API-Key": t.key });
    assert.equal(response.status, 403, `${method} ${path}`);
    assert.equal((await response.json()).error.code, "read_only_key");
  }
});

Deno.test("revoked API key is unauthorized on other read routes", async () => {
  const t = await build(() => ok(null), { revoked: true });
  const response = await send(t.app, "/v1/matches/m_owned", "GET", {
    "X-API-Key": t.key,
  });
  assert.equal(response.status, 401);
  assert.equal((await response.json()).error.code, "unauthorized");
  assert.equal(t.adminLog.length, 1);
});
