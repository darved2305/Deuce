import assert from "node:assert/strict";
import { test } from "node:test";
import { startHarness, type Stub } from "./harness.ts";
import { matchFixture, MATCH_ID } from "./fixture.ts";

const json = (response: { end(b: string): void }, body: unknown) => response.end(JSON.stringify(body));

const stub: Stub = (path, _request, response) => {
  if (path === `/v1/matches/${MATCH_ID}`) return json(response, matchFixture());
  if (path.startsWith(`/v1/matches/${MATCH_ID}/shots/sh_r_b_1/comparisons?scope=own&limit=2`)) {
    return json(response, {
      query_shot_id: "sh_r_b_1",
      candidates: [{
        shot_id: "sh_r_a_0",
        clip_id: "c_sh_r_a_0",
        distance: 0.12,
        matched_features: ["zone"],
        role: "similar_moment",
        evidence: { won: 1, of: 2, matches: 1, rallies: 2 },
        provenance: "own",
        attribution: null,
      }],
    });
  }
  if (path === "/v1/matches/m_missing") {
    response.statusCode = 404;
    return json(response, { error: { code: "not_found", message: "match not found" } });
  }
  response.statusCode = 404;
  json(response, { error: { code: "not_found", message: "nope" } });
};

test("rewatch_picks answers from the match record with deep links, over stdio", async () => {
  const h = await startHarness(stub, { ELEVATE_API_KEY: "ek_test" });
  try {
    const res = await h.call("rewatch_picks", { match_id: MATCH_ID, limit: 2 });
    assert.equal(res.isError, false, res.text);
    const body = JSON.parse(res.text);
    assert.equal(body.picks[0].rally_id, "r_b");
    assert.equal(body.picks[0].deep_link, `elevate://m/${MATCH_ID}/r/r_b?t_ms=10000`);
    assert.equal(h.requests[0].headers["x-api-key"], "ek_test");
    assert.equal(h.requests[0].headers.authorization, undefined);

    const landings = JSON.parse((await h.call("rewatch_picks", { match_id: MATCH_ID, focus: "landings" })).text);
    assert.equal(landings.landing_zones.length, 2);

    const zones = JSON.parse((await h.call("get_landing_zones", { match_id: MATCH_ID, player: "p_me" })).text);
    assert.equal(zones.players.length, 1);
    assert.equal(zones.players[0].shots_total, 6);

    const rally = JSON.parse((await h.call("get_rally", { match_id: MATCH_ID, rally_id: "r_c" })).text);
    assert.equal(rally.shots.length, 3);
    assert.match(rally.deep_link, /^elevate:\/\/m\/m_demo1\/r\/r_c\?t_ms=40000$/);

    const moments = JSON.parse((await h.call("find_moments", { match_id: MATCH_ID, tags: ["long_rally"] })).text);
    assert.equal(moments.items.length, 1);

    const caps = JSON.parse((await h.call("get_capabilities", { match_id: MATCH_ID })).text);
    assert.equal(caps.capabilities.ball.status, "pass");

    assert.deepEqual(h.stray, [], "nothing but JSON-RPC may reach stdout");
  } finally {
    await h.close();
  }
});

test("compare_moment forwards scope and limit and validates the response", async () => {
  const h = await startHarness(stub, { ELEVATE_JWT: "jwt_test" });
  try {
    const res = await h.call("compare_moment", { match_id: MATCH_ID, shot_id: "sh_r_b_1", scope: "own", limit: 2 });
    assert.equal(res.isError, false, res.text);
    assert.equal(JSON.parse(res.text).candidates[0].shot_id, "sh_r_a_0");
    assert.equal(h.requests[0].headers.authorization, "Bearer jwt_test");
  } finally {
    await h.close();
  }
});

test("errors keep the API envelope code and message, and filters without a match are refused", async () => {
  const h = await startHarness(stub, { ELEVATE_JWT: "jwt_test" });
  try {
    const missing = await h.call("rewatch_picks", { match_id: "m_missing" });
    assert.equal(missing.isError, true);
    assert.match(missing.text, /HTTP 404 \(not_found\): match not found/);

    const noMatch = await h.call("find_moments", { tags: ["landing"] });
    assert.equal(noMatch.isError, true);
    assert.match(noMatch.text, /need a match_id/);

    const badInput = await h.call("rewatch_picks", { match_id: MATCH_ID, limit: 99 });
    assert.equal(badInput.isError, true);
    assert.equal(h.requests.length, 1, "invalid input never reaches the API");
  } finally {
    await h.close();
  }
});

test("without a credential the tools say exactly what to set", async () => {
  const h = await startHarness(stub);
  try {
    const res = await h.call("rewatch_picks", { match_id: MATCH_ID });
    assert.equal(res.isError, true);
    assert.match(res.text, /ELEVATE_API_KEY or ELEVATE_JWT/);
    assert.equal(h.requests.length, 0);
  } finally {
    await h.close();
  }
});

test("get_analysis_progress counts windows and hit candidates from signed artifacts", async () => {
  let base = "";
  const progressStub: Stub = (path, _req, response) => {
    const ev = (seq: number, type: string, extra: object = {}) => ({
      event_id: `e_${seq}`, analysis_id: "a_1", match_id: MATCH_ID, seq, schema_version: 1,
      engine_version: "0.3.0", created_at: "2026-10-11T00:00:00.000Z", type, ...extra,
    });
    if (path === "/v1/analyses/a_1") {
      return json(response, {
        analysis_id: "a_1", session_id: "s_1", match_id: MATCH_ID, state: "processing",
        engine_version: "0.3.0", last_seq: 4, failure_reason: null,
        created_at: "2026-10-11T00:00:00.000Z", updated_at: "2026-10-11T00:00:01.000Z", finalized_at: null,
      });
    }
    if (path.startsWith("/v1/analyses/a_1/events")) {
      return json(response, {
        events: [
          ev(1, "analysis.queued"),
          ev(2, "analysis.started", { windows_expected: 3 }),
          ev(3, "window.completed", { window_seq: 0, artifact_id: "art_w0" }),
          ev(4, "window.completed", { window_seq: 1, artifact_id: "art_w1" }),
        ],
        next_after_seq: 4, has_more: false, state: "processing",
      });
    }
    if (path.startsWith("/v1/artifacts/")) {
      return json(response, { url: `${base}/r2/${path.split("/").pop()}`, expires_at: "2026-10-11T01:00:00.000Z" });
    }
    if (path === "/r2/art_w0") return json(response, { events: [{ type: "hit" }, { type: "hit" }, { type: "bounce" }] });
    if (path === "/r2/art_w1") return json(response, { events: [{ type: "hit" }] });
    response.statusCode = 404;
    json(response, { error: { code: "not_found", message: "nope" } });
  };
  const h = await startHarness(progressStub, { ELEVATE_JWT: "jwt_test" });
  base = h.base;
  try {
    const res = await h.call("get_analysis_progress", { analysis_id: "a_1", include_hits: false });
    assert.equal(res.isError, false, res.text);
    const body = JSON.parse(res.text);
    assert.equal(body.windows_expected, 3);
    assert.equal(body.windows_done, 2);
    assert.equal(body.hit_candidates_so_far, null);
    assert.equal(body.state, "processing");

    const withHits = JSON.parse((await h.call("get_analysis_progress", { analysis_id: "a_1" })).text);
    assert.equal(withHits.hit_candidates_so_far, 3);
    assert.deepEqual(h.stray, []);
  } finally {
    await h.close();
  }
});

test("get_friend_feed forwards its cursor and validates feed.page.v1", async () => {
  const feed = {
    items: [{
      post_id: "po_feed",
      match_id: "m_feed",
      owner: {
        player_id: "11111111-1111-4111-8111-111111111111",
        display_name: "Friend",
      },
      created_at: "2026-10-11T10:00:00Z",
      caption: null,
      summary: {
        title: null,
        duration_ms: null,
        rally_count: null,
        hit_count: null,
        landings_in: null,
        landings_out: null,
        longest_rally_ms: null,
        top_coach_points: [],
        landing_points_m: [],
        owner_display_name: "Friend",
      },
    }],
    next_cursor: null,
  };
  const feedStub: Stub = (path, _request, response) => {
    if (path === "/v1/openapi.json") {
      return json(response, { paths: { "/v1/feed": { get: {} } } });
    }
    if (path === "/v1/feed?cursor=next") return json(response, feed);
    response.statusCode = 404;
    json(response, { error: { code: "not_found", message: "nope" } });
  };
  // Both credentials set: the feed is caller-scoped, so the tool must send the JWT, never the key.
  const h = await startHarness(feedStub, {
    ELEVATE_API_KEY: "ek_feed",
    ELEVATE_JWT: "jwt_feed",
  });
  try {
    const result = await h.call("get_friend_feed", { cursor: "next" });
    assert.equal(result.isError, false, result.text);
    assert.deepEqual(JSON.parse(result.text), feed);
    assert.deepEqual(h.requests.map(({ path }) => path), [
      "/v1/openapi.json",
      "/v1/feed?cursor=next",
    ]);
    assert.equal(h.requests[1].headers.authorization, "Bearer jwt_feed");
    assert.equal(h.requests[1].headers["x-api-key"], undefined);
    assert.deepEqual(h.stray, []);
  } finally {
    await h.close();
  }
  // Key only: a clear tool error, and no feed request is sent.
  const k = await startHarness(feedStub, { ELEVATE_API_KEY: "ek_feed" });
  try {
    const result = await k.call("get_friend_feed", {});
    assert.equal(result.isError, true);
    assert.match(result.text, /needs ELEVATE_JWT/);
    assert.equal(k.requests.some(({ path }) => path.startsWith("/v1/feed")), false);
  } finally {
    await k.close();
  }
});
