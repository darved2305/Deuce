import * as z from "zod";
import {
  analysisIdSchema,
  artifactIdSchema,
  calibrationIdSchema,
  challengeIdSchema,
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
  postIdSchema,
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
import { origR2KeySchema, r2ObjectKeySchema, segmentR2KeySchema } from "./keys.ts";
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

// §1: error.code is a free string; these are the codes the API and engine emit today, with
// their HTTP status. 429s also carry top-level retry_after_s where stated.
export const knownErrorCodes = {
  unauthorized: 401,
  not_found: 404,
  analysis_not_found: 404,
  conflict: 409,
  idempotency_conflict: 409,
  analysis_run_conflict: 409,
  upload_parts_invalid: 409,
  upload_not_complete: 409,
  invalid_transition: 409,
  upload_too_large: 413,
  validation_failed: 422,
  invalid_body: 422,
  storage_budget_reached: 429,
  daily_upload_limit: 429,
  too_many_part_requests: 429,
  daily_download_limit: 429,
  daily_post_limit: 429,
  analysis_limit_reached: 429,
  internal: 500,
  segment_gap: 409,
  dispatch_in_flight: 503,
  engine_unavailable: 503,
  uploads_paused: 503,
  storage_busy: 503,
} as const;

export const healthResponseSchema = strictObject({
  status: z.literal("ok"),
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

// D-261011-HW14: multipart (unchanged) or segments (chunked upload, contracts §2).
export const sessionCreateResponseSchema = z.union([
  strictObject({
    session_id: sessionIdSchema,
    match_id: matchIdSchema,
    upload: strictObject({
      upload_id: z.string(),
      r2_key: origR2KeySchema,
      part_size: nonNegativeInteger,
      max_concurrency: nonNegativeInteger,
    }),
  }),
  strictObject({
    session_id: sessionIdSchema,
    match_id: matchIdSchema,
    upload_mode: z.literal("segments"),
    segments: strictObject({
      max_segment_bytes: nonNegativeInteger,
      max_concurrency: nonNegativeInteger,
      url_ttl_s: nonNegativeInteger,
    }),
  }),
]);

export const uploadPartsRequestSchema = strictObject({
  part_numbers: z.array(nonNegativeInteger),
});

// Each part URL with its number and expiry, so the app can resume and re-request before expiry
// (Cloudflare rule G6: upload-part URLs live 10 minutes).
export const uploadPartsResponseSchema = strictObject({
  parts: z.array(
    strictObject({
      n: nonNegativeInteger,
      url: z.string().url(),
      expires_at: isoTimestampSchema,
    }),
  ),
});

export const uploadCompleteResponseSchema = strictObject({
  status: z.literal("completed"),
  bytes: nonNegativeInteger,
});

export const uploadAbortResponseSchema = strictObject({
  status: z.literal("aborted"),
});

// GET /v1/sessions/{id}
export const sessionGetResponseSchema = strictObject({
  session_id: sessionIdSchema,
  match_id: matchIdSchema,
  mode: sessionModeSchema,
  upload_mode: z.enum(["multipart", "segments"]),
  source: mediaSourceSchema,
  created_at: isoTimestampSchema,
  upload: strictObject({
    status: z.enum(["initiated", "completed", "aborted"]),
    part_size: nonNegativeInteger,
    bytes: nonNegativeInteger,
    part_count: nonNegativeInteger,
    completed_at: isoTimestampSchema.nullable(),
  }).nullable(),
});

export const uploadCompleteRequestSchema = strictObject({
  parts: z.array(
    strictObject({
      n: nonNegativeInteger,
      etag: z.string(),
    }),
  ),
});

// §2 chunked upload (D-261011-HW3, D-261011-HW8). Times are on the ORIGINAL video's timeline.
// `final` marks the last segment of the session; the engine stops waiting after it.
const segmentFields = {
  seq: nonNegativeInteger,
  pts_start_us: microsecondsSchema,
  pts_end_us: microsecondsSchema,
  starts_on_sync_sample: z.boolean(),
  bytes: nonNegativeInteger,
  sha256: sha256Schema,
  final: z.boolean(),
};

export const segmentManifestSchema = strictObject({
  ...segmentFields,
  r2_key: segmentR2KeySchema,
});

// POST /v1/sessions/{id}/segments: register the next segment and get its PUT URL. seq must be
// at most (highest registered seq + 1): no gaps; re-registering the same seq is idempotent.
export const segmentRegisterRequestSchema = strictObject(segmentFields);

export const segmentRegisterResponseSchema = strictObject({
  seq: nonNegativeInteger,
  r2_key: segmentR2KeySchema,
  put_url: z.string().url(),
  expires_at: isoTimestampSchema,
});

// POST /v1/sessions/{id}/segments/{seq}/complete: the object is in R2. Idempotent. After
// segment 0 completes the app calls POST /v1/analyses as for a whole-file upload.
export const segmentCompleteResponseSchema = strictObject({
  ...segmentFields,
  r2_key: segmentR2KeySchema,
  completed_at: isoTimestampSchema,
});

// GET /internal/analyses/{id}/segments?after_seq=n: completed segments the engine has not seen,
// in seq order (the contiguous completed prefix only). The engine never lists R2.
export const internalSegmentsPageSchema = strictObject({
  segments: z.array(segmentManifestSchema),
  final_seq: nonNegativeInteger.nullable(),
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
  // D-261011-HW15 (#77): the engine fills vision_result.match_id / session_ids from these. Optional
  // so an engine deployed before the API keeps accepting requests; finalize needs them.
  match_id: matchIdSchema.optional(),
  session_id: sessionIdSchema.optional(),
  // multipart: the original; segments: segment 0 (the engine then polls /internal/.../segments).
  r2_key: z.union([origR2KeySchema, segmentR2KeySchema]),
  session_spec: sessionCreateRequestSchema,
});

// §3 window fields. window_seq is required on window.completed / window.failed: the event
// dedupe key is (type, window_seq), so a missing window_seq would merge every window into one.
const windowRangeFields = {
  // Total windows the engine will emit, on analysis.started, so the app can show "n of N" (#66).
  windows_expected: nonNegativeInteger.optional(),
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

function eventVariants<const T extends string>(
  types: readonly T[],
  base: Record<string, z.ZodType>,
) {
  return types.map((type) => {
    const isWindow = type === "window.completed" || type === "window.failed";
    return strictObject({
      ...base,
      ...windowRangeFields,
      type: z.literal(type),
      window_seq: isWindow ? nonNegativeInteger : nonNegativeInteger.optional(),
      reason: type === "window.failed" ? z.string() : z.string().optional(),
    });
  });
}

// engine_version is sent by the engine (set on analysis.started at the latest, D-261010-NP1 item 2).
export const internalEventRequestSchema = z.union(
  eventVariants(engineEventTypeSchema.options, {
    engine_version: z.string().optional(),
  }) as unknown as [z.ZodType, z.ZodType, ...z.ZodType[]],
);

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
  // API-emitted events (analysis.queued, point.confirmed, ...) carry no engine version.
  engine_version: z.string().nullable().optional(),
  created_at: isoTimestampSchema,
};

// Read events (the cursor) carry every §3 type, not only the engine's four.
export const analysisEventSchema = z.union(
  eventVariants(analysisEventTypeSchema.options, analysisEventFields) as unknown as [
    z.ZodType,
    z.ZodType,
    ...z.ZodType[],
  ],
);

export const realtimeHintSchema = strictObject({
  schema_version: z.literal(1),
  analysis_id: analysisIdSchema,
  latest_seq: nonNegativeInteger,
});

// GET /v1/analyses/{id}. Never exposes modal_call_id or dispatch columns.
export const analysisGetResponseSchema = strictObject({
  analysis_id: analysisIdSchema,
  session_id: sessionIdSchema,
  match_id: matchIdSchema,
  state: analysisStateSchema,
  engine_version: z.string().nullable(),
  last_seq: nonNegativeInteger,
  failure_reason: z.string().nullable(),
  created_at: isoTimestampSchema,
  updated_at: isoTimestampSchema,
  finalized_at: isoTimestampSchema.nullable(),
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

// D-261011-HW14: H maps IMAGE pixels -> COURT metres, row-major 3x3, bottom-right = 1 (the app uses
// its inverse to draw court lines). H_cov: covariance of the flattened H (81 numbers) or null.
// Tier: A residual <= 1 px, worst metre std <= 0.15 m, >= 90% of court in frame; B <= 3 px, <= 0.40 m,
// >= 50%; else C (thresholds under review until measured on real taps).
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
  H_cov: z.array(z.number()).length(81).nullable(),
  residual_px: z.number(),
  visible_fraction: z.number(),
  tier: z.enum(["A", "B", "C"]),
});

const analysisSummarySchema = strictObject({
  analysis_id: analysisIdSchema,
  engine_version: z.string(),
  state: analysisStateSchema,
  models: z.array(canonicalModelIdSchema),
  // D-261011-HW15 (#77): null until a single whole-match track artifact exists; the per-window
  // window_result artifacts carry the tracks today (G8 allows only window keys).
  artifacts: strictObject({
    ball_track: artifactIdSchema.nullable(),
    player_tracks: artifactIdSchema.nullable(),
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
  rally_id: rallyIdSchema.optional(), // absent before rally segmentation (hit candidates)
  player_id: playerIdSchema.optional(),
  track_id: trackIdSchema.optional(),
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
  // D-261011-HW12: an engine estimate has no confirmation yet; a player confirmation sets all three.
  // `confirmed_by` is the match player the signed-in account is mapped to (who_is_me); the database
  // keeps the account itself as the audit actor.
  confirmed_point_winner: playerIdSchema.nullable(),
  confirmed_by: playerIdSchema.nullable(),
  confirmed_at: isoTimestampSchema.nullable(),
}).refine(
  (r) => [r.confirmed_point_winner, r.confirmed_by, r.confirmed_at].every((v) => v === null) ||
    [r.confirmed_point_winner, r.confirmed_by, r.confirmed_at].every((v) => v !== null),
  { message: "confirmed_point_winner, confirmed_by and confirmed_at are all null or all set" },
);

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
  player_id: playerIdSchema.nullable(), // null = a match-level metric (D-261011-HW12)
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
  calibration: calibrationSchema.nullable(), // null = not calibrated (D-261011-HW14)
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

// D-261011-HW16 (#65, B6 reads): one page envelope; items reuse match.v1 pieces, never a new shape.
// `next_cursor` is opaque (null = last page). limit: default 20, max 20 on /v1/moments, else 100.
const nextCursorSchema = z.string().min(1).nullable();

export const matchListItemSchema = strictObject({
  match_id: matchIdSchema,
  sport: builtSportSchema,
  format: matchFormatSchema,
  ruleset: matchV1Schema.shape.ruleset,
  created_at: isoTimestampSchema,
  // State of the match's latest analysis; null before the first analysis starts.
  state: analysisStateSchema.nullable(),
  score: strictObject({ games: z.array(scorePairSchema) }),
});

export const matchesPageSchema = strictObject({ items: z.array(matchListItemSchema), next_cursor: nextCursorSchema });
export const ralliesPageSchema = strictObject({ items: z.array(rallySchema), next_cursor: nextCursorSchema });
// GET /v1/matches/{id}/rallies/{rid}: the rally plus its shots, in shot_ids order.
export const rallyDetailSchema = rallySchema.extend({ shots: z.array(shotSchema) });
// GET /v1/matches/{id}/moments and GET /v1/moments (filters: ?tag=, ?match_id= on /v1/moments).
export const momentsPageSchema = strictObject({
  items: z.array(momentSchema.extend({ match_id: matchIdSchema })),
  next_cursor: nextCursorSchema,
});
// GET /v1/capabilities: what this build can measure, in match.v1's {tier, status} vocabulary.
export const capabilitiesResponseSchema = strictObject({ capabilities: capabilitiesSchema });

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

// Friends and challenges (1.11.0, D-261011-HW31). A challenge is one Reflex run on a match:
// the exact freeze set (the rounds), the creator's score and per-round results. Players accept
// it, play the same rounds and post their own entry. Scores are the app's own Reflex scoring;
// the server checks that a score equals the sum of its round points and nothing else.
export const challengeVerdictSchema = z.enum(["exact", "adjacent", "wrong", "too_slow"]);

// One frozen shot. ladder_us are real decoded frame times of the creator's file (same sha256
// means the same frames); landing_event_id and hit_event_id name events of the match.
export const challengeRoundSchema = strictObject({
  hit_event_id: eventIdSchema,
  landing_event_id: eventIdSchema,
  contact_ms: millisecondsSchema,
  contact_sigma_ms: millisecondsSchema,
  ladder_us: z.array(microsecondsSchema).min(1).max(8),
});

export const challengeRoundResultSchema = strictObject({
  hit_event_id: eventIdSchema,
  pick: zoneIdSchema.nullable(),
  reaction_ms: millisecondsSchema.nullable(),
  verdict: challengeVerdictSchema,
  points: nonNegativeInteger.max(1000),
});

const challengeDisplayNameSchema = z.string().trim().min(1).max(24);
const challengeScoreSchema = nonNegativeInteger.max(100000);
const challengeRoundsSchema = z.array(challengeRoundSchema).min(3).max(50);
const challengeRoundResultsSchema = z.array(challengeRoundResultSchema).min(1).max(50);

// POST /v1/challenges (Idempotency-Key). rematch_of: a new challenge on the same match and rounds
// by someone already in the old one; only they can accept it.
export const challengeCreateRequestSchema = strictObject({
  match_id: matchIdSchema,
  display_name: challengeDisplayNameSchema.optional(),
  rounds: challengeRoundsSchema,
  score: challengeScoreSchema,
  round_results: challengeRoundResultsSchema,
  rematch_of: challengeIdSchema.optional(),
});

// POST /v1/challenges/{id}/accept
export const challengeAcceptRequestSchema = strictObject({
  display_name: challengeDisplayNameSchema.optional(),
});

// POST /v1/challenges/{id}/entries: the accepting player's own run of the same rounds.
export const challengeEntryRequestSchema = strictObject({
  display_name: challengeDisplayNameSchema.optional(),
  score: challengeScoreSchema,
  round_results: challengeRoundResultsSchema,
});

const challengePlayerSchema = strictObject({
  player_id: z.uuid(),
  display_name: z.string(),
  score: nonNegativeInteger,
  round_results: z.array(challengeRoundResultSchema),
});

// score and round_results are null until that player has played (accepted, not played yet).
// outcome is from the creator's side, null until played: creator won, opponent won, or tie.
export const challengeEntrySchema = strictObject({
  player_id: z.uuid(),
  display_name: z.string(),
  score: nonNegativeInteger.nullable(),
  round_results: z.array(challengeRoundResultSchema).nullable(),
  outcome: z.enum(["creator", "opponent", "tie"]).nullable(),
  accepted_at: isoTimestampSchema,
  played_at: isoTimestampSchema.nullable(),
});

// GET /v1/challenges/{id} and the answer of create, accept and entries. The creator sees every
// entry; an opponent sees only their own. video_sha256 names the file that has the frames.
export const challengeSchema = strictObject({
  id: challengeIdSchema,
  match_id: matchIdSchema,
  status: z.enum(["open", "played"]),
  video_sha256: sha256Schema,
  rematch_of: challengeIdSchema.nullable(),
  rounds: challengeRoundsSchema,
  creator: challengePlayerSchema,
  entries: z.array(challengeEntrySchema),
  viewer: strictObject({ player_id: z.uuid(), role: z.enum(["creator", "opponent"]) }),
  created_at: isoTimestampSchema,
});

// GET /v1/friends: people you have exchanged challenges with, with the head-to-head record. A
// challenge counts in the record once both scores exist.
export const friendSchema = strictObject({
  player_id: z.uuid(),
  display_name: z.string(),
  challenges: nonNegativeInteger,
  played: nonNegativeInteger,
  wins: nonNegativeInteger,
  losses: nonNegativeInteger,
  ties: nonNegativeInteger,
  last_challenge_at: isoTimestampSchema,
});
export const friendsResponseSchema = strictObject({ items: z.array(friendSchema) });

const postOwnerSchema = strictObject({
  player_id: z.uuid(),
  display_name: z.string().nullable(),
});

export const postCreateRequestSchema = strictObject({
  caption: z.string().max(140).optional(),
});

export const postSummarySchema = strictObject({
  title: z.string().nullable(),
  duration_ms: millisecondsSchema.nullable(),
  rally_count: nonNegativeInteger.nullable(),
  hit_count: nonNegativeInteger.nullable(),
  landings_in: nonNegativeInteger.nullable(),
  landings_out: nonNegativeInteger.nullable(),
  longest_rally_ms: millisecondsSchema.nullable(),
  top_coach_points: z.array(z.string()).max(3),
  landing_points_m: z.array(strictObject({ x_m: z.number(), y_m: z.number() })),
  owner_display_name: z.string().nullable(),
});

export const postV1Schema = strictObject({
  post_id: postIdSchema,
  match_id: matchIdSchema,
  owner: postOwnerSchema,
  created_at: isoTimestampSchema,
  caption: z.string().max(140).nullable(),
  summary: postSummarySchema,
});

export const feedPageSchema = strictObject({
  items: z.array(postV1Schema),
  next_cursor: nextCursorSchema,
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
export type ChallengeCreateRequest = z.infer<typeof challengeCreateRequestSchema>;
export type ChallengeEntryRequest = z.infer<typeof challengeEntryRequestSchema>;
export type Challenge = z.infer<typeof challengeSchema>;
export type FriendsResponse = z.infer<typeof friendsResponseSchema>;
export type PostCreateRequest = z.infer<typeof postCreateRequestSchema>;
export type PostSummary = z.infer<typeof postSummarySchema>;
export type PostV1 = z.infer<typeof postV1Schema>;
export type FeedPage = z.infer<typeof feedPageSchema>;
