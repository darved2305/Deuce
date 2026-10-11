import assert from "node:assert/strict";
import { HTTPException } from "hono/http-exception";
import { errorEnvelopeSchema } from "../lib/contracts_vendor/api.ts";
import type { Handler } from "./fakes.ts";
import { makeApp, ok } from "./fakes.ts";

function appWithUserError(error: unknown) {
  const user: Handler = () => {
    throw error;
  };
  return makeApp(user, () => ok(null));
}

function getMatch(app: ReturnType<typeof makeApp>) {
  return app.fetch(
    new Request("http://localhost/api/v1/matches/m_1", {
      headers: { Authorization: "Bearer jwt" },
    }),
  );
}

async function assertInternalEnvelope(response: Response) {
  assert.equal(response.status, 500);
  assert.match(
    response.headers.get("content-type") ?? "",
    /^application\/json(?:;|$)/,
  );
  const body = await response.json();
  assert.equal(errorEnvelopeSchema.safeParse(body).success, true);
  assert.deepEqual(body, {
    error: { code: "internal", message: "internal error" },
  });
}

Deno.test("route dependency plain-object errors return the internal envelope", async () => {
  const app = appWithUserError({
    code: "42P01",
    message: "function get_match_v1 does not exist",
    details: "database schema details must not be returned",
  });
  await assertInternalEnvelope(await getMatch(app));
});

Deno.test("route dependency Error instances return the internal envelope", async () => {
  const app = appWithUserError(
    new Error("database connection details must not be returned"),
  );
  await assertInternalEnvelope(await getMatch(app));
});

Deno.test("HTTP 500 exceptions do not leak their message", async () => {
  const app = makeApp(() => ok(null), () => ok(null));
  app.get("/http-error", () => {
    throw new HTTPException(500, {
      message: "HTTP exception details must not be returned",
    });
  });
  const response = await app.fetch(new Request("http://localhost/http-error"));
  await assertInternalEnvelope(response);
});

Deno.test("the outer app handles errors outside the /api mount", async () => {
  const app = makeApp(() => ok(null), () => ok(null));
  app.get("/outer-error", () => {
    throw { message: "outer app details must not be returned" };
  });
  const response = await app.fetch(
    new Request("http://localhost/outer-error"),
  );
  await assertInternalEnvelope(response);
});
