import { createRoute, type OpenAPIHono, z } from "@hono/zod-openapi";
import type { SupabaseClient } from "@supabase/supabase-js";
import type { AppEnv } from "../lib/auth.ts";
import {
  confirmedPointSchema,
  errorEnvelopeSchema,
  matchIdSchema,
  reviewConfirmRequestSchema,
  reviewIdSchema,
} from "../lib/contracts_vendor/index.ts";
import { ApiFailure } from "../lib/errors.ts";
import { appendEvent } from "../lib/events.ts";
import { canonicalHash, idemBegin, idemStore } from "../lib/idempotency.ts";
import {
  applyPoint,
  initialState,
  RulesError,
  type RulesState,
  type TeamIndex,
} from "../lib/rules_vendor/index.ts";

const ENDPOINT = "POST /v1/matches/{id}/reviews/{rvid}/confirm";
const ConfirmRequest = reviewConfirmRequestSchema;
const ConfirmResponse = confirmedPointSchema;

const confirmReview = createRoute({
  method: "post",
  path: "/v1/matches/{id}/reviews/{rvid}/confirm",
  operationId: "confirmReview",
  request: {
    params: z.object({ id: matchIdSchema, rvid: reviewIdSchema }),
    headers: z.object({ "idempotency-key": z.string().min(1).max(255) }),
    body: { content: { "application/json": { schema: ConfirmRequest } } },
  },
  responses: {
    200: {
      description: "confirmed point",
      content: { "application/json": { schema: ConfirmResponse } },
    },
    409: {
      description: "confirmation conflict",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
    422: {
      description: "invalid confirmation",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
  },
});

type MatchRow = {
  id: string;
  owner_id: string;
  sport: string;
  format: string;
  ruleset_id: string;
  ruleset_version: string;
};

type Player = { player_id: string; identity: string };
type AnalysisRow = { id: string; players: unknown };
type RallyRow = { id: string; start_ms: number; server: string | null };
type ShotRole = { player_id: string; xy_m?: [number, number] };
type ShotRow = { rally_id: string; contact_ms: number; roles: unknown };
type ReviewRow = {
  id: string;
  rally_id: string | null;
  confirmed_point_winner: string | null;
  confirmed_by: string | null;
  confirmed_at: string | null;
};
type ConfirmedReview = {
  review_id: string;
  rally_id: string;
  winner: string;
};
type ConfirmScoreInput = {
  analysis: AnalysisRow | null;
  rallies: RallyRow[];
  shots: ShotRow[];
  reviews: ReviewRow[];
};
type ConfirmScore = {
  score: {
    timeline: Array<{
      rally_id: string;
      after: [number, number];
      server_next: string;
    }>;
    games: Array<[number, number]>;
  };
  confirmedReviews: ConfirmedReview[];
};

async function readConfirmScoreInput(
  user: SupabaseClient,
  matchId: string,
): Promise<ConfirmScoreInput> {
  const [analysis, rallies, shots, reviews] = await Promise.all([
    user.from("analysis_runs").select("id,players").eq("match_id", matchId)
      .order("created_at", { ascending: false }).limit(1).maybeSingle(),
    user.from("rallies").select("id,start_ms,server").eq("match_id", matchId),
    user.from("shots").select("rally_id,contact_ms,roles").eq(
      "match_id",
      matchId,
    ),
    user.from("reviews").select(
      "id,rally_id,confirmed_point_winner,confirmed_by,confirmed_at",
    ).eq("match_id", matchId),
  ]);
  for (const result of [analysis, rallies, shots, reviews]) {
    if (result.error) throw result.error;
  }
  return {
    analysis: analysis.data as AnalysisRow | null,
    rallies: (rallies.data ?? []) as RallyRow[],
    shots: (shots.data ?? []) as ShotRow[],
    reviews: (reviews.data ?? []) as ReviewRow[],
  };
}

function matchPlayers(value: unknown): Player[] {
  if (!Array.isArray(value)) return [];
  return value.filter((player): player is Player =>
    typeof player === "object" && player !== null &&
    typeof (player as Player).player_id === "string" &&
    typeof (player as Player).identity === "string"
  );
}

function team0StartsNear(
  sport: string,
  playerId: string,
  rallyId: string,
  shots: ShotRow[],
): boolean {
  if (sport !== "badminton") return true;
  const firstShot = shots.filter((shot) => shot.rally_id === rallyId)
    .sort((a, b) => a.contact_ms - b.contact_ms)[0];
  if (
    !firstShot || typeof firstShot.roles !== "object" ||
    firstShot.roles === null
  ) {
    return true;
  }
  const roles = firstShot.roles as { focal?: ShotRole; opponent?: ShotRole };
  const role = [roles.focal, roles.opponent].find((candidate) =>
    candidate?.player_id === playerId
  );
  const y = role?.xy_m?.[1];
  const midpoint = 13.4 / 2;
  return typeof y !== "number" || !Number.isFinite(y) || y <= midpoint;
}

function scoreForConfirm(
  match: MatchRow,
  players: [Player, Player],
  input: ConfirmScoreInput,
  targetReview: ReviewRow,
  winner: string,
): ConfirmScore {
  const rallies = input.rallies.slice().sort((a, b) =>
    a.start_ms - b.start_ms || a.id.localeCompare(b.id)
  );
  const firstRally = rallies[0];
  if (!firstRally?.server) {
    throw new ApiFailure(
      409,
      "conflict",
      "match is missing its first rally server",
    );
  }
  const rallyOrder = new Map(rallies.map((rally, index) => [rally.id, index]));
  const reviews = input.reviews.map((review) =>
    review.id === targetReview.id
      ? { ...review, confirmed_point_winner: winner }
      : review
  );
  const confirmedReviews = reviews.filter((review) =>
    review.rally_id !== null && rallyOrder.has(review.rally_id) &&
    review.confirmed_point_winner !== null
  ).map((review) => ({
    review_id: review.id,
    rally_id: review.rally_id!,
    winner: review.confirmed_point_winner!,
  })).sort((a, b) =>
    rallyOrder.get(a.rally_id)! - rallyOrder.get(b.rally_id)! ||
    a.review_id.localeCompare(b.review_id)
  );
  const winnerByRally = new Map<string, string | null>();
  for (const review of confirmedReviews) {
    const existing = winnerByRally.get(review.rally_id);
    if (existing === undefined) {
      winnerByRally.set(review.rally_id, review.winner);
    } else if (existing !== review.winner) {
      winnerByRally.set(review.rally_id, null);
    }
  }

  let state: RulesState;
  try {
    state = initialState({
      ruleset: { id: match.ruleset_id, version: match.ruleset_version },
      format: match.format as "singles" | "doubles",
      teams: [[players[0].player_id], [players[1].player_id]],
      firstServer: firstRally.server,
      ends: {
        team0: team0StartsNear(
            match.sport,
            players[0].player_id,
            firstRally.id,
            input.shots,
          )
          ? "near"
          : "far",
      },
    });
  } catch (error) {
    if (error instanceof RulesError) {
      throw new ApiFailure(
        422,
        "invalid_input",
        "match ruleset cannot score this match",
      );
    }
    throw error;
  }

  const timeline: ConfirmScore["score"]["timeline"] = [];
  for (const rally of rallies) {
    const rallyWinner = winnerByRally.get(rally.id);
    if (!rallyWinner || state.status === "match_over") break;
    const winningTeam: TeamIndex | null = rallyWinner === players[0].player_id
      ? 0
      : rallyWinner === players[1].player_id
      ? 1
      : null;
    if (winningTeam === null) break;
    const gamesBefore = state.games.length;
    state = applyPoint(state, winningTeam);
    const after = state.games.length > gamesBefore
      ? state.games[state.games.length - 1]
      : state.current;
    timeline.push({
      rally_id: rally.id,
      after: [...after] as [number, number],
      server_next: state.server,
    });
  }
  return {
    score: {
      timeline,
      games: state.games.map((game) => [...game] as [number, number]),
    },
    confirmedReviews,
  };
}

export function registerReviews(api: OpenAPIHono<AppEnv>): void {
  api.openapi(confirmReview, async (c) => {
    const { id: matchId, rvid: reviewId } = c.req.valid("param");
    const { winner } = c.req.valid("json");
    const key = c.req.valid("header")["idempotency-key"];
    const ownerId = c.var.userId;
    const jwt = (c.req.header("Authorization") ?? "").slice("Bearer ".length);
    const user = c.var.deps.user(jwt);
    const admin = c.var.deps.admin();

    const matchResult = await user.from("matches").select(
      "id,owner_id,sport,format,ruleset_id,ruleset_version",
    ).eq("id", matchId).maybeSingle();
    if (matchResult.error) throw matchResult.error;
    const match = matchResult.data as MatchRow | null;
    if (!match || match.owner_id !== ownerId) {
      throw new ApiFailure(404, "not_found", "match not found");
    }

    const hash = await canonicalHash({
      match_id: matchId,
      review_id: reviewId,
      winner,
    });
    const idem = await idemBegin(admin, key, ownerId, ENDPOINT, hash);
    if (idem.kind === "conflict") {
      throw new ApiFailure(
        409,
        "idempotency_conflict",
        "Idempotency-Key was used with a different request",
      );
    }
    if (idem.kind === "replay") {
      return c.json(ConfirmResponse.parse(idem.response), 200);
    }

    if (match.format === "doubles") {
      throw new ApiFailure(
        422,
        "invalid_input",
        "doubles confirm not supported yet",
      );
    }
    if (match.format !== "singles") {
      throw new ApiFailure(
        422,
        "invalid_input",
        "confirm not supported for this format",
      );
    }

    for (let attempt = 0; attempt < 3; attempt++) {
      const input = await readConfirmScoreInput(user, matchId);
      const players = matchPlayers(input.analysis?.players);
      const mappedPlayers = players.filter((player) =>
        player.identity === "user_confirmed"
      );
      if (
        mappedPlayers.length !== 1 ||
        !players.some((player) =>
          player.player_id === mappedPlayers[0]?.player_id
        )
      ) {
        throw new ApiFailure(409, "conflict", "who_is_me not resolved yet");
      }
      if (players.length !== 2) {
        throw new ApiFailure(
          422,
          "validation_failed",
          "singles match must have two players",
        );
      }
      const teamPlayers = players as [Player, Player];
      if (!teamPlayers.some((player) => player.player_id === winner)) {
        throw new ApiFailure(
          422,
          "validation_failed",
          "winner must be a player of the match",
        );
      }
      const targetReview = input.reviews.find((review) =>
        review.id === reviewId
      );
      if (!targetReview) {
        throw new ApiFailure(404, "not_found", "review not found");
      }
      if (!targetReview.rally_id) {
        throw new ApiFailure(
          409,
          "conflict",
          "review is not linked to a rally",
        );
      }
      const score = scoreForConfirm(
        match,
        teamPlayers,
        input,
        targetReview,
        winner,
      );
      if (!input.analysis?.id) {
        throw new ApiFailure(409, "conflict", "match has no analysis");
      }
      const result = await admin.rpc("confirm_review", {
        p_match_id: matchId,
        p_review_id: reviewId,
        p_winner: winner,
        p_actor_id: ownerId,
        p_by_player_id: mappedPlayers[0].player_id,
        p_idempotency_key: key,
        p_score: score.score,
        p_confirmed_reviews: score.confirmedReviews,
      });
      if (result.error) {
        if (
          result.error.code === "P0001" &&
          result.error.message === "score_stale"
        ) {
          if (attempt < 2) continue;
          throw new ApiFailure(
            409,
            "conflict",
            "score changed during confirmation; retry",
          );
        }
        if (result.error.code === "P0002") {
          throw new ApiFailure(404, "not_found", "review not found");
        }
        if (
          result.error.code === "P0001" &&
          result.error.message === "idempotency_conflict"
        ) {
          throw new ApiFailure(
            409,
            "idempotency_conflict",
            "Idempotency-Key was used with a different request",
          );
        }
        if (
          result.error.code === "P0001" &&
          result.error.message === "review_already_confirmed"
        ) {
          throw new ApiFailure(409, "conflict", "review is already confirmed");
        }
        throw new Error(
          `confirm_review failed: ${result.error.code} ${result.error.message}`,
        );
      }
      const out = result.data as { point: unknown; created: boolean };
      const response = ConfirmResponse.parse(out.point);
      if (out.created) {
        await appendEvent(admin, input.analysis.id, "point.confirmed");
      }
      try {
        await idemStore(admin, key, ownerId, ENDPOINT, hash, 200, response);
      } catch (error) {
        console.error("idempotency store failed", (error as Error).message);
      }
      return c.json(response, 200);
    }
    throw new ApiFailure(
      409,
      "conflict",
      "score changed during confirmation; retry",
    );
  });
}
