import { createRoute, type OpenAPIHono, z } from "@hono/zod-openapi";
import type { Context } from "hono";
import type { AppEnv } from "../lib/auth.ts";
import {
  challengeAcceptRequestSchema,
  challengeCreateRequestSchema,
  challengeEntryRequestSchema,
  challengeIdSchema,
  challengeSchema,
  errorEnvelopeSchema,
  friendsResponseSchema,
} from "../lib/contracts_vendor/index.ts";
import { ApiFailure } from "../lib/errors.ts";
import { canonicalHash, idemBegin, idemStore } from "../lib/idempotency.ts";

const ENDPOINT = "POST /v1/challenges";
const IdemHeader = z.object({
  "idempotency-key": z.string().min(1).max(255),
});
type Challenge = z.infer<typeof challengeSchema>;
type RoundResult = z.infer<typeof challengeEntryRequestSchema>["round_results"];

const errors = {
  401: {
    description: "missing or invalid token",
    content: { "application/json": { schema: errorEnvelopeSchema } },
  },
  404: {
    description: "challenge or match not visible to the caller",
    content: { "application/json": { schema: errorEnvelopeSchema } },
  },
  409: {
    description: "conflict with the stored challenge",
    content: { "application/json": { schema: errorEnvelopeSchema } },
  },
  422: {
    description: "invalid request",
    content: { "application/json": { schema: errorEnvelopeSchema } },
  },
};

const createChallenge = createRoute({
  method: "post",
  path: "/v1/challenges",
  operationId: "createChallenge",
  request: {
    headers: IdemHeader,
    body: {
      content: { "application/json": { schema: challengeCreateRequestSchema } },
    },
  },
  responses: {
    201: {
      description: "challenge created",
      content: { "application/json": { schema: challengeSchema } },
    },
    ...errors,
  },
});

const acceptChallenge = createRoute({
  method: "post",
  path: "/v1/challenges/{id}/accept",
  operationId: "acceptChallenge",
  request: {
    params: z.object({ id: challengeIdSchema }),
    body: {
      content: { "application/json": { schema: challengeAcceptRequestSchema } },
      required: false,
    },
  },
  responses: {
    200: {
      description:
        "challenge accepted; the caller can now read the match through a share grant",
      content: { "application/json": { schema: challengeSchema } },
    },
    ...errors,
  },
});

const postEntry = createRoute({
  method: "post",
  path: "/v1/challenges/{id}/entries",
  operationId: "createChallengeEntry",
  request: {
    params: z.object({ id: challengeIdSchema }),
    body: {
      content: { "application/json": { schema: challengeEntryRequestSchema } },
    },
  },
  responses: {
    201: {
      description: "the caller's run is stored",
      content: { "application/json": { schema: challengeSchema } },
    },
    ...errors,
  },
});

const getChallenge = createRoute({
  method: "get",
  path: "/v1/challenges/{id}",
  operationId: "getChallenge",
  request: { params: z.object({ id: challengeIdSchema }) },
  responses: {
    200: {
      description: "challenge as the caller may see it",
      content: { "application/json": { schema: challengeSchema } },
    },
    ...errors,
  },
});

const getFriends = createRoute({
  method: "get",
  path: "/v1/friends",
  operationId: "listFriends",
  responses: {
    200: {
      description: "people the caller has exchanged challenges with",
      content: { "application/json": { schema: friendsResponseSchema } },
    },
    ...errors,
  },
});

function bearer(c: Context<AppEnv>): string {
  return (c.req.header("Authorization") ?? "").slice("Bearer ".length);
}

// A score is only ever the sum of its rounds' points; the app's scoring decides the points.
function checkScore(score: number, results: RoundResult): void {
  const total = results.reduce((sum, r) => sum + r.points, 0);
  if (total !== score) {
    throw new ApiFailure(
      422,
      "validation_failed",
      "score must equal the sum of round points",
    );
  }
  const seen = new Set(results.map((r) => r.hit_event_id));
  if (seen.size !== results.length) {
    throw new ApiFailure(
      422,
      "validation_failed",
      "each round can be answered once",
    );
  }
}

type RpcError = { code?: string; message: string };

// Maps the errors the challenge functions raise (see the migration) onto the envelope.
function rpcFailure(name: string, error: RpcError): never {
  if (error.code === "P0002") {
    throw new ApiFailure(404, "not_found", "challenge not found");
  }
  if (error.code === "P0001") {
    switch (error.message) {
      case "rematch_forbidden":
        throw new ApiFailure(404, "not_found", "challenge not found");
      case "own_challenge":
        throw new ApiFailure(
          409,
          "conflict",
          "you cannot accept your own challenge",
        );
      case "already_played":
        throw new ApiFailure(409, "conflict", "you already played this one");
      case "no_source_hash":
        throw new ApiFailure(
          409,
          "conflict",
          "the match has no source video hash",
        );
      case "unknown_event":
      case "rounds_mismatch":
        throw new ApiFailure(422, "validation_failed", error.message);
    }
  }
  throw new Error(`${name} failed: ${error.code} ${error.message}`);
}

async function loadView(c: Context<AppEnv>, id: string): Promise<Challenge> {
  const result = await c.var.deps.user(bearer(c)).rpc("get_challenge_v1", {
    p_id: id,
  });
  if (result.error) throw result.error;
  if (result.data == null) {
    throw new ApiFailure(404, "not_found", "challenge not found");
  }
  return challengeSchema.parse(result.data);
}

export function registerChallenges(api: OpenAPIHono<AppEnv>): void {
  api.openapi(createChallenge, async (c) => {
    const body = c.req.valid("json");
    const key = c.req.valid("header")["idempotency-key"];
    const player = c.var.userId;
    checkScore(body.score, body.round_results);
    const hits = new Set(body.rounds.map((r) => r.hit_event_id));
    if (
      hits.size !== body.rounds.length ||
      body.round_results.some((r) => !hits.has(r.hit_event_id))
    ) {
      throw new ApiFailure(
        422,
        "validation_failed",
        "results must belong to distinct rounds of the freeze set",
      );
    }

    const admin = c.var.deps.admin();
    const hash = await canonicalHash(body);
    const idem = await idemBegin(admin, key, player, ENDPOINT, hash);
    if (idem.kind === "conflict") {
      throw new ApiFailure(
        409,
        "idempotency_conflict",
        "Idempotency-Key was used with a different request",
      );
    }
    if (idem.kind === "replay") {
      return c.json(challengeSchema.parse(idem.response), 201);
    }

    const created = await admin.rpc("create_challenge", {
      p_player: player,
      p_match_id: body.match_id,
      p_display_name: body.display_name ?? null,
      p_rounds: body.rounds,
      p_score: body.score,
      p_results: body.round_results,
      p_rematch_of: body.rematch_of ?? null,
    });
    if (created.error) {
      if (created.error.code === "P0002") {
        throw new ApiFailure(404, "not_found", "match not found");
      }
      rpcFailure("create_challenge", created.error);
    }
    const view = await loadView(c, created.data as string);
    try {
      await idemStore(admin, key, player, ENDPOINT, hash, 201, view);
    } catch (err) {
      console.error("idempotency store failed", (err as Error).message);
    }
    return c.json(view, 201);
  });

  api.openapi(acceptChallenge, async (c) => {
    const { id } = c.req.valid("param");
    const body = c.req.valid("json") ?? {};
    const accepted = await c.var.deps.admin().rpc("accept_challenge", {
      p_player: c.var.userId,
      p_challenge_id: id,
      p_display_name: body.display_name ?? null,
    });
    if (accepted.error) rpcFailure("accept_challenge", accepted.error);
    return c.json(await loadView(c, id), 200);
  });

  api.openapi(postEntry, async (c) => {
    const { id } = c.req.valid("param");
    const body = c.req.valid("json");
    checkScore(body.score, body.round_results);
    const stored = await c.var.deps.admin().rpc("submit_challenge_entry", {
      p_player: c.var.userId,
      p_challenge_id: id,
      p_display_name: body.display_name ?? null,
      p_score: body.score,
      p_results: body.round_results,
    });
    if (stored.error) rpcFailure("submit_challenge_entry", stored.error);
    return c.json(await loadView(c, id), 201);
  });

  api.openapi(getChallenge, async (c) => {
    const { id } = c.req.valid("param");
    return c.json(await loadView(c, id), 200);
  });

  api.openapi(getFriends, async (c) => {
    const result = await c.var.deps.user(bearer(c)).rpc("list_friends_v1");
    if (result.error) throw result.error;
    return c.json(
      friendsResponseSchema.parse({ items: result.data ?? [] }),
      200,
    );
  });
}
