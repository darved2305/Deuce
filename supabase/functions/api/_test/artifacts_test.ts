import assert from "node:assert/strict";
import { type Call, type Handler, makeApp, ok } from "./fakes.ts";
import type { R2 } from "../lib/r2.ts";

const ARTIFACT = {
  id: "art_1",
  r2_key: "analysis/a_1/0.3.0/windows/0.json",
  match_id: "m_1",
};
const URL =
  "https://r2.test/artifact?X-Amz-Date=20300102T030405Z&X-Amz-Expires=300";

type Opts = {
  visible?: unknown;
  artifact?: unknown;
  grants?: unknown;
  download?: unknown;
};

function build(o: Opts = {}) {
  const adminLog: Call[] = [];
  const signedKeys: string[] = [];
  const user: Handler = (c) => {
    if (c.kind === "from" && c.table === "artifacts") {
      return ok("visible" in o ? o.visible : ARTIFACT);
    }
    return ok(null);
  };
  const admin: Handler = (c) => {
    if (c.kind === "from" && c.table === "artifacts") {
      return ok("artifact" in o ? o.artifact : ARTIFACT);
    }
    if (c.kind === "from" && c.table === "share_grants") {
      return ok("grants" in o ? o.grants : []);
    }
    if (c.kind === "rpc" && c.name === "r2_take_download") {
      return ok("download" in o ? o.download : 1);
    }
    if (c.kind === "rpc" && c.name === "r2_add_usage") return ok(true);
    return ok(null);
  };
  const r2 = {
    presignGet: (key: string) => {
      signedKeys.push(key);
      return Promise.resolve(URL);
    },
  } as unknown as R2;
  const app = makeApp(user, admin, undefined, [], adminLog, r2);
  return { app, adminLog, signedKeys };
}

function get(
  app: ReturnType<typeof build>["app"],
  headers: HeadersInit = { Authorization: "Bearer jwt" },
) {
  return app.fetch(
    new Request("http://localhost/api/v1/artifacts/art_1", { headers }),
  );
}

Deno.test("GET artifact owner receives a signed URL expiring in 300 seconds", async () => {
  const t = build();
  const res = await get(t.app);
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), {
    url: URL,
    expires_at: "2030-01-02T03:09:05.000Z",
  });
  assert.deepEqual(t.signedKeys, [ARTIFACT.r2_key]);
  assert.ok(
    t.adminLog.some((c) =>
      c.kind === "rpc" && c.name === "r2_add_usage" &&
      c.args.p_class_a === 0 && c.args.p_class_b === 1
    ),
  );
  assert.ok(
    t.adminLog.some((c) =>
      c.kind === "rpc" && c.name === "r2_take_download" &&
      c.args.p_owner === "u1" && c.args.p_limit === 200
    ),
  );
});

Deno.test("GET artifact returns 404 to a non-owner without a share grant", async () => {
  const t = build({ visible: null, artifact: ARTIFACT, grants: [] });
  const res = await get(t.app);
  assert.equal(res.status, 404);
  assert.equal((await res.json()).error.code, "not_found");
  assert.deepEqual(t.signedKeys, []);
});

Deno.test("GET artifact signs a row returned by caller-scoped RLS", async () => {
  const t = build({ visible: ARTIFACT });
  const res = await get(t.app);
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), {
    url: URL,
    expires_at: "2030-01-02T03:09:05.000Z",
  });
  assert.deepEqual(t.signedKeys, [ARTIFACT.r2_key]);
});

Deno.test("GET artifact enforces the daily download cap", async () => {
  const t = build({ download: null });
  const res = await get(t.app);
  assert.equal(res.status, 429);
  const body = await res.json();
  assert.equal(body.error.code, "daily_download_limit");
  assert.ok(body.retry_after_s > 0);
  assert.deepEqual(t.signedKeys, []);
});

Deno.test("GET unknown artifact returns 404", async () => {
  const t = build({ visible: null, artifact: null });
  const res = await get(t.app);
  assert.equal(res.status, 404);
  assert.equal((await res.json()).error.code, "not_found");
  assert.deepEqual(t.signedKeys, []);
});

Deno.test("GET artifact requires Bearer authentication", async () => {
  const res = await get(build().app, {});
  assert.equal(res.status, 401);
  assert.equal((await res.json()).error.code, "unauthorized");
});
