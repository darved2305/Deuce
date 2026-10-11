/**
 * Calls the Elevate MCP server over real stdio, the way Claude does, and prints the JSON-RPC
 * exchange. Use it to show tools/list and one tool call without a host app.
 *
 *   ELEVATE_API_KEY=<developer key> npx tsx scripts/call.ts rewatch_picks '{"match_id":"m_..."}'
 *   npx tsx scripts/call.ts            # just tools/list
 *
 * The server's own logs go to stderr and are passed through; its stdout is JSON-RPC only.
 */
import { spawn } from "node:child_process";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const [tool, rawArgs] = process.argv.slice(2);
const root = resolve(fileURLToPath(new URL("..", import.meta.url)));
const child = spawn(process.execPath, ["--import=tsx", resolve(root, "src/index.ts")], {
  cwd: root,
  stdio: ["pipe", "pipe", "inherit"],
});

let buffer = "";
const waiting = new Map<number, (message: unknown) => void>();
child.stdout.setEncoding("utf8");
child.stdout.on("data", (chunk: string) => {
  buffer += chunk;
  let nl = buffer.indexOf("\n");
  while (nl !== -1) {
    const line = buffer.slice(0, nl).trim();
    buffer = buffer.slice(nl + 1);
    nl = buffer.indexOf("\n");
    if (!line) continue;
    const message = JSON.parse(line) as { id?: number };
    if (message.id !== undefined) waiting.get(message.id)?.(message);
  }
});

let nextId = 0;
function rpc(method: string, params: Record<string, unknown>): Promise<any> {
  const id = ++nextId;
  console.log(`> ${method}${method === "tools/call" ? ` ${JSON.stringify(params)}` : ""}`);
  return new Promise((ok) => {
    waiting.set(id, ok);
    child.stdin.write(`${JSON.stringify({ jsonrpc: "2.0", id, method, params })}\n`);
  });
}

await rpc("initialize", {
  protocolVersion: "2026-07-28",
  capabilities: {},
  clientInfo: { name: "elevate-mcp-call", version: "1.0.0" },
});
child.stdin.write(`${JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized" })}\n`);

const listed = await rpc("tools/list", {});
for (const t of listed.result.tools as Array<{ name: string; description: string; annotations?: { readOnlyHint?: boolean } }>) {
  console.log(`  ${t.name}${t.annotations?.readOnlyHint ? " [read-only]" : ""}`);
}
if (tool) {
  const result = await rpc("tools/call", { name: tool, arguments: rawArgs ? JSON.parse(rawArgs) : {} });
  const text: string = result.result?.content?.[0]?.text ?? JSON.stringify(result.error);
  console.log(`${result.result?.isError ? "isError: true\n" : ""}${text}`);
}
child.kill();
