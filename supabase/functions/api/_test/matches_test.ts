import assert from "node:assert/strict";
import type { Call, Handler } from "./fakes.ts";
import { makeApp, ok } from "./fakes.ts";
import { conforms } from "./jsonschema.ts";

function calibration() {
  return {
    calibration_id: "cal_1",
    plane: "court",
    template: "badminton_singles",
    method: "tap",
    H: [1, 0, 0, 0, 1, 0, 0, 0, 1],
    H_cov: null,
    residual_px: 0,
    visible_fraction: 1,
    tier: "A",
  };
}

function matchV1(
  matchId: string,
  calibrated: boolean,
  state = "completed",
  artifacts: { ball_track: string | null; player_tracks: string | null } = {
    ball_track: "art_ball",
    player_tracks: "art_players",
  },
) {
  return {
    schema: "match.v1",
    match_id: matchId,
    session_ids: ["s_1"],
    sport: "badminton",
    format: "singles",
    ruleset: { id: "bwf_3x21", version: "2025-04" },
    players: [],
    calibration: calibrated ? calibration() : null,
    analysis: {
      analysis_id: "a_1",
      engine_version: "0.3.0",
      state,
      models: [],
      artifacts,
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

function build(
  resultFor: (matchId: string) => unknown,
  visibleMatch: (matchId: string) => boolean = () => false,
) {
  const userCalls: Call[] = [];
  const adminCalls: Call[] = [];
  const user: Handler = (call) => {
    userCalls.push(call);
    if (call.kind === "rpc" && call.name === "get_match_v1") {
      return ok(resultFor(String(call.args.p_match_id)));
    }
    if (call.kind === "from" && call.table === "matches") {
      const matchId = String(call.filters.id);
      return ok(visibleMatch(matchId) ? { id: matchId } : null);
    }
    return ok(null);
  };
  const admin: Handler = () => ok(null);
  const app = makeApp(user, admin, undefined, [], adminCalls);
  return { app, userCalls, adminCalls };
}

function getMatch(
  app: ReturnType<typeof build>["app"],
  matchId: string,
  jwt = "jwt",
) {
  return app.fetch(
    new Request(`http://localhost/api/v1/matches/${matchId}`, {
      headers: { Authorization: `Bearer ${jwt}` },
    }),
  );
}

Deno.test("GET match returns schema-valid uncalibrated and calibrated match.v1", async () => {
  for (const calibrated of [false, true]) {
    const expected = matchV1("m_1", calibrated);
    const t = build(() => expected);
    const res = await getMatch(t.app, "m_1");
    assert.equal(res.status, 200, await res.clone().text());
    const body = await res.json();
    conforms("match.v1", body);
    assert.deepEqual(body, expected);
    assert.equal(t.userCalls.length, 1);
    assert.ok(t.userCalls[0].kind === "rpc");
    assert.equal(t.userCalls[0].name, "get_match_v1");
    assert.equal(t.adminCalls.length, 0);
  }
});

Deno.test("GET match maps invisible and unknown records to 404", async () => {
  const t = build(() => null);
  for (const matchId of ["m_hidden", "m_unknown"]) {
    const res = await getMatch(t.app, matchId);
    assert.equal(res.status, 404);
    assert.equal((await res.json()).error.code, "not_found");
  }
});

Deno.test("GET match returns 409 while a visible match analysis is incomplete", async () => {
  const cases = [
    {
      app: build(() => null, () => true).app,
      matchId: "m_no_analysis",
    },
    ...[
      "awaiting_upload",
      "uploaded",
      "queued",
      "processing",
      "partial",
      "finalizing",
      "failed",
    ].map((state) => ({
      app: build(() => matchV1(`m_${state}`, false, state), () => true).app,
      matchId: `m_${state}`,
    })),
  ];
  for (const { app, matchId } of cases) {
    const res = await getMatch(app, matchId);
    assert.equal(res.status, 409);
    const body = await res.json();
    assert.deepEqual(body.error, {
      code: "conflict",
      message: "analysis not complete",
    });
    conforms("error.envelope.v1", body);
  }
});

Deno.test("GET match accepts null whole-match track artifact ids", async () => {
  const expected = matchV1("m_no_tracks", false, "completed", {
    ball_track: null,
    player_tracks: null,
  });
  const t = build(() => expected);
  const res = await getMatch(t.app, "m_no_tracks");
  assert.equal(res.status, 200, await res.clone().text());
  const body = await res.json();
  conforms("match.v1", body);
  assert.deepEqual(body.analysis.artifacts, {
    ball_track: null,
    player_tracks: null,
  });
});

Deno.test("GET match uses the user RPC for a share-grant reader fake", async () => {
  const expected = matchV1("m_shared", false);
  const t = build((matchId) => matchId === "m_shared" ? expected : null);
  const res = await getMatch(t.app, "m_shared", "share-reader-jwt");
  assert.equal(res.status, 200, await res.clone().text());
  conforms("match.v1", await res.json());
  assert.equal(t.userCalls.length, 1);
  assert.equal(t.adminCalls.length, 0);
});

Deno.test("GET match returns internal when the RPC result violates match.v1", async () => {
  const t = build(() => ({ ...matchV1("m_1", false), unexpected: true }));
  const res = await getMatch(t.app, "m_1");
  assert.equal(res.status, 500);
  assert.equal((await res.json()).error.code, "internal");
});

Deno.test("GET match appears in the public OpenAPI document", async () => {
  const t = build(() => null);
  const res = await t.app.fetch(
    new Request("http://localhost/api/v1/openapi.json"),
  );
  assert.equal(res.status, 200, await res.clone().text());
  const doc = await res.json();
  assert.ok(doc.paths["/v1/matches/{id}"].get);
});
