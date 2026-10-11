import { createRoute, type OpenAPIHono, z } from "@hono/zod-openapi";
import type { AppEnv } from "../lib/auth.ts";
import {
  errorEnvelopeSchema,
  matchIdSchema,
  matchV1Schema,
} from "../lib/contracts_vendor/index.ts";
import { ApiFailure } from "../lib/errors.ts";

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
}
