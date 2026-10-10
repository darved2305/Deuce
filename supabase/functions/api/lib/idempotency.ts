import type { SupabaseClient } from "@supabase/supabase-js";

export type IdemResult =
  | { kind: "new" }
  | { kind: "replay"; status: number; response: unknown }
  | { kind: "conflict" };

const TTL_MS = 24 * 60 * 60 * 1000;

function canonical(v: unknown): string {
  if (Array.isArray(v)) return `[${v.map(canonical).join(",")}]`;
  if (v && typeof v === "object") {
    const o = v as Record<string, unknown>;
    const parts = Object.keys(o).sort().map((k) =>
      `${JSON.stringify(k)}:${canonical(o[k])}`
    );
    return `{${parts.join(",")}}`;
  }
  return JSON.stringify(v) ?? "null";
}

export async function canonicalHash(body: unknown): Promise<string> {
  const bytes = new TextEncoder().encode(canonical(body));
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, "0")).join("");
}

export async function idemBegin(
  admin: SupabaseClient,
  key: string,
  ownerId: string,
  endpoint: string,
  hash: string,
): Promise<IdemResult> {
  const { data, error } = await admin.from("idempotency")
    .select("request_hash,status_code,response,created_at")
    .eq("key", key).eq("owner_id", ownerId).eq("endpoint", endpoint)
    .maybeSingle();
  if (error) throw error;
  if (!data) return { kind: "new" };
  const row = data as {
    request_hash: string;
    status_code: number;
    response: unknown;
    created_at: string;
  };
  if (Date.now() - Date.parse(row.created_at) > TTL_MS) return { kind: "new" };
  if (row.request_hash !== hash) return { kind: "conflict" };
  return { kind: "replay", status: row.status_code, response: row.response };
}

export async function idemStore(
  admin: SupabaseClient,
  key: string,
  ownerId: string,
  endpoint: string,
  hash: string,
  status: number,
  response: unknown,
): Promise<void> {
  if (status !== 200 && status !== 201 && status !== 202) return;
  const { error } = await admin.from("idempotency").upsert({
    key,
    owner_id: ownerId,
    endpoint,
    request_hash: hash,
    status_code: status,
    response,
    created_at: new Date().toISOString(),
  }, { onConflict: "key,owner_id,endpoint" });
  if (error) throw error;
}
