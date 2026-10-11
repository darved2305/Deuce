import assert from "node:assert/strict";
import { type IncomingMessage, type ServerResponse, createServer } from "node:http";
import { spawn } from "node:child_process";
import { once } from "node:events";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

export type Stub = (
  path: string,
  request: IncomingMessage,
  response: ServerResponse,
) => void;

/** Starts a local HTTP stub API and a real stdio MCP server pointed at it. */
export async function startHarness(stub: Stub, env: Record<string, string> = {}) {
  const requests: Array<{ path: string; headers: IncomingMessage["headers"] }> = [];
  const api = createServer((request, response) => {
    const path = request.url ?? "";
    requests.push({ path, headers: request.headers });
    response.setHeader("content-type", "application/json");
    stub(path, request, response);
  });
  await new Promise<void>((ok) => api.listen(0, "127.0.0.1", () => ok()));
  const address = api.address();
  assert.ok(address && typeof address !== "string");
  const root = resolve(fileURLToPath(new URL("..", import.meta.url)));
  const child = spawn(process.execPath, ["--import=tsx", resolve(root, "src/index.ts")], {
    cwd: root,
    env: {
      ...process.env,
      ELEVATE_API_BASE: `http://127.0.0.1:${address.port}`,
      ELEVATE_API_KEY: "",
      ELEVATE_JWT: "",
      ...env,
    },
    stdio: ["pipe", "pipe", "pipe"],
  });
  let buffer = "";
  const stray: string[] = [];
  const waiting = new Map<number, (value: any) => void>();
  child.stdout.setEncoding("utf8");
  child.stdout.on("data", (chunk: string) => {
    buffer += chunk;
    let nl = buffer.indexOf("\n");
    while (nl !== -1) {
      const line = buffer.slice(0, nl).trim();
      buffer = buffer.slice(nl + 1);
      nl = buffer.indexOf("\n");
      if (!line) continue;
      try {
        const msg = JSON.parse(line);
        waiting.get(msg.id)?.(msg);
        waiting.delete(msg.id);
      } catch {
        stray.push(line); // anything that is not JSON-RPC on stdout is a bug
      }
    }
  });
  let id = 0;
  const rpc = (method: string, params: Record<string, unknown>) =>
    new Promise<any>((ok, fail) => {
      const n = ++id;
      const timer = setTimeout(() => fail(new Error(`timeout: ${method}`)), 10_000);
      waiting.set(n, (m) => {
        clearTimeout(timer);
        ok(m);
      });
      child.stdin.write(`${JSON.stringify({ jsonrpc: "2.0", id: n, method, params })}\n`);
    });
  const init = await rpc("initialize", {
    protocolVersion: "2026-07-28",
    capabilities: {},
    clientInfo: { name: "elevate-mcp-test", version: "1.0.0" },
  });
  assert.equal(init.error, undefined);
  child.stdin.write(`${JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized" })}\n`);
  return {
    base: `http://127.0.0.1:${address.port}`,
    requests,
    stray,
    async call(name: string, args: Record<string, unknown>) {
      const res = await rpc("tools/call", { name, arguments: args });
      const text: string = res.result?.content?.[0]?.text ?? JSON.stringify(res.error);
      return { isError: Boolean(res.result?.isError || res.error), text, rpcError: res.error };
    },
    async close() {
      const exited = once(child, "exit");
      child.kill();
      await exited;
      await new Promise<void>((ok) => api.close(() => ok()));
    },
  };
}
