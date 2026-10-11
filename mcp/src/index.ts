import { McpServer } from "@modelcontextprotocol/server";
import { serveStdio } from "@modelcontextprotocol/server/stdio";
import { createRequire } from "node:module";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import type * as ContractVendor from "../../supabase/functions/api/lib/contracts_vendor/index.ts";
import * as z from "zod/v4";

const contracts = createRequire(import.meta.url)(
  "../../supabase/functions/api/lib/contracts_vendor/index.ts",
) as typeof ContractVendor;
const {
  capabilitiesResponseSchema,
  errorEnvelopeSchema,
  matchIdSchema,
  matchV1Schema,
  momentsPageSchema,
  playerIdSchema,
  rallyDetailSchema,
  rallyIdSchema,
} = contracts;

const DEFAULT_API_BASE =
  "https://zpwxjjsugujmihhkmfjx.supabase.co/functions/v1/api";

const getMatchSummaryInput = z.object({
  match_id: matchIdSchema.describe("Match ID from the Elevate API."),
});

const findMomentsInput = z.object({
  match_id: matchIdSchema.optional(),
  tags: z.array(z.string()).optional(),
  player: playerIdSchema.optional(),
  outcome: z.enum(["won", "lost"]).optional(),
  limit: z.number().int().max(20).optional(),
});

const getRallyInput = z.object({
  match_id: matchIdSchema,
  rally_id: rallyIdSchema,
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

function authHeaders(): Headers {
  const headers = new Headers({ accept: "application/json" });
  const apiKey = process.env.ELEVATE_API_KEY;
  const jwt = process.env.ELEVATE_JWT;
  if (apiKey) headers.set("X-API-Key", apiKey);
  else if (jwt) headers.set("Authorization", `Bearer ${jwt}`);
  else throw new Error("Set ELEVATE_API_KEY or ELEVATE_JWT to query the API.");
  return headers;
}

async function requestApi(path: string): Promise<unknown> {
  const response = await fetch(`${apiBase()}${path}`, {
    method: "GET",
    headers: authHeaders(),
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
): Promise<T> {
  const paths = await getOpenApiPaths();
  if (!hasGetOperation(paths[path])) {
    throw new ToolUnavailable(
      `Not available yet: GET ${path} is not present in the deployed OpenAPI document.`,
    );
  }
  const parsed = schema.safeParse(await requestApi(requestPath));
  if (!parsed.success) {
    throw new Error(
      `The API response for GET ${path} did not match the vendored response schema.`,
    );
  }
  return parsed.data;
}

async function findMomentsRoute(input: z.infer<typeof findMomentsInput>) {
  if (
    (input.tags?.length ?? 0) > 0 || input.player !== undefined ||
    input.outcome !== undefined || input.limit !== undefined
  ) {
    throw new ToolUnavailable(
      "Not available yet: the vendored API contract does not define a request schema for moments query filters.",
    );
  }
  const route = input.match_id ? "/v1/matches/{id}/moments" : "/v1/moments";
  const requestPath = input.match_id
    ? `/v1/matches/${encodeURIComponent(input.match_id)}/moments`
    : "/v1/moments";
  const page = await requestContract(route, requestPath, momentsPageSchema);
  return {
    items: page.items.map((moment) => ({
      ...moment,
      deep_link:
        `elevate://m/${moment.match_id}/r/${moment.rally_id}?t_ms=${moment.contact_ms}`,
    })),
    next_cursor: page.next_cursor,
  };
}

function summarizeMatch(value: unknown) {
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

export function createServer(): McpServer {
  const server = new McpServer({ name: "elevate", version: "1.0.0" });

  server.registerTool(
    "get_match_summary",
    {
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
      description:
        "Find real moments for a match or across your matches. Filtered queries are withheld until their API request schemas are defined.",
      inputSchema: findMomentsInput,
    },
    (input) => handleTool("find_moments", () => findMomentsRoute(input)),
  );

  server.registerTool(
    "get_rally",
    {
      description: "Read a rally and its shots.",
      inputSchema: getRallyInput,
    },
    ({ match_id, rally_id }) =>
      handleTool("get_rally", () =>
        requestContract(
          "/v1/matches/{id}/rallies/{rid}",
          `/v1/matches/${encodeURIComponent(match_id)}/rallies/${
            encodeURIComponent(rally_id)
          }`,
          rallyDetailSchema,
        )),
  );

  server.registerTool(
    "get_capabilities",
    {
      description: "Read capability tiers and verification status.",
      inputSchema: z.object({}),
    },
    () =>
      handleTool("get_capabilities", () =>
        requestContract(
          "/v1/capabilities",
          "/v1/capabilities",
          capabilitiesResponseSchema,
        )),
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
