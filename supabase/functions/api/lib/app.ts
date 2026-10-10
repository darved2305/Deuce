import { Hono } from "hono";
import { OpenAPIHono } from "@hono/zod-openapi";
import { type AppEnv, internalAuth, userAuth } from "./auth.ts";
import { env } from "./env.ts";
import { defaultHook, notFound, onError } from "./errors.ts";
import { registerHealth } from "../routes/health.ts";

export function createApp(): Hono {
  const api = new OpenAPIHono<AppEnv>({ defaultHook });
  api.use("/internal/*", internalAuth);
  api.use("/v1/*", userAuth);
  registerHealth(api);
  api.doc("/v1/openapi.json", {
    openapi: "3.0.4",
    info: { title: "Elevate API", version: "0.1.0" },
    servers: [{ url: `${env("SUPABASE_URL")}/functions/v1/api` }],
  });
  const root = new Hono().route("/api", api);
  root.notFound(notFound);
  root.onError(onError);
  return root;
}
