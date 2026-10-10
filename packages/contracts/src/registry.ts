import * as z from "zod";
import {
  analysisCreateRequestSchema,
  analysisCreateResponseSchema,
  analysisEventSchema,
  analysisEventsPageSchema,
  analysisFinalizeRequestSchema,
  analysisGetResponseSchema,
  comparisonsResponseSchema,
  confirmedPointSchema,
  errorEnvelopeSchema,
  healthResponseSchema,
  internalArtifactRequestSchema,
  internalArtifactResponseSchema,
  internalEventRequestSchema,
  internalSegmentsPageSchema,
  matchV1Schema,
  modalSubmitRequestSchema,
  opponentScoutResponseSchema,
  realtimeHintSchema,
  reviewConfirmRequestSchema,
  segmentCompleteResponseSchema,
  segmentManifestSchema,
  segmentRegisterRequestSchema,
  segmentRegisterResponseSchema,
  sessionCreateRequestSchema,
  sessionCreateResponseSchema,
  sessionGetResponseSchema,
  simulationRunRequestSchema,
  simulationRunResponseSchema,
  uploadAbortResponseSchema,
  uploadCompleteRequestSchema,
  uploadCompleteResponseSchema,
  uploadPartsRequestSchema,
  uploadPartsResponseSchema,
  visionResultSchema,
} from "./api.ts";
import { trailLabelsSchema } from "./fixtures.ts";
import { modelDescriptorSchema } from "./model.ts";
import { rulesStateSchema, sportPackSchema, sportZoneSchema } from "./sport.ts";
import { windowResultSchema } from "./window_result.ts";

export type SchemaRegistryEntry = {
  schema: z.ZodType;
  version: string;
  file: string;
  description: string;
};

function entry(
  name: string,
  schema: z.ZodType,
  description: string,
): SchemaRegistryEntry {
  return {
    schema,
    version: "1.5.0",
    file: `${name}.schema.json`,
    description,
  };
}

export const schemaRegistry = {
  "error.envelope.v1": entry(
    "error.envelope.v1",
    errorEnvelopeSchema,
    "Public and internal API error envelope.",
  ),
  "session.create.request.v1": entry(
    "session.create.request.v1",
    sessionCreateRequestSchema,
    "Request body for creating a session.",
  ),
  "session.create.response.v1": entry(
    "session.create.response.v1",
    sessionCreateResponseSchema,
    "Response body for creating a session.",
  ),
  "upload.parts.request.v1": entry(
    "upload.parts.request.v1",
    uploadPartsRequestSchema,
    "Request body for requesting multipart upload URLs.",
  ),
  "upload.parts.response.v1": entry(
    "upload.parts.response.v1",
    uploadPartsResponseSchema,
    "Response body containing multipart upload URLs.",
  ),
  "upload.complete.request.v1": entry(
    "upload.complete.request.v1",
    uploadCompleteRequestSchema,
    "Request body for completing multipart upload.",
  ),
  "segment.manifest.v1": entry(
    "segment.manifest.v1",
    segmentManifestSchema,
    "Manifest row for a remuxed upload segment.",
  ),
  "analysis.create.request.v1": entry(
    "analysis.create.request.v1",
    analysisCreateRequestSchema,
    "Request body for creating an analysis.",
  ),
  "analysis.create.response.v1": entry(
    "analysis.create.response.v1",
    analysisCreateResponseSchema,
    "Response body for creating an analysis.",
  ),
  "modal.submit.request.v1": entry(
    "modal.submit.request.v1",
    modalSubmitRequestSchema,
    "Request body sent from Person 3 to the Modal submit endpoint.",
  ),
  "internal.event.request.v1": entry(
    "internal.event.request.v1",
    internalEventRequestSchema,
    "Request body for appending an internal analysis event.",
  ),
  "internal.artifact.request.v1": entry(
    "internal.artifact.request.v1",
    internalArtifactRequestSchema,
    "Request body for registering an artifact.",
  ),
  "internal.artifact.response.v1": entry(
    "internal.artifact.response.v1",
    internalArtifactResponseSchema,
    "Response body after registering an artifact.",
  ),
  "analysis.finalize.request.v1": entry(
    "analysis.finalize.request.v1",
    analysisFinalizeRequestSchema,
    "Request body for finalizing an analysis.",
  ),
  "analysis.event.v1": entry(
    "analysis.event.v1",
    analysisEventSchema,
    "Persisted analysis event row.",
  ),
  "realtime.hint.v1": entry(
    "realtime.hint.v1",
    realtimeHintSchema,
    "Realtime sequence hint payload.",
  ),
  "analysis.events.page.v1": entry(
    "analysis.events.page.v1",
    analysisEventsPageSchema,
    "Paginated event response for an analysis.",
  ),
  "window_result.v1": entry(
    "window_result.v1",
    windowResultSchema,
    "Model output for one analysis window.",
  ),
  "model.descriptor.v1": entry(
    "model.descriptor.v1",
    modelDescriptorSchema,
    "Descriptor for a model artifact or capability.",
  ),
  "match.v1": entry(
    "match.v1",
    matchV1Schema,
    "Canonical match record served to clients.",
  ),
  "vision_result.v1": entry(
    "vision_result.v1",
    visionResultSchema,
    "Vision portion of match.v1 without insights.",
  ),
  "review.confirm.request.v1": entry(
    "review.confirm.request.v1",
    reviewConfirmRequestSchema,
    "Request body for confirming a review winner.",
  ),
  "confirmed.point.v1": entry(
    "confirmed.point.v1",
    confirmedPointSchema,
    "Confirmed point returned after review confirmation.",
  ),
  "comparisons.response.v1": entry(
    "comparisons.response.v1",
    comparisonsResponseSchema,
    "Response body for shot comparisons.",
  ),
  "simulation.run.request.v1": entry(
    "simulation.run.request.v1",
    simulationRunRequestSchema,
    "Request body for a simulation run.",
  ),
  "simulation.run.response.v1": entry(
    "simulation.run.response.v1",
    simulationRunResponseSchema,
    "Response body for a simulation run.",
  ),
  "opponent.scout.response.v1": entry(
    "opponent.scout.response.v1",
    opponentScoutResponseSchema,
    "Opponent scout tiles response.",
  ),
  "sport.pack.v1": entry(
    "sport.pack.v1",
    sportPackSchema,
    "Sport configuration pack.",
  ),
  "sport.zone.v1": entry(
    "sport.zone.v1",
    sportZoneSchema,
    "Named court zone identifier.",
  ),
  "rules.state.v1": entry(
    "rules.state.v1",
    rulesStateSchema,
    "Rules package state.",
  ),
  "health.response.v1": entry(
    "health.response.v1",
    healthResponseSchema,
    "GET /v1/health response.",
  ),
  "session.get.response.v1": entry(
    "session.get.response.v1",
    sessionGetResponseSchema,
    "GET /v1/sessions/{id} response.",
  ),
  "upload.complete.response.v1": entry(
    "upload.complete.response.v1",
    uploadCompleteResponseSchema,
    "POST /v1/sessions/{id}/upload/complete response.",
  ),
  "upload.abort.response.v1": entry(
    "upload.abort.response.v1",
    uploadAbortResponseSchema,
    "POST /v1/sessions/{id}/upload/abort response.",
  ),
  "analysis.get.response.v1": entry(
    "analysis.get.response.v1",
    analysisGetResponseSchema,
    "GET /v1/analyses/{id} response.",
  ),
  "segment.register.request.v1": entry(
    "segment.register.request.v1",
    segmentRegisterRequestSchema,
    "POST /v1/sessions/{id}/segments request (chunked upload).",
  ),
  "segment.register.response.v1": entry(
    "segment.register.response.v1",
    segmentRegisterResponseSchema,
    "POST /v1/sessions/{id}/segments response: the segment's PUT URL.",
  ),
  "segment.complete.response.v1": entry(
    "segment.complete.response.v1",
    segmentCompleteResponseSchema,
    "POST /v1/sessions/{id}/segments/{seq}/complete response.",
  ),
  "internal.segments.page.v1": entry(
    "internal.segments.page.v1",
    internalSegmentsPageSchema,
    "GET /internal/analyses/{id}/segments?after_seq= response for the engine.",
  ),
  "elevate.trail_labels.v1": entry(
    "elevate.trail_labels.v1",
    trailLabelsSchema,
    "fixture_trail.json: hand-labelled shuttle points per decoded frame (contracts §11).",
  ),
} satisfies Record<string, SchemaRegistryEntry>;

export type ContractName = keyof typeof schemaRegistry;
