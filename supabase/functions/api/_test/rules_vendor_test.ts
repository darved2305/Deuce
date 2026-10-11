import assert from "node:assert/strict";
import vectorsJson from "../../../../packages/rules/vectors/bwf_3x21.json" with {
  type: "json",
};
import {
  applyPoint,
  MatchOverError,
  type RulesState,
  type TeamIndex,
  undo,
} from "../lib/rules_vendor/index.ts";

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

const vectors = vectorsJson as unknown as GoldenVector[];

for (const [index, vector] of vectors.entries()) {
  Deno.test(`vendored BWF vector ${index + 1}: ${vector.category}`, () => {
    assert.ok(vector.law.trim(), `vector ${index + 1} has no law citation`);
    assert.ok(vector.note.trim(), `vector ${index + 1} has no note`);

    if (vector.operation === "undo") {
      const history = vector.history;
      assert.ok(history?.length, `vector ${index + 1} needs an undo history`);
      assert.deepEqual(undo(vector.initial, history), vector.expected);
      return;
    }

    if (vector.winningTeam === null) {
      throw new Error(`vector ${index + 1} needs a winning team`);
    }
    if ("throws" in vector.expected) {
      assert.equal(vector.expected.throws, "MatchOverError");
      assert.throws(
        () => applyPoint(vector.initial, vector.winningTeam!),
        (error: unknown) =>
          error instanceof MatchOverError && error.code === "match_over",
        `vector ${index + 1}: ${vector.note}`,
      );
      return;
    }
    assert.deepEqual(
      applyPoint(vector.initial, vector.winningTeam),
      vector.expected,
      `vector ${index + 1}: ${vector.note}`,
    );
  });
}

Deno.test("vendored BWF vector set has the expected 45 cases", () => {
  assert.equal(vectors.length, 45);
});
