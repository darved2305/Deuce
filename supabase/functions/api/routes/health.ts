import { createRoute, type OpenAPIHono, z } from "@hono/zod-openapi";
import type { AppEnv } from "../lib/auth.ts";

const healthRoute = createRoute({
  method: "get",
  path: "/v1/health",
  responses: {
    200: {
      description: "liveness",
      content: {
        "application/json": {
          schema: z.object({ status: z.literal("ok") }),
        },
      },
    },
  },
});

export function registerHealth(api: OpenAPIHono<AppEnv>): void {
  api.openapi(healthRoute, (c) => c.json({ status: "ok" as const }, 200));
}
