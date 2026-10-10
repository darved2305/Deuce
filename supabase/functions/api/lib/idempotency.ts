import type { SupabaseClient } from "@supabase/supabase-js";

export type IdemResult =
  | { kind: "new" }
  | { kind: "replay"; status: number; response: unknown }
  | { kind: "conflict" };

export type IdemReservationResult =
  | { kind: "reserved" }
  | { kind: "pending" }
  | { kind: "replay"; status: number; response: unknown }
  | { kind: "conflict" };

const TTL_MS = 24 * 60 * 60 * 1000;
const RESERVATION_TTL_MS = 5 * 60 * 1000;
const RESERVED_STATUS = 0;

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

// Atomically claims a key before session provisioning. A duplicate either replays the saved
// response, observes the in-flight reservation, or conflicts on a different request body.
export async function idemReserve(
  admin: SupabaseClient,
  key: string,
  ownerId: string,
  endpoint: string,
  hash: string,
): Promise<IdemReservationResult> {
  const createdAt = new Date().toISOString();
  const inserted = await admin.from("idempotency").upsert({
    key,
    owner_id: ownerId,
    endpoint,
    request_hash: hash,
    status_code: RESERVED_STATUS,
    response: { reserved: true },
    created_at: createdAt,
  }, { onConflict: "key,owner_id,endpoint", ignoreDuplicates: true })
    .select("key").maybeSingle();
  if (inserted.error) throw inserted.error;
  if (inserted.data) return { kind: "reserved" };

  const existing = await admin.from("idempotency")
    .select("request_hash,status_code,response,created_at")
    .eq("key", key).eq("owner_id", ownerId).eq("endpoint", endpoint)
    .maybeSingle();
  if (existing.error) throw existing.error;
  if (!existing.data) return await idemReserve(admin, key, ownerId, endpoint, hash);

  const row = existing.data as {
    request_hash: string;
    status_code: number;
    response: unknown;
    created_at: string;
  };
  const ageMs = Date.now() - Date.parse(row.created_at);
  if (ageMs > TTL_MS) {
    const deleted = await admin.from("idempotency").delete()
      .eq("key", key).eq("owner_id", ownerId).eq("endpoint", endpoint)
      .lt("created_at", new Date(Date.now() - TTL_MS).toISOString())
      .select("key").maybeSingle();
    if (deleted.error) throw deleted.error;
    return await idemReserve(admin, key, ownerId, endpoint, hash);
  }
  if (row.request_hash !== hash) return { kind: "conflict" };
  if (row.status_code !== RESERVED_STATUS) {
    return { kind: "replay", status: row.status_code, response: row.response };
  }
  if (ageMs > RESERVATION_TTL_MS) {
    const reclaimed = await admin.from("idempotency").update({
      created_at: createdAt,
    }).eq("key", key).eq("owner_id", ownerId).eq("endpoint", endpoint)
      .eq("request_hash", hash).eq("status_code", RESERVED_STATUS)
      .lt("created_at", new Date(Date.now() - RESERVATION_TTL_MS).toISOString())
      .select("key").maybeSingle();
    if (reclaimed.error) throw reclaimed.error;
    if (reclaimed.data) return { kind: "reserved" };
  }
  return { kind: "pending" };
}

export async function idemWaitForReservation(
  admin: SupabaseClient,
  key: string,
  ownerId: string,
  endpoint: string,
  hash: string,
): Promise<IdemReservationResult> {
  let result = await idemReserve(admin, key, ownerId, endpoint, hash);
  for (let i = 0; result.kind === "pending" && i < 50; i++) {
    await new Promise<void>((resolve) => setTimeout(resolve, 100));
    result = await idemReserve(admin, key, ownerId, endpoint, hash);
  }
  return result;
}

export async function idemCompleteReservation(
  admin: SupabaseClient,
  key: string,
  ownerId: string,
  endpoint: string,
  hash: string,
  status: number,
  response: unknown,
): Promise<void> {
  const stored = await admin.from("idempotency").update({
    status_code: status,
    response,
    created_at: new Date().toISOString(),
  }).eq("key", key).eq("owner_id", ownerId).eq("endpoint", endpoint)
    .eq("request_hash", hash).eq("status_code", RESERVED_STATUS)
    .select("key").maybeSingle();
  if (stored.error) throw stored.error;
  if (!stored.data) throw new Error("idempotency reservation was not stored");
}

export async function idemReleaseReservation(
  admin: SupabaseClient,
  key: string,
  ownerId: string,
  endpoint: string,
  hash: string,
): Promise<void> {
  const { error } = await admin.from("idempotency").delete()
    .eq("key", key).eq("owner_id", ownerId).eq("endpoint", endpoint)
    .eq("request_hash", hash).eq("status_code", RESERVED_STATUS);
  if (error) throw error;
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
