import { createMiddleware } from "hono/factory";
import { env } from "./env.ts";
import { userClient } from "./db.ts";
import { fail } from "./errors.ts";

export type AppEnv = {
  Variables: { userId: string; isAnonymous: boolean };
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

export const authMiddleware = createMiddleware<AppEnv>(async (c, next) => {
  const path = c.req.path;
  if (path.endsWith("/v1/health") || path.endsWith("/v1/openapi.json")) {
    return await next();
  }
  if (path.includes("/internal/")) {
    const token = c.req.header("X-Internal-Token");
    const expected = Deno.env.get("INTERNAL_API_TOKEN");
    if (!token || !expected || !timingSafeEqual(token, expected)) {
      return fail(c, 401, "unauthorized", "unauthorized");
    }
    return await next();
  }
  const header = c.req.header("Authorization") ?? "";
  if (!header.startsWith("Bearer ")) {
    return fail(c, 401, "unauthorized", "missing bearer token");
  }
  const jwt = header.slice("Bearer ".length);
  const jwks = JSON.parse(env("SUPABASE_JWKS"));
  const { data, error } = await userClient(jwt).auth.getClaims(jwt, { jwks });
  const claims = data?.claims as
    | { sub?: unknown; is_anonymous?: unknown }
    | undefined;
  if (error || typeof claims?.sub !== "string") {
    return fail(c, 401, "unauthorized", "invalid token");
  }
  c.set("userId", claims.sub);
  c.set("isAnonymous", claims.is_anonymous === true);
  return await next();
});
