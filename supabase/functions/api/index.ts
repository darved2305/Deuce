import type { Hono } from "hono";
import { createApp } from "./lib/app.ts";

const root: Hono = createApp();

Deno.serve(root.fetch);

export { root };
