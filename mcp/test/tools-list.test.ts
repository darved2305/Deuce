import assert from "node:assert/strict";
import { createServer as createHttpServer } from "node:http";
import { spawn } from "node:child_process";
import { once } from "node:events";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { test } from "node:test";

type RpcResponse = {
  id: number;
  result?: {
    tools?: Array<{ name: string }>;
    content?: Array<{ text?: string }>;
    isError?: boolean;
  };
  error?: { message: string };
};

test("stdio server lists tools and validates capability responses", async (t) => {
  const apiRequests: Array<{ path: string; authorization?: string }> = [];
  const apiServer = createHttpServer((request, response) => {
    const path = request.url ?? "";
    apiRequests.push({ path, authorization: request.headers.authorization });
    response.setHeader("content-type", "application/json");
    if (path === "/v1/openapi.json") {
      response.end(
        JSON.stringify({ paths: { "/v1/capabilities": { get: {} } } }),
      );
    } else if (path === "/v1/capabilities") {
      response.end("{}");
    } else {
      response.statusCode = 404;
      response.end("{}");
    }
  });
  await new Promise<void>((resolveServer) =>
    apiServer.listen(0, "127.0.0.1", () => resolveServer())
  );
  const address = apiServer.address();
  assert.ok(address && typeof address !== "string");

  const packageRoot = resolve(fileURLToPath(new URL("..", import.meta.url)));
  const entrypoint = resolve(packageRoot, "src/index.ts");
  const child = spawn(process.execPath, ["--import=tsx", entrypoint], {
    cwd: packageRoot,
    env: {
      ...process.env,
      ELEVATE_API_BASE: `http://127.0.0.1:${address.port}`,
      ELEVATE_JWT: "local-test-token",
    },
    stdio: ["pipe", "pipe", "pipe"],
  });
  const pending = new Map<
    number,
    { resolve: (response: RpcResponse) => void; reject: (error: Error) => void }
  >();
  const stderr: string[] = [];
  let stdout = "";
  const rejectPending = (error: Error) => {
    for (const waiter of pending.values()) waiter.reject(error);
    pending.clear();
  };

  child.stderr.setEncoding("utf8");
  child.stderr.on("data", (chunk: string) => stderr.push(chunk));
  child.on("error", rejectPending);
  child.on("exit", (code, signal) => {
    if (pending.size) {
      rejectPending(
        new Error(
          `MCP exited with code ${code} (${signal}). ${stderr.join("")}`,
        ),
      );
    }
  });
  child.stdout.setEncoding("utf8");
  child.stdout.on("data", (chunk: string) => {
    stdout += chunk;
    let newline = stdout.indexOf("\n");
    while (newline !== -1) {
      const line = stdout.slice(0, newline).trim();
      stdout = stdout.slice(newline + 1);
      newline = stdout.indexOf("\n");
      if (!line) continue;
      let response: RpcResponse;
      try {
        response = JSON.parse(line) as RpcResponse;
      } catch {
        for (const waiter of pending.values()) {
          waiter.reject(new Error(`Non-JSON server output: ${line}`));
        }
        pending.clear();
        continue;
      }
      const waiter = pending.get(response.id);
      if (waiter) {
        pending.delete(response.id);
        waiter.resolve(response);
      }
    }
  });

  function request(
    id: number,
    method: string,
    params: Record<string, unknown>,
  ): Promise<RpcResponse> {
    return new Promise((resolveResponse, rejectResponse) => {
      const timer = setTimeout(() => {
        pending.delete(id);
        rejectResponse(
          new Error(`Timed out waiting for ${method}. ${stderr.join("")}`),
        );
      }, 10_000);
      pending.set(id, {
        resolve(response) {
          clearTimeout(timer);
          resolveResponse(response);
        },
        reject(error) {
          clearTimeout(timer);
          rejectResponse(error);
        },
      });
      child.stdin.write(
        `${JSON.stringify({ jsonrpc: "2.0", id, method, params })}\n`,
      );
    });
  }

  try {
    const initialized = await request(1, "initialize", {
      protocolVersion: "2026-07-28",
      capabilities: {},
      clientInfo: { name: "elevate-mcp-test", version: "1.0.0" },
    });
    assert.equal(initialized.error, undefined, JSON.stringify(initialized));
    child.stdin.write(
      `${
        JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized" })
      }\n`,
    );
    const listed = await request(2, "tools/list", {});
    assert.equal(listed.error, undefined, JSON.stringify(listed));
    const names = listed.result?.tools?.map((tool) => tool.name) ?? [];
    assert.deepEqual(names.sort(), [
      "compare_moment",
      "find_moments",
      "get_analysis_progress",
      "get_capabilities",
      "get_landing_zones",
      "get_match_summary",
      "get_rally",
      "rewatch_picks",
    ]);
    t.diagnostic(`tools/list: ${names.join(", ")}`);

    const capabilities = await request(3, "tools/call", {
      name: "get_capabilities",
      arguments: {},
    });
    assert.equal(capabilities.error, undefined, JSON.stringify(capabilities));
    assert.equal(
      capabilities.result?.isError,
      true,
      JSON.stringify(capabilities),
    );
    assert.match(
      capabilities.result?.content?.[0]?.text ?? "",
      /vendored response schema/,
    );
    assert.deepEqual(apiRequests.map(({ path }) => path), [
      "/v1/openapi.json",
      "/v1/capabilities",
    ]);
    assert.equal(apiRequests[1].authorization, "Bearer local-test-token");
  } finally {
    if (child.exitCode === null && child.signalCode === null) {
      const exited = once(child, "exit");
      child.kill();
      await exited;
    }
    await new Promise<void>((resolveServer) =>
      apiServer.close(() => resolveServer())
    );
  }
});
