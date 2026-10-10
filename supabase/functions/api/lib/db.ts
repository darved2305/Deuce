import { createClient, type SupabaseClient } from "@supabase/supabase-js";
import { env } from "./env.ts";

function defaultKey(name: string): string {
  const keys = JSON.parse(env(name)) as Record<string, string>;
  const key = keys["default"];
  if (!key) throw new Error(`env ${name} has no "default" key`);
  return key;
}

export function userClient(jwt: string): SupabaseClient {
  return createClient(
    env("SUPABASE_URL"),
    defaultKey("SUPABASE_PUBLISHABLE_KEYS"),
    {
      auth: {
        persistSession: false,
        autoRefreshToken: false,
        detectSessionInUrl: false,
      },
      global: { headers: { Authorization: `Bearer ${jwt}` } },
    },
  );
}

export function adminClient(): SupabaseClient {
  return createClient(env("SUPABASE_URL"), defaultKey("SUPABASE_SECRET_KEYS"), {
    auth: {
      persistSession: false,
      autoRefreshToken: false,
      detectSessionInUrl: false,
    },
  });
}
