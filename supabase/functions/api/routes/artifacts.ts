import { createRoute, type OpenAPIHono, z } from "@hono/zod-openapi";
import type { AppEnv } from "../lib/auth.ts";
import {
  errorEnvelopeSchema,
  isoTimestampSchema,
} from "../lib/contracts_vendor/index.ts";
import { addUsage, takeDownloadUrl } from "../lib/budget.ts";
import { ApiFailure } from "../lib/errors.ts";
import { GET_URL_TTL_S } from "../lib/r2.ts";

const ArtifactId = z.string().regex(/^art_[A-Za-z0-9_-]+$/);
const ArtifactResponse = z.strictObject({
  url: z.string().url(),
  expires_at: isoTimestampSchema,
});

const getArtifact = createRoute({
  method: "get",
  path: "/v1/artifacts/{id}",
  request: { params: z.object({ id: ArtifactId }) },
  responses: {
    200: {
      description:
        "pending Person 4: signed artifact URL, expires in 300 seconds",
      content: { "application/json": { schema: ArtifactResponse } },
    },
    404: {
      description: "artifact not found or not shared with the caller",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
    429: {
      description: "daily download URL limit reached",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
    503: {
      description: "R2 usage could not be recorded",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
  },
});

function expiresAt(signedUrl: string): string {
  const url = new URL(signedUrl);
  const issued = url.searchParams.get("X-Amz-Date") ?? "";
  const ttl = url.searchParams.get("X-Amz-Expires");
  const match = /^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})Z$/.exec(
    issued,
  );
  if (ttl !== String(GET_URL_TTL_S) || !match) {
    throw new Error("presigned artifact URL has invalid expiry metadata");
  }
  const issuedAtMs = Date.parse(
    `${match[1]}-${match[2]}-${match[3]}T${match[4]}:${match[5]}:${match[6]}Z`,
  );
  if (!Number.isFinite(issuedAtMs)) {
    throw new Error("presigned artifact URL has an invalid issue time");
  }
  return new Date(issuedAtMs + GET_URL_TTL_S * 1000).toISOString();
}

type ArtifactRow = { id: string; r2_key: string; match_id: string };

export function registerArtifacts(api: OpenAPIHono<AppEnv>): void {
  api.openapi(getArtifact, async (c) => {
    const { id } = c.req.valid("param");
    const jwt = (c.req.header("Authorization") ?? "").slice(
      "Bearer ".length,
    );
    const deps = c.var.deps;
    const user = deps.user(jwt);
    const admin = deps.admin();

    const owned = await user.from("artifacts")
      .select("id,r2_key,match_id")
      .eq("id", id)
      .maybeSingle();
    if (owned.error) throw owned.error;
    let artifact = owned.data as ArtifactRow | null;

    if (!artifact) {
      const hidden = await admin.from("artifacts")
        .select("id,r2_key,match_id")
        .eq("id", id)
        .maybeSingle();
      if (hidden.error) throw hidden.error;
      if (!hidden.data) {
        throw new ApiFailure(404, "not_found", "artifact not found");
      }

      // #48 keeps private.has_share_grant outside the REST-exposed schemas. Mirror its
      // active read/compare predicate after the owner lookup has already applied RLS.
      const grants = await admin.from("share_grants")
        .select("scope,revoked_at")
        .eq("match_id", (hidden.data as ArtifactRow).match_id)
        .eq("grantee_id", c.var.userId);
      if (grants.error) throw grants.error;
      const rows = grants.data as
        | { scope: string; revoked_at: string | null }[]
        | null;
      const canRead = (rows ?? []).some((grant) =>
        grant.revoked_at === null &&
        (grant.scope === "read" || grant.scope === "compare")
      );
      if (!canRead) {
        throw new ApiFailure(404, "not_found", "artifact not found");
      }
      artifact = hidden.data as ArtifactRow;
    }

    await takeDownloadUrl(admin, c.var.userId);
    const url = await deps.r2().presignGet(artifact.r2_key);
    await addUsage(admin, 0, 1); // Each issued artifact URL accounts for one Class B read.
    return c.json(
      ArtifactResponse.parse({ url, expires_at: expiresAt(url) }),
      200,
    );
  });
}
