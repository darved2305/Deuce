import type { SupabaseClient } from "@supabase/supabase-js";
import { createApp } from "../lib/app.ts";
import type { Deps } from "../lib/deps.ts";
import { createR2, type R2 } from "../lib/r2.ts";
import { sessionCreateRequestSchema } from "../lib/contracts_vendor/index.ts";

export const testSessionSpec = sessionCreateRequestSchema.parse({
  sport: "badminton",
  format: "singles",
  ruleset: { id: "bwf_3x21", version: "2025-04" },
  mode: "match",
  source: {
    width: 1920,
    height: 1080,
    rotation: 0,
    fps_nominal: 60,
    duration_ms: 20480,
    pts: {
      median_interval_us: 16667,
      min_us: 16600,
      max_us: 33400,
      gaps: 0,
      vfr: false,
    },
    codec: "avc1",
    has_audio: true,
    audio_rate_hz: 48000,
    device: "SM-S711B",
    bytes: 51203344,
    sha256: "a".repeat(64),
  },
  calibration_input: {
    method: "tap",
    plane: "court",
    template: "badminton_singles",
    points_px: {
      near_left: [16, 196],
      near_right: [334, 196],
      far_right: [248, 34],
      far_left: [102, 34],
    },
    frame_ms: 1200,
  },
  who_is_me: { image_xy: [128, 150], t_ms: 1200 },
  free_play_seed: null,
  rally_candidates: [],
  upload_mode: "multipart",
});

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
      is: (k: string, v: unknown) => {
        state.filters[`${k}.is`] = v;
        return chain;
      },
      in: (k: string, values: readonly unknown[]) => {
        state.filters[`${k} in`] = values;
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
      delete: () => {
        state.op = "delete";
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
  r2: R2 = createR2({
    accountId: "acc",
    accessKeyId: "AK",
    secretAccessKey: "SK",
    bucket: "bkt",
    fetchImpl: () => Promise.reject(new Error("no r2 network")),
  }),
  waitUntil: (task: Promise<unknown>) => void = (task) => {
    void task;
  },
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
    r2: () => r2,
    waitUntil,
    verifyJwt: () => Promise.resolve({ sub: "u1", isAnonymous: true }),
  };
  return createApp(deps);
}

export const jsonRes = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
