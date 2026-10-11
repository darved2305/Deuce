import assert from "node:assert/strict";
import { comparisonsResponseSchema } from "../lib/contracts_vendor/index.ts";
import { type Call, type Handler, makeApp, ok } from "./fakes.ts";
import { conforms } from "./jsonschema.ts";

const CANDIDATE = {
  shot_id: "sh_candidate",
  clip_id: "c_candidate",
  distance: 0.12,
  matched_features: ["focal_position", "zones"],
  role: "similar_moment",
  evidence: { won: 1, of: 2, matches: 1, rallies: 2 },
  provenance: "own",
  attribution: null,
};

type Options = {
  match?: unknown;
  shot?: unknown;
  rows?: unknown;
};

function build(options: Options = {}) {
  const userCalls: Call[] = [];
  const adminCalls: Call[] = [];
  const user: Handler = (call) => {
    userCalls.push(call);
    if (call.kind === "from" && call.table === "matches") {
      return ok(
        "match" in options ? options.match : { id: "m_query", owner_id: "u1" },
      );
    }
    if (call.kind === "from" && call.table === "shots") {
      return ok("shot" in options ? options.shot : { id: "sh_query" });
    }
    if (call.kind === "rpc" && call.name === "compare_shot") {
      return ok("rows" in options ? options.rows : [CANDIDATE]);
    }
    return ok(null);
  };
  const admin: Handler = (call) => {
    adminCalls.push(call);
    return ok(null);
  };
  return { app: makeApp(user, admin), userCalls, adminCalls };
}

function get(
  app: ReturnType<typeof build>["app"],
  query = "scope=own",
  headers: HeadersInit = { Authorization: "Bearer jwt" },
) {
  return app.fetch(
    new Request(
      `http://localhost/api/v1/matches/m_query/shots/sh_query/comparisons?${query}`,
      { headers },
    ),
  );
}

function comparisonRpc(calls: Call[]) {
  return calls.find((call) =>
    call.kind === "rpc" && call.name === "compare_shot"
  ) as Extract<Call, { kind: "rpc" }> | undefined;
}

Deno.test("GET comparisons uses the user RPC and returns contract-conforming candidates", async () => {
  const t = build();
  const response = await get(t.app);
  const body = await response.json();
  assert.equal(response.status, 200);
  assert.deepEqual(body, {
    query_shot_id: "sh_query",
    candidates: [CANDIDATE],
  });
  comparisonsResponseSchema.parse(body);
  conforms("comparisons.response.v1", body);
  assert.equal(t.adminCalls.length, 0);
  assert.deepEqual(comparisonRpc(t.userCalls)?.args, {
    p_match_id: "m_query",
    p_shot_id: "sh_query",
    p_scope: "own",
    p_limit: 3,
  });
  assert.ok(
    t.userCalls.some((call) =>
      call.kind === "from" && call.table === "matches" &&
      call.filters.id === "m_query"
    ),
  );
  assert.ok(
    t.userCalls.some((call) =>
      call.kind === "from" && call.table === "shots" &&
      call.filters.match_id === "m_query" && call.filters.id === "sh_query"
    ),
  );
});

Deno.test("GET comparisons applies the reference scope and caps requested limit at five", async () => {
  const t = build();
  const response = await get(t.app, "scope=reference&limit=20");
  assert.equal(response.status, 200);
  assert.deepEqual(comparisonRpc(t.userCalls)?.args, {
    p_match_id: "m_query",
    p_shot_id: "sh_query",
    p_scope: "reference",
    p_limit: 5,
  });
});

Deno.test("GET comparisons returns 404 for a visible match owned by another user", async () => {
  const t = build({ match: { id: "m_query", owner_id: "u2" } });
  const response = await get(t.app);
  assert.equal(response.status, 404);
  assert.equal((await response.json()).error.code, "not_found");
  assert.equal(t.userCalls.filter((call) => call.kind === "from").length, 1);
  assert.equal(comparisonRpc(t.userCalls), undefined);
});

Deno.test("GET comparisons returns 404 when the match is not visible", async () => {
  const t = build({ match: null });
  const response = await get(t.app);
  assert.equal(response.status, 404);
  assert.equal((await response.json()).error.code, "not_found");
  assert.equal(t.userCalls.length, 1);
});

Deno.test("GET comparisons returns 404 when the shot is not in the owned match", async () => {
  const t = build({ shot: null });
  const response = await get(t.app);
  assert.equal(response.status, 404);
  assert.equal((await response.json()).error.code, "not_found");
  assert.equal(comparisonRpc(t.userCalls), undefined);
});

Deno.test("GET comparisons validates the supported scope and positive limit", async () => {
  const scope = build();
  const scopeResponse = await get(scope.app, "scope=invalid");
  assert.equal(scopeResponse.status, 422);
  assert.equal(scope.userCalls.length, 0);

  const limit = build();
  const limitResponse = await get(limit.app, "scope=own&limit=0");
  assert.equal(limitResponse.status, 422);
  assert.equal(limit.userCalls.length, 0);
});

Deno.test("GET comparisons requires a bearer token", async () => {
  const t = build();
  const response = await get(t.app, "scope=own", {});
  assert.equal(response.status, 401);
  assert.equal((await response.json()).error.code, "unauthorized");
  assert.equal(t.userCalls.length, 0);
});
