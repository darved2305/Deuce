import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { createServer } from "node:http";
import { test } from "node:test";
import { promisify } from "node:util";
import { matchFixture, MATCH_ID } from "../../mcp/test/fixture";

const run = promisify(execFile);

test("coach-digest prints per-player zone shares and rhythm from the SDK, sending the key", async () => {
  const seen: Array<string | string[] | undefined> = [];
  const server = createServer((req, res) => {
    seen.push(req.headers["x-api-key"]);
    res.setHeader("content-type", "application/json");
    if (req.url === `/v1/matches/${MATCH_ID}`) return void res.end(JSON.stringify(matchFixture()));
    res.statusCode = 404;
    res.end(JSON.stringify({ error: { code: "not_found", message: "match not found" } }));
  });
  await new Promise<void>((ok) => server.listen(0, "127.0.0.1", () => ok()));
  const address = server.address();
  assert.ok(address && typeof address !== "string");
  try {
    const { stdout, stderr } = await run(process.execPath, ["--import=tsx", "coach-digest.ts", MATCH_ID, "m_gone"], {
      cwd: import.meta.dirname ?? __dirname,
      env: { ...process.env, ELEVATE_API_KEY: "ek_test", ELEVATE_API_URL: `http://127.0.0.1:${address.port}` },
    });
    assert.match(stdout, /Digest over 1 of 2 matches/);
    assert.match(stdout, /rally rhythm: median 3 shots, median 12.0 s, longest 5 shots/);
    assert.match(stdout, /You \(you\): 6 shots in 1 match\(es\); most-aimed zones F1 3\/6 \(50%\), B3 2\/6 \(33%\), L 1\/6 \(17%\); 1 of 6 landings inferred/);
    assert.match(stderr, /m_gone: HTTP 404 .*not_found/);
    assert.ok(seen.every((k) => k === "ek_test"));
  } finally {
    await new Promise<void>((ok) => server.close(() => ok()));
  }
});
