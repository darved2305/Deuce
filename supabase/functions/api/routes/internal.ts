import { createRoute, type OpenAPIHono, z } from "@hono/zod-openapi";
import type { AppEnv } from "../lib/auth.ts";
import { ApiFailure } from "../lib/errors.ts";
import {
  appendEvent,
  fieldsToRpc,
  type InternalEventBody,
  rowToWire,
} from "../lib/events.ts";
import { MODAL_EVENT_TYPES } from "../lib/state.ts";
import { EventWireSchema } from "./analyses.ts";

const Range = z.tuple([
  z.number().int().nonnegative(),
  z.number().int().nonnegative(),
]).refine(([a, b]) => b >= a);

const EventBody = z.object({
  type: z.enum(MODAL_EVENT_TYPES),
  window_seq: z.number().int().nonnegative().optional(),
  decode_window_us: Range.optional(),
  emit_range_us: Range.optional(),
  artifact_id: z.string().optional(),
  reason: z.string().max(500).optional(),
  engine_version: z.string().max(64).optional(),
}).superRefine((b, ctx) => {
  if (
    (b.type === "window.completed" || b.type === "window.failed") &&
    b.window_seq === undefined
  ) {
    ctx.addIssue({
      code: "custom",
      path: ["window_seq"],
      message: "window_seq is required for window events",
    });
  }
});

const ArtifactBody = z.object({
  kind: z.enum([
    "window_result",
    "ball_track",
    "player_tracks",
    "match_vision",
    "clip",
    "poster",
  ]),
  r2_key: z.string().min(1),
  bytes: z.number().int().nonnegative(),
  sha256: z.string().regex(/^[0-9a-f]{64}$/),
});

const Params = z.object({ id: z.string().regex(/^a_[A-Za-z0-9_-]{1,64}$/) });

const postEvent = createRoute({
  method: "post",
  path: "/internal/analyses/{id}/events",
  request: {
    params: Params,
    body: { content: { "application/json": { schema: EventBody } } },
  },
  responses: {
    200: {
      description: "appended (or deduplicated) event",
      content: { "application/json": { schema: EventWireSchema } },
    },
  },
});

const postArtifact = createRoute({
  method: "post",
  path: "/internal/analyses/{id}/artifacts",
  request: {
    params: Params,
    body: { content: { "application/json": { schema: ArtifactBody } } },
  },
  responses: {
    200: {
      description: "artifact registered",
      content: {
        "application/json": { schema: z.object({ artifact_id: z.string() }) },
      },
    },
  },
});

type ArtifactRow = { id: string; analysis_id: string | null };

export function registerInternal(api: OpenAPIHono<AppEnv>): void {
  api.openapi(postEvent, async (c) => {
    const { id } = c.req.valid("param");
    const body = c.req.valid("json");
    const row = await appendEvent(
      c.var.deps.admin(),
      id,
      body.type,
      fieldsToRpc(body as InternalEventBody),
    );
    return c.json(EventWireSchema.parse(rowToWire(row)), 200);
  });

  api.openapi(postArtifact, async (c) => {
    const { id } = c.req.valid("param");
    const body = c.req.valid("json");
    const admin = c.var.deps.admin();
    const run = await admin.from("analysis_runs").select("id,match_id,owner_id")
      .eq("id", id).maybeSingle();
    if (run.error) throw run.error;
    if (!run.data) throw new ApiFailure(404, "not_found", "analysis not found");
    const r = run.data as { id: string; match_id: string; owner_id: string };
    if (
      !body.r2_key.startsWith(`analysis/${id}/`) &&
      !body.r2_key.startsWith(`clips/${r.match_id}/`)
    ) {
      throw new ApiFailure(
        422,
        "validation_failed",
        "r2_key outside allowed prefix",
      );
    }
    const existing = async (): Promise<ArtifactRow | null> => {
      const res = await admin.from("artifacts").select("id,analysis_id")
        .eq("r2_key", body.r2_key).maybeSingle();
      if (res.error) throw res.error;
      return res.data as ArtifactRow | null;
    };
    const reuse = (row: ArtifactRow) => {
      if (row.analysis_id !== id) {
        throw new ApiFailure(
          422,
          "validation_failed",
          "r2_key already registered",
        );
      }
      return c.json({ artifact_id: row.id }, 200);
    };
    const found = await existing();
    if (found) return reuse(found);
    const ins = await admin.from("artifacts").insert({
      analysis_id: id,
      match_id: r.match_id,
      owner_id: r.owner_id,
      kind: body.kind,
      r2_key: body.r2_key,
      bytes: body.bytes,
      sha256: body.sha256,
    }).select("id,analysis_id").single();
    if (ins.error) {
      if (ins.error.code !== "23505") throw ins.error;
      const again = await existing();
      if (!again) throw ins.error;
      return reuse(again);
    }
    return c.json({ artifact_id: (ins.data as ArtifactRow).id }, 200);
  });
}
