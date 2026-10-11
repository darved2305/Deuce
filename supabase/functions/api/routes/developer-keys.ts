import { createRoute, type OpenAPIHono, z } from "@hono/zod-openapi";
import type { AppEnv } from "../lib/auth.ts";
import { sha256Hex } from "../lib/auth.ts";
import {
  developerKeyIdSchema,
  errorEnvelopeSchema,
  isoTimestampSchema,
} from "../lib/contracts_vendor/index.ts";
import { ApiFailure } from "../lib/errors.ts";
import { canonicalHash, idemBegin, idemStore } from "../lib/idempotency.ts";

const ENDPOINT = "POST /v1/developer-keys";
const IdempotencyHeader = z.object({
  "idempotency-key": z.string().min(1).max(255),
});
const DeveloperKeyId = developerKeyIdSchema;

const createDeveloperKey = createRoute({
  method: "post",
  path: "/v1/developer-keys",
  request: { headers: IdempotencyHeader },
  responses: {
    201: {
      description:
        "pending Person 4: no vendored response schema; plaintext key is returned only once",
    },
    401: {
      description: "missing or invalid authentication",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
    403: {
      description: "developer keys cannot create keys",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
    409: {
      description: "idempotency key was reused for a different request",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
    422: {
      description: "request validation failed",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
  },
});

const revokeDeveloperKey = createRoute({
  method: "delete",
  path: "/v1/developer-keys/{id}",
  request: { params: z.object({ id: DeveloperKeyId }) },
  responses: {
    204: { description: "developer key revoked" },
    401: {
      description: "missing or invalid authentication",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
    403: {
      description: "developer keys cannot revoke keys",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
    404: {
      description: "developer key not found or not owned by the caller",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
  },
});

function generateKey(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  const base64 = btoa(String.fromCharCode(...bytes));
  return `k_${
    base64.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
  }`;
}

export function registerDeveloperKeys(api: OpenAPIHono<AppEnv>): void {
  api.openapi(createDeveloperKey, async (c) => {
    const idemKey = c.req.valid("header")["idempotency-key"];
    const ownerId = c.var.userId;
    const requestHash = await canonicalHash({});
    const admin = c.var.deps.admin();
    const existing = await idemBegin(
      admin,
      idemKey,
      ownerId,
      ENDPOINT,
      requestHash,
    );
    if (existing.kind === "conflict") {
      throw new ApiFailure(
        409,
        "idempotency_conflict",
        "idempotency key was reused for a different request",
      );
    }
    if (existing.kind === "replay") {
      const response = existing.response as Record<string, unknown>;
      if (response.scope !== "read") {
        throw new Error("invalid developer key idempotency response");
      }
      return c.json({
        key_id: developerKeyIdSchema.parse(response.key_id),
        scope: "read",
        created_at: isoTimestampSchema.parse(response.created_at),
      }, 201);
    }

    const key = generateKey();
    const keyHash = await sha256Hex(key);
    const { data, error } = await admin.from("developer_keys").insert({
      owner_id: ownerId,
      key_hash: keyHash,
      scope: "read",
    }).select("id,scope,created_at").single();
    if (error) throw error;
    if (!data) throw new Error("developer key insert returned no row");
    const row = data as { id: string; scope: "read"; created_at: string };
    const response = {
      key_id: developerKeyIdSchema.parse(row.id),
      key,
      scope: row.scope,
      created_at: isoTimestampSchema.parse(row.created_at),
    };
    const replayResponse = {
      key_id: response.key_id,
      scope: response.scope,
      created_at: response.created_at,
    };
    try {
      await idemStore(
        admin,
        idemKey,
        ownerId,
        ENDPOINT,
        requestHash,
        201,
        replayResponse,
      );
    } catch {
      console.error("developer key idempotency record failed");
    }
    return c.json(response, 201);
  });

  api.openapi(revokeDeveloperKey, async (c) => {
    const { id } = c.req.valid("param");
    const jwt = (c.req.header("Authorization") ?? "").slice(
      "Bearer ".length,
    );
    const owned = await c.var.deps.user(jwt).from("developer_keys")
      .select("id,revoked_at")
      .eq("id", id)
      .maybeSingle();
    if (owned.error) throw owned.error;
    const key = owned.data as { id: string; revoked_at: string | null } | null;
    if (!key) {
      throw new ApiFailure(404, "not_found", "developer key not found");
    }
    if (key.revoked_at === null) {
      const { error } = await c.var.deps.admin().from("developer_keys")
        .update({ revoked_at: new Date().toISOString() })
        .eq("id", id)
        .eq("owner_id", c.var.userId)
        .is("revoked_at", null);
      if (error) throw error;
    }
    return new Response(null, { status: 204 });
  });
}
