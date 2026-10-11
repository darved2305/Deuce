import { createRoute, type OpenAPIHono, z } from "@hono/zod-openapi";
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
  operationId: "getMatch",
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

const R2_DELETE_BATCH_SIZE = 8;
const R2_DELETE_RETRY_MS = [250, 500] as const;

const deleteMatch = createRoute({
  method: "delete",
  path: "/v1/matches/{id}",
  operationId: "deleteMatch",
  request: { params: z.object({ id: matchIdSchema }) },
  responses: {
    202: {
      description:
        "Match rows are deleted; recorded R2 objects are removed asynchronously after commit.",
    },
    404: {
      description: "match not owned by the caller",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
    409: {
      description: "an analysis is in progress",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
    500: {
      description: "match deletion could not be completed",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
  },
});

function matchR2Keys(matchId: string, value: unknown): string[] {
  if (!Array.isArray(value)) {
    throw new ApiFailure(500, "internal", "stored match R2 keys are invalid");
  }
  const keys = new Set<string>();
  for (const key of value) {
    if (
      typeof key !== "string" || !r2ObjectKeySchema.safeParse(key).success ||
      (!key.startsWith("orig/") && !key.startsWith("analysis/") &&
        !key.startsWith(`clips/${matchId}/`))
    ) {
      throw new ApiFailure(500, "internal", "stored match R2 keys are invalid");
    }
    keys.add(key);
  }
  return [...keys];
}

async function deleteR2Key(r2: R2, key: string): Promise<void> {
  for (let attempt = 0; attempt <= R2_DELETE_RETRY_MS.length; attempt++) {
    try {
      await r2.deleteObject(key);
      return;
    } catch (error) {
      console.error("match R2 cleanup failed", {
        attempt: attempt + 1,
        error: error instanceof Error ? error.name : "unknown",
      });
      const delay = R2_DELETE_RETRY_MS[attempt];
      if (delay !== undefined) {
        await new Promise<void>((resolve) => setTimeout(resolve, delay));
      }
    }
  }
}

async function cleanupMatchR2(r2: R2, keys: string[]): Promise<void> {
  for (let i = 0; i < keys.length; i += R2_DELETE_BATCH_SIZE) {
    await Promise.all(
      keys.slice(i, i + R2_DELETE_BATCH_SIZE).map((key) =>
        deleteR2Key(r2, key)
      ),
    );
  }
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

    const deleted = await deps.admin().rpc("delete_match", {
      p_match_id: id,
      p_owner_id: ownerId,
    });
    if (deleted.error) {
      if (deleted.error.code === "P0002") {
        throw new ApiFailure(404, "not_found", "match not found");
      }
      if (
        deleted.error.code === "P0001" &&
        deleted.error.message === "analysis_in_progress"
      ) {
        throw new ApiFailure(409, "conflict", "analysis in progress");
      }
      throw deleted.error;
    }

    const keys = matchR2Keys(id, deleted.data);
    deps.waitUntil(cleanupMatchR2(deps.r2(), keys));
    return c.body(null, 202);
  });
}
