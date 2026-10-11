import { createMiddleware } from "hono/factory";
import type { Deps } from "./deps.ts";
import { fail } from "./errors.ts";

export type AppEnv = {
  Variables: {
    userId: string;
    isAnonymous: boolean;
    readOnly: boolean;
    deps: Deps;
  };
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

export async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(value),
  );
  return Array.from(
    new Uint8Array(digest),
    (b) => b.toString(16).padStart(2, "0"),
  ).join("");
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
  if (
    PUBLIC_PATHS.has(c.req.path) &&
    (c.req.method === "GET" || c.req.header("X-API-Key") === undefined)
  ) return await next();

  const key = c.req.header("X-API-Key");
  if (key !== undefined) {
    if (!/^k_[A-Za-z0-9_-]{43}$/.test(key)) {
      return fail(c, 401, "unauthorized", "unauthorized");
    }
    const { data, error } = await c.var.deps.admin().from("developer_keys")
      .select("owner_id,scope")
      .eq("key_hash", await sha256Hex(key))
      .is("revoked_at", null)
      .maybeSingle();
    if (error) throw error;
    const developerKey = data as { owner_id: string; scope: string } | null;
    if (!developerKey || developerKey.scope !== "read") {
      return fail(c, 401, "unauthorized", "unauthorized");
    }

    c.set("userId", developerKey.owner_id);
    c.set("isAnonymous", false);
    c.set("readOnly", true);
    if (c.req.method !== "GET") {
      return fail(
        c,
        403,
        "read_only_key",
        "API key does not support this route",
      );
    }
    return await next();
  }

  const header = c.req.header("Authorization") ?? "";
  if (header.startsWith("Bearer ")) {
    const claims = await c.var.deps.verifyJwt(header.slice("Bearer ".length));
    if (!claims) return fail(c, 401, "unauthorized", "invalid token");
    c.set("userId", claims.sub);
    c.set("isAnonymous", claims.isAnonymous);
    c.set("readOnly", false);
    return await next();
  }
  return fail(c, 401, "unauthorized", "unauthorized");
});
