import assert from "node:assert/strict";
import {
  addUsage,
  assertCanCreateUpload,
  BUDGET,
  secondsToNextUtcDay,
  secondsToNextUtcMonth,
  takeDownloadUrl,
  takeSegmentUrl,
  writesEnabled,
} from "../lib/budget.ts";
import { ApiFailure } from "../lib/errors.ts";
import { fakeClient, ok } from "./fakes.ts";

const MIB = 1024 * 1024;
const GIB = 1024 * MIB;
const snap = (o: Partial<Record<string, number>> = {}) => ({
  sessions_today: 0,
  uploads_today: 0,
  storage_bytes: 0,
  class_a: 0,
  class_b: 0,
  ...o,
});

function refused(fn: () => void, code: string, status: number) {
  assert.throws(
    fn,
    (e) => e instanceof ApiFailure && e.code === code && e.status === status,
  );
}

Deno.test("budget numbers are the D-261011-HW2 values", () => {
  assert.equal(BUDGET.maxVideoBytes, 300 * MIB);
  assert.equal(BUDGET.storageBudgetBytes, 2 * GIB);
  assert.equal(BUDGET.storagePauseBytes, 1 * GIB);
  assert.equal(BUDGET.classABudget, 200_000);
  assert.equal(BUDGET.classAPause, 100_000);
  assert.equal(BUDGET.sessionsPerUserPerDay, 5);
  assert.equal(BUDGET.uploadsPerDay, 30);
  assert.equal(BUDGET.partUrlsPerPart, 3);
  assert.equal(BUDGET.downloadsPerUserPerDay, 1000);
});

Deno.test("G2: originals of the last day + artifacts + this upload must stay within 2 GiB", () => {
  assertCanCreateUpload(
    snap({ storage_bytes: 2 * GIB - 100 * MIB }),
    100 * MIB,
  );
  refused(
    () =>
      assertCanCreateUpload(
        snap({ storage_bytes: 2 * GIB - 100 * MIB }),
        100 * MIB + 1,
      ),
    "storage_budget_reached",
    429,
  );
});

Deno.test("G5: refuses new uploads at 200,000 Class A ops in the month", () => {
  assertCanCreateUpload(snap({ class_a: 199_999 }), MIB);
  try {
    assertCanCreateUpload(snap({ class_a: 200_000 }), MIB);
    assert.fail("expected refusal");
  } catch (e) {
    assert.ok(e instanceof ApiFailure);
    assert.equal(e.code, "storage_budget_reached");
    assert.equal(e.status, 429);
    assert.ok((e.retryAfterS ?? 0) > 0);
  }
});

Deno.test("retry windows run to the next UTC day and month", () => {
  const t = new Date(Date.UTC(2026, 9, 11, 23, 0, 0));
  assert.equal(secondsToNextUtcDay(t), 3600);
  assert.equal(secondsToNextUtcMonth(t), 20 * 86400 + 3600);
});

Deno.test("G9 reads R2_WRITES_ENABLED: only false pauses", () => {
  const prev = Deno.env.get("R2_WRITES_ENABLED");
  try {
    Deno.env.delete("R2_WRITES_ENABLED");
    assert.equal(writesEnabled(), true);
    Deno.env.set("R2_WRITES_ENABLED", "true");
    assert.equal(writesEnabled(), true);
    Deno.env.set("R2_WRITES_ENABLED", " False ");
    assert.equal(writesEnabled(), false);
  } finally {
    if (prev === undefined) Deno.env.delete("R2_WRITES_ENABLED");
    else Deno.env.set("R2_WRITES_ENABLED", prev);
  }
});

Deno.test("G6: 1,000 download URLs per user per day, deduped by artifact within validity", async () => {
  const calls: unknown[] = [];
  const allow = fakeClient((c) => {
    calls.push(c);
    return ok(1);
  });
  await takeDownloadUrl(allow, "u1", "art_1");
  assert.deepEqual((calls[0] as { args: unknown }).args, {
    p_owner: "u1",
    p_limit: 1000,
    p_artifact: "art_1",
    p_ttl: 300,
  });
  const deny = fakeClient(() => ok(null));
  await assert.rejects(
    () => takeDownloadUrl(deny, "u1", "art_1"),
    (e) =>
      e instanceof ApiFailure && e.code === "daily_download_limit" &&
      e.status === 429 && (e.retryAfterS ?? 0) > 0,
  );
});

Deno.test("G4: segment URL issuance is atomic and capped at three per segment", async () => {
  const calls: unknown[] = [];
  const admin = fakeClient((c) => {
    calls.push(c);
    return ok(1);
  });
  await takeSegmentUrl(admin, "s_1", 0, "u1");
  assert.deepEqual((calls[0] as { args: unknown }).args, {
    p_session: "s_1",
    p_owner: "u1",
    p_seq: 0,
    p_limit: 3,
  });
  const denied = fakeClient(() => ok(null));
  await assert.rejects(
    () => takeSegmentUrl(denied, "s_1", 0, "u1"),
    (e) =>
      e instanceof ApiFailure && e.code === "too_many_part_requests" &&
      e.status === 429 && e.retryAfterS === 600,
  );
});

Deno.test("R2 usage counter failures fail closed with 503 storage_busy", async () => {
  const admin = fakeClient(() => ({
    data: null,
    error: { code: "XX000", message: "counter unavailable" },
  }));
  await assert.rejects(
    () => addUsage(admin, 0, 1),
    (e) =>
      e instanceof ApiFailure && e.status === 503 &&
      e.code === "storage_busy",
  );
});
