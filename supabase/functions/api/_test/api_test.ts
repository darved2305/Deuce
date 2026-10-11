import assert from "node:assert/strict";
import { createApp } from "../lib/app.ts";
import { timingSafeEqual } from "../lib/auth.ts";

Deno.env.set("SUPABASE_URL", "https://test.supabase.co");
Deno.env.set("SUPABASE_PUBLISHABLE_KEYS", '{"default":"sb_publishable_test"}');
Deno.env.set("SUPABASE_JWKS", '{"keys":[]}');
Deno.env.delete("INTERNAL_API_TOKEN");

const root = createApp();

Deno.test("GET /v1/health -> 200 {status:ok}", async () => {
  const res = await root.fetch(new Request("http://localhost/api/v1/health"));
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.equal(body.status, "ok");
});

Deno.test("GET /v1/openapi.json -> 3.0.4 doc with /v1 paths only", async () => {
  const res = await root.fetch(
    new Request("http://localhost/api/v1/openapi.json"),
  );
  assert.equal(res.status, 200);
  const doc = await res.json();
  assert.equal(doc.openapi, "3.0.4");
  assert.equal(
    doc.info.description,
    "X-API-Key is read-only and supports owner-scoped GET routes; share grants do not apply.",
  );
  assert.ok(doc.paths["/v1/health"], "doc has /v1/health");
  assert.ok(doc.paths["/v1/developer-keys"].post.responses["201"]);
  assert.equal(
    doc.paths["/v1/developer-keys"].post.responses["201"].content,
    undefined,
  );
  assert.ok(doc.paths["/v1/analyses"].post.responses["403"]);
  assert.ok(doc.paths["/v1/analyses"].post.responses["503"]);
  assert.ok(doc.paths["/v1/analyses"].post.responses["429"]);
  assert.ok(doc.paths["/v1/artifacts/{id}"].get.responses["200"]);
  assert.match(
    doc.paths["/v1/artifacts/{id}"].get.responses["200"].description,
    /pending Person 4/,
  );
  assert.ok(
    Object.keys(doc.paths).every((p: string) => p.startsWith("/v1/")),
    "no /api/ prefix in emitted paths",
  );
  assert.equal(
    doc.servers[0].url,
    "https://test.supabase.co/functions/v1/api",
  );
});

Deno.test("unknown route -> 404 envelope", async () => {
  const res = await root.fetch(new Request("http://localhost/api/nope"));
  assert.equal(res.status, 404);
  const body = await res.json();
  assert.equal(body.error.code, "not_found");
});

Deno.test("/internal/* without token -> 401 envelope", async () => {
  const res = await root.fetch(
    new Request("http://localhost/api/internal/analyses/a_x/events"),
  );
  assert.equal(res.status, 401);
  const body = await res.json();
  assert.equal(body.error.code, "unauthorized");
});

Deno.test("/internal/* wrong token -> 401, right token passes middleware", async () => {
  Deno.env.set("INTERNAL_API_TOKEN", "expected-token");
  const bad = await root.fetch(
    new Request("http://localhost/api/internal/x", {
      headers: { "X-Internal-Token": "wrong-token" },
    }),
  );
  assert.equal(bad.status, 401);
  assert.equal((await bad.json()).error.code, "unauthorized");
  const ok = await root.fetch(
    new Request("http://localhost/api/internal/x", {
      headers: { "X-Internal-Token": "expected-token" },
    }),
  );
  assert.equal(ok.status, 404);
  assert.equal((await ok.json()).error.code, "not_found");
  Deno.env.delete("INTERNAL_API_TOKEN");
});

Deno.test("/v1/* without Bearer -> 401 envelope", async () => {
  const res = await root.fetch(
    new Request("http://localhost/api/v1/sessions"),
  );
  assert.equal(res.status, 401);
  const body = await res.json();
  assert.equal(body.error.code, "unauthorized");
});

Deno.test("/v1/* with invalid Bearer -> 401 envelope", async () => {
  const res = await root.fetch(
    new Request("http://localhost/api/v1/sessions", {
      headers: { Authorization: "Bearer not-a-jwt" },
    }),
  );
  assert.equal(res.status, 401);
  const body = await res.json();
  assert.equal(body.error.code, "unauthorized");
});

Deno.test("public exemption is exact-path only", async () => {
  const res = await root.fetch(
    new Request("http://localhost/api/v1/sessions/v1/health"),
  );
  assert.equal(res.status, 401);
});

Deno.test("timingSafeEqual", () => {
  assert.ok(timingSafeEqual("abc", "abc"));
  assert.ok(!timingSafeEqual("abc", "abd"));
  assert.ok(!timingSafeEqual("abc", "abcd"));
  assert.ok(!timingSafeEqual("abcd", "abc"));
  assert.ok(!timingSafeEqual("", "x"));
  assert.ok(timingSafeEqual("", ""));
});
