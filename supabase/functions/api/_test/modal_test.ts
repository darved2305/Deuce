import assert from "node:assert/strict";
import {
  ModalRejected,
  ModalUnavailable,
  submitToModal,
} from "../lib/modal.ts";
import { jsonRes } from "./fakes.ts";

const body = {
  analysis_id: "a_1",
  r2_key: "orig/s_1/source.mp4",
  session_spec: { sport: "badminton" },
};

function setup(responses: Array<() => Response | Promise<Response>>) {
  const sleeps: number[] = [];
  let calls = 0;
  const fetchImpl = (() => {
    const r = responses[calls++];
    return Promise.resolve(r());
  }) as typeof fetch;
  return {
    sleeps,
    calls: () => calls,
    deps: {
      url: "https://m",
      token: "t",
      fetchImpl,
      sleep: (ms: number) => {
        sleeps.push(ms);
        return Promise.resolve();
      },
    },
  };
}

Deno.test("modal 4xx is not retried", async () => {
  const s = setup([() => jsonRes(422, { error: { code: "invalid_body" } })]);
  await assert.rejects(
    submitToModal(body, s.deps),
    (err: unknown) =>
      err instanceof ModalRejected &&
      err.modalErrorCode === "invalid_body",
  );
  assert.equal(s.calls(), 1);
  assert.deepEqual(s.sleeps, []);
});

Deno.test("modal 5xx x3 then 200 -> 4 calls, backoff 500/1000/2000", async () => {
  const fail = () => jsonRes(503, { error: { code: "dispatch_in_flight" } });
  const s = setup([
    fail,
    fail,
    fail,
    () => jsonRes(200, { modal_call_id: "fc-1" }),
  ]);
  const out = await submitToModal(body, s.deps);
  assert.equal(out.modal_call_id, "fc-1");
  assert.equal(s.calls(), 4);
  assert.deepEqual(s.sleeps, [500, 1000, 2000]);
});

Deno.test("modal 5xx x4 -> ModalUnavailable after 4 calls", async () => {
  const fail = () => jsonRes(500, {});
  const s = setup([fail, fail, fail, fail]);
  await assert.rejects(submitToModal(body, s.deps), ModalUnavailable);
  assert.equal(s.calls(), 4);
  assert.deepEqual(s.sleeps, [500, 1000, 2000]);
});

Deno.test("modal timeout (AbortError) is retried", async () => {
  const abort = () => {
    throw new DOMException("timed out", "TimeoutError");
  };
  const s = setup([abort, () => jsonRes(200, { modal_call_id: "fc-2" })]);
  const out = await submitToModal(body, s.deps);
  assert.equal(out.modal_call_id, "fc-2");
  assert.equal(s.calls(), 2);
});

Deno.test("modal 2xx without modal_call_id is a failure", async () => {
  const s = setup([() => jsonRes(200, {})]);
  await assert.rejects(submitToModal(body, s.deps), ModalUnavailable);
});

Deno.test("Modal submit sets a 30 second per-attempt timeout", async () => {
  const original = AbortSignal.timeout;
  let observed = 0;
  AbortSignal.timeout = ((milliseconds: number) => {
    observed = milliseconds;
    return original(milliseconds);
  }) as typeof AbortSignal.timeout;
  try {
    const s = setup([() => jsonRes(200, { modal_call_id: "fc-3" })]);
    await submitToModal(body, s.deps);
  } finally {
    AbortSignal.timeout = original;
  }
  assert.equal(observed, 30_000);
});

Deno.test("modal invalid ids fail fast without calling", async () => {
  const s = setup([]);
  await assert.rejects(
    submitToModal({ ...body, r2_key: "orig/x/source.mp4" }, s.deps),
    ModalUnavailable,
  );
  assert.equal(s.calls(), 0);
});

Deno.test("modal accepts a segment object key", async () => {
  const s = setup([() => jsonRes(200, { modal_call_id: "fc-segment" })]);
  const out = await submitToModal(
    { ...body, r2_key: "seg/s_1/0.mp4" },
    s.deps,
  );
  assert.equal(out.modal_call_id, "fc-segment");
  assert.equal(s.calls(), 1);
});
