import { addUsage } from "../lib/budget.ts";
import { createRoute, OpenAPIHono, z } from "@hono/zod-openapi";
import type { AppEnv } from "../lib/auth.ts";
import {
  internalArtifactRequestSchema,
  internalArtifactResponseSchema,
  internalEventRequestSchema,
} from "../lib/contracts_vendor/index.ts";
import { ApiFailure, defaultHook } from "../lib/errors.ts";
import { appendEvent, fieldsToRpc, rowToWire } from "../lib/events.ts";
import { EventWireSchema } from "./analyses.ts";

// Contract shape plus the review rule: window events must carry a window_seq,
// otherwise append_event would dedupe every windowless event together.
const EventBody = internalEventRequestSchema.superRefine((b, ctx) => {
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

// artifacts.kind is a DB check constraint; the contract types it as a plain string.
const ARTIFACT_KINDS = new Set([
  "window_result",
  "ball_track",
  "player_tracks",
  "match_vision",
  "clip",
  "poster",
]);

const Params = z.object({ id: z.string().regex(/^a_[A-Za-z0-9_-]+$/) });

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
    body: {
      content: {
        "application/json": { schema: internalArtifactRequestSchema },
      },
    },
  },
  responses: {
    200: {
      description: "artifact registered",
      content: {
        "application/json": { schema: internalArtifactResponseSchema },
      },
    },
  },
});

type ArtifactRow = { id: string; analysis_id: string | null };

// Internal routes live on their own OpenAPIHono so they stay out of the public
// /v1/openapi.json document.
export function registerInternal(api: OpenAPIHono<AppEnv>): void {
  const internal = new OpenAPIHono<AppEnv>({ defaultHook });

  internal.openapi(postEvent, async (c) => {
    const { id } = c.req.valid("param");
    const body = c.req.valid("json");
    const row = await appendEvent(
      c.var.deps.admin(),
      id,
      body.type,
      fieldsToRpc(body),
    );
    return c.json(EventWireSchema.parse(rowToWire(row)), 200);
  });

  internal.openapi(postArtifact, async (c) => {
    const { id } = c.req.valid("param");
    const body = c.req.valid("json");
    if (!ARTIFACT_KINDS.has(body.kind)) {
      throw new ApiFailure(422, "validation_failed", "unknown artifact kind");
    }
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
      sha256: body.sha256.toLowerCase(),
    }).select("id,analysis_id").single();
    if (ins.error) {
      if (ins.error.code !== "23505") throw ins.error;
      const again = await existing();
      if (!again) throw ins.error;
      return reuse(again);
    }
    await addUsage(admin, 1, 0); // the engine's PutObject for this artifact is Class A (G5)
    return c.json({ artifact_id: (ins.data as ArtifactRow).id }, 200);
  });

  api.route("/", internal);
}
