import { createRoute, type OpenAPIHono, z } from "@hono/zod-openapi";
import type { SupabaseClient } from "@supabase/supabase-js";
import type { AppEnv } from "../lib/auth.ts";
import { ApiFailure } from "../lib/errors.ts";
import { appendEvent, listEvents } from "../lib/events.ts";
import { canonicalHash, idemBegin, idemStore } from "../lib/idempotency.ts";
import { ModalUnavailable, submitToModal } from "../lib/modal.ts";
import { ANALYSIS_STATES, isTerminal } from "../lib/state.ts";

const Id = (p: string) =>
  z.string().regex(new RegExp(`^${p}_[A-Za-z0-9_-]{1,64}$`));
const Range = z.tuple([z.number(), z.number()]);
const ENDPOINT = "POST /v1/analyses";

const CreateBody = z.object({ session_id: Id("s") });
const CreateResponse = z.object({
  analysis_id: Id("a"),
  state: z.enum(ANALYSIS_STATES),
});
const GetResponse = z.object({
  analysis_id: Id("a"),
  session_id: z.string(),
  match_id: z.string(),
  state: z.enum(ANALYSIS_STATES),
  engine_version: z.string().nullable(),
  last_seq: z.number().int(),
  failure_reason: z.string().nullable(),
  created_at: z.string(),
  updated_at: z.string(),
  finalized_at: z.string().nullable(),
});
export const EventWireSchema = z.object({
  event_id: z.string(),
  analysis_id: z.string(),
  match_id: z.string(),
  seq: z.number().int(),
  schema_version: z.number().int(),
  type: z.string(),
  window_seq: z.number().int().nullable(),
  decode_window_us: Range.nullable(),
  emit_range_us: Range.nullable(),
  artifact_id: z.string().nullable(),
  reason: z.string().nullable(),
  engine_version: z.string().nullable(),
  created_at: z.string(),
});
const EventsResponse = z.object({
  events: z.array(EventWireSchema),
  next_after_seq: z.number().int(),
  has_more: z.boolean(),
  state: z.enum(ANALYSIS_STATES),
});

const createRun = createRoute({
  method: "post",
  path: "/v1/analyses",
  request: {
    headers: z.object({ "idempotency-key": z.string().min(1).max(255) }),
    body: { content: { "application/json": { schema: CreateBody } } },
  },
  responses: {
    200: {
      description: "existing or terminal analysis, nothing dispatched",
      content: { "application/json": { schema: CreateResponse } },
    },
    202: {
      description: "analysis dispatched to the engine",
      content: { "application/json": { schema: CreateResponse } },
    },
  },
});

const getRun = createRoute({
  method: "get",
  path: "/v1/analyses/{id}",
  description: "Response shape is proposed, pending Person 4 (contracts).",
  request: { params: z.object({ id: Id("a") }) },
  responses: {
    200: {
      description: "analysis",
      content: { "application/json": { schema: GetResponse } },
    },
  },
});

const getEvents = createRoute({
  method: "get",
  path: "/v1/analyses/{id}/events",
  request: {
    params: z.object({ id: Id("a") }),
    query: z.object({
      after_seq: z.coerce.number().int().min(0).default(0),
      limit: z.coerce.number().int().min(1).max(500).default(100),
    }),
  },
  responses: {
    200: {
      description: "events after the cursor",
      content: { "application/json": { schema: EventsResponse } },
    },
  },
});

type RunRow = { id: string; state: string };

async function dispatch(
  admin: SupabaseClient,
  run: RunRow,
  sessionId: string,
  r2Key: string,
  modal: Parameters<typeof submitToModal>[1],
): Promise<string | null> {
  const spec = await admin.rpc("session_spec", { p_session_id: sessionId });
  if (spec.error) throw spec.error;
  try {
    const out = await submitToModal({
      analysis_id: run.id,
      r2_key: r2Key,
      session_spec: spec.data as Record<string, unknown>,
    }, modal);
    return out.modal_call_id;
  } catch (err) {
    if (!(err instanceof ModalUnavailable)) throw err;
    return null;
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
        "upload_not_complete",
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
          "idempotency_conflict",
          "a conflicting analysis run exists",
        );
      }
      throw created.error;
    }
    const run = created.data as RunRow;
    const state = run.state as z.infer<typeof CreateResponse>["state"];
    if (isTerminal(run.state)) {
      return c.json({ analysis_id: run.id, state }, 200);
    }

    await appendEvent(admin, run.id, "analysis.queued");
    const claim = await admin.rpc("claim_dispatch", { p_analysis_id: run.id });
    if (claim.error) throw claim.error;
    const claimed = claim.data as RunRow | null;
    if (!claimed?.id) {
      const cur = await admin.from("analysis_runs").select("id,state")
        .eq("id", run.id).maybeSingle();
      if (cur.error) throw cur.error;
      const s =
        ((cur.data as RunRow | null)?.state ?? "queued") as typeof state;
      return c.json({ analysis_id: run.id, state: s }, 200);
    }

    const callId = await dispatch(
      admin,
      run,
      session_id,
      up.r2_key,
      deps.modal(),
    );
    if (callId === null) {
      await appendEvent(admin, run.id, "analysis.failed", {
        reason: "dispatch_failed",
      });
      const rel = await admin.from("analysis_runs")
        .update({ dispatching: false, updated_at: new Date().toISOString() })
        .eq("id", run.id);
      if (rel.error) console.error("dispatch release failed", rel.error.code);
      throw new ApiFailure(502, "engine_unavailable", "engine is unavailable");
    }
    const upd = await admin.from("analysis_runs").update({
      modal_call_id: callId,
      dispatching: false,
      updated_at: new Date().toISOString(),
    }).eq("id", run.id);
    if (upd.error) console.error("modal_call_id store failed", upd.error.code);
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
    const res = await c.var.deps.user(jwt).from("analysis_runs").select(
      "id,session_id,match_id,state,engine_version,last_seq,failure_reason,created_at,updated_at,finalized_at",
    ).eq("id", id).maybeSingle();
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
