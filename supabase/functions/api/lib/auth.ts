import { createMiddleware } from "hono/factory";
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
  const jwt = header.slice("Bearer ".length);
  const rawJwks = Deno.env.get("SUPABASE_JWKS");
  const jwks = rawJwks ? JSON.parse(rawJwks) : undefined;
  const { data, error } = await userClient(jwt).auth.getClaims(
    jwt,
    jwks ? { jwks } : undefined,
  );
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
