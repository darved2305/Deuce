import { createRoute, type OpenAPIHono, z } from "@hono/zod-openapi";
import type { SupabaseClient } from "@supabase/supabase-js";
import type { AppEnv } from "../lib/auth.ts";
import {
  errorEnvelopeSchema,
  matchIdSchema,
  matchV1Schema,
  r2ObjectKeySchema,
} from "../lib/contracts_vendor/index.ts";
import { ApiFailure } from "../lib/errors.ts";
import type { R2 } from "../lib/r2.ts";

const getMatch = createRoute({
  method: "get",
  path: "/v1/matches/{id}",
  request: { params: z.object({ id: matchIdSchema }) },
  responses: {
    200: {
      description: "match.v1",
      content: { "application/json": { schema: matchV1Schema } },
    },
    404: {
      description: "match not visible",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
    409: {
      description: "analysis not complete",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
    500: {
      description: "stored match failed contract validation",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
  },
});

type SessionRow = { id: string };
type UploadRow = { session_id: string; r2_key: string };
type ArtifactKeyRow = {
  id: string;
  analysis_id: string | null;
  r2_key: string;
};

const QUERY_PAGE_SIZE = 500;
const UPLOAD_SESSION_BATCH_SIZE = 100;
const R2_DELETE_BATCH_SIZE = 8;

const deleteMatch = createRoute({
  method: "delete",
  path: "/v1/matches/{id}",
  request: { params: z.object({ id: matchIdSchema }) },
  responses: {
    202: {
      description:
        "Match deletion accepted; R2 objects are removed before cascading the match row.",
    },
    404: {
      description: "match not owned by the caller",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
    500: {
      description: "match deletion could not be scheduled",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
  },
});

async function matchR2Keys(
  admin: SupabaseClient,
  matchId: string,
): Promise<string[]> {
  const sessionIds: string[] = [];
  let sessionCursor: string | undefined;
  while (true) {
    let query = admin.from("sessions").select("id").eq("match_id", matchId);
    if (sessionCursor) query = query.gt("id", sessionCursor);
    const result = await query.order("id").limit(QUERY_PAGE_SIZE);
    if (result.error) throw result.error;
    const page = (result.data ?? []) as SessionRow[];
    sessionIds.push(...page.map((row) => row.id));
    if (page.length < QUERY_PAGE_SIZE) break;
    sessionCursor = page[page.length - 1].id;
  }

  const sessionIdSet = new Set(sessionIds);
  const keys = new Set<string>();
  for (let i = 0; i < sessionIds.length; i += UPLOAD_SESSION_BATCH_SIZE) {
    const result = await admin.from("uploads").select("session_id,r2_key").in(
      "session_id",
      sessionIds.slice(i, i + UPLOAD_SESSION_BATCH_SIZE),
    );
    if (result.error) throw result.error;
    for (const upload of (result.data ?? []) as UploadRow[]) {
      if (
        !sessionIdSet.has(upload.session_id) ||
        upload.r2_key !== `orig/${upload.session_id}/source.mp4` ||
        !r2ObjectKeySchema.safeParse(upload.r2_key).success
      ) {
        throw new Error("stored upload key is invalid");
      }
      keys.add(upload.r2_key);
    }
  }

  let artifactCursor: string | undefined;
  while (true) {
    let query = admin.from("artifacts").select("id,analysis_id,r2_key").eq(
      "match_id",
      matchId,
    );
    if (artifactCursor) query = query.gt("id", artifactCursor);
    const result = await query.order("id").limit(QUERY_PAGE_SIZE);
    if (result.error) throw result.error;
    const page = (result.data ?? []) as ArtifactKeyRow[];
    for (const artifact of page) {
      const { analysis_id, r2_key } = artifact;
      if (!r2ObjectKeySchema.safeParse(r2_key).success) {
        throw new Error("stored artifact key is invalid");
      }
      if (r2_key.startsWith("analysis/")) {
        if (!analysis_id || !r2_key.startsWith(`analysis/${analysis_id}/`)) {
          throw new Error("stored analysis key does not belong to match");
        }
      } else if (!r2_key.startsWith(`clips/${matchId}/`)) {
        throw new Error("stored clip key does not belong to match");
      }
      keys.add(r2_key);
    }
    if (page.length < QUERY_PAGE_SIZE) break;
    artifactCursor = page[page.length - 1].id;
  }
  return [...keys];
}

async function deleteMatchData(
  admin: SupabaseClient,
  r2: R2,
  matchId: string,
  ownerId: string,
  keys: string[],
): Promise<void> {
  for (let i = 0; i < keys.length; i += R2_DELETE_BATCH_SIZE) {
    await Promise.all(
      keys.slice(i, i + R2_DELETE_BATCH_SIZE).map((key) =>
        r2.deleteObject(key)
      ),
    );
  }
  const deleted = await admin.from("matches").delete().eq("id", matchId).eq(
    "owner_id",
    ownerId,
  );
  if (deleted.error) throw deleted.error;
}

export function registerMatches(api: OpenAPIHono<AppEnv>): void {
  api.openapi(getMatch, async (c) => {
    const { id } = c.req.valid("param");
    const jwt = (c.req.header("Authorization") ?? "").slice(
      "Bearer ".length,
    );
    const user = c.var.deps.user(jwt);
    const result = await user.rpc("get_match_v1", { p_match_id: id });
    if (result.error) throw result.error;
    if (result.data == null) {
      const visible = await user.from("matches").select("id").eq("id", id)
        .maybeSingle();
      if (visible.error) throw visible.error;
      if (!visible.data) {
        throw new ApiFailure(404, "not_found", "match not found");
      }
      throw new ApiFailure(409, "conflict", "analysis not complete");
    }
    const parsed = matchV1Schema.safeParse(result.data);
    if (!parsed.success) {
      console.error(
        "get_match_v1 returned invalid match.v1",
        parsed.error.issues,
      );
      throw new ApiFailure(
        500,
        "internal",
        "match response failed schema validation",
      );
    }
    if (parsed.data.analysis.state !== "completed") {
      throw new ApiFailure(409, "conflict", "analysis not complete");
    }
    return c.json(parsed.data, 200);
  });
  api.openapi(deleteMatch, async (c) => {
    const { id } = c.req.valid("param");
    const jwt = (c.req.header("Authorization") ?? "").slice(
      "Bearer ".length,
    );
    const ownerId = c.var.userId;
    const deps = c.var.deps;
    const owned = await deps.user(jwt).from("matches").select("id").eq(
      "id",
      id,
    ).eq("owner_id", ownerId).maybeSingle();
    if (owned.error) throw owned.error;
    if (!owned.data) {
      throw new ApiFailure(404, "not_found", "match not found");
    }
    const admin = deps.admin();
    const keys = await matchR2Keys(admin, id);
    const cleanup = deleteMatchData(admin, deps.r2(), id, ownerId, keys).catch(
      (error: unknown) => {
        console.error(
          "match deletion failed",
          error instanceof Error ? error.name : "unknown",
        );
      },
    );
    deps.waitUntil(cleanup);
    return c.body(null, 202);
  });
}
