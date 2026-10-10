import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { rulesStateSchema } from "@elevate/contracts";
import {
  applyPoint,
  courtSide,
  initialState,
  isGameOver,
  isMatchOver,
  MatchOverError,
  server,
  undo,
  type RulesState,
  type TeamIndex,
} from "../src/index.ts";

type GoldenVector = {
  category: string;
  operation: "applyPoint" | "undo";
  initial: RulesState;
  winningTeam: TeamIndex | null;
  expected: RulesState | { throws: string };
  history?: RulesState[];
  law: string;
  note: string;
};

const vectors = JSON.parse(
  readFileSync(new URL("../vectors/bwf_3x21.json", import.meta.url), "utf8"),
) as GoldenVector[];

test("BWF golden vectors match the state transitions and cite their Laws", () => {
  assert.ok(vectors.length >= 40, `expected at least 40 vectors, found ${vectors.length}`);
  for (const [index, vector] of vectors.entries()) {
    assert.ok(vector.law.trim(), `vector ${index} has no law citation`);
    assert.ok(vector.note.trim(), `vector ${index} has no note`);
    assertValidState(vector.initial, `vector ${index} initial`);

    if (vector.operation === "undo") {
      assert.ok(vector.history?.length, `vector ${index} needs an undo history`);
      for (const [historyIndex, historyState] of vector.history.entries()) {
        assertValidState(historyState, `vector ${index} history ${historyIndex}`);
      }
      const actual = undo(vector.initial, vector.history);
      assert.deepEqual(actual, vector.expected, `vector ${index}: ${vector.note}`);
      assertValidState(actual, `vector ${index} undo output`);
      continue;
    }

    assert.notEqual(vector.winningTeam, null, `vector ${index} needs a winning team`);
    if ("throws" in vector.expected) {
      assert.equal(vector.expected.throws, "MatchOverError");
      assert.throws(
        () => applyPoint(vector.initial, vector.winningTeam!),
        (error: unknown) => error instanceof MatchOverError && error.code === "match_over",
        `vector ${index}: ${vector.note}`,
      );
      continue;
    }

    const initialTotal = cumulativePoints(vector.initial);
    const actual = applyPoint(vector.initial, vector.winningTeam!);
    assert.deepEqual(actual, vector.expected, `vector ${index}: ${vector.note}`);
    assert.equal(cumulativePoints(actual), initialTotal + 1, `vector ${index}: a rally adds one point`);
    assert.equal(isGameOver(actual), actual.status !== "in_progress");
    assert.equal(isMatchOver(actual), actual.status === "match_over");
    assert.equal(server(actual), actual.server);
    assert.equal(courtSide(actual), actual.service_court);
    assertValidState(actual, `vector ${index} output`);
    assertServerAndCourt(actual, `vector ${index} output`);
    if (actual.status === "game_over" || actual.status === "match_over") {
      const lastGame = actual.games[actual.games.length - 1];
      const lastGameWinner: TeamIndex = lastGame[0] > lastGame[1] ? 0 : 1;
      assert.equal(actual.teams.findIndex((team) => team.includes(actual.server)), lastGameWinner);
      assert.equal(actual.service_court, "right", "next game's first server is staged on the right");
      assert.deepEqual(actual.current, [0, 0], "no game is active between completed games");
    }
  }
});

test("initialState creates contract-valid singles and doubles openings", () => {
  const singles = initialState({
    ruleset: { id: "bwf_3x21", version: "2025-04" },
    format: "singles",
    teams: [["p_a"], ["p_b"]],
    firstServer: "p_b",
    ends: { team0: "near" },
  });
  assert.equal(singles.server, "p_b");
  assert.equal(singles.service_court, "right");
  assert.equal("doubles_right_player" in singles, false);
  assertValidState(singles, "singles opening");

  const doubles = initialState({
    ruleset: { id: "bwf_3x21", version: "2025-04" },
    format: "doubles",
    teams: [["p_a", "p_b"], ["p_c", "p_d"]],
    firstServer: "p_c",
    ends: { team0: "far" },
  });
  assert.equal(doubles.server, "p_c");
  assert.equal(doubles.doubles_right_player?.[1], "p_c");
  assert.equal(doubles.doubles_right_player?.[0], "p_b");
  assertValidState(doubles, "doubles opening");
});

test("score, single server, and court-parity properties hold through a full match", () => {
  for (const format of ["singles", "doubles"] as const) {
    let state = initialState({
      ruleset: { id: "bwf_3x21", version: "2025-04" },
      format,
      teams: format === "singles" ? [["p_a"], ["p_c"]] : [["p_a", "p_b"], ["p_c", "p_d"]],
      firstServer: "p_a",
      ends: { team0: "near" },
    });
    assertServerAndCourt(state, `${format} opening`);

    let rallies = 0;
    while (!isMatchOver(state) && rallies < 300) {
      const before = cumulativePoints(state);
      const winner: TeamIndex = rallies % 3 === 0 ? 0 : 1;
      state = applyPoint(state, winner);
      rallies += 1;
      assert.equal(cumulativePoints(state), before + 1, `${format}: cumulative score advanced by one`);
      assertValidState(state, `${format} rally ${rallies}`);
      assertServerAndCourt(state, `${format} rally ${rallies}`);
    }
    assert.ok(rallies < 300, `${format} simulation did not finish`);
    assert.equal(isMatchOver(state), true);
  }
});

test("undo returns an exact defensive copy of the last history state", () => {
  const previous = initialState({
    ruleset: { id: "bwf_3x21", version: "2025-04" },
    format: "doubles",
    teams: [["p_a", "p_b"], ["p_c", "p_d"]],
    firstServer: "p_a",
    ends: { team0: "near" },
  });
  const changed = applyPoint(previous, 0);
  const restored = undo(changed, [previous]);
  assert.deepEqual(restored, previous);
  assert.notEqual(restored, previous);
  assert.notEqual(restored.teams[0], previous.teams[0]);
});

function assertValidState(state: RulesState, label: string): void {
  const result = rulesStateSchema.safeParse(state);
  assert.equal(result.success, true, `${label} failed rules.state.v1: ${
    result.success ? "" : result.error.message
  }`);
}

function assertServerAndCourt(state: RulesState, label: string): void {
  const teamIndex = state.teams.findIndex((team) => team.includes(state.server));
  assert.ok(teamIndex === 0 || teamIndex === 1, `${label}: server must be on one team`);
  assert.equal(
    state.service_court,
    state.current[teamIndex] % 2 === 0 ? "right" : "left",
    `${label}: service court must match server score parity`,
  );
  assert.equal(
    state.teams.flat().filter((player) => player === state.server).length,
    1,
    `${label}: exactly one player is server`,
  );

  if (state.format === "doubles") {
    const team = teamIndex as TeamIndex;
    const rightPlayer = state.doubles_right_player?.[team];
    assert.ok(rightPlayer, `${label}: each doubles side has a right-court player`);
    assert.equal(
      state.service_court === "right" ? rightPlayer === state.server : rightPlayer !== state.server,
      true,
      `${label}: doubles server occupies the court matching parity`,
    );
  }
}

function cumulativePoints(state: RulesState): number {
  return state.games.reduce((total, score) => total + score[0] + score[1], 0) +
    state.current[0] + state.current[1];
}
