import { McpServer } from "@modelcontextprotocol/server";
import { serveStdio } from "@modelcontextprotocol/server/stdio";
import { createRequire } from "node:module";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import type * as ContractVendor from "../../supabase/functions/api/lib/contracts_vendor/index.ts";
import * as z from "zod/v4";
import {
  countHits,
  eventSummary,
  filterMoments,
  landingZones,
  rallyDetail,
  rewatchPicks,
  windowProgress,
} from "./coach.ts";

const contracts = createRequire(import.meta.url)(
  "../../supabase/functions/api/lib/contracts_vendor/index.ts",
) as typeof ContractVendor;
const {
  analysisIdSchema,
  analysisGetResponseSchema,
  analysisEventsPageSchema,
  capabilitiesResponseSchema,
  comparisonsResponseSchema,
  feedPageSchema,
  errorEnvelopeSchema,
  matchIdSchema,
  matchV1Schema,
  momentsPageSchema,
  playerIdSchema,
  rallyDetailSchema,
  rallyIdSchema,
  shotIdSchema,
} = contracts;

const DEFAULT_API_BASE =
  "https://zpwxjjsugujmihhkmfjx.supabase.co/functions/v1/api";

export const getMatchSummaryInput = z.object({
  match_id: matchIdSchema.describe("Match ID from the Elevate API."),
});

export const getFriendFeedInput = z.object({
  cursor: z.string().min(1).optional().describe("Opaque cursor from the previous feed page."),
});

export const findMomentsInput = z.object({
  match_id: matchIdSchema.optional().describe(
    "Match to search. Required when any filter is used.",
  ),
  tags: z.array(z.string().min(1).max(40)).max(10).optional().describe(
    "Keep moments that carry every one of these tags.",
  ),
  player: playerIdSchema.optional().describe(
    "Keep moments whose shot was played by this player.",
  ),
  outcome: z.enum(["won", "lost"]).optional().describe(
    "Keep moments from rallies the shot's player won or lost.",
  ),
  limit: z.number().int().min(1).max(20).optional().describe(
    "Maximum moments to return (1 to 20, default 20).",
  ),
});

export const getRallyInput = z.object({
  match_id: matchIdSchema,
  rally_id: rallyIdSchema,
});

export const getCapabilitiesInput = z.object({
  match_id: matchIdSchema.optional().describe(
    "Read the capability tiers recorded on this match instead of the build-wide ones.",
  ),
});

export const rewatchPicksInput = z.object({
  match_id: matchIdSchema,
  focus: z.enum(["longest_rallies", "lost_rallies", "landings"]).default(
    "longest_rallies",
  ).describe(
    "longest_rallies: ranked by shot count. lost_rallies: only rallies you did not win. landings: also returns where each player aimed.",
  ),
  limit: z.number().int().min(1).max(10).default(3),
  player: playerIdSchema.optional().describe(
    "The player to coach. Defaults to the player confirmed as you (user_confirmed).",
  ),
});

export const getLandingZonesInput = z.object({
  match_id: matchIdSchema,
  player: playerIdSchema.optional(),
});

export const compareMomentInput = z.object({
  match_id: matchIdSchema,
  shot_id: shotIdSchema,
  scope: z.enum(["own", "shared", "reference"]).default("own"),
  limit: z.number().int().min(1).max(5).default(5),
});

export const listEventsInput = z.object({
  match_id: matchIdSchema,
  type: z.string().min(1).max(40).optional().describe(
    "Keep only this event type, for example hit or landing.",
  ),
  state: z.enum(["observed", "candidate", "inferred", "user"]).optional()
    .describe("Keep only events with this provenance state."),
  limit: z.number().int().min(1).max(100).default(20),
});

export const getAnalysisProgressInput = z.object({
  analysis_id: analysisIdSchema,
  include_hits: z.boolean().default(true).describe(
    "Download each finished window result and count its hit candidates (at most 20 windows).",
  ),
});

const openApiPaths = new Map<string, Promise<Record<string, unknown>>>();

class ToolUnavailable extends Error {}

function apiBase(): string {
  return (process.env.ELEVATE_API_BASE?.trim() || DEFAULT_API_BASE).replace(
    /\/+$/,
    "",
  );
}

function textResult(value: unknown) {
  return {
    content: [{ type: "text" as const, text: JSON.stringify(value, null, 2) }],
  };
}

function errorResult(message: string) {
  return {
    content: [{ type: "text" as const, text: message }],
    isError: true,
  };
}

async function handleTool(
  name: string,
  operation: () => Promise<unknown>,
) {
  try {
    return textResult(await operation());
  } catch (error) {
    const message = error instanceof Error
      ? error.message
      : "The API request could not be completed.";
    if (!(error instanceof ToolUnavailable)) {
      console.error(`${name}: ${message}`);
    }
    return errorResult(message);
  }
}

// "user" auth is for caller-scoped routes (feed, posts, challenges): they span several accounts
// under RLS, and developer keys are owner-only, so the API rejects keys there (403 read_only_key).
type AuthMode = "any" | "user";

function authHeaders(mode: AuthMode = "any"): Headers {
  const headers = new Headers({ accept: "application/json" });
  const apiKey = process.env.ELEVATE_API_KEY;
  const jwt = process.env.ELEVATE_JWT;
  if (mode === "user") {
    if (!jwt) {
      throw new ToolUnavailable(
        "This tool reads a per-person feed and needs ELEVATE_JWT (a signed-in user's token); developer keys cannot read it.",
      );
    }
    headers.set("Authorization", `Bearer ${jwt}`);
  } else if (apiKey) headers.set("X-API-Key", apiKey);
  else if (jwt) headers.set("Authorization", `Bearer ${jwt}`);
  else throw new Error("Set ELEVATE_API_KEY or ELEVATE_JWT to query the API.");
  return headers;
}

export async function requestApi(
  path: string,
  mode: AuthMode = "any",
): Promise<unknown> {
  const response = await fetch(`${apiBase()}${path}`, {
    method: "GET",
    headers: authHeaders(mode),
  });
  let body: unknown;
  try {
    body = await response.json();
  } catch {
    throw new Error(`The API returned invalid JSON (HTTP ${response.status}).`);
  }
  if (!response.ok) {
    const parsedError = errorEnvelopeSchema.safeParse(body);
    if (parsedError.success) {
      const { code, message } = parsedError.data.error;
      throw new Error(
        `API returned HTTP ${response.status} (${code}): ${message}`,
      );
    }
    throw new Error(`The API request failed with HTTP ${response.status}.`);
  }
  return body;
}

async function getOpenApiPaths(): Promise<Record<string, unknown>> {
  const base = apiBase();
  let request = openApiPaths.get(base);
  if (!request) {
    request = (async () => {
      const response = await fetch(`${base}/v1/openapi.json`, {
        headers: { accept: "application/json" },
      });
      if (!response.ok) {
        throw new Error(
          `The OpenAPI request failed with HTTP ${response.status}.`,
        );
      }
      const document: unknown = await response.json();
      if (
        typeof document !== "object" || document === null ||
        !("paths" in document) || typeof document.paths !== "object" ||
        document.paths === null
      ) {
        throw new Error(
          "The OpenAPI document does not contain a paths object.",
        );
      }
      return document.paths as Record<string, unknown>;
    })();
    openApiPaths.set(base, request);
  }
  try {
    return await request;
  } catch (error) {
    openApiPaths.delete(base);
    throw error;
  }
}

function hasGetOperation(pathItem: unknown): boolean {
  return typeof pathItem === "object" && pathItem !== null &&
    Object.hasOwn(pathItem, "get");
}

async function requestContract<T>(
  path: string,
  requestPath: string,
  schema: z.ZodType<T>,
  mode: AuthMode = "any",
): Promise<T> {
  const paths = await getOpenApiPaths();
  if (!hasGetOperation(paths[path])) {
    throw new ToolUnavailable(
      `Not available yet: GET ${path} is not present in the deployed OpenAPI document.`,
    );
  }
  const parsed = schema.safeParse(await requestApi(requestPath, mode));
  if (!parsed.success) {
    throw new Error(
      `The API response for GET ${path} did not match the vendored response schema.`,
    );
  }
  return parsed.data;
}

async function getMatch(matchId: string) {
  const parsed = matchV1Schema.safeParse(
    await requestApi(`/v1/matches/${encodeURIComponent(matchId)}`),
  );
  if (!parsed.success) {
    throw new Error(
      "The API response did not match the vendored match.v1 schema.",
    );
  }
  return parsed.data;
}

async function findMomentsRoute(input: z.infer<typeof findMomentsInput>) {
  const filtered = (input.tags?.length ?? 0) > 0 || input.player !== undefined ||
    input.outcome !== undefined || input.limit !== undefined;
  if (input.match_id) {
    // match.v1 carries the moments, shots and rallies, so filters are applied here, typed.
    return filterMoments(await getMatch(input.match_id), input);
  }
  if (filtered) {
    throw new ToolUnavailable(
      "Filters need a match_id: pass the match to search.",
    );
  }
  const page = await requestContract(
    "/v1/moments",
    "/v1/moments",
    momentsPageSchema,
  );
  return {
    items: page.items.map((moment) => ({
      ...moment,
      deep_link:
        `elevate://m/${moment.match_id}/r/${moment.rally_id}?t_ms=${moment.contact_ms}`,
    })),
    next_cursor: page.next_cursor,
  };
}

export function summarizeMatch(value: unknown) {
  const parsed = matchV1Schema.safeParse(value);
  if (!parsed.success) {
    throw new Error(
      "The API response did not match the vendored match.v1 schema.",
    );
  }
  const match = parsed.data;
  const hero = match.insights.find((insight) => insight.kind === "hero");
  const firstRally =
    [...match.rallies].sort((a, b) => a.start_ms - b.start_ms)[0];
  return {
    match_id: match.match_id,
    score: match.score,
    hero_insight: hero
      ? {
        caption: hero.caption,
        count: hero.count,
        evidence_moment_ids: hero.evidence_moment_ids,
      }
      : null,
    rally_count: match.rallies.length,
    events: eventSummary(match.events, { limit: 0 }).counts,
    capability_tiers: Object.fromEntries(
      Object.entries(match.capabilities).map(([name, capability]) => [
        name,
        { tier: capability.tier, status: capability.status },
      ]),
    ),
    deep_link: firstRally
      ? `elevate://m/${match.match_id}/r/${firstRally.rally_id}?t_ms=${firstRally.start_ms}`
      : null,
  };
}

// Every tool only reads the Elevate API: tell clients so they need not confirm each call.
const READ_ONLY = {
  readOnlyHint: true,
  destructiveHint: false,
  idempotentHint: true,
  openWorldHint: false,
} as const;

export function createServer(): McpServer {
  const server = new McpServer({ name: "elevate", version: "1.0.0" });

  server.registerTool(
    "get_friend_feed",
    {
      title: "Get friend feed",
      annotations: READ_ONLY,
      description:
        "Read the caller's and friends' frozen match posts, newest first. Landing counts remain MODEL ESTIMATE values.",
      inputSchema: getFriendFeedInput,
    },
    ({ cursor }) =>
      handleTool("get_friend_feed", () =>
        requestContract(
          "/v1/feed",
          cursor ? `/v1/feed?cursor=${encodeURIComponent(cursor)}` : "/v1/feed",
          feedPageSchema,
          "user",
        )),
  );

  server.registerTool(
    "get_match_summary",
    {
      title: "Get match summary",
      annotations: READ_ONLY,
      description:
        "Read a match.v1 record and return its score, hero insight, rally count, capability tiers, and a deep link.",
      inputSchema: getMatchSummaryInput,
    },
    ({ match_id }) =>
      handleTool("get_match_summary", async () =>
        summarizeMatch(
          await requestApi(`/v1/matches/${encodeURIComponent(match_id)}`),
        )),
  );

  server.registerTool(
    "find_moments",
    {
      title: "Find moments",
      annotations: READ_ONLY,
      description:
        "Find real moments for a match or across your matches. Filtered queries are withheld until their API request schemas are defined.",
      inputSchema: findMomentsInput,
    },
    (input) => handleTool("find_moments", () => findMomentsRoute(input)),
  );

  server.registerTool(
    "get_rally",
    {
      title: "Get rally",
      annotations: READ_ONLY,
      description:
        "Read one rally of a match with its shots (landing zones, contact times, provenance states) and a deep link to its start.",
      inputSchema: getRallyInput,
    },
    ({ match_id, rally_id }) =>
      handleTool("get_rally", async () => {
        const detail = rallyDetail(await getMatch(match_id), rally_id);
        if (!detail) {
          throw new Error(`Rally ${rally_id} is not in match ${match_id}.`);
        }
        return detail;
      }),
  );

  server.registerTool(
    "list_events",
    {
      title: "List events",
      annotations: READ_ONLY,
      description:
        "The engine's detections on the match timeline (hits, landings) with their provenance state (observed, candidate, inferred, user), time and time uncertainty. Returns counts per type and state over the whole match plus the first events in time order. Candidates are not confirmed facts: say so when quoting them.",
      inputSchema: listEventsInput,
    },
    ({ match_id, type, state, limit }) =>
      handleTool("list_events", async () => ({
        match_id,
        ...eventSummary((await getMatch(match_id)).events, {
          type,
          state,
          limit,
        }),
      })),
  );

  server.registerTool(
    "get_capabilities",
    {
      title: "Get capabilities",
      annotations: READ_ONLY,
      description:
        "Read capability tiers and verification status, build-wide or for one match.",
      inputSchema: getCapabilitiesInput,
    },
    ({ match_id }) =>
      handleTool("get_capabilities", async () =>
        match_id
          ? { capabilities: (await getMatch(match_id)).capabilities }
          : await requestContract(
            "/v1/capabilities",
            "/v1/capabilities",
            capabilitiesResponseSchema,
          )),
  );

  server.registerTool(
    "rewatch_picks",
    {
      title: "Rewatch picks",
      annotations: READ_ONLY,
      description:
        "Answer 'which rallies should I rewatch and why?'. Ranks rallies by length (or only the ones you lost), gives a checkable reason for each, the match's top insights, and a deep link elevate://m/{match}/r/{rally}?t_ms= that opens the Elevate app at that moment. Counts come from the record; quote them, do not recompute.",
      inputSchema: rewatchPicksInput,
    },
    (input) =>
      handleTool("rewatch_picks", async () => {
        const match = await getMatch(input.match_id);
        const picks = rewatchPicks(match, input);
        return input.focus === "landings"
          ? { ...picks, landing_zones: landingZones(match, input.player) }
          : picks;
      }),
  );

  server.registerTool(
    "get_landing_zones",
    {
      title: "Get landing zones",
      annotations: READ_ONLY,
      description:
        "Where each player aimed their shots in a match: zone counts and shares with denominators, plus how many were observed versus inferred.",
      inputSchema: getLandingZonesInput,
    },
    ({ match_id, player }) =>
      handleTool("get_landing_zones", async () => ({
        match_id,
        players: landingZones(await getMatch(match_id), player),
      })),
  );

  server.registerTool(
    "compare_moment",
    {
      title: "Compare moment",
      annotations: READ_ONLY,
      description:
        "The most similar moments to one shot (up to 5), from your own matches, shared ones or the reference set, with how often they worked and their provenance.",
      inputSchema: compareMomentInput,
    },
    ({ match_id, shot_id, scope, limit }) =>
      handleTool("compare_moment", async () => {
        const path = `/v1/matches/${encodeURIComponent(match_id)}/shots/${
          encodeURIComponent(shot_id)
        }/comparisons?scope=${scope}&limit=${limit}`;
        const parsed = comparisonsResponseSchema.safeParse(
          await requestApi(path),
        );
        if (!parsed.success) {
          throw new Error(
            "The API response did not match the vendored comparisons schema.",
          );
        }
        return parsed.data;
      }),
  );

  server.registerTool(
    "get_analysis_progress",
    {
      title: "Get analysis progress",
      annotations: READ_ONLY,
      description:
        "Live progress of an analysis: state, windows done of expected, and hit candidates so far.",
      inputSchema: getAnalysisProgressInput,
    },
    ({ analysis_id, include_hits }) =>
      handleTool("get_analysis_progress", async () => {
        const id = encodeURIComponent(analysis_id);
        const analysis = analysisGetResponseSchema.parse(
          await requestApi(`/v1/analyses/${id}`),
        );
        const events: Array<Record<string, unknown>> = [];
        let after = 0;
        for (let page = 0; page < 20; page++) {
          const body = analysisEventsPageSchema.parse(
            await requestApi(`/v1/analyses/${id}/events?after_seq=${after}&limit=100`),
          );
          events.push(...(body.events as Array<Record<string, unknown>>));
          after = body.next_after_seq;
          if (!body.has_more) break;
        }
        const progress = windowProgress(
          events as unknown as Parameters<typeof windowProgress>[0],
        );
        let hits: number | null = null;
        if (include_hits) {
          hits = 0;
          for (const artifactId of progress.window_artifact_ids.slice(0, 20)) {
            const signed = await requestApi(
              `/v1/artifacts/${encodeURIComponent(artifactId)}`,
            ) as { url?: string };
            if (!signed.url) continue;
            const res = await fetch(signed.url);
            if (res.ok) hits += countHits(await res.json());
          }
        }
        return {
          analysis_id: analysis.analysis_id,
          match_id: analysis.match_id,
          state: analysis.state,
          engine_version: analysis.engine_version,
          failure_reason: analysis.failure_reason,
          windows_expected: progress.windows_expected,
          windows_done: progress.windows_done,
          windows_failed: progress.windows_failed,
          hit_candidates_so_far: hits,
          last_seq: analysis.last_seq,
        };
      }),
  );

  return server;
}

const entrypoint = process.argv[1];
if (entrypoint && fileURLToPath(import.meta.url) === resolve(entrypoint)) {
  try {
    await serveStdio(createServer);
  } catch (error) {
    console.error(
      "MCP server failed to start:",
      error instanceof Error ? error.message : error,
    );
    process.exitCode = 1;
  }
}
