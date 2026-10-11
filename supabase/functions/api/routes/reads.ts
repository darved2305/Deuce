import { createRoute, type OpenAPIHono, z } from "@hono/zod-openapi";
import type { Context } from "hono";
import type { AppEnv } from "../lib/auth.ts";
import {
  capabilitiesResponseSchema,
  errorEnvelopeSchema,
  isoTimestampSchema,
  matchesPageSchema,
  matchIdSchema,
  millisecondsSchema,
  momentIdSchema,
  momentsPageSchema,
  ralliesPageSchema,
  rallyDetailSchema,
  rallyIdSchema,
} from "../lib/contracts_vendor/index.ts";
import { ApiFailure } from "../lib/errors.ts";

const pageQuery = (max: number) =>
  z.object({
    cursor: z.string().optional(),
    limit: z.coerce.number().int().min(1).max(max).default(20),
  });

const momentsQuery = z.object({
  cursor: z.string().optional(),
  limit: z.coerce.number().int().min(1).max(20).default(20),
  tag: z.string().min(1).optional(),
});

const allMomentsQuery = momentsQuery.extend({
  match_id: matchIdSchema.optional(),
});

const cursorBase = {
  version: z.literal(1),
  scope: z.string(),
};

const matchCursorSchema = z.strictObject({
  ...cursorBase,
  created_at: isoTimestampSchema,
  id: matchIdSchema,
});

const rallyCursorSchema = z.strictObject({
  ...cursorBase,
  index: millisecondsSchema,
  start_ms: millisecondsSchema,
  id: rallyIdSchema,
});

const momentCursorSchema = z.strictObject({
  ...cursorBase,
  start_ms: millisecondsSchema,
  id: momentIdSchema,
});

type CursorBase = { version: 1; scope: string };

function encodeCursor(cursor: CursorBase & Record<string, unknown>): string {
  const bytes = new TextEncoder().encode(JSON.stringify(cursor));
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(
    /=+$/,
    "",
  );
}

function decodeCursor<T extends CursorBase>(
  token: string | undefined,
  scope: string,
  schema: z.ZodType<T>,
): T | undefined {
  if (token === undefined) return undefined;
  try {
    const base64 = token.replaceAll("-", "+").replaceAll("_", "/");
    const padded = base64.padEnd(
      base64.length + (4 - base64.length % 4) % 4,
      "=",
    );
    const binary = atob(padded);
    const bytes = Uint8Array.from(
      binary,
      (character) => character.charCodeAt(0),
    );
    const result = schema.safeParse(
      JSON.parse(new TextDecoder().decode(bytes)),
    );
    if (!result.success || result.data.scope !== scope) {
      throw new Error("invalid cursor");
    }
    return result.data;
  } catch {
    throw new ApiFailure(422, "validation_failed", "invalid cursor");
  }
}

function bearerClient(c: Context<AppEnv>) {
  const jwt = (c.req.header("Authorization") ?? "").slice("Bearer ".length);
  return c.var.deps.user(jwt);
}

const capabilityResponse = createRoute({
  method: "get",
  path: "/v1/capabilities",
  responses: {
    200: {
      description: "capabilities.v1",
      content: { "application/json": { schema: capabilitiesResponseSchema } },
    },
  },
});

const listMatches = createRoute({
  method: "get",
  path: "/v1/matches",
  request: { query: pageQuery(100) },
  responses: {
    200: {
      description: "matches.page.v1",
      content: { "application/json": { schema: matchesPageSchema } },
    },
    422: {
      description: "invalid query or cursor",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
  },
});

const listRallies = createRoute({
  method: "get",
  path: "/v1/matches/{id}/rallies",
  request: {
    params: z.object({ id: matchIdSchema }),
    query: pageQuery(100),
  },
  responses: {
    200: {
      description: "rallies.page.v1",
      content: { "application/json": { schema: ralliesPageSchema } },
    },
    422: {
      description: "invalid query or cursor",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
  },
});

const getRally = createRoute({
  method: "get",
  path: "/v1/matches/{id}/rallies/{rid}",
  request: {
    params: z.object({ id: matchIdSchema, rid: rallyIdSchema }),
  },
  responses: {
    200: {
      description: "rally.detail.v1",
      content: { "application/json": { schema: rallyDetailSchema } },
    },
    404: {
      description: "rally not visible",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
  },
});

const listMatchMoments = createRoute({
  method: "get",
  path: "/v1/matches/{id}/moments",
  request: {
    params: z.object({ id: matchIdSchema }),
    query: momentsQuery,
  },
  responses: {
    200: {
      description: "moments.page.v1",
      content: { "application/json": { schema: momentsPageSchema } },
    },
    422: {
      description: "invalid query or cursor",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
  },
});

const listMoments = createRoute({
  method: "get",
  path: "/v1/moments",
  request: { query: allMomentsQuery },
  responses: {
    200: {
      description: "moments.page.v1",
      content: { "application/json": { schema: momentsPageSchema } },
    },
    422: {
      description: "invalid query or cursor",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
  },
});

const capabilities = {
  capabilities: {
    court: { tier: "assisted", status: "unknown" },
    ball: { tier: "experimental", status: "unknown" },
    reviews: { tier: "experimental", status: "unknown" },
    score: { tier: "assisted", status: "unknown" },
  },
};

type MatchRow = {
  id: string;
  sport: string;
  format: string;
  ruleset_id: string;
  ruleset_version: string;
  created_at: string;
  score: { games?: unknown } | null;
  analysis_runs?: { id: string; state: string; created_at: string }[] | null;
};

type RallyRow = {
  id: string;
  index: number;
  start_ms: number;
  end_ms: number;
  shot_ids: string[];
  server: string;
  winner: string;
  winner_state: string;
  end_reason: string;
  ball_coverage: number;
  clip_id: string;
  state: string;
};

type ShotRow = {
  id: string;
  rally_id: string;
  index: number;
  player_id: string;
  contact_ms: number;
  contact_sigma_ms: number;
  phase: string;
  reference_plane: string;
  coordinate_frame_version: number;
  roles: unknown;
  incoming: unknown;
  chosen: unknown;
  flight_time_ms: number;
  flight_time_state: string;
  context: unknown;
  outcome: unknown;
  quality: unknown;
  clip_id: string;
};

type MomentRow = {
  id: string;
  match_id: string;
  rally_id: string;
  shot_id: string;
  start_ms: number;
  end_ms: number;
  contact_ms: number;
  tags: string[];
  clip_id: string;
};

function rallyItem(row: RallyRow) {
  return {
    rally_id: row.id,
    start_ms: row.start_ms,
    end_ms: row.end_ms,
    shot_ids: row.shot_ids,
    server: row.server,
    winner: row.winner,
    winner_state: row.winner_state,
    end_reason: row.end_reason,
    ball_coverage: row.ball_coverage,
    clip_id: row.clip_id,
    state: row.state,
  };
}

function shotItem(row: ShotRow) {
  return {
    shot_id: row.id,
    rally_id: row.rally_id,
    index: row.index,
    player_id: row.player_id,
    contact_ms: row.contact_ms,
    contact_sigma_ms: row.contact_sigma_ms,
    phase: row.phase,
    reference_plane: row.reference_plane,
    coordinate_frame_version: row.coordinate_frame_version,
    roles: row.roles,
    incoming: row.incoming,
    chosen: row.chosen,
    flight_time_ms: row.flight_time_ms,
    flight_time_state: row.flight_time_state,
    context: row.context,
    outcome: row.outcome,
    quality: row.quality,
    clip_id: row.clip_id,
  };
}

function momentItem(row: MomentRow) {
  return {
    moment_id: row.id,
    match_id: row.match_id,
    rally_id: row.rally_id,
    shot_id: row.shot_id,
    start_ms: row.start_ms,
    end_ms: row.end_ms,
    contact_ms: row.contact_ms,
    tags: row.tags,
    clip_id: row.clip_id,
  };
}

export function registerReads(api: OpenAPIHono<AppEnv>): void {
  api.openapi(capabilityResponse, (c) => {
    return c.json(capabilitiesResponseSchema.parse(capabilities), 200);
  });

  api.openapi(listMatches, async (c) => {
    const { cursor: token, limit } = c.req.valid("query");
    const scope = "matches";
    const cursor = decodeCursor(token, scope, matchCursorSchema);
    let query = bearerClient(c).from("matches").select(
      "id,sport,format,ruleset_id,ruleset_version,created_at,score,analysis_runs!analysis_runs_match_id_fkey(id,state,created_at)",
    ).order("created_at", { ascending: false })
      .order("id", { ascending: false })
      .order("created_at", {
        ascending: false,
        referencedTable: "analysis_runs",
      })
      .order("id", { ascending: false, referencedTable: "analysis_runs" })
      .limit(1, { referencedTable: "analysis_runs" });
    if (cursor) {
      query = query.or(
        `created_at.lt.${cursor.created_at},and(created_at.eq.${cursor.created_at},id.lt.${cursor.id})`,
      );
    }
    const result = await query.limit(limit + 1);
    if (result.error) throw result.error;
    const rows = (result.data ?? []) as MatchRow[];
    const hasMore = rows.length > limit;
    const pageRows = rows.slice(0, limit);
    const response = matchesPageSchema.parse({
      items: pageRows.map((row) => ({
        match_id: row.id,
        sport: row.sport,
        format: row.format,
        ruleset: { id: row.ruleset_id, version: row.ruleset_version },
        created_at: row.created_at,
        state: row.analysis_runs?.[0]?.state ?? null,
        score: { games: row.score?.games ?? [] },
      })),
      next_cursor: hasMore && pageRows.length > 0
        ? encodeCursor({
          version: 1,
          scope,
          created_at: pageRows.at(-1)!.created_at,
          id: pageRows.at(-1)!.id,
        })
        : null,
    });
    return c.json(response, 200);
  });

  api.openapi(listRallies, async (c) => {
    const { id: matchId } = c.req.valid("param");
    const { cursor: token, limit } = c.req.valid("query");
    const scope = `rallies:${matchId}`;
    const cursor = decodeCursor(token, scope, rallyCursorSchema);
    let query = bearerClient(c).from("rallies").select(
      "id,index,start_ms,end_ms,shot_ids,server,winner,winner_state,end_reason,ball_coverage,clip_id,state",
    ).eq("match_id", matchId)
      .order("index", { ascending: true })
      .order("start_ms", { ascending: true })
      .order("id", { ascending: true });
    if (cursor) {
      query = query.or(
        `index.gt.${cursor.index},and(index.eq.${cursor.index},start_ms.gt.${cursor.start_ms}),and(index.eq.${cursor.index},start_ms.eq.${cursor.start_ms},id.gt.${cursor.id})`,
      );
    }
    const result = await query.limit(limit + 1);
    if (result.error) throw result.error;
    const rows = (result.data ?? []) as RallyRow[];
    const hasMore = rows.length > limit;
    const pageRows = rows.slice(0, limit);
    const response = ralliesPageSchema.parse({
      items: pageRows.map(rallyItem),
      next_cursor: hasMore && pageRows.length > 0
        ? encodeCursor({
          version: 1,
          scope,
          index: pageRows.at(-1)!.index,
          start_ms: pageRows.at(-1)!.start_ms,
          id: pageRows.at(-1)!.id,
        })
        : null,
    });
    return c.json(response, 200);
  });

  api.openapi(getRally, async (c) => {
    const { id: matchId, rid } = c.req.valid("param");
    const user = bearerClient(c);
    const result = await user.from("rallies").select(
      "id,index,start_ms,end_ms,shot_ids,server,winner,winner_state,end_reason,ball_coverage,clip_id,state",
    ).eq("match_id", matchId).eq("id", rid).maybeSingle();
    if (result.error) throw result.error;
    if (!result.data) throw new ApiFailure(404, "not_found", "rally not found");
    const rally = result.data as RallyRow;
    if (!Array.isArray(rally.shot_ids)) {
      throw new ApiFailure(500, "internal", "rally shot ids are invalid");
    }
    const shotIds = rally.shot_ids;
    let shots: ShotRow[] = [];
    if (shotIds.length > 0) {
      const shotResult = await user.from("shots").select(
        "id,rally_id,index,player_id,contact_ms,contact_sigma_ms,phase,reference_plane,coordinate_frame_version,roles,incoming,chosen,flight_time_ms,flight_time_state,context,outcome,quality,clip_id",
      ).eq("match_id", matchId).eq("rally_id", rid).in("id", shotIds);
      if (shotResult.error) throw shotResult.error;
      const byId = new Map(
        ((shotResult.data ?? []) as ShotRow[]).map((row) => [row.id, row]),
      );
      if (shotIds.some((shotId) => !byId.has(shotId))) {
        throw new ApiFailure(500, "internal", "rally shots are incomplete");
      }
      shots = shotIds.map((shotId) => byId.get(shotId)!);
    }
    const response = rallyDetailSchema.parse({
      ...rallyItem(rally),
      shots: shots.map(shotItem),
    });
    return c.json(response, 200);
  });

  api.openapi(listMatchMoments, async (c) => {
    const { id: matchId } = c.req.valid("param");
    const { cursor: token, limit, tag } = c.req.valid("query");
    const scope = JSON.stringify(["match-moments", matchId, tag ?? null]);
    const cursor = decodeCursor(token, scope, momentCursorSchema);
    let query = bearerClient(c).from("moments").select(
      "id,match_id,rally_id,shot_id,start_ms,end_ms,contact_ms,tags,clip_id",
    ).eq("match_id", matchId).order("start_ms", { ascending: true })
      .order("id", { ascending: true });
    if (tag !== undefined) query = query.contains("tags", [tag]);
    if (cursor) {
      query = query.or(
        `start_ms.gt.${cursor.start_ms},and(start_ms.eq.${cursor.start_ms},id.gt.${cursor.id})`,
      );
    }
    const result = await query.limit(limit + 1);
    if (result.error) throw result.error;
    const rows = (result.data ?? []) as MomentRow[];
    const hasMore = rows.length > limit;
    const pageRows = rows.slice(0, limit);
    const response = momentsPageSchema.parse({
      items: pageRows.map(momentItem),
      next_cursor: hasMore && pageRows.length > 0
        ? encodeCursor({
          version: 1,
          scope,
          start_ms: pageRows.at(-1)!.start_ms,
          id: pageRows.at(-1)!.id,
        })
        : null,
    });
    return c.json(response, 200);
  });

  api.openapi(listMoments, async (c) => {
    const { cursor: token, limit, tag, match_id } = c.req.valid("query");
    const scope = JSON.stringify(["moments", match_id ?? null, tag ?? null]);
    const cursor = decodeCursor(token, scope, momentCursorSchema);
    let query = bearerClient(c).from("moments").select(
      "id,match_id,rally_id,shot_id,start_ms,end_ms,contact_ms,tags,clip_id",
    ).order("start_ms", { ascending: true }).order("id", { ascending: true });
    if (match_id !== undefined) query = query.eq("match_id", match_id);
    if (tag !== undefined) query = query.contains("tags", [tag]);
    if (cursor) {
      query = query.or(
        `start_ms.gt.${cursor.start_ms},and(start_ms.eq.${cursor.start_ms},id.gt.${cursor.id})`,
      );
    }
    const result = await query.limit(limit + 1);
    if (result.error) throw result.error;
    const rows = (result.data ?? []) as MomentRow[];
    const hasMore = rows.length > limit;
    const pageRows = rows.slice(0, limit);
    const response = momentsPageSchema.parse({
      items: pageRows.map(momentItem),
      next_cursor: hasMore && pageRows.length > 0
        ? encodeCursor({
          version: 1,
          scope,
          start_ms: pageRows.at(-1)!.start_ms,
          id: pageRows.at(-1)!.id,
        })
        : null,
    });
    return c.json(response, 200);
  });
}
