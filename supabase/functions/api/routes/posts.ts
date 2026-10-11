import { createRoute, type OpenAPIHono, z } from "@hono/zod-openapi";
import type { Context } from "hono";
import type { AppEnv } from "../lib/auth.ts";
import {
  errorEnvelopeSchema,
  feedPageSchema,
  isoTimestampSchema,
  matchIdSchema,
  postCreateRequestSchema,
  postIdSchema,
  postSummarySchema,
  postV1Schema,
} from "../lib/contracts_vendor/index.ts";
import { ApiFailure } from "../lib/errors.ts";
import { canonicalHash, idemBegin, idemStore } from "../lib/idempotency.ts";

const FEED_PAGE_SIZE = 20;
const IdempotencyHeader = z.object({
  "idempotency-key": z.string().min(1).max(255),
});
const feedQuery = z.strictObject({ cursor: z.string().min(1).optional() });
const feedCursorSchema = z.strictObject({
  version: z.literal(1),
  scope: z.literal("friend-feed"),
  created_at: isoTimestampSchema,
  id: postIdSchema,
});
const postSelect = "id,owner_id,match_id,created_at,caption,summary";

const errors = {
  401: {
    description: "missing or invalid token",
    content: { "application/json": { schema: errorEnvelopeSchema } },
  },
  403: {
    description: "read-only developer key",
    content: { "application/json": { schema: errorEnvelopeSchema } },
  },
  404: {
    description: "post or match not visible to the caller",
    content: { "application/json": { schema: errorEnvelopeSchema } },
  },
  409: {
    description: "idempotency key conflict",
    content: { "application/json": { schema: errorEnvelopeSchema } },
  },
  422: {
    description: "invalid request or cursor",
    content: { "application/json": { schema: errorEnvelopeSchema } },
  },
  429: {
    description: "daily post limit reached",
    content: { "application/json": { schema: errorEnvelopeSchema } },
  },
};

const createPost = createRoute({
  method: "post",
  path: "/v1/matches/{id}/posts",
  operationId: "createMatchPost",
  request: {
    params: z.object({ id: matchIdSchema }),
    headers: IdempotencyHeader,
    body: {
      content: { "application/json": { schema: postCreateRequestSchema } },
    },
  },
  responses: {
    201: {
      description: "frozen match post created",
      content: { "application/json": { schema: postV1Schema } },
    },
    ...errors,
  },
});

const getFeed = createRoute({
  method: "get",
  path: "/v1/feed",
  operationId: "getFriendFeed",
  request: { query: feedQuery },
  responses: {
    200: {
      description: "caller and friends' posts, newest first",
      content: { "application/json": { schema: feedPageSchema } },
    },
    ...errors,
  },
});

const getPost = createRoute({
  method: "get",
  path: "/v1/posts/{id}",
  operationId: "getMatchPost",
  request: { params: z.object({ id: postIdSchema }) },
  responses: {
    200: {
      description: "a post visible to its owner or a friend",
      content: { "application/json": { schema: postV1Schema } },
    },
    ...errors,
  },
});

const deletePost = createRoute({
  method: "delete",
  path: "/v1/posts/{id}",
  operationId: "deleteMatchPost",
  request: { params: z.object({ id: postIdSchema }) },
  responses: {
    204: { description: "post deleted" },
    ...errors,
  },
});

function bearer(c: Context<AppEnv>): string {
  return (c.req.header("Authorization") ?? "").slice("Bearer ".length);
}

function bearerClient(c: Context<AppEnv>) {
  return c.var.deps.user(bearer(c));
}

function cursorEncode(cursor: z.infer<typeof feedCursorSchema>): string {
  const bytes = new TextEncoder().encode(JSON.stringify(cursor));
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(
    /=+$/,
    "",
  );
}

function cursorDecode(
  token: string | undefined,
): z.infer<typeof feedCursorSchema> | undefined {
  if (token === undefined) return undefined;
  let value: unknown = null;
  try {
    const base64 = token.replaceAll("-", "+").replaceAll("_", "/");
    const padded = base64.padEnd(
      base64.length + (4 - base64.length % 4) % 4,
      "=",
    );
    const bytes = Uint8Array.from(
      atob(padded),
      (character) => character.charCodeAt(0),
    );
    value = JSON.parse(new TextDecoder().decode(bytes));
  } catch {
    value = null;
  }
  const parsed = feedCursorSchema.safeParse(value);
  if (parsed.success) return parsed.data;
  throw new ApiFailure(422, "validation_failed", "invalid cursor");
}

type PostRow = {
  id: string;
  owner_id: string;
  match_id: string;
  created_at: string;
  caption: string | null;
  summary: unknown;
};

function postView(value: unknown) {
  const row = value as PostRow;
  const summary = postSummarySchema.parse(row.summary);
  return postV1Schema.parse({
    post_id: row.id,
    match_id: row.match_id,
    owner: {
      player_id: row.owner_id,
      display_name: summary.owner_display_name,
    },
    created_at: row.created_at,
    caption: row.caption,
    summary,
  });
}

async function loadPost(c: Context<AppEnv>, id: string) {
  const result = await bearerClient(c).from("posts").select(postSelect)
    .eq("id", id).maybeSingle();
  if (result.error) throw result.error;
  if (!result.data) throw new ApiFailure(404, "not_found", "post not found");
  return postView(result.data);
}

function retryAfterUtcMidnight(): number {
  const now = new Date();
  const nextDay = Date.UTC(
    now.getUTCFullYear(),
    now.getUTCMonth(),
    now.getUTCDate() + 1,
  );
  return Math.max(1, Math.ceil((nextDay - now.getTime()) / 1000));
}

type RpcError = { code?: string; message: string };

function createFailure(error: RpcError): never {
  if (error.code === "P0002") {
    throw new ApiFailure(404, "not_found", "match not found");
  }
  if (error.code === "P0001" && error.message === "daily_post_limit") {
    throw new ApiFailure(
      429,
      "daily_post_limit",
      "daily post limit reached",
      undefined,
      retryAfterUtcMidnight(),
    );
  }
  throw new Error(`create_match_post failed: ${error.code} ${error.message}`);
}

export function registerPosts(api: OpenAPIHono<AppEnv>): void {
  api.openapi(createPost, async (c) => {
    const { id: matchId } = c.req.valid("param");
    const body = c.req.valid("json");
    const key = c.req.valid("header")["idempotency-key"];
    const ownerId = c.var.userId;
    const endpoint = `POST /v1/matches/${matchId}/posts`;
    const request = { match_id: matchId, caption: body.caption ?? null };
    const hash = await canonicalHash(request);
    const admin = c.var.deps.admin();
    const idem = await idemBegin(admin, key, ownerId, endpoint, hash);
    if (idem.kind === "conflict") {
      throw new ApiFailure(
        409,
        "idempotency_conflict",
        "Idempotency-Key was used with a different request",
      );
    }
    if (idem.kind === "replay") {
      return c.json(postV1Schema.parse(idem.response), 201);
    }

    const user = bearerClient(c);
    const created = await user.rpc("create_match_post", {
      p_match_id: matchId,
      p_caption: body.caption ?? null,
    });
    if (created.error) createFailure(created.error);
    if (typeof created.data !== "string") {
      throw new Error("create_match_post returned no post id");
    }
    const post = await loadPost(c, created.data);
    try {
      await idemStore(admin, key, ownerId, endpoint, hash, 201, post);
    } catch (err) {
      console.error("idempotency store failed", (err as Error).message);
    }
    return c.json(post, 201);
  });

  api.openapi(getFeed, async (c) => {
    const { cursor: token } = c.req.valid("query");
    const cursor = cursorDecode(token);
    let query = bearerClient(c).from("posts").select(postSelect)
      .order("created_at", { ascending: false })
      .order("id", { ascending: false });
    if (cursor) {
      query = query.or(
        `created_at.lt.${cursor.created_at},and(created_at.eq.${cursor.created_at},id.lt.${cursor.id})`,
      );
    }
    const result = await query.limit(FEED_PAGE_SIZE + 1);
    if (result.error) throw result.error;
    const rows = (result.data ?? []) as PostRow[];
    const hasMore = rows.length > FEED_PAGE_SIZE;
    const pageRows = rows.slice(0, FEED_PAGE_SIZE);
    const response = feedPageSchema.parse({
      items: pageRows.map(postView),
      next_cursor: hasMore && pageRows.length > 0
        ? cursorEncode({
          version: 1,
          scope: "friend-feed",
          created_at: pageRows.at(-1)!.created_at,
          id: pageRows.at(-1)!.id,
        })
        : null,
    });
    return c.json(response, 200);
  });

  api.openapi(getPost, async (c) => {
    const { id } = c.req.valid("param");
    return c.json(await loadPost(c, id), 200);
  });

  api.openapi(deletePost, async (c) => {
    const { id } = c.req.valid("param");
    const result = await bearerClient(c).from("posts").delete().eq("id", id)
      .select("id").maybeSingle();
    if (result.error) throw result.error;
    if (!result.data) throw new ApiFailure(404, "not_found", "post not found");
    return c.body(null, 204);
  });
}
