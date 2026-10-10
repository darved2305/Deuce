// GENERATED from packages/contracts - do not edit.
// Regenerate with supabase/scripts/sync-contracts.ts
import * as z from "zod";

function idSchema(prefix: string) {
  return z.string().regex(new RegExp(`^${prefix}[A-Za-z0-9_-]+$`));
}

export const matchIdSchema = idSchema("m_");
export const sessionIdSchema = idSchema("s_");
export const analysisIdSchema = idSchema("a_");
export const windowIdSchema = idSchema("w_");
export const rallyIdSchema = idSchema("r_");
export const shotIdSchema = idSchema("sh_");
export const eventIdSchema = idSchema("e_");
export const playerIdSchema = idSchema("p_");
export const trackIdSchema = idSchema("t_");
export const momentIdSchema = idSchema("mo_");
export const clipIdSchema = idSchema("c_");
export const rallyClipIdSchema = idSchema("c_r_");
export const shotClipIdSchema = idSchema("c_sh_");
export const reviewIdSchema = idSchema("rv_");
export const confirmedPointIdSchema = idSchema("cp_");
export const correctionIdSchema = idSchema("cr_");
export const artifactIdSchema = idSchema("art_");
export const developerKeyIdSchema = idSchema("k_");
export const calibrationIdSchema = idSchema("cal_");
export const insightIdSchema = idSchema("in_");

export const idSchemas = {
  m_: matchIdSchema,
  s_: sessionIdSchema,
  a_: analysisIdSchema,
  w_: windowIdSchema,
  r_: rallyIdSchema,
  sh_: shotIdSchema,
  e_: eventIdSchema,
  p_: playerIdSchema,
  t_: trackIdSchema,
  mo_: momentIdSchema,
  c_: clipIdSchema,
  c_r_: rallyClipIdSchema,
  c_sh_: shotClipIdSchema,
  rv_: reviewIdSchema,
  cp_: confirmedPointIdSchema,
  cr_: correctionIdSchema,
  art_: artifactIdSchema,
  k_: developerKeyIdSchema,
  cal_: calibrationIdSchema,
  in_: insightIdSchema,
} as const;

export const provenanceStateSchema = z.enum([
  "observed",
  "candidate",
  "inferred",
  "user",
]);

export const capabilityTierSchema = z.enum([
  "core",
  "assisted",
  "experimental",
  "unsupported",
]);

export const capabilityStatusSchema = z.enum([
  "unknown",
  "pass",
  "unmet",
  "could-not-run",
]);

export const sportSchema = z.enum([
  "badminton",
  "table_tennis",
  "tennis",
  "free_play",
  "squash",
  "padel",
  "pickleball",
]);

export const formatSchema = z.enum(["singles", "doubles"]);

export const surfacePlaneSchema = z.enum(["court", "tabletop", "floor"]);

export const zoneIdSchema = z.enum([
  "F1",
  "F2",
  "F3",
  "M1",
  "M2",
  "M3",
  "B1",
  "B2",
  "B3",
  "S",
  "L",
]);

export const canonicalModelIdSchema = z.enum([
  "tracknet_v3",
  "wasb_hrnet_badminton",
  "wasb_hrnet_tennis",
  "dfine_s_coco",
  "tapir_causal",
]);

// §0: times are measured from the first presented frame, so they are never negative.
export const millisecondsSchema = z.number().int().nonnegative();
export const microsecondsSchema = z.number().int().nonnegative();

function orderedRangeSchema(unit: z.ZodNumber) {
  return z.tuple([unit, unit]).refine(([start, end]) => start <= end, {
    message: "range start must be less than or equal to range end",
  }).meta({
    description:
      "Section 0 time range [start, end]. Producers enforce start <= end with Zod; JSON Schema preserves the tuple shape but cannot compare its items.",
  });
}

// §0 ranges are fixed tuples; Zod adds order validation that JSON Schema cannot express.
export const rangeMsSchema = orderedRangeSchema(millisecondsSchema);
export const rangeUsSchema = orderedRangeSchema(microsecondsSchema);

export const sha256Schema = z.string().regex(/^[A-Fa-f0-9]{64}$/);
export const isoTimestampSchema = z.iso.datetime({ offset: true });

export type ProvenanceState = z.infer<typeof provenanceStateSchema>;
export type CapabilityTier = z.infer<typeof capabilityTierSchema>;
export type CapabilityStatus = z.infer<typeof capabilityStatusSchema>;
export type Sport = z.infer<typeof sportSchema>;
export type Format = z.infer<typeof formatSchema>;
export type SurfacePlane = z.infer<typeof surfacePlaneSchema>;
export type ZoneId = z.infer<typeof zoneIdSchema>;
export type CanonicalModelId = z.infer<typeof canonicalModelIdSchema>;
