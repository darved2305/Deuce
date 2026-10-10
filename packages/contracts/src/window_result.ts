import * as z from "zod";
import {
  analysisIdSchema,
  canonicalModelIdSchema,
  eventIdSchema,
  millisecondsSchema,
  provenanceStateSchema,
  rangeUsSchema,
  trackIdSchema,
} from "./common.ts";

export const ballRowSchema = z.tuple([
  millisecondsSchema,
  z.number(),
  z.number(),
  z.number().nullable(),
  provenanceStateSchema,
]);

export const playerRowSchema = z.tuple([
  millisecondsSchema,
  trackIdSchema,
  z.number(),
  z.number(),
  z.number(),
  z.number(),
  z.number(),
  z.number(),
  z.number(),
]);

export const onsetRowSchema = z.tuple([millisecondsSchema, z.number()]);

const windowEventSchema = z.strictObject({
  event_id: eventIdSchema,
  type: z.string(),
  t_ms: millisecondsSchema,
  t_sigma_ms: millisecondsSchema,
  track_id: trackIdSchema,
  image_xy: z.tuple([z.number(), z.number()]),
  cues: z.array(z.string()),
  state: provenanceStateSchema,
});

export const windowResultSchema = z.strictObject({
  schema: z.literal("window_result.v1"),
  analysis_id: analysisIdSchema,
  window_seq: z.number().int().nonnegative(),
  decode_window_us: rangeUsSchema,
  emit_range_us: rangeUsSchema,
  models: z.array(canonicalModelIdSchema),
  ball: z.array(ballRowSchema),
  players: z.array(playerRowSchema),
  onsets: z.array(onsetRowSchema),
  events: z.array(windowEventSchema),
  quality: z.strictObject({
    ball_coverage: z.number(),
    camera_motion_px: z.number(),
    blur_index: z.number(),
  }),
});

export type BallRow = z.infer<typeof ballRowSchema>;
export type PlayerRow = z.infer<typeof playerRowSchema>;
export type OnsetRow = z.infer<typeof onsetRowSchema>;
export type WindowResult = z.infer<typeof windowResultSchema>;
