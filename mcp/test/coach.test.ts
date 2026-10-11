import assert from "node:assert/strict";
import { test } from "node:test";
import { createRequire } from "node:module";
import {
  countHits,
  deepLink,
  filterMoments,
  landingZones,
  rallyDetail,
  rewatchPicks,
  windowProgress,
  eventSummary,
  type Match,
} from "../src/coach.ts";
import { matchFixture, MATCH_ID } from "./fixture.ts";

const contracts = createRequire(import.meta.url)(
  "../../supabase/functions/api/lib/contracts_vendor/index.ts",
);
const match = matchFixture() as unknown as Match;

test("the fixture itself is a valid match.v1", () => {
  assert.equal(contracts.matchV1Schema.safeParse(matchFixture()).success, true);
});

test("deepLink uses the elevate:// scheme and clamps t_ms", () => {
  assert.equal(deepLink("m_1", "r_2", 1234.6), "elevate://m/m_1/r/r_2?t_ms=1235");
  assert.equal(deepLink("m_1", "r_2", -5), "elevate://m/m_1/r/r_2?t_ms=0");
});

test("rewatch picks rank by shot count and carry a deep link and reasons", () => {
  const out = rewatchPicks(match, { limit: 2 });
  assert.equal(out.focal_player, "p_me");
  assert.deepEqual(out.picks.map((p) => p.rally_id), ["r_b", "r_c"]);
  assert.equal(out.picks[0].deep_link, `elevate://m/${MATCH_ID}/r/r_b?t_ms=10000`);
  assert.match(out.picks[0].reasons[0], /5 shots over 20 s/);
  assert.equal(out.rhythm.median_shots, 3);
  assert.equal(out.insights[0].deep_links[0], `elevate://m/${MATCH_ID}/r/r_b?t_ms=11500`);
});

test("lost_rallies keeps only rallies the focal player did not win", () => {
  const out = rewatchPicks(match, { focus: "lost_rallies", limit: 5 });
  assert.deepEqual(out.picks.map((p) => p.rally_id), ["r_c", "r_a"]);
  assert.ok(out.picks.every((p) => p.winner !== "p_me"));
  assert.match(out.picks[0].reasons.join(" "), /you lost it/);
});

test("lost_rallies is empty when no player is confirmed as you", () => {
  const anon = { ...match, players: match.players.map((p) => ({ ...p, identity: "opponent" })) };
  const out = rewatchPicks(anon, { focus: "lost_rallies" });
  assert.equal(out.focal_player_known, false);
  assert.deepEqual(out.picks, []);
});

test("landing zones are counts with denominators and a state breakdown", () => {
  const [me] = landingZones(match, "p_me");
  assert.equal(me.shots_total, 6);
  assert.equal(me.zones.reduce((n, z) => n + z.shots, 0), 6);
  assert.ok(me.zones.every((z) => z.share.den === 6));
  assert.equal(me.zones[0].zone, "F1");
  assert.deepEqual(Object.keys(me.landing_state_counts).sort(), ["inferred", "observed"]);
});

test("moment filters: tags, player, outcome, limit", () => {
  assert.equal(filterMoments(match, {}).items.length, 3);
  assert.deepEqual(filterMoments(match, { tags: ["LONG_RALLY"] }).items.map((m) => m.moment_id), ["mo_2"]);
  assert.deepEqual(filterMoments(match, { player: "p_opp" }).items.map((m) => m.moment_id), ["mo_2"]);
  // mo_3 is p_me's shot in a rally the opponent won
  assert.deepEqual(filterMoments(match, { outcome: "lost", player: "p_me" }).items.map((m) => m.moment_id), ["mo_1", "mo_3"]);
  assert.equal(filterMoments(match, { limit: 1 }).items.length, 1);
  const first = filterMoments(match, {}).items[0];
  assert.equal(first.deep_link, `elevate://m/${MATCH_ID}/r/r_a?t_ms=1000`);
});

test("rally detail orders shots and returns null for an unknown rally", () => {
  const detail = rallyDetail(match, "r_b");
  assert.deepEqual(detail?.shots.map((s) => s.index), [0, 1, 2, 3, 4]);
  assert.equal(rallyDetail(match, "r_zzz"), null);
});

test("window progress reads windows_expected from window.completed when analysis.started has none", () => {
  // Shape seen on the deployed API for run a_1549d3f005ce4e098e205526e86fcaa5.
  const p = windowProgress([
    { type: "analysis.started", seq: 2 },
    { type: "window.completed", seq: 3, window_seq: 0, windows_expected: 11, artifact_id: "art_1" },
    { type: "window.completed", seq: 4, window_seq: 1, windows_expected: 11, artifact_id: "art_2" },
  ]);
  assert.equal(p.windows_expected, 11);
  assert.equal(p.windows_done, 2);
});

test("window progress reads windows_expected, done and failed from the events", () => {
  const p = windowProgress([
    { type: "analysis.queued", seq: 1 },
    { type: "analysis.started", seq: 2, windows_expected: 4 },
    { type: "window.completed", seq: 3, window_seq: 0, artifact_id: "art_1" },
    { type: "window.completed", seq: 4, window_seq: 0, artifact_id: "art_1" },
    { type: "window.failed", seq: 5, window_seq: 1, reason: "x" },
    { type: "window.completed", seq: 6, window_seq: 2, artifact_id: "art_3" },
  ]);
  assert.equal(p.windows_expected, 4);
  assert.equal(p.windows_done, 2);
  assert.equal(p.windows_failed, 1);
  assert.equal(p.last_seq, 6);
  assert.equal(windowProgress([{ type: "analysis.failed", seq: 1, reason: "boom" }]).failure_reason, "boom");
});

test("countHits counts only hit events and tolerates bad bodies", () => {
  assert.equal(countHits({ events: [{ type: "hit" }, { type: "bounce" }, { type: "hit" }] }), 2);
  assert.equal(countHits(null), 0);
  assert.equal(countHits({ events: "nope" }), 0);
});

test("eventSummary counts per type and state over the whole record and keeps candidates as candidates", () => {
  // Shape and mix seen on the deployed API for match m_e4e43ddfcae54f6aa09808b535d0fb95.
  const events = [
    { event_id: "e_3", type: "landing", t_ms: 58300, t_sigma_ms: 10, state: "candidate", cues: ["trajectory"] },
    { event_id: "e_1", type: "hit", t_ms: 780, t_sigma_ms: 20, state: "candidate", cues: ["trajectory"] },
    { event_id: "e_2", type: "hit", t_ms: 2100, state: "candidate" },
  ];
  const all = eventSummary(events);
  assert.equal(all.total_events, 3);
  assert.deepEqual(all.counts, [
    { type: "hit", state: "candidate", count: 2 },
    { type: "landing", state: "candidate", count: 1 },
  ]);
  assert.deepEqual(all.items.map((e) => e.event_id), ["e_1", "e_2", "e_3"]);
  assert.equal(all.items[1].t_sigma_ms, null);
  const hits = eventSummary(events, { type: "hit", limit: 1 });
  assert.equal(hits.matching, 2);
  assert.equal(hits.items.length, 1);
  assert.equal(hits.total_events, 3);
  assert.equal(eventSummary(events, { state: "observed" }).matching, 0);
  assert.equal(eventSummary(events, { limit: 0 }).items.length, 0);
});
