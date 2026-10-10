// GENERATED from packages/contracts - do not edit.
// Regenerate with supabase/scripts/sync-contracts.ts
import * as z from "zod";
import {
  isoTimestampSchema,
  microsecondsSchema,
  millisecondsSchema,
  provenanceStateSchema,
  rangeUsSchema,
} from "./common.ts";

// §11 fixture_trail.json: hand-labelled shuttle points per decoded frame (D-261011-HW4).
// Same rules as Person 2's TrailLabelsParser.kt, so a document the app would reject fails CI too.
const lowerSha256Schema = z.string().regex(/^[0-9a-f]{64}$/);

const trailFrameSchema = z.strictObject({
  t_us: microsecondsSchema,
  t_ms: millisecondsSchema.optional(),
  // null = looked at this frame, shuttle not visible. Frames never looked at are absent.
  ball: z.tuple([z.number(), z.number()]).nullable(),
});

export const trailLabelsSchema = z
  .strictObject({
    schema: z.literal("elevate.trail_labels.v1"),
    clip: z.strictObject({
      id: z.string().min(1),
      file: z.string().optional(),
      video_stream_sha256: lowerSha256Schema.optional(),
      accepted_file_sha256: z.array(lowerSha256Schema).min(1),
      width: z.number().int().positive(),
      height: z.number().int().positive(),
      rotation: z.number().int().optional(),
      time_base: z.string().optional(),
    }),
    provenance: z.strictObject({
      state: provenanceStateSchema,
      engine_version: z.string(),
      labelled_by: z.string().optional(),
      tool: z.string().optional(),
      labelled_at: isoTimestampSchema.optional(),
      note: z.string().optional(),
      validated_by: z.string().optional(),
      removed_frames_t_us: z.array(microsecondsSchema).optional(),
      removals_approved_by: z.string().optional(),
    }),
    range_us: rangeUsSchema,
    frame_count_in_range: z.number().int().positive(),
    frames: z.array(trailFrameSchema),
  })
  .superRefine((doc, ctx) => {
    const issue = (message: string, path: (string | number)[]) =>
      ctx.addIssue({ code: "custom", message, path });
    if ((doc.provenance.state === "user") !== (doc.provenance.engine_version === "hand-labelled")) {
      issue("hand labels need state user together with engine_version hand-labelled", ["provenance"]);
    }
    const [start, end] = doc.range_us;
    const seen = new Map<number, string>();
    doc.frames.forEach((frame, i) => {
      if (frame.t_us < start || frame.t_us > end) issue("frame outside range_us", ["frames", i, "t_us"]);
      if (frame.t_ms !== undefined && Math.abs(frame.t_ms * 1000 - frame.t_us) >= 1000) {
        issue("t_ms does not match t_us", ["frames", i, "t_ms"]);
      }
      if (frame.ball) {
        const [x, y] = frame.ball;
        if (x < 0 || y < 0 || x > doc.clip.width || y > doc.clip.height) {
          issue("ball outside the video frame", ["frames", i, "ball"]);
        }
      }
      const key = JSON.stringify(frame.ball);
      const previous = seen.get(frame.t_us);
      if (previous !== undefined && previous !== key) {
        issue("frame labelled twice with different values", ["frames", i]);
      }
      seen.set(frame.t_us, key);
    });
    if (seen.size > doc.frame_count_in_range) issue("more labelled frames than frames in range", ["frames"]);
  });

export type TrailLabels = z.infer<typeof trailLabelsSchema>;
