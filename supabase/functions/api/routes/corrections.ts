import { createRoute, type OpenAPIHono, z } from "@hono/zod-openapi";
import type { SupabaseClient } from "@supabase/supabase-js";
import type { AppEnv } from "../lib/auth.ts";
import {
  errorEnvelopeSchema,
  matchIdSchema,
  matchV1Schema,
} from "../lib/contracts_vendor/index.ts";
import { ApiFailure } from "../lib/errors.ts";
import { canonicalHash, idemBegin, idemStore } from "../lib/idempotency.ts";

const ENDPOINT = "POST /v1/matches/{id}/corrections";
const CorrectionItem = matchV1Schema.shape.corrections.element;
const CreateBody = CorrectionItem.omit({
  correction_id: true,
  actor: true,
  at: true,
});
const IdemHeader = z.object({
  "idempotency-key": z.string().min(1).max(255),
});

type CorrectionRow = {
  id: string;
  target_type: string;
  target_id: string;
  field: string;
  old: string | null;
  new: string | null;
  reason: string;
  created_at: string;
};

const createCorrection = createRoute({
  method: "post",
  path: "/v1/matches/{id}/corrections",
  request: {
    params: z.object({ id: matchIdSchema }),
    headers: IdemHeader,
    body: { content: { "application/json": { schema: CreateBody } } },
  },
  responses: {
    201: {
      description: "correction appended with the resolved player actor",
      content: { "application/json": { schema: CorrectionItem } },
    },
    404: {
      description: "match not visible to the caller",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
    409: {
      description: "Idempotency-Key was used with a different request",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
    422: {
      description: "invalid request",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
  },
});

function storageText(value: unknown): string | null {
  if (value === null) return null;
  if (typeof value === "string") return value;
  const encoded = JSON.stringify(value);
  if (encoded === undefined) {
    throw new ApiFailure(
      422,
      "validation_failed",
      "old and new must be JSON values",
    );
  }
  return encoded;
}

async function userConfirmedPlayerId(
  user: SupabaseClient,
  matchId: string,
): Promise<string | undefined> {
  const result = await user.from("analysis_runs").select("players")
    .eq("match_id", matchId)
    .order("created_at", { ascending: false })
    .order("id", { ascending: false })
    .limit(1)
    .maybeSingle();
  if (result.error) throw result.error;

  const run = result.data as { players?: unknown } | null;
  if (!run || !Array.isArray(run.players)) return undefined;
  const confirmed = run.players.filter((value) =>
    value && typeof value === "object" && !Array.isArray(value) &&
    (value as { identity?: unknown }).identity === "user_confirmed"
  );
  if (confirmed.length !== 1) return undefined;

  const actor = CorrectionItem.shape.actor.safeParse(
    (confirmed[0] as { player_id?: unknown }).player_id,
  );
  return actor.success ? actor.data : undefined;
}

function wireCorrection(
  row: CorrectionRow,
  actor: string,
): z.infer<typeof CorrectionItem> {
  const correction = {
    correction_id: row.id,
    target: { type: row.target_type, id: row.target_id },
    field: row.field,
    old: row.old,
    new: row.new,
    actor,
    at: row.created_at,
    reason: row.reason,
  };
  return CorrectionItem.parse(correction);
}

export function registerCorrections(api: OpenAPIHono<AppEnv>): void {
  api.openapi(createCorrection, async (c) => {
    const { id: matchId } = c.req.valid("param");
    const body = c.req.valid("json");
    const key = c.req.valid("header")["idempotency-key"];
    const ownerId = c.var.userId;
    const jwt = (c.req.header("Authorization") ?? "").slice("Bearer ".length);
    const user = c.var.deps.user(jwt);

    const match = await user.from("matches").select("id,owner_id")
      .eq("id", matchId).maybeSingle();
    if (match.error) throw match.error;
    if (!match.data || match.data.owner_id !== ownerId) {
      throw new ApiFailure(404, "not_found", "match not found");
    }

    const admin = c.var.deps.admin();
    const hash = await canonicalHash({ match_id: matchId, correction: body });
    const idem = await idemBegin(admin, key, ownerId, ENDPOINT, hash);
    if (idem.kind === "conflict") {
      throw new ApiFailure(
        409,
        "idempotency_conflict",
        "Idempotency-Key was used with a different request",
      );
    }
    if (idem.kind === "replay") {
      return c.json(idem.response as z.infer<typeof CorrectionItem>, 201);
    }

    const actor = await userConfirmedPlayerId(user, matchId);
    if (actor === undefined) {
      throw new ApiFailure(409, "conflict", "who_is_me not resolved yet");
    }
    const inserted = await user.from("corrections").insert({
      match_id: matchId,
      owner_id: ownerId,
      target_type: body.target.type,
      target_id: body.target.id,
      field: body.field,
      old: storageText(body.old),
      new: storageText(body.new),
      actor: ownerId,
      reason: body.reason,
    }).select("id,target_type,target_id,field,old,new,reason,created_at")
      .single();
    if (inserted.error) throw inserted.error;

    const response = wireCorrection(inserted.data as CorrectionRow, actor);
    try {
      await idemStore(admin, key, ownerId, ENDPOINT, hash, 201, response);
    } catch (err) {
      console.error("idempotency store failed", (err as Error).message);
    }
    return c.json(response, 201);
  });
}
