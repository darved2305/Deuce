import assert from "node:assert/strict";
import { trustedClaims } from "../lib/deps.ts";

const projectUrl = "https://project.supabase.co";
const base = {
  sub: "user-1",
  is_anonymous: true,
  iss: `${projectUrl}/auth/v1`,
};

Deno.test("JWT issuer and authenticated audience are required", () => {
  assert.deepEqual(
    trustedClaims({ ...base, aud: "authenticated" }, projectUrl),
    { sub: "user-1", isAnonymous: true },
  );
  assert.deepEqual(
    trustedClaims({ ...base, aud: ["other", "authenticated"] }, projectUrl),
    { sub: "user-1", isAnonymous: true },
  );
  assert.equal(
    trustedClaims({ ...base, aud: "other" }, projectUrl),
    null,
  );
  assert.equal(
    trustedClaims({
      ...base,
      aud: "authenticated",
      iss: "https://wrong/auth/v1",
    }, projectUrl),
    null,
  );
});
