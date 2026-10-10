// GENERATED from packages/contracts - do not edit.
// Regenerate with supabase/scripts/sync-contracts.ts
import * as z from "zod";
import {
  canonicalModelIdSchema,
  builtSportSchema,
  formatSchema,
  matchFormatSchema,
  playerIdSchema,
  sportSchema,
  zoneIdSchema,
} from "./common.ts";

const courtTemplateSchema = z.strictObject({
  length_m: z.number(),
  width_m: z.number(),
  lines: z.array(z.unknown()),
});

export const sportPackSchema = z.strictObject({
  sport: sportSchema,
  formats: z.array(formatSchema),
  court_templates: z.partialRecord(formatSchema, courtTemplateSchema),
  ruleset_ids: z.array(z.string()),
  ball_profile: z.strictObject({
    diameter_mm: z.number(),
    behaviour: z.enum(["ball", "shuttle"]),
  }),
  event_vocabulary: z.array(z.string()),
  zone_grid: z.unknown(),
  tracker_ids: z.array(canonicalModelIdSchema),
});

export const rulesStateSchema = z.strictObject({
  ruleset_id: z.string(),
  ruleset_version: z.string(),
  sport: builtSportSchema,
  format: matchFormatSchema,
  teams: z.tuple([z.array(playerIdSchema), z.array(playerIdSchema)]),
  games: z.array(z.tuple([z.number().int().nonnegative(), z.number().int().nonnegative()])),
  current: z.tuple([z.number().int().nonnegative(), z.number().int().nonnegative()]),
  server: playerIdSchema,
  service_court: z.enum(["right", "left"]),
  ends: z.strictObject({
    team0: z.enum(["near", "far"]),
  }),
  status: z.enum(["in_progress", "game_over", "match_over"]),
});

export const sportZoneSchema = zoneIdSchema;

export type SportPack = z.infer<typeof sportPackSchema>;
export type RulesState = z.infer<typeof rulesStateSchema>;
export type SportZone = z.infer<typeof sportZoneSchema>;
