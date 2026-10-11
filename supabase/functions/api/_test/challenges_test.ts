import assert from "node:assert/strict";
import { canonicalHash } from "../lib/idempotency.ts";
import { type Call, type Handler, makeApp, ok, type Res } from "./fakes.ts";
import { conforms } from "./jsonschema.ts";

const CREATOR = "11111111-1111-4111-8111-111111111111";
const FRIEND = "22222222-2222-4222-8222-222222222222";
const ROUNDS = ["e_1", "e_3", "e_5"].map((hit, i) => ({
  hit_event_id: hit,
  landing_event_id: `e_${i * 2 + 2}`,
  contact_ms: 1000 * (i + 1),
  contact_sigma_ms: 8,
  ladder_us: [750000 + i * 1000000, 850000 + i * 1000000],
}));
const result = (hit: string, points: number) => ({
  hit_event_id: hit,
  pick: points > 0 ? "B2" : null,
  reaction_ms: points > 0 ? 500 : null,
  verdict: points > 0 ? "exact" : "too_slow",
  points,
});
const RESULTS = [result("e_1", 120), result("e_3", 100), result("e_5", 0)];
const CREATE = {
  match_id: "m_1",
  display_name: "Hetansh",
  rounds: ROUNDS,
  score: 220,
  round_results: RESULTS,
};
const VIEW = {
  id: "ch_1",
  match_id: "m_1",
  status: "open",
  video_sha256: "c".repeat(64),
  rematch_of: null,
  rounds: ROUNDS,
  creator: {
    player_id: CREATOR,
    display_name: "Hetansh",
    score: 220,
    round_results: RESULTS,
  },
  entries: [],
  viewer: { player_id: CREATOR, role: "creator" },
  created_at: "2026-10-11T10:00:00+00:00",
};
const PLAYED_VIEW = {
  ...VIEW,
  status: "played",
  viewer: { player_id: FRIEND, role: "opponent" },
  entries: [{
    player_id: FRIEND,
    display_name: "Asha",
    score: 100,
    round_results: [result("e_1", 100)],
    outcome: "creator",
    accepted_at: "2026-10-11T10:01:00.123456+00:00",
    played_at: "2026-10-11T10:05:00.123456+00:00",
  }],
};

type Options = {
  view?: unknown;
  rpc?: Record<string, Res>;
  idem?: Res;
  friends?: unknown;
};

function build(options: Options = {}) {
  const userLog: Call[] = [];
  const adminLog: Call[] = [];
  const user: Handler = (call) => {
    userLog.push(call);
    if (call.kind === "rpc" && call.name === "get_challenge_v1") {
      return ok("view" in options ? options.view : VIEW);
    }
    if (call.kind === "rpc" && call.name === "list_friends_v1") {
      return ok(options.friends ?? []);
    }
    return ok(null);
  };
  const admin: Handler = (call) => {
    if (call.kind === "from" && call.table === "idempotency") {
      return call.op === "select" ? options.idem ?? ok(null) : ok(null);
    }
    if (call.kind === "rpc") {
      return options.rpc?.[call.name] ??
        ok(call.name === "create_challenge" ? "ch_1" : null);
    }
    return ok(null);
  };
  return {
    app: makeApp(user, admin, undefined, [], adminLog),
    userLog,
    adminLog,
  };
}

const rpcError = (code: string, message: string): Res => ({
  data: null,
  error: { code, message },
});

function req(
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
        ...(body === undefined ? {} : { "Content-Type": "application/json" }),
        ...headers,
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    }),
  );
}

const create = (
  app: ReturnType<typeof build>["app"],
  body: unknown = CREATE,
  headers: HeadersInit = { "Idempotency-Key": "k1" },
) => req(app, "POST", "/v1/challenges", body, headers);

const errorCode = async (res: Response) =>
  ((await res.json()) as { error: { code: string } }).error.code;

Deno.test("create stores the freeze set and returns challenge.v1", async () => {
  const { app, adminLog, userLog } = build();
  const res = await create(app);
  assert.equal(res.status, 201);
  const body = await res.json();
  assert.deepEqual(body, VIEW);
  conforms("challenge.v1", body);
  const rpc = adminLog.find((c) =>
    c.kind === "rpc" && c.name === "create_challenge"
  ) as
    & Call
    & { args: Record<string, unknown> };
  assert.deepEqual(rpc.args, {
    p_player: "u1",
    p_match_id: "m_1",
    p_display_name: "Hetansh",
    p_rounds: ROUNDS,
    p_score: 220,
    p_results: RESULTS,
    p_rematch_of: null,
  });
  // The view is read back as the caller, under RLS.
  assert.ok(
    userLog.some((c) => c.kind === "rpc" && c.name === "get_challenge_v1"),
  );
  const stored = adminLog.find((c) =>
    c.kind === "from" && c.table === "idempotency" && c.op === "upsert"
  ) as
    & Call
    & { payload: Record<string, unknown> };
  assert.equal(stored.payload.endpoint, "POST /v1/challenges");
  assert.equal(stored.payload.status_code, 201);
});

Deno.test("create replays an Idempotency-Key without creating twice", async () => {
  const { app, adminLog } = build({
    idem: ok({
      request_hash: await canonicalHash(CREATE),
      status_code: 201,
      response: VIEW,
      created_at: new Date().toISOString(),
    }),
  });
  const res = await create(app);
  assert.equal(res.status, 201);
  assert.deepEqual(await res.json(), VIEW);
  assert.ok(!adminLog.some((c) => c.kind === "rpc"));
});

Deno.test("create refuses an Idempotency-Key used for another request", async () => {
  const { app } = build({
    idem: ok({
      request_hash: "0".repeat(64),
      status_code: 201,
      response: VIEW,
      created_at: new Date().toISOString(),
    }),
  });
  const res = await create(app);
  assert.equal(res.status, 409);
  assert.equal(await errorCode(res), "idempotency_conflict");
});

Deno.test("create needs the Idempotency-Key header and a valid body", async () => {
  const { app, adminLog } = build();
  assert.equal((await create(app, CREATE, {})).status, 422);
  assert.equal(
    (await create(app, { ...CREATE, rounds: ROUNDS.slice(0, 2) })).status,
    422,
  );
  assert.equal((await create(app, { ...CREATE, extra: true })).status, 422);
  assert.equal(
    (await create(app, { ...CREATE, display_name: "x".repeat(25) })).status,
    422,
  );
  assert.ok(!adminLog.some((c) => c.kind === "rpc"));
});

Deno.test("create checks the score is the sum of the round points", async () => {
  const { app, adminLog } = build();
  const res = await create(app, { ...CREATE, score: 9999 });
  assert.equal(res.status, 422);
  assert.equal(await errorCode(res), "validation_failed");
  assert.ok(!adminLog.some((c) => c.kind === "rpc"));
});

Deno.test("create refuses results for rounds that are not in the freeze set", async () => {
  const { app } = build();
  const res = await create(app, {
    ...CREATE,
    score: 220,
    round_results: [result("e_1", 120), result("e_99", 100)],
  });
  assert.equal(res.status, 422);
  assert.equal(await errorCode(res), "validation_failed");
});

Deno.test("create maps database errors onto the envelope", async () => {
  const cases: Array<[Res, number, string]> = [
    [rpcError("P0002", "match_not_found"), 404, "not_found"],
    [rpcError("P0001", "rematch_forbidden"), 404, "not_found"],
    [rpcError("P0001", "unknown_event"), 422, "validation_failed"],
    [rpcError("P0001", "rounds_mismatch"), 422, "validation_failed"],
    [rpcError("P0001", "no_source_hash"), 409, "conflict"],
  ];
  for (const [res, status, code] of cases) {
    const { app, adminLog } = build({ rpc: { create_challenge: res } });
    const out = await create(app);
    assert.equal(out.status, status, res.error?.message);
    assert.equal(await errorCode(out), code);
    assert.ok(!adminLog.some((c) => c.kind === "from" && c.op === "upsert"));
  }
});

Deno.test("create passes rematch_of through", async () => {
  const { app, adminLog } = build();
  const res = await create(app, { ...CREATE, rematch_of: "ch_0" });
  assert.equal(res.status, 201);
  const rpc = adminLog.find((c) =>
    c.kind === "rpc" && c.name === "create_challenge"
  ) as
    & Call
    & { args: Record<string, unknown> };
  assert.equal(rpc.args.p_rematch_of, "ch_0");
});

Deno.test("accept grants through the database function and returns the caller's view", async () => {
  const { app, adminLog } = build({ view: PLAYED_VIEW });
  const res = await req(app, "POST", "/v1/challenges/ch_1/accept", {
    display_name: "Asha",
  });
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.deepEqual(body, PLAYED_VIEW);
  conforms("challenge.v1", body);
  const rpc = adminLog.find((c) =>
    c.kind === "rpc" && c.name === "accept_challenge"
  ) as
    & Call
    & { args: Record<string, unknown> };
  assert.deepEqual(rpc.args, {
    p_player: "u1",
    p_challenge_id: "ch_1",
    p_display_name: "Asha",
  });
});

Deno.test("accept works without a body", async () => {
  const { app, adminLog } = build();
  const res = await req(app, "POST", "/v1/challenges/ch_1/accept");
  assert.equal(res.status, 200);
  const rpc = adminLog.find((c) =>
    c.kind === "rpc" && c.name === "accept_challenge"
  ) as
    & Call
    & { args: Record<string, unknown> };
  assert.equal(rpc.args.p_display_name, null);
});

Deno.test("accept: unknown challenge is 404, own challenge is 409", async () => {
  const missing = build({
    rpc: { accept_challenge: rpcError("P0002", "challenge_not_found") },
  });
  const a = await req(missing.app, "POST", "/v1/challenges/ch_9/accept", {});
  assert.equal(a.status, 404);
  assert.equal(await errorCode(a), "not_found");
  const own = build({
    rpc: { accept_challenge: rpcError("P0001", "own_challenge") },
  });
  const b = await req(own.app, "POST", "/v1/challenges/ch_1/accept", {});
  assert.equal(b.status, 409);
  assert.equal(await errorCode(b), "conflict");
});

Deno.test("accept rejects an id that is not a challenge id", async () => {
  const { app } = build();
  const res = await req(app, "POST", "/v1/challenges/m_1/accept", {});
  assert.equal(res.status, 422);
});

Deno.test("entries stores the run and returns the view with the outcome", async () => {
  const { app, adminLog } = build({ view: PLAYED_VIEW });
  const res = await req(app, "POST", "/v1/challenges/ch_1/entries", {
    display_name: "Asha",
    score: 100,
    round_results: [result("e_1", 100)],
  });
  assert.equal(res.status, 201);
  const body = await res.json();
  assert.equal(body.entries[0].outcome, "creator");
  const rpc = adminLog.find((c) =>
    c.kind === "rpc" && c.name === "submit_challenge_entry"
  ) as
    & Call
    & { args: Record<string, unknown> };
  assert.equal(rpc.args.p_score, 100);
  assert.equal(rpc.args.p_player, "u1");
});

Deno.test("entries: wrong score is 422, not a participant is 404, played twice is 409", async () => {
  const body = { score: 100, round_results: [result("e_1", 100)] };
  const bad = build();
  const a = await req(bad.app, "POST", "/v1/challenges/ch_1/entries", {
    ...body,
    score: 5,
  });
  assert.equal(a.status, 422);
  assert.ok(!bad.adminLog.some((c) => c.kind === "rpc"));
  const stranger = build({
    rpc: { submit_challenge_entry: rpcError("P0002", "not_participant") },
  });
  const b = await req(
    stranger.app,
    "POST",
    "/v1/challenges/ch_1/entries",
    body,
  );
  assert.equal(b.status, 404);
  const twice = build({
    rpc: { submit_challenge_entry: rpcError("P0001", "already_played") },
  });
  const c = await req(twice.app, "POST", "/v1/challenges/ch_1/entries", body);
  assert.equal(c.status, 409);
  assert.equal(await errorCode(c), "conflict");
});

Deno.test("get returns the caller's view; a non-participant sees 404", async () => {
  const { app } = build();
  const res = await req(app, "GET", "/v1/challenges/ch_1");
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), VIEW);
  const hidden = build({ view: null });
  const miss = await req(hidden.app, "GET", "/v1/challenges/ch_1");
  assert.equal(miss.status, 404);
  assert.equal(await errorCode(miss), "not_found");
});

Deno.test("get reads as the caller, never through the admin client", async () => {
  const { app, adminLog, userLog } = build();
  await req(app, "GET", "/v1/challenges/ch_1");
  assert.ok(
    userLog.some((c) => c.kind === "rpc" && c.name === "get_challenge_v1"),
  );
  assert.ok(!adminLog.some((c) => c.kind === "rpc"));
});

Deno.test("a stored view that breaks the contract is an internal error, not a leak", async () => {
  const { app } = build({ view: { ...VIEW, status: "weird" } });
  const res = await req(app, "GET", "/v1/challenges/ch_1");
  assert.equal(res.status, 500);
  assert.equal(await errorCode(res), "internal");
});

Deno.test("friends lists head-to-head records", async () => {
  const items = [{
    player_id: FRIEND,
    display_name: "Asha",
    challenges: 3,
    played: 2,
    wins: 1,
    losses: 1,
    ties: 0,
    last_challenge_at: "2026-10-11T10:05:00+00:00",
  }];
  const { app } = build({ friends: items });
  const res = await req(app, "GET", "/v1/friends");
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.deepEqual(body, { items });
  conforms("friends.response.v1", body);
  const empty = await req(build().app, "GET", "/v1/friends");
  assert.deepEqual(await empty.json(), { items: [] });
});

Deno.test("challenge routes need a token", async () => {
  const { app } = build();
  for (
    const [method, path] of [
      ["GET", "/v1/friends"],
      ["GET", "/v1/challenges/ch_1"],
      ["POST", "/v1/challenges/ch_1/accept"],
    ]
  ) {
    const res = await app.fetch(
      new Request(`http://localhost/api${path}`, { method }),
    );
    assert.equal(res.status, 401, `${method} ${path}`);
  }
});

Deno.test("challenge routes are published in OpenAPI", async () => {
  const { app } = build();
  const doc = await (await app.fetch(
    new Request("http://localhost/api/v1/openapi.json"),
  )).json();
  assert.ok(doc.paths["/v1/challenges"].post);
  assert.ok(doc.paths["/v1/challenges/{id}/accept"].post);
  assert.ok(doc.paths["/v1/challenges/{id}/entries"].post);
  assert.ok(doc.paths["/v1/challenges/{id}"].get);
  assert.ok(doc.paths["/v1/friends"].get);
});
