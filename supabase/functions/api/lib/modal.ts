import {
  type ModalSubmitRequest,
  modalSubmitRequestSchema,
} from "./contracts_vendor/index.ts";

export type ModalDeps = {
  fetchImpl?: typeof fetch;
  sleep?: (ms: number) => Promise<void>;
  url: string;
  token: string;
};

// contracts 1.9.0 (D-261011-HW15): the engine needs both to build its finalize body.
export type ModalSubmitBody = ModalSubmitRequest;

export class ModalUnavailable extends Error {}

export class ModalRejected extends ModalUnavailable {
  constructor(readonly modalErrorCode: string) {
    super("Modal rejected the analysis");
  }
}

const BACKOFF_MS = [500, 1000, 2000];
const TIMEOUT_MS = 30_000;

export async function submitToModal(
  body: ModalSubmitBody,
  deps: ModalDeps,
): Promise<{ modal_call_id: string }> {
  const parsedBody = modalSubmitRequestSchema.safeParse(body);
  if (!parsedBody.success) {
    throw new ModalUnavailable("submit body fails Modal preconditions");
  }
  const doFetch = deps.fetchImpl ?? fetch;
  const sleep = deps.sleep ??
    ((ms: number) => new Promise<void>((r) => setTimeout(r, ms)));
  for (let attempt = 0; attempt <= BACKOFF_MS.length; attempt++) {
    try {
      const res = await doFetch(deps.url, {
        method: "POST",
        headers: {
          "Authorization": `Bearer ${deps.token}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify(parsedBody.data),
        signal: AbortSignal.timeout(TIMEOUT_MS),
      });
      const parsed = await res.json().catch(() => null) as
        | { modal_call_id?: unknown; error?: { code?: unknown } }
        | null;
      if (res.ok) {
        if (typeof parsed?.modal_call_id === "string" && parsed.modal_call_id) {
          return { modal_call_id: parsed.modal_call_id };
        }
        console.error("modal submit: 2xx without modal_call_id");
      } else {
        console.error(
          `modal submit: status ${res.status} code ${parsed?.error?.code}`,
        );
        if (res.status >= 400 && res.status < 500) {
          const code = typeof parsed?.error?.code === "string"
            ? parsed.error.code
            : `HTTP_${res.status}`;
          throw new ModalRejected(code);
        }
      }
    } catch (err) {
      if (err instanceof ModalRejected) throw err;
      console.error(`modal submit: ${(err as Error).name}`);
    }
    if (attempt < BACKOFF_MS.length) await sleep(BACKOFF_MS[attempt]);
  }
  throw new ModalUnavailable("modal submit failed");
}
