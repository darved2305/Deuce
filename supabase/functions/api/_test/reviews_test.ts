import assert from "node:assert/strict";
import vectorsJson from "../../../../packages/rules/vectors/bwf_3x21.json" with {
  type: "json",
};
import { canonicalHash } from "../lib/idempotency.ts";
import { type Call, type Handler, makeApp, ok, type Res } from "./fakes.ts";
import { conforms } from "./jsonschema.ts";

const MATCH = {
  id: "m_1",
  owner_id: "u1",
  sport: "badminton",
  format: "singles",
  ruleset_id: "bwf_3x21",
  ruleset_version: "2025-04",
};
const PLAYERS = [
  { player_id: "p_a", identity: "user_confirmed" },
  { player_id: "p_b", identity: "engine" },
];
const RUN = { id: "a_1", players: PLAYERS };
const RALLIES = [{ id: "r_1", start_ms: 0, server: "p_a" }];
const SHOTS = [{
  rally_id: "r_1",
  contact_ms: 10,
  roles: {
    focal: { player_id: "p_a", xy_m: [1, 2] },
    opponent: { player_id: "p_b", xy_m: [2, 10] },
  },
}];
const REVIEWS = [{
  id: "rv_1",
  rally_id: "r_1",
  confirmed_point_winner: null,
  confirmed_by: null,
  confirmed_at: null,
}];
const POINT = {
  confirmed_point_id: "cp_1",
  rally_id: "r_1",
  winner: "p_b",
  by: "p_a",
  at: "2026-10-11T12:00:00Z",
  review_id: "rv_1",
};
const EVENT = {
  event_id: "e_1",
  analysis_id: "a_1",
  match_id: "m_1",
  seq: 1,
  schema_version: 1,
  type: "point.confirmed",
  window_seq: null,
  decode_start_us: null,
  decode_end_us: null,
  emit_start_us: null,
  emit_end_us: null,
  artifact_id: null,
  reason: null,
  engine_version: null,
  created_at: "2026-10-11T12:00:00Z",
};

type Options = {
  match?: unknown;
  analysis?: unknown;
  rallies?: unknown;
  shots?: unknown;
  reviews?: unknown;
  idem?: Res;
  confirm?: Res | ((call: Call) => Res);
};

function build(options: Options = {}) {
  const userLog: Call[] = [];
  const adminLog: Call[] = [];
  const user: Handler = (call) => {
    userLog.push(call);
    if (call.kind !== "from") return ok(null);
    if (call.table === "matches") {
      return ok("match" in options ? options.match : MATCH);
    }
    if (call.table === "analysis_runs") {
      return ok("analysis" in options ? options.analysis : RUN);
    }
    if (call.table === "rallies") {
      return ok("rallies" in options ? options.rallies : RALLIES);
    }
    if (call.table === "shots") {
      return ok("shots" in options ? options.shots : SHOTS);
    }
    if (call.table === "reviews") {
      return ok("reviews" in options ? options.reviews : REVIEWS);
    }
    return ok(null);
  };
  const admin: Handler = (call) => {
    if (call.kind === "from" && call.table === "idempotency") {
      return call.op === "select" ? options.idem ?? ok(null) : ok(null);
    }
    if (call.kind === "rpc" && call.name === "confirm_review") {
      return typeof options.confirm === "function"
        ? options.confirm(call)
        : options.confirm ?? ok({ point: POINT, created: true });
    }
    if (call.kind === "rpc" && call.name === "append_event") return ok(EVENT);
    return ok(null);
  };
  const app = makeApp(user, admin, undefined, [], adminLog);
  return { app, userLog, adminLog };
}

function post(
  app: ReturnType<typeof build>["app"],
  body: unknown = { winner: "p_b" },
  headers: HeadersInit = { "Idempotency-Key": "key-1" },
  reviewId = "rv_1",
) {
  return app.fetch(
    new Request(
      `http://localhost/api/v1/matches/m_1/reviews/${reviewId}/confirm`,
      {
        method: "POST",
        headers: {
          Authorization: "Bearer jwt",
          "Content-Type": "application/json",
          ...headers,
        },
        body: JSON.stringify(body),
      },
    ),
  );
}

const errorBody = async (response: Response) =>
  await response.json() as { error: { code: string; message: string } };
const confirmCall = (calls: Call[]) =>
  calls.find((call) => call.kind === "rpc" && call.name === "confirm_review") as
    | Extract<Call, { kind: "rpc" }>
    | undefined;

Deno.test("confirm review writes a rules score, event and contract-shaped point", async () => {
  conforms("review.confirm.request.v1", { winner: "p_b" });
  const { app, adminLog } = build();
  const response = await post(app);
  assert.equal(response.status, 200);
  const body = await response.json();
  conforms("confirmed.point.v1", body);
  assert.deepEqual(body, POINT);

  const confirmation = confirmCall(adminLog);
  assert.ok(confirmation);
  assert.equal(confirmation.args.p_actor_id, "u1");
  assert.equal(confirmation.args.p_by_player_id, "p_a");
  assert.deepEqual(confirmation.args.p_score, {
    timeline: [{ rally_id: "r_1", after: [0, 1], server_next: "p_b" }],
    games: [],
  });
  assert.deepEqual(confirmation.args.p_confirmed_reviews, [
    { review_id: "rv_1", rally_id: "r_1", winner: "p_b" },
  ]);
  assert.ok(
    adminLog.some((call) =>
      call.kind === "rpc" && call.name === "append_event" &&
      call.args.p_type === "point.confirmed"
    ),
  );
  assert.ok(
    adminLog.some((call) =>
      call.kind === "from" && call.table === "idempotency" &&
      call.op === "upsert"
    ),
  );
});

Deno.test("no user_confirmed player returns 409 conflict", async () => {
  const { app, adminLog } = build({
    analysis: {
      id: "a_1",
      players: PLAYERS.map((player) => ({ ...player, identity: "engine" })),
    },
  });
  const response = await post(app);
  assert.equal(response.status, 409);
  assert.deepEqual(await errorBody(response), {
    error: { code: "conflict", message: "who_is_me not resolved yet" },
  });
  assert.equal(confirmCall(adminLog), undefined);
});

Deno.test("doubles confirm is rejected with the accepted invalid_input code", async () => {
  const { app } = build({ match: { ...MATCH, format: "doubles" } });
  const response = await post(app);
  assert.equal(response.status, 422);
  assert.deepEqual(await errorBody(response), {
    error: {
      code: "invalid_input",
      message: "doubles confirm not supported yet",
    },
  });
});

Deno.test("winner outside the match returns 422 validation_failed", async () => {
  const { app, adminLog } = build();
  const response = await post(app, { winner: "p_unknown" });
  assert.equal(response.status, 422);
  assert.equal((await errorBody(response)).error.code, "validation_failed");
  assert.equal(confirmCall(adminLog), undefined);
});

Deno.test("a non-owner match is hidden as 404", async () => {
  const { app, adminLog } = build({ match: null });
  const response = await post(app);
  assert.equal(response.status, 404);
  assert.equal((await errorBody(response)).error.code, "not_found");
  assert.equal(adminLog.length, 0);
});

Deno.test("missing Idempotency-Key is 422 validation_failed", async () => {
  const { app, userLog, adminLog } = build();
  const response = await post(app, { winner: "p_b" }, {});
  assert.equal(response.status, 422);
  assert.equal((await errorBody(response)).error.code, "validation_failed");
  assert.equal(userLog.length, 0);
  assert.equal(adminLog.length, 0);
});

Deno.test("an idempotency replay returns the stored point without writes or events", async () => {
  const hash = await canonicalHash({
    match_id: "m_1",
    review_id: "rv_1",
    winner: "p_b",
  });
  const { app, adminLog } = build({
    idem: ok({
      request_hash: hash,
      status_code: 200,
      response: POINT,
      created_at: new Date().toISOString(),
    }),
  });
  const response = await post(app);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), POINT);
  assert.equal(confirmCall(adminLog), undefined);
  assert.ok(
    !adminLog.some((call) =>
      call.kind === "rpc" && call.name === "append_event"
    ),
  );
});

Deno.test("a rally-three confirmation leaves score at the confirmed contiguous prefix", async () => {
  const { app, adminLog } = build({
    confirm: ok({
      point: { ...POINT, rally_id: "r_3", review_id: "rv_3" },
      created: true,
    }),
    rallies: [
      { id: "r_1", start_ms: 0, server: "p_a" },
      { id: "r_2", start_ms: 100, server: "p_b" },
      { id: "r_3", start_ms: 200, server: "p_a" },
    ],
    reviews: [
      {
        id: "rv_1",
        rally_id: "r_1",
        confirmed_point_winner: "p_a",
        confirmed_by: "p_a",
        confirmed_at: "2026-10-11T11:00:00Z",
      },
      {
        id: "rv_2",
        rally_id: "r_2",
        confirmed_point_winner: null,
        confirmed_by: null,
        confirmed_at: null,
      },
      {
        id: "rv_3",
        rally_id: "r_3",
        confirmed_point_winner: null,
        confirmed_by: null,
        confirmed_at: null,
      },
    ],
  });
  const response = await post(
    app,
    { winner: "p_b" },
    { "Idempotency-Key": "key-3" },
    "rv_3",
  );
  assert.equal(response.status, 200);
  const confirmation = confirmCall(adminLog);
  assert.ok(confirmation);
  assert.deepEqual(confirmation.args.p_score, {
    timeline: [{ rally_id: "r_1", after: [1, 0], server_next: "p_a" }],
    games: [],
  });
  assert.deepEqual(confirmation.args.p_confirmed_reviews, [
    { review_id: "rv_1", rally_id: "r_1", winner: "p_a" },
    { review_id: "rv_3", rally_id: "r_3", winner: "p_b" },
  ]);
});

Deno.test("single-point score matches the opening BWF golden vector", async () => {
  const { app, adminLog } = build({
    confirm: ok({ point: { ...POINT, winner: "p_a" }, created: true }),
  });
  const response = await post(app, { winner: "p_a" }, {
    "Idempotency-Key": "golden",
  });
  assert.equal(response.status, 200);
  const confirmation = confirmCall(adminLog);
  assert.ok(confirmation);
  const vector = vectorsJson[0] as unknown as {
    expected: {
      current: [number, number];
      games: Array<[number, number]>;
      server: string;
    };
  };
  const score = confirmation.args.p_score as {
    timeline: Array<{ after: [number, number]; server_next: string }>;
    games: Array<[number, number]>;
  };
  assert.deepEqual(score.timeline[0].after, vector.expected.current);
  assert.equal(score.timeline[0].server_next, vector.expected.server);
  assert.deepEqual(score.games, vector.expected.games);
});

Deno.test("a concurrent score change is re-read and recomputed", async () => {
  let attempts = 0;
  const { app, adminLog, userLog } = build({
    confirm: () => {
      attempts++;
      return attempts === 1
        ? { data: null, error: { code: "P0001", message: "score_stale" } }
        : ok({ point: POINT, created: true });
    },
  });
  const response = await post(app);
  assert.equal(response.status, 200);
  assert.equal(attempts, 2);
  assert.equal(
    userLog.filter((call) =>
      call.kind === "from" && call.table === "analysis_runs"
    ).length,
    2,
  );
  assert.equal(
    adminLog.filter((call) =>
      call.kind === "rpc" && call.name === "confirm_review"
    ).length,
    2,
  );
});
