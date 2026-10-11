import { createRoute, type OpenAPIHono, z } from "@hono/zod-openapi";
import type { SupabaseClient } from "@supabase/supabase-js";
import type { AppEnv } from "../lib/auth.ts";
import {
  analysisCreateRequestSchema,
  analysisCreateResponseSchema,
  analysisEventsPageSchema,
  analysisGetResponseSchema,
  analysisIdSchema,
  errorEnvelopeSchema,
} from "../lib/contracts_vendor/index.ts";
import { ApiFailure } from "../lib/errors.ts";
import { appendEvent, listEvents } from "../lib/events.ts";
import { canonicalHash, idemBegin, idemStore } from "../lib/idempotency.ts";
import {
  ModalRejected,
  ModalUnavailable,
  submitToModal,
} from "../lib/modal.ts";
import { isTerminal } from "../lib/state.ts";

const ENDPOINT = "POST /v1/analyses";
const CreateBody = analysisCreateRequestSchema;
const CreateResponse = analysisCreateResponseSchema;
const AnalysisId = analysisIdSchema;
const GetResponse = analysisGetResponseSchema;
const EventsResponse = analysisEventsPageSchema;

const createRun = createRoute({
  method: "post",
  path: "/v1/analyses",
  request: {
    headers: z.object({ "idempotency-key": z.string().min(1).max(255) }),
    body: { content: { "application/json": { schema: CreateBody } } },
  },
  responses: {
    200: {
      description: "existing run past queued or terminal; nothing dispatched",
      content: { "application/json": { schema: CreateResponse } },
    },
    202: {
      description: "new dispatch or an existing run still queued",
      content: { "application/json": { schema: CreateResponse } },
    },
    403: {
      description: "read-only developer keys cannot dispatch analyses",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
    429: {
      description: "session has reached its analysis run limit",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
    503: {
      description: "Modal rejected the dispatch",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
  },
});

const getRun = createRoute({
  method: "get",
  path: "/v1/analyses/{id}",
  request: { params: z.object({ id: AnalysisId }) },
  responses: {
    200: {
      description: "analysis",
      content: { "application/json": { schema: analysisGetResponseSchema } },
    },
  },
});

const getEvents = createRoute({
  method: "get",
  path: "/v1/analyses/{id}/events",
  request: {
    params: z.object({ id: AnalysisId }),
    query: z.object({
      after_seq: z.coerce.number().int().min(0).default(0),
      limit: z.coerce.number().int().min(1).max(500).default(100),
    }),
  },
  responses: {
    200: {
      description: "events after the cursor",
      content: { "application/json": { schema: analysisEventsPageSchema } },
    },
  },
});

type RunRow = { id: string; state: string; match_id?: string };

async function dispatch(
  admin: SupabaseClient,
  run: RunRow,
  sessionId: string,
  r2Key: string,
  modal: Parameters<typeof submitToModal>[1],
): Promise<
  | { kind: "submitted"; modal_call_id: string }
  | {
    kind: "deferred";
  }
  | { kind: "rejected"; modal_error_code: string }
  | { kind: "invalid" }
> {
  const spec = await admin.rpc("session_spec", { p_session_id: sessionId });
  if (spec.error) throw spec.error;
  if (spec.data == null) return { kind: "invalid" };
  try {
    const out = await submitToModal({
      analysis_id: run.id,
      r2_key: r2Key,
      session_spec: spec.data as Record<string, unknown>,
      // The run row comes from create_analysis_run (the whole analysis_runs row), so match_id is
      // the run's own match; the engine echoes it in vision_result (D-261011-HW15).
      ...(run.match_id
        ? { match_id: run.match_id, session_id: sessionId }
        : {}),
    }, modal);
    return { kind: "submitted", modal_call_id: out.modal_call_id };
  } catch (err) {
    if (err instanceof ModalRejected) {
      return { kind: "rejected", modal_error_code: err.modalErrorCode };
    }
    if (!(err instanceof ModalUnavailable)) throw err;
    return { kind: "deferred" };
  }
}

export function registerAnalyses(api: OpenAPIHono<AppEnv>): void {
  api.openapi(createRun, async (c) => {
    const deps = c.var.deps;
    const { session_id } = c.req.valid("json");
    const key = c.req.valid("header")["idempotency-key"];
    const jwt = (c.req.header("Authorization") ?? "").slice("Bearer ".length);
    const user = deps.user(jwt);
    const admin = deps.admin();
    const ownerId = c.var.userId;

    const session = await user.from("sessions").select("id")
      .eq("id", session_id).maybeSingle();
    if (session.error) throw session.error;
    if (!session.data) {
      throw new ApiFailure(404, "not_found", "session not found");
    }
    const upload = await user.from("uploads").select("r2_key,status")
      .eq("session_id", session_id).maybeSingle();
    if (upload.error) throw upload.error;
    const up = upload.data as { r2_key: string; status: string } | null;
    if (!up || up.status !== "completed") {
      throw new ApiFailure(
        409,
        "conflict",
        "upload is not complete",
      );
    }

    const hash = await canonicalHash({ session_id });
    const idem = await idemBegin(admin, key, ownerId, ENDPOINT, hash);
    if (idem.kind === "conflict") {
      throw new ApiFailure(
        409,
        "idempotency_conflict",
        "Idempotency-Key was used with a different request",
      );
    }
    if (idem.kind === "replay") {
      const body = idem.response as z.infer<typeof CreateResponse>;
      return idem.status === 202 ? c.json(body, 202) : c.json(body, 200);
    }

    const existingRuns = await admin.from("analysis_runs").select("id,state")
      .eq("session_id", session_id);
    if (existingRuns.error) throw existingRuns.error;
    const runs = existingRuns.data as { id: string; state: string }[] | null;
    if (
      (runs?.length ?? 0) >= 3 &&
      !(runs ?? []).some((existing) => !isTerminal(existing.state))
    ) {
      throw new ApiFailure(
        429,
        "analysis_limit_reached",
        "session has reached its analysis run limit",
      );
    }

    const created = await admin.rpc("create_analysis_run", {
      p_session_id: session_id,
      p_idempotency_key: key,
    });
    if (created.error) {
      if (created.error.code === "P0002") {
        throw new ApiFailure(404, "not_found", "session not found");
      }
      if (created.error.code === "P0001") {
        throw new ApiFailure(
          409,
          "analysis_run_conflict",
          "a conflicting analysis run exists",
        );
      }
      throw created.error;
    }
    const run = created.data as RunRow;
    const state = run.state as z.infer<typeof CreateResponse>["state"];
    if (isTerminal(run.state)) {
      const body = { analysis_id: run.id, state };
      try {
        await idemStore(admin, key, ownerId, ENDPOINT, hash, 200, body);
      } catch (err) {
        console.error("idempotency store failed", (err as Error).message);
      }
      return c.json(body, 200);
    }

    await appendEvent(admin, run.id, "analysis.queued");
    const claim = await admin.rpc("claim_dispatch", { p_analysis_id: run.id });
    if (claim.error) throw claim.error;
    const claimed = claim.data as RunRow | null;
    if (claimed?.state === "failed") {
      const body = { analysis_id: run.id, state: "failed" as const };
      try {
        await idemStore(admin, key, ownerId, ENDPOINT, hash, 200, body);
      } catch (err) {
        console.error("idempotency store failed", (err as Error).message);
      }
      return c.json(body, 200);
    }
    if (!claimed?.id) {
      const cur = await admin.from("analysis_runs")
        .select("id,state")
        .eq("id", run.id).maybeSingle();
      if (cur.error) throw cur.error;
      const curRow = cur.data as RunRow | null;
      const s = (curRow?.state ?? "queued") as typeof state;
      const body = { analysis_id: run.id, state: s };
      const status = s === "queued" ? 202 : 200;
      try {
        await idemStore(admin, key, ownerId, ENDPOINT, hash, status, body);
      } catch (err) {
        console.error("idempotency store failed", (err as Error).message);
      }
      return status === 202 ? c.json(body, 202) : c.json(body, 200);
    }

    const dispatchResult = await dispatch(
      admin,
      run,
      session_id,
      up.r2_key,
      deps.modal(),
    );
    if (dispatchResult.kind === "invalid") {
      const rel = await admin.from("analysis_runs")
        .update({ dispatching: false, updated_at: new Date().toISOString() })
        .eq("id", run.id);
      if (rel.error) console.error("dispatch release failed", rel.error.code);
      throw new ApiFailure(500, "internal", "session specification is missing");
    }
    if (dispatchResult.kind === "rejected") {
      await appendEvent(admin, run.id, "analysis.failed", {
        reason: "dispatch_failed",
      });
      const rel = await admin.from("analysis_runs")
        .update({ dispatching: false, updated_at: new Date().toISOString() })
        .eq("id", run.id);
      if (rel.error) console.error("dispatch release failed", rel.error.code);
      throw new ApiFailure(
        503,
        "engine_unavailable",
        "Modal rejected the analysis",
        { modal_error_code: dispatchResult.modal_error_code },
      );
    }
    if (dispatchResult.kind === "deferred") {
      const rel = await admin.from("analysis_runs")
        .update({ dispatching: false, updated_at: new Date().toISOString() })
        .eq("id", run.id);
      if (rel.error) console.error("dispatch release failed", rel.error.code);
      const body = { analysis_id: run.id, state: "queued" as const };
      try {
        await idemStore(admin, key, ownerId, ENDPOINT, hash, 202, body);
      } catch (err) {
        console.error("idempotency store failed", (err as Error).message);
      }
      return c.json(body, 202);
    }
    const upd = await admin.from("analysis_runs").update({
      modal_call_id: dispatchResult.modal_call_id,
      dispatching: false,
      updated_at: new Date().toISOString(),
    }).eq("id", run.id).select("id").maybeSingle();
    if (upd.error || !upd.data) {
      throw new ApiFailure(
        500,
        "internal",
        "could not store the dispatch result",
      );
    }
    const body = { analysis_id: run.id, state: "queued" as const };
    try {
      await idemStore(admin, key, ownerId, ENDPOINT, hash, 202, body);
    } catch (err) {
      console.error("idempotency store failed", (err as Error).message);
    }
    return c.json(body, 202);
  });

  api.openapi(getRun, async (c) => {
    const { id } = c.req.valid("param");
    const jwt = (c.req.header("Authorization") ?? "").slice("Bearer ".length);
    const columns =
      "id,session_id,match_id,state,engine_version,last_seq,failure_reason,created_at,updated_at,finalized_at";
    const res = c.var.readOnly
      ? await c.var.deps.admin().from("analysis_runs").select(columns)
        .eq("id", id).eq("owner_id", c.var.userId).maybeSingle()
      : await c.var.deps.user(jwt).from("analysis_runs").select(columns)
        .eq("id", id).maybeSingle();
    if (res.error) throw res.error;
    if (!res.data) throw new ApiFailure(404, "not_found", "analysis not found");
    const { id: analysisId, ...rest } = res.data as Record<string, unknown> & {
      id: string;
    };
    return c.json(
      GetResponse.parse({ analysis_id: analysisId, ...rest }),
      200,
    );
  });

  api.openapi(getEvents, async (c) => {
    const { id } = c.req.valid("param");
    const { after_seq, limit } = c.req.valid("query");
    const jwt = (c.req.header("Authorization") ?? "").slice("Bearer ".length);
    const out = await listEvents(c.var.deps.user(jwt), id, after_seq, limit);
    return c.json(EventsResponse.parse(out), 200);
  });
}
