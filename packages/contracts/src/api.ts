import * as z from "zod";
import {
  analysisIdSchema,
  artifactIdSchema,
  calibrationIdSchema,
  capabilityStatusSchema,
  capabilityTierSchema,
  canonicalModelIdSchema,
  clipIdSchema,
  confirmedPointIdSchema,
  correctionIdSchema,
  eventIdSchema,
  artifactKindSchema,
  builtSportSchema,
  matchFormatSchema,
  sessionModeSchema,
  insightIdSchema,
  isoTimestampSchema,
  matchIdSchema,
  millisecondsSchema,
  microsecondsSchema,
  momentIdSchema,
  playerIdSchema,
  provenanceStateSchema,
  rallyClipIdSchema,
  rallyIdSchema,
  rangeUsSchema,
  reviewIdSchema,
  sessionIdSchema,
  sha256Schema,
  shotClipIdSchema,
  shotIdSchema,
  surfacePlaneSchema,
  trackIdSchema,
  zoneIdSchema,
} from "./common.ts";
import { origR2KeySchema, r2ObjectKeySchema } from "./keys.ts";
const strictObject = z.strictObject;
const integer = z.number().int();
const nonNegativeInteger = integer.nonnegative();
const xyPxSchema = z.tuple([z.number(), z.number()]);
const xyMetresSchema = z.tuple([z.number(), z.number()]);
const scorePairSchema = z.tuple([nonNegativeInteger, nonNegativeInteger]);

export const apiErrorSchema = strictObject({
  code: z.string(),
  message: z.string(),
  details: z.unknown().optional(),
});

export const errorEnvelopeSchema = strictObject({
  error: apiErrorSchema,
  retry_after_s: z.number().nonnegative().optional(),
});

const sourcePtsSchema = strictObject({
  median_interval_us: microsecondsSchema,
  min_us: microsecondsSchema,
  max_us: microsecondsSchema,
  gaps: nonNegativeInteger,
  vfr: z.boolean(),
});

const mediaSourceSchema = strictObject({
  width: nonNegativeInteger,
  height: nonNegativeInteger,
  rotation: integer,
  fps_nominal: z.number(),
  duration_ms: millisecondsSchema,
  pts: sourcePtsSchema,
  codec: z.string(),
  has_audio: z.boolean(),
  audio_rate_hz: nonNegativeInteger,
  device: z.string(),
  bytes: nonNegativeInteger,
  sha256: sha256Schema,
});

const courtPointsPxSchema = strictObject({
  near_left: xyPxSchema,
  near_right: xyPxSchema,
  far_right: xyPxSchema,
  far_left: xyPxSchema,
});

const courtCalibrationInputSchema = strictObject({
  method: z.string(),
  plane: z.literal("court"),
  template: z.string(),
  points_px: courtPointsPxSchema,
  frame_ms: millisecondsSchema,
});

// §2: free play calibrates on "a taped rectangle on the floor plane": its four corners, tapped
// with the same named points as the court.
const floorCalibrationInputSchema = strictObject({
  method: z.string(),
  plane: z.literal("floor"),
  template: z.string().optional(),
  points_px: courtPointsPxSchema,
  frame_ms: millisecondsSchema,
});

const calibrationInputSchema = z.union([
  courtCalibrationInputSchema,
  floorCalibrationInputSchema,
  z.null(),
]);

const freePlaySeedSchema = strictObject({
  t_ms: millisecondsSchema,
  image_xy: xyPxSchema,
});

const rallyCandidateSchema = strictObject({
  start_ms: millisecondsSchema,
  end_ms: millisecondsSchema,
  onsets: nonNegativeInteger,
  state: provenanceStateSchema,
});

export const sessionCreateRequestSchema = strictObject({
  sport: builtSportSchema,
  format: matchFormatSchema,
  ruleset: strictObject({
    id: z.string(),
    version: z.string(),
  }),
  mode: sessionModeSchema,
  source: mediaSourceSchema,
  calibration_input: calibrationInputSchema,
  who_is_me: strictObject({
    image_xy: xyPxSchema,
    t_ms: millisecondsSchema,
  }),
  free_play_seed: freePlaySeedSchema.nullable(),
  rally_candidates: z.array(rallyCandidateSchema),
  // D-261011-HW3: chunked real-time upload is core; multipart stays as the fallback.
  upload_mode: z.enum(["multipart", "segments"]),
}).superRefine((session, ctx) => {
  // Free play has no court format; racket sports always have one.
  if ((session.sport === "free_play") !== (session.format === "none")) {
    ctx.addIssue({
      code: "custom",
      path: ["format"],
      message: 'format is "none" exactly when sport is free_play',
    });
  }
  // §2: "The court template always matches format (badminton_singles / badminton_doubles)."
  const calibration = session.calibration_input;
  if (session.sport === "badminton" && calibration?.plane === "court") {
    const expected = `badminton_${session.format}`;
    if (calibration.template !== expected) {
      ctx.addIssue({
        code: "custom",
        path: ["calibration_input", "template"],
        message: `court template must be ${expected} for format ${session.format} (§2)`,
      });
    }
  }
});

export const sessionCreateResponseSchema = strictObject({
  session_id: sessionIdSchema,
  match_id: matchIdSchema,
  upload: strictObject({
    upload_id: z.string(),
    r2_key: origR2KeySchema,
    part_size: nonNegativeInteger,
    max_concurrency: nonNegativeInteger,
  }),
});

export const uploadPartsRequestSchema = strictObject({
  part_numbers: z.array(nonNegativeInteger),
});

export const uploadPartsResponseSchema = z.array(z.string().url());

export const uploadCompleteRequestSchema = strictObject({
  parts: z.array(
    strictObject({
      n: nonNegativeInteger,
      etag: z.string(),
    }),
  ),
});

export const segmentManifestSchema = strictObject({
  seq: nonNegativeInteger,
  pts_start_us: microsecondsSchema,
  pts_end_us: microsecondsSchema,
  starts_on_sync_sample: z.boolean(),
  bytes: nonNegativeInteger,
  sha256: sha256Schema,
  r2_key: z.string(),
});

export const analysisStateSchema = z.enum([
  "awaiting_upload",
  "uploaded",
  "queued",
  "processing",
  "partial",
  "finalizing",
  "completed",
  "failed",
]);

export const analysisEventTypeSchema = z.enum([
  "analysis.queued",
  "analysis.started",
  "window.completed",
  "window.failed",
  "analysis.partial",
  "analysis.finalizing",
  "analysis.completed",
  "analysis.failed",
  "point.confirmed",
]);

export const analysisCreateRequestSchema = strictObject({
  session_id: sessionIdSchema,
});

export const analysisCreateResponseSchema = strictObject({
  analysis_id: analysisIdSchema,
  state: analysisStateSchema,
});

export const modalSubmitRequestSchema = strictObject({
  analysis_id: analysisIdSchema,
  r2_key: origR2KeySchema,
  session_spec: sessionCreateRequestSchema,
});

const internalEventFields = {
  window_seq: nonNegativeInteger.optional(),
  decode_window_us: rangeUsSchema.optional(),
  emit_range_us: rangeUsSchema.optional(),
  artifact_id: artifactIdSchema.optional(),
};

// §3: Person 3 alone appends analysis.completed (and queued/partial/finalizing, point.confirmed).
// D-261010-NP1: the engine posts only these four types to /internal.
export const engineEventTypeSchema = z.enum([
  "analysis.started",
  "window.completed",
  "window.failed",
  "analysis.failed",
]);

const otherInternalEventTypes = engineEventTypeSchema.options.filter(
  (type) => type !== "window.failed",
);

export const internalEventRequestSchema = z.union([
  strictObject({
    type: z.literal("window.failed"),
    ...internalEventFields,
    reason: z.string(),
  }),
  ...otherInternalEventTypes.map((type) =>
    strictObject({
      type: z.literal(type),
      ...internalEventFields,
      reason: z.string().optional(),
    }),
  ),
]);

export const internalArtifactRequestSchema = strictObject({
  kind: artifactKindSchema,
  r2_key: r2ObjectKeySchema,
  bytes: nonNegativeInteger,
  sha256: sha256Schema,
});

export const internalArtifactResponseSchema = strictObject({
  artifact_id: artifactIdSchema,
});

const analysisEventFields = {
  event_id: eventIdSchema,
  analysis_id: analysisIdSchema,
  match_id: matchIdSchema,
  seq: nonNegativeInteger,
  schema_version: z.literal(1),
  window_seq: nonNegativeInteger.optional(),
  decode_window_us: rangeUsSchema.optional(),
  emit_range_us: rangeUsSchema.optional(),
  artifact_id: artifactIdSchema.optional(),
  engine_version: z.string(),
  created_at: isoTimestampSchema,
};

export const analysisEventSchema = z.union([
  strictObject({
    ...analysisEventFields,
    type: z.literal("window.failed"),
    reason: z.string(),
  }),
  ...otherInternalEventTypes.map((type) =>
    strictObject({
      ...analysisEventFields,
      type: z.literal(type),
      reason: z.string().optional(),
    }),
  ),
]);

export const realtimeHintSchema = strictObject({
  schema_version: z.literal(1),
  analysis_id: analysisIdSchema,
  latest_seq: nonNegativeInteger,
});

export const analysisEventsPageSchema = strictObject({
  events: z.array(analysisEventSchema),
  next_after_seq: nonNegativeInteger,
  has_more: z.boolean(),
  state: analysisStateSchema,
});

const capabilitySchema = strictObject({
  tier: capabilityTierSchema,
  status: capabilityStatusSchema,
});

const matchPlayerSchema = strictObject({
  player_id: playerIdSchema,
  track_ids: z.array(trackIdSchema),
  label: z.string(),
  identity: z.string(),
});

const calibrationSchema = strictObject({
  calibration_id: calibrationIdSchema,
  plane: surfacePlaneSchema,
  template: z.string(),
  method: z.string(),
  H: z.tuple([
    z.number(),
    z.number(),
    z.number(),
    z.number(),
    z.number(),
    z.number(),
    z.number(),
    z.number(),
    z.number(),
  ]),
  H_cov: z.unknown().nullable(),
  residual_px: z.number(),
  visible_fraction: z.number(),
  tier: z.string(),
});

const analysisSummarySchema = strictObject({
  analysis_id: analysisIdSchema,
  engine_version: z.string(),
  state: analysisStateSchema,
  models: z.array(canonicalModelIdSchema),
  artifacts: strictObject({
    ball_track: artifactIdSchema,
    player_tracks: artifactIdSchema,
  }),
});

const capabilitiesSchema = strictObject({
  court: capabilitySchema,
  ball: capabilitySchema,
  reviews: capabilitySchema,
  score: capabilitySchema,
});

const rallySchema = strictObject({
  rally_id: rallyIdSchema,
  start_ms: millisecondsSchema,
  end_ms: millisecondsSchema,
  shot_ids: z.array(shotIdSchema),
  server: playerIdSchema,
  winner: playerIdSchema,
  winner_state: provenanceStateSchema,
  end_reason: z.string(),
  ball_coverage: z.number(),
  clip_id: z.union([clipIdSchema, rallyClipIdSchema, shotClipIdSchema]),
  state: provenanceStateSchema,
});

const shotRoleSchema = strictObject({
  player_id: playerIdSchema,
  xy_m: xyMetresSchema,
  state: provenanceStateSchema,
  conf: z.number(),
});

const shotSchema = strictObject({
  shot_id: shotIdSchema,
  rally_id: rallyIdSchema,
  index: nonNegativeInteger,
  player_id: playerIdSchema,
  contact_ms: millisecondsSchema,
  contact_sigma_ms: millisecondsSchema,
  phase: z.string(),
  reference_plane: surfacePlaneSchema,
  coordinate_frame_version: nonNegativeInteger,
  roles: strictObject({
    focal: shotRoleSchema,
    opponent: shotRoleSchema,
  }),
  incoming: strictObject({
    source_zone: zoneIdSchema,
    landing_zone: zoneIdSchema,
    zones_state: provenanceStateSchema,
    family: z.string(),
  }),
  chosen: strictObject({
    target_zone: zoneIdSchema,
    landing_xy_m: xyMetresSchema,
    landing_state: provenanceStateSchema,
    family: z.string(),
  }),
  flight_time_ms: millisecondsSchema,
  flight_time_state: provenanceStateSchema,
  context: strictObject({
    score_before: scorePairSchema,
    pressure_bucket: z.string(),
    rally_len_so_far: nonNegativeInteger,
    state: provenanceStateSchema,
  }),
  outcome: strictObject({
    point_winner: playerIdSchema,
    shots_to_end: nonNegativeInteger,
    state: provenanceStateSchema,
  }),
  quality: strictObject({
    camera_quality_grade: z.string(),
  }),
  clip_id: z.union([clipIdSchema, rallyClipIdSchema, shotClipIdSchema]),
});

// §0: a homography maps surface points only; airborne points stay in image space.
// Surface events (landing, bounce) carry plane + contact_xy_m; airborne ones (hit) carry image_xy.
const matchEventSchema = strictObject({
  event_id: eventIdSchema,
  type: z.string(),
  t_ms: millisecondsSchema,
  t_sigma_ms: millisecondsSchema,
  rally_id: rallyIdSchema,
  plane: surfacePlaneSchema.optional(),
  contact_xy_m: xyMetresSchema.optional(),
  cov_m2: z
    .tuple([
      z.tuple([z.number(), z.number()]),
      z.tuple([z.number(), z.number()]),
    ])
    .optional(),
  image_xy: xyPxSchema.optional(),
  cues: z.array(z.string()),
  state: provenanceStateSchema,
  evidence: z.array(
    strictObject({
      t_ms: millisecondsSchema,
    }),
  ),
}).refine((event) => event.contact_xy_m === undefined || event.plane !== undefined, {
  message: "contact_xy_m is on a surface plane, so plane is required with it (§0)",
  path: ["plane"],
});

export const reviewEstimateSchema = z.enum([
  "IN",
  "OUT",
  "TOO_CLOSE",
  "UNOBSERVED",
]);

const reviewSchema = strictObject({
  review_id: reviewIdSchema,
  match_id: matchIdSchema,
  rally_id: rallyIdSchema,
  event_id: eventIdSchema,
  t_ms: millisecondsSchema,
  evidence_frames: z.array(millisecondsSchema),
  contact_xy_m: xyMetresSchema,
  cov_m2: z.tuple([
    z.tuple([z.number(), z.number()]),
    z.tuple([z.number(), z.number()]),
  ]),
  line_id: z.string(),
  margin_m: z.number(),
  sigma_m: z.number(),
  estimate: reviewEstimateSchema,
  visibility: z.string(),
  model_ids: z.array(canonicalModelIdSchema),
  calibration_id: calibrationIdSchema,
  confirmed_point_winner: playerIdSchema,
  confirmed_by: playerIdSchema,
  confirmed_at: isoTimestampSchema,
});

export const confirmedPointSchema = strictObject({
  confirmed_point_id: confirmedPointIdSchema,
  rally_id: rallyIdSchema,
  winner: playerIdSchema,
  by: playerIdSchema,
  at: isoTimestampSchema,
  review_id: reviewIdSchema,
});

const scoreTimelineRowSchema = strictObject({
  rally_id: rallyIdSchema,
  after: scorePairSchema,
  server_next: playerIdSchema,
});

const metricSchema = strictObject({
  key: z.string(),
  definition_version: nonNegativeInteger,
  player_id: playerIdSchema,
  numerator: nonNegativeInteger,
  denominator: nonNegativeInteger,
  unit: z.string(),
  tier: capabilityTierSchema,
  source_event_ids: z.array(eventIdSchema),
});

const insightSchema = strictObject({
  insight_id: insightIdSchema,
  kind: z.string(),
  caption: z.string(),
  count: strictObject({
    num: nonNegativeInteger,
    den: nonNegativeInteger,
  }),
  visual: strictObject({
    type: z.string(),
    filter: strictObject({
      player: playerIdSchema,
    }),
  }),
  evidence_moment_ids: z.array(momentIdSchema),
  gate: z.string(),
});

const momentSchema = strictObject({
  moment_id: momentIdSchema,
  rally_id: rallyIdSchema,
  shot_id: shotIdSchema,
  start_ms: millisecondsSchema,
  end_ms: millisecondsSchema,
  contact_ms: millisecondsSchema,
  tags: z.array(z.string()),
  clip_id: z.union([clipIdSchema, rallyClipIdSchema, shotClipIdSchema]),
});

const clipSchema = strictObject({
  clip_id: z.union([clipIdSchema, rallyClipIdSchema, shotClipIdSchema]),
  source: z.string(),
  rights: z.string(),
  owner: playerIdSchema,
  match_id: matchIdSchema,
  start_ms: millisecondsSchema,
  end_ms: millisecondsSchema,
  contact_ms: millisecondsSchema,
  remote: strictObject({
    artifact_id: artifactIdSchema,
    poster_artifact_id: artifactIdSchema,
  }),
  local: strictObject({
    source_uri: z.string(),
    offset_ms: millisecondsSchema,
  }),
  attribution: z.unknown().nullable(),
});

const correctionSchema = strictObject({
  correction_id: correctionIdSchema,
  target: strictObject({
    type: z.string(),
    id: z.string(),
  }),
  field: z.string(),
  old: z.unknown(),
  new: z.unknown(),
  actor: playerIdSchema,
  at: isoTimestampSchema,
  reason: z.string(),
});

export const matchV1Schema = strictObject({
  schema: z.literal("match.v1"),
  match_id: matchIdSchema,
  session_ids: z.array(sessionIdSchema),
  sport: builtSportSchema,
  format: matchFormatSchema,
  ruleset: strictObject({
    id: z.string(),
    version: z.string(),
  }),
  players: z.array(matchPlayerSchema),
  calibration: calibrationSchema,
  analysis: analysisSummarySchema,
  capabilities: capabilitiesSchema,
  rallies: z.array(rallySchema),
  shots: z.array(shotSchema),
  events: z.array(matchEventSchema),
  reviews: z.array(reviewSchema),
  confirmed_points: z.array(confirmedPointSchema),
  score: strictObject({
    timeline: z.array(scoreTimelineRowSchema),
    games: z.array(scorePairSchema),
  }),
  metrics: z.array(metricSchema),
  insights: z.array(insightSchema),
  moments: z.array(momentSchema),
  clips: z.array(clipSchema),
  corrections: z.array(correctionSchema),
});

export const visionResultSchema = matchV1Schema.omit({ insights: true });

export const analysisFinalizeRequestSchema = strictObject({
  vision_result: visionResultSchema,
  clip_artifact_ids: z.array(artifactIdSchema),
});

export const reviewConfirmRequestSchema = strictObject({
  winner: playerIdSchema,
});

const comparisonCandidateSchema = strictObject({
  shot_id: shotIdSchema,
  clip_id: z.union([clipIdSchema, rallyClipIdSchema, shotClipIdSchema]),
  distance: z.number(),
  matched_features: z.array(z.string()),
  role: z.enum([
    "similar_moment",
    "worked_for_you",
    "coach_pick",
    "simulated",
  ]),
  evidence: strictObject({
    won: nonNegativeInteger,
    of: nonNegativeInteger,
    matches: nonNegativeInteger,
    rallies: nonNegativeInteger,
  }),
  provenance: z.enum(["own", "shared", "team_reference", "public_cc"]),
  attribution: z.unknown().nullable(),
});

export const comparisonsResponseSchema = strictObject({
  query_shot_id: shotIdSchema,
  candidates: z.array(comparisonCandidateSchema),
});

export const simulationRunRequestSchema = strictObject({
  moment_id: momentIdSchema,
  mode: z.enum(["read", "decide"]),
  target_zone: zoneIdSchema.optional(),
});

const simulationOutcomeSchema = z.union([
  z.enum(["exact", "adjacent", "wrong", "unscored"]),
  strictObject({
    legal: z.boolean(),
    spacing: z.enum(["covered", "adjacent", "separated"]),
  }),
]);

export const simulationRunResponseSchema = strictObject({
  result: simulationOutcomeSchema,
  evidence_ids: z.array(z.string()),
  continuation_clip_id: z.union([clipIdSchema, rallyClipIdSchema, shotClipIdSchema]),
});

export const opponentScoutResponseSchema = z.array(
  strictObject({
    pattern: z.string(),
    n: nonNegativeInteger,
    matches: nonNegativeInteger,
    clip_ids: z.array(z.union([clipIdSchema, rallyClipIdSchema, shotClipIdSchema])),
  }),
);

export type ApiError = z.infer<typeof apiErrorSchema>;
export type ErrorEnvelope = z.infer<typeof errorEnvelopeSchema>;
export type SessionCreateRequest = z.infer<typeof sessionCreateRequestSchema>;
export type SessionCreateResponse = z.infer<typeof sessionCreateResponseSchema>;
export type UploadPartsRequest = z.infer<typeof uploadPartsRequestSchema>;
export type UploadPartsResponse = z.infer<typeof uploadPartsResponseSchema>;
export type UploadCompleteRequest = z.infer<typeof uploadCompleteRequestSchema>;
export type SegmentManifest = z.infer<typeof segmentManifestSchema>;
export type AnalysisState = z.infer<typeof analysisStateSchema>;
export type AnalysisEventType = z.infer<typeof analysisEventTypeSchema>;
export type AnalysisCreateRequest = z.infer<typeof analysisCreateRequestSchema>;
export type AnalysisCreateResponse = z.infer<typeof analysisCreateResponseSchema>;
export type ModalSubmitRequest = z.infer<typeof modalSubmitRequestSchema>;
export type InternalEventRequest = z.infer<typeof internalEventRequestSchema>;
export type InternalArtifactRequest = z.infer<typeof internalArtifactRequestSchema>;
export type InternalArtifactResponse = z.infer<typeof internalArtifactResponseSchema>;
export type AnalysisEvent = z.infer<typeof analysisEventSchema>;
export type RealtimeHint = z.infer<typeof realtimeHintSchema>;
export type AnalysisEventsPage = z.infer<typeof analysisEventsPageSchema>;
export type ConfirmedPoint = z.infer<typeof confirmedPointSchema>;
export type MatchV1 = z.infer<typeof matchV1Schema>;
export type VisionResult = z.infer<typeof visionResultSchema>;
export type AnalysisFinalizeRequest = z.infer<typeof analysisFinalizeRequestSchema>;
export type ReviewConfirmRequest = z.infer<typeof reviewConfirmRequestSchema>;
export type ComparisonsResponse = z.infer<typeof comparisonsResponseSchema>;
export type SimulationRunRequest = z.infer<typeof simulationRunRequestSchema>;
export type SimulationRunResponse = z.infer<typeof simulationRunResponseSchema>;
export type OpponentScoutResponse = z.infer<typeof opponentScoutResponseSchema>;
