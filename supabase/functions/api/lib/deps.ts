import type { SupabaseClient } from "@supabase/supabase-js";
import { adminClient, userClient } from "./db.ts";
import { env } from "./env.ts";
import type { ModalDeps } from "./modal.ts";
import { type R2, r2FromEnv } from "./r2.ts";

export type Claims = { sub: string; isAnonymous: boolean };

export type Deps = {
  user: (jwt: string) => SupabaseClient;
  admin: () => SupabaseClient;
  modal: () => ModalDeps;
  r2: () => R2;
  verifyJwt: (jwt: string) => Promise<Claims | null>;
};

export function trustedClaims(
  raw: unknown,
  supabaseUrl: string,
): Claims | null {
  if (raw === null || typeof raw !== "object") return null;
  const claims = raw as {
    sub?: unknown;
    is_anonymous?: unknown;
    iss?: unknown;
    aud?: unknown;
  };
  const audience = claims.aud;
  const validAudience = audience === "authenticated" ||
    (Array.isArray(audience) && audience.includes("authenticated"));
  if (
    typeof claims.sub !== "string" ||
    claims.iss !== `${supabaseUrl}/auth/v1` || !validAudience
  ) return null;
  return { sub: claims.sub, isAnonymous: claims.is_anonymous === true };
}

// A missing or malformed JWKS falls back to getClaims fetching the project's keys.
type Jwks = NonNullable<
  NonNullable<Parameters<SupabaseClient["auth"]["getClaims"]>[1]>["jwks"]
>;

function parseJwks(raw: string | undefined): Jwks | undefined {
  if (!raw) return undefined;
  try {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed?.keys) ? parsed : undefined;
  } catch {
    return undefined;
  }
}

async function verifyJwt(jwt: string): Promise<Claims | null> {
  const jwks = parseJwks(Deno.env.get("SUPABASE_JWKS"));
  const { data, error } = await userClient(jwt).auth.getClaims(
    jwt,
    jwks ? { jwks } : undefined,
  );
  if (error) return null;
  return trustedClaims(data?.claims, env("SUPABASE_URL"));
}

export function defaultDeps(): Deps {
  return {
    user: userClient,
    admin: adminClient,
    modal: () => ({
      url: env("MODAL_SUBMIT_URL"),
      token: env("MODAL_SUBMIT_TOKEN"),
    }),
    r2: r2FromEnv,
    verifyJwt,
  };
}
