import { createRoute, type OpenAPIHono, z } from "@hono/zod-openapi";
import type { AppEnv } from "../lib/auth.ts";
import {
  comparisonsResponseSchema,
  errorEnvelopeSchema,
  matchIdSchema,
  shotIdSchema,
} from "../lib/contracts_vendor/index.ts";
import { ApiFailure } from "../lib/errors.ts";

const getComparisons = createRoute({
  method: "get",
  path: "/v1/matches/{mid}/shots/{shot_id}/comparisons",
  request: {
    params: z.object({ mid: matchIdSchema, shot_id: shotIdSchema }),
    query: z.object({
      scope: z.enum(["own", "shared", "reference"]),
      limit: z.coerce.number().int().min(1).max(Number.MAX_SAFE_INTEGER)
        .default(3),
    }),
  },
  responses: {
    200: {
      description: "ranked, viewable shot comparisons",
      content: { "application/json": { schema: comparisonsResponseSchema } },
    },
    404: {
      description: "match or shot not found for the caller",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
  },
});

type ComparisonRow = {
  shot_id: string;
  clip_id: string;
  distance: number;
  matched_features: string[];
  role: string;
  evidence: { won: number; of: number; matches: number; rallies: number };
  provenance: string;
  attribution: unknown | null;
};

export function registerComparisons(api: OpenAPIHono<AppEnv>): void {
  api.openapi(getComparisons, async (c) => {
    const { mid, shot_id } = c.req.valid("param");
    const { scope, limit } = c.req.valid("query");
    const jwt = (c.req.header("Authorization") ?? "").slice("Bearer ".length);
    const reader = c.var.readOnly ? c.var.deps.admin() : c.var.deps.user(jwt);
    const matchQuery = reader.from("matches").select("id,owner_id")
      .eq("id", mid);
    const match = c.var.readOnly
      ? await matchQuery.eq("owner_id", c.var.userId).maybeSingle()
      : await matchQuery.maybeSingle();
    if (match.error) throw match.error;
    const matchRow = match.data as { id: string; owner_id: string } | null;
    if (!matchRow || matchRow.owner_id !== c.var.userId) {
      throw new ApiFailure(404, "not_found", "match not found");
    }

    const shotQuery = reader.from("shots").select("id")
      .eq("match_id", mid).eq("id", shot_id);
    const shot = c.var.readOnly
      ? await shotQuery.eq("owner_id", c.var.userId).maybeSingle()
      : await shotQuery.maybeSingle();
    if (shot.error) throw shot.error;
    if (!shot.data) throw new ApiFailure(404, "not_found", "shot not found");

    const result = c.var.readOnly
      ? await reader.rpc("compare_shot_for_owner", {
        p_owner: c.var.userId,
        p_match_id: mid,
        p_shot_id: shot_id,
        p_scope: scope,
        p_limit: Math.min(limit, 5),
      })
      : await reader.rpc("compare_shot", {
        p_match_id: mid,
        p_shot_id: shot_id,
        p_scope: scope,
        p_limit: Math.min(limit, 5),
      });
    if (result.error) throw result.error;
    const body = comparisonsResponseSchema.parse({
      query_shot_id: shot_id,
      candidates: ((result.data ?? []) as ComparisonRow[]).map((candidate) => ({
        shot_id: candidate.shot_id,
        clip_id: candidate.clip_id,
        distance: candidate.distance,
        matched_features: candidate.matched_features,
        role: candidate.role,
        evidence: candidate.evidence,
        provenance: candidate.provenance,
        attribution: candidate.attribution,
      })),
    });
    return c.json(body, 200);
  });
}
