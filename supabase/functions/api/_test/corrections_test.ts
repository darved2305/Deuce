import assert from "node:assert/strict";
import { canonicalHash } from "../lib/idempotency.ts";
import { type Call, type Handler, makeApp, ok, type Res } from "./fakes.ts";
import { check, load } from "./jsonschema.ts";

const BODY = {
  target: { type: "rally", id: "r_1" },
  field: "winner",
  old: "p_2",
  new: "p_1",
  reason: "player correction",
};
const STORED = {
  id: "cr_1",
  target_type: "rally",
  target_id: "r_1",
  field: "winner",
  old: "p_2",
  new: "p_1",
  reason: BODY.reason,
  created_at: "2026-10-10T10:00:00Z",
};
const RESPONSE = {
  correction_id: "cr_1",
  target: BODY.target,
  field: BODY.field,
  old: BODY.old,
  new: BODY.new,
  actor: "p_1",
  at: STORED.created_at,
  reason: BODY.reason,
};
type Options = { match?: unknown; players?: unknown; idem?: Res };

function build(options: Options = {}) {
  const userLog: Call[] = [];
  const adminLog: Call[] = [];
  const user: Handler = (call) => {
    userLog.push(call);
    if (call.kind !== "from") return ok(null);
    if (call.table === "matches") {
      return ok("match" in options ? options.match : { id: "m_1" });
    }
    if (call.table === "analysis_runs") {
      return ok(
        "players" in options
          ? options.players
          : [{ players: [{ player_id: "p_1", identity: "user_confirmed" }] }],
      );
    }
    if (call.table === "corrections" && call.op === "insert") {
      return ok(STORED);
    }
    return ok(null);
  };
  const admin: Handler = (call) => {
    if (call.kind === "from" && call.table === "idempotency") {
      return call.op === "select" ? options.idem ?? ok(null) : ok(null);
    }
    return ok(null);
  };
  return {
    app: makeApp(user, admin, undefined, [], adminLog),
    userLog,
    adminLog,
  };
}

function post(
  app: ReturnType<typeof build>["app"],
  body: unknown = BODY,
  headers: HeadersInit = { "Idempotency-Key": "k1" },
  matchId = "m_1",
) {
  return app.fetch(
    new Request(`http://localhost/api/v1/matches/${matchId}/corrections`, {
      method: "POST",
      headers: {
        Authorization: "Bearer jwt",
        "Content-Type": "application/json",
        ...headers,
      },
      body: JSON.stringify(body),
    }),
  );
}

const errorCode = async (res: Response) =>
  ((await res.json()) as { error: { code: string } }).error.code;

Deno.test("owner appends a contract-shaped correction with auth UUID audit actor", async () => {
  const { app, userLog, adminLog } = build();
  const res = await post(app);
  assert.equal(res.status, 201);
  const body = await res.json();
  assert.deepEqual(body, RESPONSE);

  const insert = userLog.find((call) =>
    call.kind === "from" && call.table === "corrections" && call.op === "insert"
  ) as Call & { payload: Record<string, unknown> };
  assert.deepEqual(insert.payload, {
    match_id: "m_1",
    owner_id: "u1",
    target_type: BODY.target.type,
    target_id: BODY.target.id,
    field: BODY.field,
    old: BODY.old,
    new: BODY.new,
    actor: "u1",
    reason: BODY.reason,
  });
  assert.ok(
    userLog.indexOf(
      userLog.find((call) => call.kind === "from" && call.table === "matches")!,
    ) < userLog.indexOf(insert),
  );
  assert.ok(
    !adminLog.some((call) =>
      call.kind === "from" && call.table === "corrections"
    ),
  );

  const schema = load("match.v1");
  const correctionItem = (schema.properties as Record<string, {
    items: Record<string, unknown>;
  }>).corrections.items;
  assert.deepEqual(
    check(correctionItem, body),
    [],
    "match.v1 corrections[] item",
  );
  const stored = adminLog.find((call) =>
    call.kind === "from" && call.table === "idempotency" && call.op === "upsert"
  ) as Call & { payload: { status_code: number; response: unknown } };
  assert.equal(stored.payload.status_code, 201);
  assert.deepEqual(stored.payload.response, RESPONSE);
});

Deno.test("non-owner match hidden by RLS returns 404 before idempotency or insert", async () => {
  const { app, userLog, adminLog } = build({ match: null });
  const res = await post(app);
  assert.equal(res.status, 404);
  assert.equal(await errorCode(res), "not_found");
  assert.ok(
    userLog.some((call) => call.kind === "from" && call.table === "matches"),
  );
  assert.ok(
    !userLog.some((call) =>
      call.kind === "from" && call.table === "corrections"
    ),
  );
  assert.equal(adminLog.length, 0);
});

Deno.test("unknown request fields are rejected with 422 validation_failed", async () => {
  const { app, userLog } = build();
  const res = await post(app, { ...BODY, actor: "p_1" });
  assert.equal(res.status, 422);
  assert.equal(await errorCode(res), "validation_failed");
  assert.equal(userLog.length, 0);
});

Deno.test("missing Idempotency-Key is 422 validation_failed", async () => {
  const { app, userLog } = build();
  const res = await post(app, BODY, {});
  assert.equal(res.status, 422);
  assert.equal(await errorCode(res), "validation_failed");
  assert.equal(userLog.length, 0);
});

Deno.test("idempotency replay returns the exact stored response", async () => {
  const hash = await canonicalHash({ match_id: "m_1", correction: BODY });
  const { app, userLog, adminLog } = build({
    idem: ok({
      request_hash: hash,
      status_code: 201,
      response: RESPONSE,
      created_at: new Date().toISOString(),
    }),
  });
  const res = await post(app);
  assert.equal(res.status, 201);
  assert.deepEqual(await res.json(), RESPONSE);
  assert.equal(userLog.length, 1);
  assert.equal((userLog[0] as { table: string }).table, "matches");
  assert.ok(
    !adminLog.some((call) => call.kind === "from" && call.op === "upsert"),
  );
});

Deno.test("same key and body on another match conflicts instead of replaying", async () => {
  const hash = await canonicalHash({ match_id: "m_1", correction: BODY });
  const { app, userLog } = build({
    match: { id: "m_2" },
    idem: ok({
      request_hash: hash,
      status_code: 201,
      response: RESPONSE,
      created_at: new Date().toISOString(),
    }),
  });
  const res = await post(app, BODY, { "Idempotency-Key": "k1" }, "m_2");
  assert.equal(res.status, 409);
  assert.equal(await errorCode(res), "idempotency_conflict");
  assert.equal(userLog.length, 1);
});

Deno.test("ambiguous user-confirmed players omit wire actor and remain UNVERIFIED", async () => {
  const { app } = build({
    players: [{
      players: [
        { player_id: "p_1", identity: "user_confirmed" },
        { player_id: "p_2", identity: "user_confirmed" },
      ],
    }],
  });
  const res = await post(app);
  assert.equal(res.status, 201);
  const body = await res.json();
  assert.equal("actor" in body, false);
  assert.equal(JSON.stringify(body).includes("u1"), false);
});

Deno.test("corrections route is published in OpenAPI", async () => {
  const { app } = build();
  const res = await app.fetch(
    new Request("http://localhost/api/v1/openapi.json"),
  );
  assert.equal(res.status, 200);
  const doc = await res.json();
  assert.ok(doc.paths["/v1/matches/{id}/corrections"].post);
});
