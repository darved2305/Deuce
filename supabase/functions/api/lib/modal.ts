export type ModalDeps = {
  fetchImpl?: typeof fetch;
  sleep?: (ms: number) => Promise<void>;
  url: string;
  token: string;
};

export type ModalSubmitBody = {
  analysis_id: string;
  r2_key: string;
  session_spec: Record<string, unknown>;
};

export class ModalUnavailable extends Error {}

const ANALYSIS_ID = /^a_[A-Za-z0-9_-]{1,64}$/;
const R2_KEY = /^orig\/s_[A-Za-z0-9_-]{1,64}\/source\.mp4$/;
const BACKOFF_MS = [500, 1000, 2000];
const TIMEOUT_MS = 10_000;

export async function submitToModal(
  body: ModalSubmitBody,
  deps: ModalDeps,
): Promise<{ modal_call_id: string }> {
  if (
    !ANALYSIS_ID.test(body.analysis_id) || !R2_KEY.test(body.r2_key) ||
    !body.session_spec.sport
  ) {
    throw new ModalUnavailable("submit body fails Modal preconditions");
  }
  const doFetch = deps.fetchImpl ?? fetch;
  const sleep = deps.sleep ??
    ((ms: number) => new Promise<void>((r) => setTimeout(r, ms)));
  for (let attempt = 0; attempt <= BACKOFF_MS.length; attempt++) {
    let retryable = true;
    try {
      const res = await doFetch(deps.url, {
        method: "POST",
        headers: {
          "Authorization": `Bearer ${deps.token}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify(body),
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
        retryable = false;
      } else {
        console.error(
          `modal submit: status ${res.status} code ${parsed?.error?.code}`,
        );
        retryable = res.status >= 500;
      }
    } catch (err) {
      console.error(`modal submit: ${(err as Error).name}`);
    }
    if (!retryable) break;
    if (attempt < BACKOFF_MS.length) await sleep(BACKOFF_MS[attempt]);
  }
  throw new ModalUnavailable("modal submit failed");
}
