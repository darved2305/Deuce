import assert from "node:assert/strict";
import type { Call, Handler } from "./fakes.ts";
import { makeApp, ok } from "./fakes.ts";

const CREATED_AT = "2026-10-11T15:00:00.000Z";

function makeKey(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  const base64 = btoa(String.fromCharCode(...bytes));
  return `k_${
    base64.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
  }`;
}

async function hashKey(key: string): Promise<string> {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(key),
  );
  return Array.from(
    new Uint8Array(digest),
    (b) => b.toString(16).padStart(2, "0"),
  ).join("");
}

function send(
  app: ReturnType<typeof makeApp>,
  path: string,
  method: string,
  headers: Record<string, string> = {},
  body?: string,
) {
  return app.fetch(
    new Request(`http://localhost/api${path}`, {
      method,
      headers,
      body,
    }),
  );
}

Deno.test("developer key creation returns the secret once and stores only its hash", async () => {
  const adminLog: Call[] = [];
  const idempotency: Record<string, unknown>[] = [];
  const inserted: Record<string, unknown>[] = [];
  const user: Handler = () => ok(null);
  const admin: Handler = (call) => {
    if (call.kind !== "from") return ok(null);
    if (call.table === "idempotency") {
      if (call.op === "select") return ok(idempotency.at(-1) ?? null);
      if (call.op === "upsert") {
        idempotency.push(call.payload as Record<string, unknown>);
        return ok(null);
      }
    }
    if (call.table === "developer_keys" && call.op === "insert") {
      const payload = call.payload as Record<string, unknown>;
      const row = {
        id: "k_record",
        scope: payload.scope,
        created_at: CREATED_AT,
      };
      inserted.push({ ...payload, ...row });
      return ok(row);
    }
    return ok(null);
  };
  const app = makeApp(user, admin, undefined, undefined, adminLog);
  const headers = {
    Authorization: "Bearer jwt",
    "Idempotency-Key": "create-once",
  };

  const first = await send(app, "/v1/developer-keys", "POST", headers);
  assert.equal(first.status, 201);
  const created = await first.json();
  assert.ok(
    typeof created.key === "string" &&
      /^k_[A-Za-z0-9_-]{43}$/.test(created.key),
  );
  assert.deepEqual(
    {
      key_id: created.key_id,
      scope: created.scope,
      created_at: created.created_at,
    },
    { key_id: "k_record", scope: "read", created_at: CREATED_AT },
  );

  assert.equal(inserted[0].key_hash, await hashKey(created.key));
  assert.equal("key" in inserted[0], false);
  assert.equal(idempotency.length, 1);
  assert.equal(
    JSON.stringify(idempotency[0]).includes(created.key),
    false,
    "the one-time secret is not persisted for idempotency replay",
  );

  const replay = await send(app, "/v1/developer-keys", "POST", headers);
  assert.equal(replay.status, 201);
  const replayed = await replay.json();
  assert.equal(replayed.key_id, "k_record");
  assert.equal("key" in replayed, false);
});

Deno.test("developer key creation requires Idempotency-Key", async () => {
  const app = makeApp(() => ok(null), () => ok(null));
  const res = await send(app, "/v1/developer-keys", "POST", {
    Authorization: "Bearer jwt",
  });
  assert.equal(res.status, 422);
  assert.equal((await res.json()).error.code, "validation_failed");
});

Deno.test("non-owner cannot revoke a developer key", async () => {
  const adminLog: Call[] = [];
  const app = makeApp(
    () => ok(null),
    () => ok(null),
    undefined,
    undefined,
    adminLog,
  );
  const res = await send(app, "/v1/developer-keys/k_other", "DELETE", {
    Authorization: "Bearer jwt",
  });
  assert.equal(res.status, 404);
  assert.equal((await res.json()).error.code, "not_found");
  assert.equal(adminLog.length, 0);
});

Deno.test("owner can revoke a key and the revoked secret is unauthorized", async () => {
  const secret = makeKey();
  const secretHash = await hashKey(secret);
  const adminLog: Call[] = [];
  const userLog: Call[] = [];
  let revokedAt: string | null = null;
  const user: Handler = (call) => {
    userLog.push(call);
    if (call.kind === "from" && call.table === "developer_keys") {
      return ok(
        call.filters.id === "k_owned"
          ? { id: "k_owned", revoked_at: revokedAt }
          : null,
      );
    }
    return ok(null);
  };
  const admin: Handler = (call) => {
    if (call.kind === "from" && call.table === "developer_keys") {
      if (call.op === "select") {
        return ok(
          call.filters.key_hash === secretHash && revokedAt === null
            ? { owner_id: "u1", scope: "read" }
            : null,
        );
      }
      if (call.op === "update") {
        revokedAt = (call.payload as { revoked_at: string }).revoked_at;
        return ok({ id: "k_owned" });
      }
    }
    return ok(null);
  };
  const app = makeApp(user, admin, undefined, undefined, adminLog);

  const revoke = await send(
    app,
    "/v1/developer-keys/k_owned",
    "DELETE",
    { Authorization: "Bearer jwt" },
  );
  assert.equal(revoke.status, 204);
  assert.equal(await revoke.text(), "");
  assert.ok(revokedAt);
  assert.ok(
    userLog.some((call) =>
      call.kind === "from" && call.table === "developer_keys"
    ),
  );
  const update = adminLog.find((call) =>
    call.kind === "from" && call.op === "update"
  );
  assert.ok(update && update.kind === "from");
  assert.equal(update.filters.id, "k_owned");
  assert.equal(update.filters.owner_id, "u1");

  const afterRevoke = await send(
    app,
    "/v1/analyses/a_owned",
    "GET",
    { "X-API-Key": secret },
  );
  assert.equal(afterRevoke.status, 401);
  assert.equal((await afterRevoke.json()).error.code, "unauthorized");
});

Deno.test("API key reads only the owner's analysis", async () => {
  const secret = makeKey();
  const actualHash = await hashKey(secret);
  const adminLog: Call[] = [];
  const run = {
    id: "a_owned",
    session_id: "s_owned",
    match_id: "m_owned",
    state: "completed",
    engine_version: "engine-v1",
    last_seq: 3,
    failure_reason: null,
    created_at: CREATED_AT,
    updated_at: CREATED_AT,
    finalized_at: CREATED_AT,
  };
  const user: Handler = () => ok(null);
  const admin: Handler = (call) => {
    if (call.kind !== "from") return ok(null);
    if (call.table === "developer_keys" && call.op === "select") {
      return ok(
        call.filters.key_hash === actualHash
          ? { owner_id: "owner-a", scope: "read" }
          : null,
      );
    }
    if (call.table === "analysis_runs" && call.op === "select") {
      if (
        call.filters.id === "a_owned" && call.filters.owner_id === "owner-a"
      ) {
        return ok(run);
      }
      if (
        call.filters.id === "a_other" && call.filters.owner_id === undefined
      ) {
        return ok({ ...run, id: "a_other", owner_id: "owner-b" });
      }
    }
    return ok(null);
  };
  const app = makeApp(user, admin, undefined, undefined, adminLog);

  const own = await send(app, "/v1/analyses/a_owned", "GET", {
    "X-API-Key": secret,
  });
  assert.equal(own.status, 200);
  assert.equal((await own.json()).analysis_id, "a_owned");

  const other = await send(app, "/v1/analyses/a_other", "GET", {
    "X-API-Key": secret,
  });
  assert.equal(other.status, 404);
  assert.equal((await other.json()).error.code, "not_found");
  assert.ok(
    adminLog.some((call) =>
      call.kind === "from" && call.table === "analysis_runs" &&
      call.filters.owner_id === "owner-a"
    ),
  );
});

Deno.test("API key cannot use POST routes", async () => {
  const secret = makeKey();
  const secretHash = await hashKey(secret);
  const admin: Handler = (call) => {
    if (call.kind === "from" && call.table === "developer_keys") {
      return ok(
        call.filters.key_hash === secretHash
          ? { owner_id: "u1", scope: "read" }
          : null,
      );
    }
    return ok(null);
  };
  const app = makeApp(() => ok(null), admin);
  const res = await send(app, "/v1/analyses", "POST", { "X-API-Key": secret });
  assert.equal(res.status, 403);
  assert.equal((await res.json()).error.code, "read_only_key");
});

Deno.test("invalid API key is unauthorized", async () => {
  const secret = makeKey();
  const admin: Handler = () => ok(null);
  const app = makeApp(() => ok(null), admin);
  const res = await send(app, "/v1/analyses/a_owned", "GET", {
    "X-API-Key": secret,
  });
  assert.equal(res.status, 401);
  assert.equal((await res.json()).error.code, "unauthorized");
});
