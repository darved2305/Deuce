import { Hono } from "hono";
import { OpenAPIHono } from "@hono/zod-openapi";
import { type AppEnv, internalAuth, userAuth } from "./auth.ts";
import { defaultDeps, type Deps } from "./deps.ts";
import { env } from "./env.ts";
import { defaultHook, notFound, onError } from "./errors.ts";
import { registerAnalyses } from "../routes/analyses.ts";
import { registerHealth } from "../routes/health.ts";
import { registerInternal } from "../routes/internal.ts";

export function createApp(deps: Deps = defaultDeps()): Hono {
  const api = new OpenAPIHono<AppEnv>({ defaultHook });
  api.use("*", async (c, next) => {
    c.set("deps", deps);
    await next();
  });
  api.use("/internal/*", internalAuth);
  api.use("/v1/*", userAuth);
  registerHealth(api);
  registerAnalyses(api);
  registerInternal(api);
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
