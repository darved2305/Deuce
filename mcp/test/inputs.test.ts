import assert from "node:assert/strict";
import { test } from "node:test";
import {
  compareMomentInput,
  findMomentsInput,
  getAnalysisProgressInput,
  getFriendFeedInput,
  getLandingZonesInput,
  getRallyInput,
  rewatchPicksInput,
} from "../src/index.ts";

const ok = (schema: { safeParse(v: unknown): { success: boolean } }, v: unknown) =>
  assert.equal(schema.safeParse(v).success, true, JSON.stringify(v));
const bad = (schema: { safeParse(v: unknown): { success: boolean } }, v: unknown) =>
  assert.equal(schema.safeParse(v).success, false, JSON.stringify(v));

test("rewatch_picks defaults and bounds", () => {
  const parsed = rewatchPicksInput.parse({ match_id: "m_1" });
  assert.equal(parsed.focus, "longest_rallies");
  assert.equal(parsed.limit, 3);
  ok(rewatchPicksInput, { match_id: "m_1", focus: "lost_rallies", limit: 10, player: "p_me" });
  bad(rewatchPicksInput, { match_id: "m_1", limit: 0 });
  bad(rewatchPicksInput, { match_id: "m_1", limit: 11 });
  bad(rewatchPicksInput, { match_id: "m_1", focus: "sql" });
  bad(rewatchPicksInput, { match_id: "not-a-match" });
  bad(rewatchPicksInput, { match_id: "m_1", player: "m_1" });
  bad(rewatchPicksInput, {});
});

test("find_moments typed filters reject free text and out-of-range limits", () => {
  ok(findMomentsInput, {});
  ok(findMomentsInput, { match_id: "m_1", tags: ["landing"], player: "p_1", outcome: "won", limit: 20 });
  bad(findMomentsInput, { outcome: "drawn" });
  bad(findMomentsInput, { limit: 21 });
  bad(findMomentsInput, { limit: 0 });
  bad(findMomentsInput, { limit: 1.5 });
  bad(findMomentsInput, { tags: [""] });
  bad(findMomentsInput, { tags: Array.from({ length: 11 }, () => "x") });
});

test("compare_moment accepts the three scopes and at most 5 candidates", () => {
  const parsed = compareMomentInput.parse({ match_id: "m_1", shot_id: "sh_1" });
  assert.equal(parsed.scope, "own");
  assert.equal(parsed.limit, 5);
  ok(compareMomentInput, { match_id: "m_1", shot_id: "sh_1", scope: "reference", limit: 1 });
  bad(compareMomentInput, { match_id: "m_1", shot_id: "sh_1", scope: "public" });
  bad(compareMomentInput, { match_id: "m_1", shot_id: "sh_1", limit: 6 });
  bad(compareMomentInput, { match_id: "m_1", shot_id: "r_1" });
});

test("ids are checked by prefix on every tool", () => {
  ok(getRallyInput, { match_id: "m_1", rally_id: "r_1" });
  bad(getRallyInput, { match_id: "m_1", rally_id: "sh_1" });
  ok(getLandingZonesInput, { match_id: "m_1" });
  bad(getLandingZonesInput, { match_id: "../etc/passwd" });
  ok(getAnalysisProgressInput, { analysis_id: "a_1" });
  bad(getAnalysisProgressInput, { analysis_id: "m_1" });
  assert.equal(getAnalysisProgressInput.parse({ analysis_id: "a_1" }).include_hits, true);
});

test("get_friend_feed accepts an optional non-empty cursor", () => {
  ok(getFriendFeedInput, {});
  ok(getFriendFeedInput, { cursor: "opaque" });
  bad(getFriendFeedInput, { cursor: "" });
});
