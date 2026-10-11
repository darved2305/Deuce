import assert from "node:assert/strict";
import { type Call, type Handler, makeApp, ok, type Res } from "./fakes.ts";
import { conforms } from "./jsonschema.ts";

const OWNER = "11111111-1111-4111-8111-111111111111";
const FRIEND = "22222222-2222-4222-8222-222222222222";
const SUMMARY = {
  title: null,
  duration_ms: null,
  rally_count: null,
  hit_count: null,
  landings_in: null,
  landings_out: null,
  longest_rally_ms: null,
  top_coach_points: [],
  landing_points_m: [],
  owner_display_name: "Owner A",
};
const POST_ROW = {
  id: "po_1",
  owner_id: OWNER,
  match_id: "m_1",
  created_at: "2026-10-11T10:00:00Z",
  caption: "Post caption",
  summary: SUMMARY,
};
const FRIEND_ROW = {
  ...POST_ROW,
  id: "po_2",
  owner_id: FRIEND,
  caption: null,
  summary: { ...SUMMARY, owner_display_name: "Friend B" },
};

type Options = {
  post?: unknown | null;
  feedRows?: unknown[];
  createRpc?: Res;
  deleted?: unknown | null;
  idem?: Res;
  key?: Res;
};

function build(options: Options = {}) {
  const userLog: Call[] = [];
  const adminLog: Call[] = [];
  const user: Handler = (call) => {
    userLog.push(call);
    if (call.kind === "rpc" && call.name === "create_match_post") {
      return options.createRpc ?? ok(POST_ROW.id);
    }
    if (call.kind === "from" && call.table === "posts") {
      if (call.op === "delete") {
        return ok(
          options.deleted === undefined ? { id: POST_ROW.id } : options.deleted,
        );
      }
      if ("id" in call.filters) {
        return ok(options.post === undefined ? POST_ROW : options.post);
      }
      return ok(options.feedRows ?? [POST_ROW]);
    }
    return ok(null);
  };
  const admin: Handler = (call) => {
    adminLog.push(call);
    if (call.kind === "from" && call.table === "idempotency") {
      return call.op === "select" ? options.idem ?? ok(null) : ok(null);
    }
    if (call.kind === "from" && call.table === "developer_keys") {
      return options.key ?? ok(null);
    }
    return ok(null);
  };
  return { app: makeApp(user, admin), userLog, adminLog };
}

function req(
  app: ReturnType<typeof build>["app"],
  method: string,
  path: string,
  body?: unknown,
  headers: HeadersInit = {},
) {
  return app.fetch(
    new Request(`http://localhost/api${path}`, {
      method,
      headers: {
        Authorization: "Bearer jwt",
        ...(body === undefined ? {} : { "Content-Type": "application/json" }),
        ...headers,
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    }),
  );
}

const errorCode = async (res: Response) =>
  ((await res.json()) as { error: { code: string } }).error.code;

Deno.test("POST creates an owner post from the stored match and returns post.v1", async () => {
  const { app, userLog, adminLog } = build();
  const res = await req(
    app,
    "POST",
    "/v1/matches/m_1/posts",
    { caption: "Post caption" },
    { "Idempotency-Key": "key-1" },
  );
  assert.equal(res.status, 201);
  const body = await res.json();
  conforms("post.v1", body);
  conforms("post.create.request.v1", { caption: "Post caption" });
  const rpc = userLog.find((call) =>
    call.kind === "rpc" && call.name === "create_match_post"
  ) as
    & Call
    & { args: Record<string, unknown> };
  assert.deepEqual(rpc.args, { p_match_id: "m_1", p_caption: "Post caption" });
  const stored = adminLog.find((call) =>
    call.kind === "from" && call.table === "idempotency" && call.op === "upsert"
  ) as Call & { payload: Record<string, unknown> };
  assert.equal(stored.payload.endpoint, "POST /v1/matches/m_1/posts");
  assert.equal(stored.payload.status_code, 201);
});

Deno.test("POST hides a match the caller does not own", async () => {
  const { app } = build({
    createRpc: {
      data: null,
      error: { code: "P0002", message: "match_not_found" },
    },
  });
  const res = await req(
    app,
    "POST",
    "/v1/matches/m_1/posts",
    {},
    { "Idempotency-Key": "key-1" },
  );
  assert.equal(res.status, 404);
  conforms("error.envelope.v1", await res.json());
});

Deno.test("GET feed returns owner and friend posts in the feed contract", async () => {
  const { app, userLog } = build({ feedRows: [POST_ROW, FRIEND_ROW] });
  const res = await req(app, "GET", "/v1/feed");
  assert.equal(res.status, 200);
  const body = await res.json();
  conforms("feed.page.v1", body);
  assert.deepEqual(
    body.items.map((item: { post_id: string }) => item.post_id),
    ["po_1", "po_2"],
  );
  assert.equal(
    (userLog[0] as { filters: Record<string, unknown> }).filters.limit,
    21,
  );
});

Deno.test("GET post returns a friend-visible post and hides a stranger with 404", async () => {
  const friend = await req(
    build({ post: FRIEND_ROW }).app,
    "GET",
    "/v1/posts/po_2",
  );
  assert.equal(friend.status, 200);
  conforms("post.v1", await friend.json());

  const stranger = await req(
    build({ post: null }).app,
    "GET",
    "/v1/posts/po_2",
  );
  assert.equal(stranger.status, 404);
  const error = await stranger.json();
  conforms("error.envelope.v1", error);
  assert.equal(error.error.code, "not_found");
});

Deno.test("DELETE returns 204 for the owner and 404 for other callers", async () => {
  const owner = await req(build().app, "DELETE", "/v1/posts/po_1");
  assert.equal(owner.status, 204);
  assert.equal(await owner.text(), "");

  const friend = await req(
    build({ deleted: null }).app,
    "DELETE",
    "/v1/posts/po_1",
  );
  assert.equal(friend.status, 404);
  conforms("error.envelope.v1", await friend.json());
});

Deno.test("daily post cap uses the vendored 429 error envelope", async () => {
  const { app } = build({
    createRpc: {
      data: null,
      error: { code: "P0001", message: "daily_post_limit" },
    },
  });
  const res = await req(
    app,
    "POST",
    "/v1/matches/m_1/posts",
    {},
    { "Idempotency-Key": "key-1" },
  );
  assert.equal(res.status, 429);
  assert.ok(Number(res.headers.get("Retry-After")) > 0);
  const body = await res.json();
  conforms("error.envelope.v1", body);
  assert.equal(body.error.code, "daily_post_limit");
  assert.ok(body.retry_after_s > 0);
});

Deno.test("read-only developer keys cannot read the caller-scoped post routes", async () => {
  const { app } = build({ key: ok({ owner_id: "u1", scope: "read" }) });
  const key = `k_${"a".repeat(43)}`;
  for (const path of ["/v1/feed", "/v1/posts/po_1"]) {
    const res = await app.fetch(
      new Request(`http://localhost/api${path}`, {
        headers: { "X-API-Key": key },
      }),
    );
    assert.equal(res.status, 403);
    conforms("error.envelope.v1", await res.json());
    assert.equal(
      await errorCode(
        await app.fetch(
          new Request(`http://localhost/api${path}`, {
            headers: { "X-API-Key": key },
          }),
        ),
      ),
      "read_only_key",
    );
  }
});

Deno.test("post routes have stable operation IDs and reject invalid cursors", async () => {
  const { app } = build();
  const doc = await (await app.fetch(
    new Request("http://localhost/api/v1/openapi.json"),
  )).json();
  assert.equal(
    doc.paths["/v1/matches/{id}/posts"].post.operationId,
    "createMatchPost",
  );
  assert.equal(doc.paths["/v1/feed"].get.operationId, "getFriendFeed");
  assert.equal(doc.paths["/v1/posts/{id}"].get.operationId, "getMatchPost");
  assert.equal(
    doc.paths["/v1/posts/{id}"].delete.operationId,
    "deleteMatchPost",
  );

  const bad = await req(app, "GET", "/v1/feed?cursor=invalid");
  assert.equal(bad.status, 422);
  conforms("error.envelope.v1", await bad.json());
});
