import assert from "node:assert/strict";
import test from "node:test";
import {
  analysisWindowKeyRegex,
  analysisTracksKeyRegex,
  buildAnalysisTracksKey,
  buildAnalysisWindowKey,
  buildClipJpgKey,
  buildClipMp4Key,
  buildOrigKey,
  clipJpgKeyRegex,
  clipMp4KeyRegex,
  origKeyRegex,
} from "../src/keys.ts";
import { schemaRegistry, type ContractName } from "../src/registry.ts";
import {
  cloneExample,
  describeZodFailure,
  getDocExamples,
  getGeneratedValidator,
} from "./doc-examples.ts";

function getExample(name: ContractName): Record<string, any> {
  const example = getDocExamples().find((item) => item.schemaName === name);
  assert.ok(example, `missing contracts.md example for ${name}`);
  assert.ok(example.value && typeof example.value === "object");
  return example.value as Record<string, any>;
}

test("contracts.md JSON examples match Zod and generated Draft 2020-12 schemas", () => {
  const examples = getDocExamples();
  assert.ok(examples.length > 0, "contracts.md must include fenced JSON examples");

  for (const example of examples) {
    const entry = schemaRegistry[example.schemaName];
    const zodResult = entry.schema.safeParse(example.value);
    assert.ok(
      zodResult.success,
      `contracts.md JSON block ${example.block} in §${example.section} fails Zod: ${describeZodFailure(zodResult)}`,
    );
    assert.equal(
      getGeneratedValidator(example.schemaName)(example.value),
      true,
      `contracts.md JSON block ${example.block} in §${example.section} fails generated JSON Schema`,
    );
  }
});

test("Zod and generated JSON Schema reject contract mutations", () => {
  const match = getExample("match.v1");
  const windowResult = getExample("window_result.v1");
  const cases: Array<{ name: string; schema: ContractName; value: Record<string, any> }> = [];

  const wrongEnum = cloneExample(match);
  wrongEnum.sport = "badmintonx";
  cases.push({ name: "wrong enum value", schema: "match.v1", value: wrongEnum });

  const missingRequired = cloneExample(match);
  delete missingRequired.match_id;
  cases.push({ name: "missing required field", schema: "match.v1", value: missingRequired });

  const unknownField = cloneExample(match);
  unknownField.unexpected = true;
  cases.push({ name: "unknown field", schema: "match.v1", value: unknownField });

  const wrongIdPrefix = cloneExample(match);
  wrongIdPrefix.match_id = "a_doc";
  cases.push({ name: "wrong ID prefix", schema: "match.v1", value: wrongIdPrefix });

  const shortBallRow = cloneExample(windowResult);
  shortBallRow.ball[0] = shortBallRow.ball[0].slice(0, 4);
  cases.push({ name: "four item ball row", schema: "window_result.v1", value: shortBallRow });

  const objectRange = cloneExample(windowResult);
  objectRange.decode_window_us = { start: 12000000, end: 18750000 };
  cases.push({ name: "range object", schema: "window_result.v1", value: objectRange });

  for (const invalid of cases) {
    const entry = schemaRegistry[invalid.schema];
    assert.equal(
      entry.schema.safeParse(invalid.value).success,
      false,
      `Zod accepted ${invalid.name}`,
    );
    assert.equal(
      getGeneratedValidator(invalid.schema)(invalid.value),
      false,
      `generated JSON Schema accepted ${invalid.name}`,
    );
  }
});

test("Zod enforces ordered ranges while JSON Schema preserves their tuple shape", () => {
  const windowResult = getExample("window_result.v1");
  const reverseRange = cloneExample(windowResult);
  reverseRange.decode_window_us = [18750000, 12000000];

  assert.equal(
    schemaRegistry["window_result.v1"].schema.safeParse(reverseRange).success,
    false,
  );
  assert.equal(getGeneratedValidator("window_result.v1")(reverseRange), true);
});

test("each deterministic R2 key builder returns a key matching its regex", () => {
  const match = getExample("match.v1");
  const windowResult = getExample("window_result.v1");
  const sessionId = match.session_ids[0] as string;
  const analysisId = match.analysis.analysis_id as string;
  const matchId = match.match_id as string;
  const clipId = match.clips[0].clip_id as string;
  const engineVersion = match.analysis.engine_version as string;
  const windowSeq = windowResult.window_seq as number;

  const keys = [
    [buildOrigKey(sessionId), origKeyRegex],
    [buildAnalysisWindowKey(analysisId, engineVersion, windowSeq), analysisWindowKeyRegex],
    [buildAnalysisTracksKey(analysisId, engineVersion, "ball_track"), analysisTracksKeyRegex],
    [buildClipMp4Key(matchId, clipId), clipMp4KeyRegex],
    [buildClipJpgKey(matchId, clipId), clipJpgKeyRegex],
  ] as const;

  for (const [key, regex] of keys) assert.match(key, regex);
});
