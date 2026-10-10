import type { SupabaseClient } from "@supabase/supabase-js";
import { adminClient, userClient } from "./db.ts";
import { env } from "./env.ts";
import type { ModalDeps } from "./modal.ts";

export type Claims = { sub: string; isAnonymous: boolean };

export type Deps = {
  user: (jwt: string) => SupabaseClient;
  admin: () => SupabaseClient;
  modal: () => ModalDeps;
  verifyJwt: (jwt: string) => Promise<Claims | null>;
};

async function verifyJwt(jwt: string): Promise<Claims | null> {
  const rawJwks = Deno.env.get("SUPABASE_JWKS");
  const jwks = rawJwks ? JSON.parse(rawJwks) : undefined;
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
    verifyJwt,
  };
}
