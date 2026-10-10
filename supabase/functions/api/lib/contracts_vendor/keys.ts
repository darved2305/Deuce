// GENERATED from packages/contracts - do not edit.
// Regenerate with supabase/scripts/sync-contracts.ts
import * as z from "zod";
import {
  analysisIdSchema,
  clipIdSchema,
  matchIdSchema,
  sessionIdSchema,
} from "./common.ts";

const sessionIdPattern = "s_[A-Za-z0-9_-]+";
const analysisIdPattern = "a_[A-Za-z0-9_-]+";
const matchIdPattern = "m_[A-Za-z0-9_-]+";
const clipIdPattern = "c_[A-Za-z0-9_-]+";
const segmentPattern = "[^/]+";

export const origKeyRegex = new RegExp(`^orig/${sessionIdPattern}/source\\.mp4$`);
// D-261011-HW3 chunked upload: one object per ~6 s segment, deleted after 1 day (Cloudflare rule).
export const segmentKeyRegex = new RegExp(`^seg/${sessionIdPattern}/[0-9]+\\.mp4$`);
export const analysisWindowKeyRegex = new RegExp(
  `^analysis/${analysisIdPattern}/${segmentPattern}/windows/[0-9]+\\.json$`,
);
export const analysisTracksKeyRegex = new RegExp(
  `^analysis/${analysisIdPattern}/${segmentPattern}/tracks/${segmentPattern}\\.json$`,
);
export const clipMp4KeyRegex = new RegExp(
  `^clips/${matchIdPattern}/${clipIdPattern}\\.mp4$`,
);
export const clipJpgKeyRegex = new RegExp(
  `^clips/${matchIdPattern}/${clipIdPattern}\\.jpg$`,
);

export const origR2KeySchema = z.string().regex(origKeyRegex);
export const segmentR2KeySchema = z.string().regex(segmentKeyRegex);
export const analysisWindowR2KeySchema = z.string().regex(analysisWindowKeyRegex);
export const analysisTracksR2KeySchema = z.string().regex(analysisTracksKeyRegex);
export const clipMp4R2KeySchema = z.string().regex(clipMp4KeyRegex);
export const clipJpgR2KeySchema = z.string().regex(clipJpgKeyRegex);
export const r2ObjectKeySchema = z.union([
  origR2KeySchema,
  segmentR2KeySchema,
  analysisWindowR2KeySchema,
  analysisTracksR2KeySchema,
  clipMp4R2KeySchema,
  clipJpgR2KeySchema,
]);

export function buildOrigKey(sessionId: string): string {
  sessionIdSchema.parse(sessionId);
  return `orig/${sessionId}/source.mp4`;
}

export function buildSegmentKey(sessionId: string, seq: number): string {
  sessionIdSchema.parse(sessionId);
  if (!Number.isInteger(seq) || seq < 0) throw new Error("seq must be a non-negative integer");
  return `seg/${sessionId}/${seq}.mp4`;
}

export function buildAnalysisWindowKey(
  analysisId: string,
  engineVersion: string,
  windowSeq: number,
): string {
  analysisIdSchema.parse(analysisId);
  if (!/^[A-Za-z0-9._+-]+$/.test(engineVersion)) {
    throw new Error("engineVersion must be a non-empty R2 path segment");
  }
  if (!Number.isInteger(windowSeq) || windowSeq < 0) {
    throw new Error("windowSeq must be a non-negative integer");
  }
  return `analysis/${analysisId}/${engineVersion}/windows/${windowSeq}.json`;
}

export function buildAnalysisTracksKey(
  analysisId: string,
  engineVersion: string,
  kind: string,
): string {
  analysisIdSchema.parse(analysisId);
  if (!/^[A-Za-z0-9._+-]+$/.test(engineVersion)) {
    throw new Error("engineVersion must be a non-empty R2 path segment");
  }
  if (!/^[A-Za-z0-9._+-]+$/.test(kind)) {
    throw new Error("kind must be a non-empty R2 path segment");
  }
  return `analysis/${analysisId}/${engineVersion}/tracks/${kind}.json`;
}

export function buildClipMp4Key(matchId: string, clipId: string): string {
  matchIdSchema.parse(matchId);
  clipIdSchema.parse(clipId);
  return `clips/${matchId}/${clipId}.mp4`;
}

export function buildClipJpgKey(matchId: string, clipId: string): string {
  matchIdSchema.parse(matchId);
  clipIdSchema.parse(clipId);
  return `clips/${matchId}/${clipId}.jpg`;
}
