import { addUsage } from "../lib/budget.ts";
import { createRoute, OpenAPIHono, z } from "@hono/zod-openapi";
import type { AppEnv } from "../lib/auth.ts";
import {
  analysisEventSchema,
  analysisFinalizeRequestSchema,
  analysisIdSchema,
  analysisStateSchema,
  internalArtifactRequestSchema,
  internalArtifactResponseSchema,
  internalEventRequestSchema,
  internalSegmentsPageSchema,
} from "../lib/contracts_vendor/index.ts";
import { ApiFailure, defaultHook } from "../lib/errors.ts";
import {
  appendEvent,
  fieldsToRpc,
  type InternalEventBody,
  rowToWire,
} from "../lib/events.ts";

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
    body: {
      content: { "application/json": { schema: internalEventRequestSchema } },
    },
  },
  responses: {
    200: {
      description: "appended (or deduplicated) event",
      content: { "application/json": { schema: analysisEventSchema } },
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

const getSegments = createRoute({
  method: "get",
  path: "/internal/analyses/{id}/segments",
  request: {
    params: Params,
    query: z.object({
      // Exclusive cursor. -1 starts before seq 0 so the engine can verify segment 0's manifest
      // (D-261011-HW33); the default stays 0 for existing callers.
      after_seq: z.coerce.number().int().min(-1).max(Number.MAX_SAFE_INTEGER)
        .default(0),
    }),
  },
  responses: {
    200: {
      description: "contiguous completed segment prefix after the cursor",
      content: { "application/json": { schema: internalSegmentsPageSchema } },
    },
  },
});

const finalizeResponseSchema = z.object({
  analysis_id: analysisIdSchema,
  state: analysisStateSchema,
  last_seq: z.number().int().nonnegative(),
}).strict();

const postFinalize = createRoute({
  method: "post",
  path: "/internal/analyses/{id}/finalize",
  request: {
    params: Params,
    body: {
      content: {
        "application/json": { schema: z.unknown() },
      },
    },
  },
  responses: {
    200: {
      description: "analysis finalized",
      content: { "application/json": { schema: finalizeResponseSchema } },
    },
  },
});

type ArtifactRow = { id: string; analysis_id: string | null };
type ClipArtifactRow = {
  id: string;
  analysis_id: string | null;
  match_id: string;
  kind: string;
  r2_key: string;
};

// Internal routes live on their own OpenAPIHono so they stay out of the public
// /v1/openapi.json document.
export function registerInternal(api: OpenAPIHono<AppEnv>): void {
  const internal = new OpenAPIHono<AppEnv>({ defaultHook });

  internal.openapi(postEvent, async (c) => {
    const { id } = c.req.valid("param");
    const body = c.req.valid("json") as InternalEventBody;
    const row = await appendEvent(
      c.var.deps.admin(),
      id,
      body.type,
      fieldsToRpc(body),
    );
    return c.json(analysisEventSchema.parse(rowToWire(row)), 200);
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

  internal.openapi(getSegments, async (c) => {
    const { id } = c.req.valid("param");
    const { after_seq } = c.req.valid("query");
    const admin = c.var.deps.admin();
    const run = await admin.from("analysis_runs").select("session_id")
      .eq("id", id).maybeSingle();
    if (run.error) throw run.error;
    if (!run.data) throw new ApiFailure(404, "not_found", "analysis not found");
    const sessionId = (run.data as { session_id: string }).session_id;
    const result = await admin.from("segments").select(
      "seq,pts_start_us,pts_end_us,starts_on_sync_sample,bytes,sha256,final,r2_key,completed_at",
    ).eq("session_id", sessionId).order("seq", { ascending: true });
    if (result.error) throw result.error;

    const rows = (result.data ?? []) as Array<{
      seq: number | string;
      pts_start_us: number | string;
      pts_end_us: number | string;
      starts_on_sync_sample: boolean;
      bytes: number | string;
      sha256: string;
      final: boolean;
      r2_key: string;
      completed_at: string | null;
    }>;
    let expected = after_seq + 1;
    const finalRow = rows.find((row) => row.final);
    const finalSeq = finalRow ? Number(finalRow.seq) : null;
    const segments = [];
    for (const row of rows) {
      const seq = Number(row.seq);
      if (seq <= after_seq) continue;
      if (seq !== expected || !row.completed_at) break;
      segments.push({
        seq,
        pts_start_us: Number(row.pts_start_us),
        pts_end_us: Number(row.pts_end_us),
        starts_on_sync_sample: row.starts_on_sync_sample,
        bytes: Number(row.bytes),
        sha256: row.sha256,
        final: row.final,
        r2_key: row.r2_key,
      });
      expected++;
    }
    return c.json(
      internalSegmentsPageSchema.parse({ segments, final_seq: finalSeq }),
      200,
    );
  });

  internal.openapi(postFinalize, async (c) => {
    const { id } = c.req.valid("param");
    const parsed = analysisFinalizeRequestSchema.safeParse(c.req.valid("json"));
    if (!parsed.success) {
      await c.var.deps.admin().rpc("append_event", {
        p_analysis_id: id,
        p_type: "analysis.failed",
        p_fields: { reason: "invalid_vision_result" },
      });
      throw new ApiFailure(
        422,
        "validation_failed",
        "request validation failed",
        parsed.error.issues,
      );
    }
    const body = parsed.data;
    const admin = c.var.deps.admin();
    const run = await admin.from("analysis_runs").select("id,match_id")
      .eq("id", id).maybeSingle();
    if (run.error) throw run.error;
    if (!run.data) throw new ApiFailure(404, "not_found", "analysis not found");
    const runRow = run.data as { id: string; match_id: string };

    const artifactRefs = new Map<
      string,
      { kind: "clip" | "poster"; key: string }
    >();
    for (const clip of body.vision_result.clips) {
      const refs = [
        { id: clip.remote.artifact_id, kind: "clip" as const, ext: "mp4" },
        {
          id: clip.remote.poster_artifact_id,
          kind: "poster" as const,
          ext: "jpg",
        },
      ];
      for (const ref of refs) {
        const key = `clips/${runRow.match_id}/${clip.clip_id}.${ref.ext}`;
        const previous = artifactRefs.get(ref.id);
        if (previous && (previous.kind !== ref.kind || previous.key !== key)) {
          throw new ApiFailure(
            422,
            "validation_failed",
            "clip artifact id is used for multiple artifacts",
          );
        }
        artifactRefs.set(ref.id, { kind: ref.kind, key });
      }
    }
    const submittedIds = new Set(body.clip_artifact_ids);
    if (
      submittedIds.size !== body.clip_artifact_ids.length ||
      submittedIds.size !== artifactRefs.size ||
      [...artifactRefs.keys()].some((artifactId) =>
        !submittedIds.has(artifactId)
      )
    ) {
      throw new ApiFailure(
        422,
        "validation_failed",
        "clip_artifact_ids do not match vision_result clips",
      );
    }
    for (const [artifactId, expected] of artifactRefs) {
      const artifact = await admin.from("artifacts").select(
        "id,analysis_id,match_id,kind,r2_key",
      ).eq("id", artifactId).maybeSingle();
      if (artifact.error) throw artifact.error;
      if (!artifact.data) {
        throw new ApiFailure(404, "not_found", "clip artifact not found");
      }
      const row = artifact.data as ClipArtifactRow;
      if (
        row.analysis_id !== id || row.match_id !== runRow.match_id ||
        row.kind !== expected.kind || row.r2_key !== expected.key
      ) {
        throw new ApiFailure(
          422,
          "validation_failed",
          "clip artifact does not match the registered clip key",
        );
      }
    }

    const ingested = await admin.rpc("ingest_vision_result", {
      p_analysis_id: id,
      p_result: body.vision_result,
    });
    if (ingested.error) {
      const message = `${ingested.error.message} ${
        ingested.error.details ?? ""
      }`;
      if (message.includes("already_finalized")) {
        throw new ApiFailure(409, "conflict", "analysis already finalized");
      }
      if (
        ingested.error.code === "P0002" ||
        message.includes("analysis_not_found")
      ) {
        throw new ApiFailure(404, "not_found", "analysis not found");
      }
      if (message.includes("invalid_vision_result")) {
        throw new ApiFailure(
          422,
          "validation_failed",
          "vision result does not match the analysis",
        );
      }
      if (message.includes("invalid_transition")) {
        throw new ApiFailure(409, "conflict", "analysis cannot be finalized");
      }
      throw ingested.error;
    }

    const persisted = await admin.from("analysis_runs").select(
      "id,state,last_seq",
    ).eq("id", id).maybeSingle();
    if (persisted.error) throw persisted.error;
    if (!persisted.data) {
      throw new ApiFailure(404, "not_found", "analysis not found");
    }
    const row = persisted.data as {
      id: string;
      state: string;
      last_seq: number;
    };
    return c.json(
      finalizeResponseSchema.parse({
        analysis_id: row.id,
        state: row.state,
        last_seq: row.last_seq,
      }),
      200,
    );
  });

  api.route("/", internal);
}
