import { createRoute, type OpenAPIHono } from "@hono/zod-openapi";
import type { AppEnv } from "../lib/auth.ts";
import { healthResponseSchema } from "../lib/contracts_vendor/index.ts";

const healthRoute = createRoute({
  method: "get",
  path: "/v1/health",
  responses: {
    200: {
      description: "liveness",
      content: {
        "application/json": {
          schema: healthResponseSchema,
        },
      },
    },
  },
});

export function registerHealth(api: OpenAPIHono<AppEnv>): void {
  api.openapi(healthRoute, (c) => c.json({ status: "ok" as const }, 200));
}
