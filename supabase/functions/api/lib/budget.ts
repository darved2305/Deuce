// R2 budget guards G1-G7, G9, G10 (docs/ops/cloudflare-budget.md, D-261011-HW1/HW2).
// Counters live in Postgres (migration r2_budget); every check and increment runs through
// service-role RPCs, so the numbers survive cold starts and concurrent requests.
import type { SupabaseClient } from "@supabase/supabase-js";
import { ApiFailure } from "./errors.ts";

const MIB = 1024 * 1024;
const GIB = 1024 * MIB;

export const BUDGET = {
  maxVideoBytes: 300 * MIB, // G1
  storageBudgetBytes: 2 * GIB, // G2
  storagePauseBytes: 1 * GIB, // G10
  classABudget: 200_000, // G5
  classAPause: 100_000, // G10
  sessionsPerUserPerDay: 5, // G3
  uploadsPerDay: 30, // G3
  partUrlsPerPart: 3, // G4: at most 3 x part count per session
  downloadsPerUserPerDay: 200, // G6
} as const;

export type Snapshot = {
  sessions_today: number;
  uploads_today: number;
  storage_bytes: number;
  class_a: number;
  class_b: number;
};

export function secondsToNextUtcDay(now = new Date()): number {
  const next = Date.UTC(
    now.getUTCFullYear(),
    now.getUTCMonth(),
    now.getUTCDate() + 1,
  );
  return Math.max(1, Math.ceil((next - now.getTime()) / 1000));
}

export function secondsToNextUtcMonth(now = new Date()): number {
  const next = Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + 1, 1);
  return Math.max(1, Math.ceil((next - now.getTime()) / 1000));
}

// G9: only an explicit "false" pauses; an unset variable keeps uploads on.
export function writesEnabled(): boolean {
  return (Deno.env.get("R2_WRITES_ENABLED") ?? "true").trim().toLowerCase() !==
    "false";
}

export async function snapshot(
  admin: SupabaseClient,
  ownerId: string,
): Promise<Snapshot> {
  const res = await admin.rpc("r2_budget_snapshot", { p_owner: ownerId });
  if (res.error) throw res.error;
  const s = (res.data ?? {}) as Record<string, unknown>;
  const n = (k: string) => Number(s[k] ?? 0);
  return {
    sessions_today: n("sessions_today"),
    uploads_today: n("uploads_today"),
    storage_bytes: n("storage_bytes"),
    class_a: n("class_a"),
    class_b: n("class_b"),
  };
}

function paused(message: string, detail: Record<string, unknown>): ApiFailure {
  console.warn("r2_budget_paused", JSON.stringify(detail));
  return new ApiFailure(503, "uploads_paused", message);
}

// G9 (kill switch) and G10 (automatic pause at 10% of the free tier): both answer 503
// uploads_paused. Runs on every route that hands out upload work.
export async function assertWritesAllowed(
  admin: SupabaseClient,
  ownerId: string,
): Promise<Snapshot> {
  if (!writesEnabled()) {
    throw new ApiFailure(503, "uploads_paused", "uploads are paused");
  }
  const snap = await snapshot(admin, ownerId);
  if (snap.storage_bytes >= BUDGET.storagePauseBytes) {
    throw paused("uploads are paused", {
      reason: "storage",
      storage_bytes: snap.storage_bytes,
    });
  }
  if (snap.class_a >= BUDGET.classAPause) {
    throw paused("uploads are paused", {
      reason: "class_a",
      class_a: snap.class_a,
    });
  }
  return snap;
}

// G2, G3, G5 at session creation, after the pause checks.
export function assertCanCreateUpload(snap: Snapshot, bytes: number): void {
  if (
    snap.sessions_today >= BUDGET.sessionsPerUserPerDay ||
    snap.uploads_today >= BUDGET.uploadsPerDay
  ) {
    throw new ApiFailure(
      429,
      "daily_upload_limit",
      "daily upload limit reached",
      undefined,
      secondsToNextUtcDay(),
    );
  }
  if (snap.class_a >= BUDGET.classABudget) {
    throw new ApiFailure(
      429,
      "storage_budget_reached",
      "monthly storage operation budget reached",
      undefined,
      secondsToNextUtcMonth(),
    );
  }
  if (snap.storage_bytes + bytes > BUDGET.storageBudgetBytes) {
    throw new ApiFailure(
      429,
      "storage_budget_reached",
      "storage budget reached",
      undefined,
    );
  }
}

// Atomically reserves today's session/upload slots, storage bytes and one Class A op.
export async function reserveSessionUpload(
  admin: SupabaseClient,
  ownerId: string,
  bytes: number,
): Promise<string> {
  const res = await admin.rpc("r2_reserve_session_upload", {
    p_owner: ownerId,
    p_bytes: bytes,
    p_sessions_limit: BUDGET.sessionsPerUserPerDay,
    p_uploads_limit: BUDGET.uploadsPerDay,
    p_storage_limit: BUDGET.storageBudgetBytes,
    p_storage_pause: BUDGET.storagePauseBytes,
    p_class_a_pause: BUDGET.classAPause,
    p_class_a_limit: BUDGET.classABudget,
  });
  if (res.error) {
    throw new ApiFailure(500, "internal", "upload budget could not be reserved");
  }
  const result = res.data as { reservation_id?: unknown; reason?: unknown } | null;
  if (typeof result?.reservation_id === "string") return result.reservation_id;
  switch (result?.reason) {
    case "daily_upload_limit":
      throw new ApiFailure(
        429,
        "daily_upload_limit",
        "daily upload limit reached",
        undefined,
        secondsToNextUtcDay(),
      );
    case "storage_budget_reached":
      throw new ApiFailure(
        429,
        "storage_budget_reached",
        "storage budget reached",
      );
    case "class_a_budget_reached":
      throw new ApiFailure(
        429,
        "storage_budget_reached",
        "monthly storage operation budget reached",
        undefined,
        secondsToNextUtcMonth(),
      );
    case "storage_paused":
    case "class_a_paused":
      throw new ApiFailure(503, "uploads_paused", "uploads are paused");
    default:
      throw new ApiFailure(500, "internal", "upload budget reservation failed");
  }
}

export async function finishSessionUploadReservation(
  admin: SupabaseClient,
  reservationId: string,
  committed: boolean,
): Promise<void> {
  const res = await admin.rpc("r2_finish_session_upload", {
    p_reservation_id: reservationId,
    p_committed: committed,
  });
  if (res.error || res.data !== true) {
    throw new ApiFailure(500, "internal", "upload budget reservation could not be released");
  }
}

// G4 + Class A counting: takes `count` part URLs against the session's allowance.
export async function takePartUrls(
  admin: SupabaseClient,
  sessionId: string,
  ownerId: string,
  count: number,
  partCount: number,
): Promise<void> {
  const res = await admin.rpc("r2_take_part_urls", {
    p_session: sessionId,
    p_owner: ownerId,
    p_count: count,
    p_limit: partCount * BUDGET.partUrlsPerPart,
  });
  if (res.error) throw res.error;
  if (res.data === null || res.data === undefined) {
    throw new ApiFailure(
      429,
      "too_many_part_requests",
      "too many part URL requests for this upload",
    );
  }
}

// Class A / Class B counting for operations the API itself performs. A failed counter write
// stops the request so later R2 operations cannot continue without accounting.
export async function addUsage(
  admin: SupabaseClient,
  classA: number,
  classB: number,
): Promise<void> {
  try {
    const res = await admin.rpc("r2_add_usage", {
      p_class_a: classA,
      p_class_b: classB,
    });
    if (res.error) {
      throw new ApiFailure(500, "internal", "R2 usage could not be recorded");
    }
  } catch (err) {
    if (err instanceof ApiFailure) throw err;
    throw new ApiFailure(500, "internal", "R2 usage could not be recorded");
  }
}

// G6: call before presigning a download URL (artifact reads, a later slice). The kill switch
// does not apply: reads must keep working during an emergency stop.
export async function takeDownloadUrl(
  admin: SupabaseClient,
  ownerId: string,
): Promise<void> {
  const res = await admin.rpc("r2_take_download", {
    p_owner: ownerId,
    p_limit: BUDGET.downloadsPerUserPerDay,
  });
  if (res.error) throw res.error;
  if (res.data === null || res.data === undefined) {
    throw new ApiFailure(
      429,
      "daily_download_limit",
      "daily download limit reached",
      undefined,
      secondsToNextUtcDay(),
    );
  }
}
