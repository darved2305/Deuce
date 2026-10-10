import { createMiddleware } from "hono/factory";
import type { Deps } from "./deps.ts";
import { fail } from "./errors.ts";

export type AppEnv = {
  Variables: { userId: string; isAnonymous: boolean; deps: Deps };
};

export function timingSafeEqual(a: string, b: string): boolean {
  const x = new TextEncoder().encode(a);
  const y = new TextEncoder().encode(b);
  let diff = x.length ^ y.length;
  for (let i = 0; i < Math.max(x.length, y.length); i++) {
    diff |= (x[i] ?? 0) ^ (y[i] ?? 0);
  }
  return diff === 0;
}

// Paths include the function name: the root app mounts the API at /api (lib/app.ts).
const PUBLIC_PATHS = new Set(["/api/v1/health", "/api/v1/openapi.json"]);

export const internalAuth = createMiddleware<AppEnv>(async (c, next) => {
  const token = c.req.header("X-Internal-Token");
  const expected = Deno.env.get("INTERNAL_API_TOKEN");
  if (!token || !expected || !timingSafeEqual(token, expected)) {
    return fail(c, 401, "unauthorized", "unauthorized");
  }
  return await next();
});

export const userAuth = createMiddleware<AppEnv>(async (c, next) => {
  if (PUBLIC_PATHS.has(c.req.path)) return await next();
  const header = c.req.header("Authorization") ?? "";
  if (!header.startsWith("Bearer ")) {
    return fail(c, 401, "unauthorized", "missing bearer token");
  }
  const claims = await c.var.deps.verifyJwt(header.slice("Bearer ".length));
  if (!claims) return fail(c, 401, "unauthorized", "invalid token");
  c.set("userId", claims.sub);
  c.set("isAnonymous", claims.isAnonymous);
  return await next();
});
