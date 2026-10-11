import { Hono } from "hono";
import { OpenAPIHono } from "@hono/zod-openapi";
import { type AppEnv, internalAuth, userAuth } from "./auth.ts";
import { defaultDeps, type Deps } from "./deps.ts";
import { env } from "./env.ts";
import { defaultHook, notFound, onError } from "./errors.ts";
import { registerAnalyses } from "../routes/analyses.ts";
import { registerArtifacts } from "../routes/artifacts.ts";
import { registerHealth } from "../routes/health.ts";
import { registerInternal } from "../routes/internal.ts";
import { registerMatches } from "../routes/matches.ts";
import { registerSessions } from "../routes/sessions.ts";

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
  registerArtifacts(api);
  registerMatches(api);
  registerSessions(api);
  registerInternal(api);
  // Internal routes (Modal only) are not part of the public document.
  api.get("/v1/openapi.json", (c) => {
    const doc = api.getOpenAPIDocument({
      openapi: "3.0.4",
      info: { title: "Elevate API", version: "0.1.0" },
      servers: [{ url: `${env("SUPABASE_URL")}/functions/v1/api` }],
    });
    const paths = Object.fromEntries(
      Object.entries(doc.paths ?? {}).filter(([p]) => p.startsWith("/v1/")),
    );
    return c.json({ ...doc, paths }, 200);
  });
  const root = new Hono().route("/api", api);
  root.notFound(notFound);
  root.onError(onError);
  return root;
}
