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
  const claims = data?.claims as
    | { sub?: unknown; is_anonymous?: unknown }
    | undefined;
  if (error || typeof claims?.sub !== "string") return null;
  return { sub: claims.sub, isAnonymous: claims.is_anonymous === true };
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
