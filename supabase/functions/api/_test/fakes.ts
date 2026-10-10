import type { SupabaseClient } from "@supabase/supabase-js";
import { createApp } from "../lib/app.ts";
import type { Deps } from "../lib/deps.ts";

Deno.env.set("SUPABASE_URL", "https://test.supabase.co");
Deno.env.set("INTERNAL_API_TOKEN", "tok");

export type Call =
  | { kind: "rpc"; name: string; args: Record<string, unknown> }
  | {
    kind: "from";
    table: string;
    op: string;
    filters: Record<string, unknown>;
    payload?: unknown;
  };

export type Res = {
  data: unknown;
  error: null | { code?: string; message: string; details?: string };
};

export type Handler = (c: Call) => Res | Promise<Res>;

export const ok = (data: unknown): Res => ({ data, error: null });

export function fakeClient(handler: Handler, log: Call[] = []): SupabaseClient {
  const run = async (c: Call) => {
    log.push(c);
    return await handler(c);
  };
  const from = (table: string) => {
    const state = {
      kind: "from" as const,
      table,
      op: "select",
      filters: {} as Record<string, unknown>,
      payload: undefined as unknown,
    };
    const chain: Record<string, unknown> = {
      select: () => chain,
      eq: (k: string, v: unknown) => {
        state.filters[k] = v;
        return chain;
      },
      gt: (k: string, v: unknown) => {
        state.filters[`${k}>`] = v;
        return chain;
      },
      order: () => chain,
      limit: (n: number) => {
        state.filters["limit"] = n;
        return chain;
      },
      insert: (p: unknown) => {
        state.op = "insert";
        state.payload = p;
        return chain;
      },
      update: (p: unknown) => {
        state.op = "update";
        state.payload = p;
        return chain;
      },
      upsert: (p: unknown) => {
        state.op = "upsert";
        state.payload = p;
        return chain;
      },
      maybeSingle: () => run({ ...state }),
      single: () => run({ ...state }),
      then: (
        res: (v: Res) => unknown,
        rej: (e: unknown) => unknown,
      ) => run({ ...state }).then(res, rej),
    };
    return chain;
  };
  const rpc = (name: string, args: Record<string, unknown>) =>
    run({ kind: "rpc", name, args });
  return { from, rpc } as unknown as SupabaseClient;
}

export function makeApp(
  user: Handler,
  admin: Handler,
  fetchImpl: typeof fetch = () => Promise.reject(new Error("no network")),
  sleeps: number[] = [],
  adminLog: Call[] = [],
) {
  Deno.env.set("INTERNAL_API_TOKEN", "tok");
  const deps: Deps = {
    user: () => fakeClient(user),
    admin: () => fakeClient(admin, adminLog),
    modal: () => ({
      url: "https://modal.test/submit",
      token: "secret",
      fetchImpl,
      sleep: (ms: number) => {
        sleeps.push(ms);
        return Promise.resolve();
      },
    }),
    verifyJwt: () => Promise.resolve({ sub: "u1", isAnonymous: true }),
  };
  return createApp(deps);
}

export const jsonRes = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
