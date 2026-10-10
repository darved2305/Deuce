import { AwsClient } from "aws4fetch";
import { ApiFailure } from "./errors.ts";

export const PART_URL_TTL_S = 900;
export const GET_URL_TTL_S = 300;
// R2 rate limits writes to one key; requests retry on 429 with this backoff.
export const BACKOFF_MS = [250, 500, 1000];

export type R2Config = {
  accountId: string;
  accessKeyId: string;
  secretAccessKey: string;
  bucket: string;
  fetchImpl?: typeof fetch;
  sleep?: (ms: number) => Promise<void>;
};

export type R2Part = { n: number; etag: string };

// An S3-style error returned by R2 (code is the XML <Code>, e.g. InvalidPart).
export class R2Error extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

export type R2 = {
  createMultipart(key: string): Promise<string>;
  completeMultipart(
    key: string,
    uploadId: string,
    parts: R2Part[],
  ): Promise<void>;
  abortMultipart(key: string, uploadId: string): Promise<void>;
  head(key: string): Promise<{ bytes: number } | null>;
  presignPart(key: string, uploadId: string, n: number): Promise<string>;
  presignGet(key: string): Promise<string>;
};

function unescapeXml(s: string): string {
  return s.replaceAll("&lt;", "<").replaceAll("&gt;", ">")
    .replaceAll("&quot;", '"').replaceAll("&apos;", "'")
    .replaceAll("&amp;", "&");
}

export function xmlTag(xml: string, tag: string): string | null {
  const m = new RegExp(`<${tag}>([\\s\\S]*?)</${tag}>`).exec(xml);
  return m ? unescapeXml(m[1]) : null;
}

export function escapeXml(s: string): string {
  return s.replaceAll("&", "&amp;").replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

export function completeBody(parts: R2Part[]): string {
  const items = parts.map((p) =>
    `<Part><PartNumber>${p.n}</PartNumber><ETag>${
      escapeXml(p.etag)
    }</ETag></Part>`
  ).join("");
  return `<CompleteMultipartUpload>${items}</CompleteMultipartUpload>`;
}

function isBusy(status: number, text: string): boolean {
  return status === 429 || xmlTag(text, "Code") === "SlowDown";
}

export function createR2(cfg: R2Config): R2 {
  const client = new AwsClient({
    accessKeyId: cfg.accessKeyId,
    secretAccessKey: cfg.secretAccessKey,
    service: "s3",
    region: "auto",
  });
  const doFetch = cfg.fetchImpl ?? fetch;
  const sleep = cfg.sleep ??
    ((ms: number) => new Promise<void>((r) => setTimeout(r, ms)));
  const base =
    `https://${cfg.accountId}.r2.cloudflarestorage.com/${cfg.bucket}`;
  const objectUrl = (key: string) =>
    `${base}/${key.split("/").map(encodeURIComponent).join("/")}`;

  // Signs and sends one request. 429 backs off 250 ms, 500 ms, 1 s, then 503 storage_busy.
  // aws4fetch's own retries are bypassed (sign + our fetch) so the policy is ours and each
  // attempt carries a fresh signature.
  async function send(
    url: string,
    method: string,
    body?: string,
  ): Promise<{ res: Response; text: string }> {
    for (let attempt = 0;; attempt++) {
      const req = await client.sign(url, {
        method,
        body,
        headers: body === undefined
          ? undefined
          : { "content-type": "application/xml" },
      });
      const res = await doFetch(req);
      const text = method === "HEAD" ? "" : await res.text();
      if (isBusy(res.status, text)) {
        if (attempt >= BACKOFF_MS.length) {
          throw new ApiFailure(
            503,
            "storage_busy",
            "storage is busy, retry shortly",
          );
        }
        await sleep(BACKOFF_MS[attempt]);
        continue;
      }
      return { res, text };
    }
  }

  function check(res: Response, text: string): void {
    // CompleteMultipartUpload can answer 200 with an <Error> body.
    if (res.ok && !/^\s*(<\?xml[^>]*\?>\s*)?<Error>/.test(text)) return;
    throw new R2Error(
      res.status,
      xmlTag(text, "Code") ?? `http_${res.status}`,
      xmlTag(text, "Message") ?? `R2 responded ${res.status}`,
    );
  }

  async function presign(
    url: string,
    method: string,
    ttl: number,
  ): Promise<string> {
    const u = new URL(url);
    u.searchParams.set("X-Amz-Expires", String(ttl));
    const signed = await client.sign(new Request(u, { method }), {
      aws: { signQuery: true },
    });
    return signed.url;
  }

  return {
    async createMultipart(key) {
      const { res, text } = await send(`${objectUrl(key)}?uploads`, "POST");
      check(res, text);
      const id = xmlTag(text, "UploadId");
      if (!id) throw new R2Error(res.status, "no_upload_id", "no UploadId");
      return id;
    },
    async completeMultipart(key, uploadId, parts) {
      const url = `${objectUrl(key)}?uploadId=${encodeURIComponent(uploadId)}`;
      const { res, text } = await send(url, "POST", completeBody(parts));
      check(res, text);
    },
    async abortMultipart(key, uploadId) {
      const url = `${objectUrl(key)}?uploadId=${encodeURIComponent(uploadId)}`;
      const { res, text } = await send(url, "DELETE");
      check(res, text);
    },
    async head(key) {
      const { res } = await send(objectUrl(key), "HEAD");
      if (res.status === 404) return null;
      if (!res.ok) {
        throw new R2Error(res.status, `http_${res.status}`, "HEAD failed");
      }
      return { bytes: Number(res.headers.get("content-length") ?? "0") };
    },
    // partNumber and uploadId are in the URL before signing; Content-Type is not signed.
    presignPart(key, uploadId, n) {
      const url = `${objectUrl(key)}?partNumber=${n}&uploadId=${
        encodeURIComponent(uploadId)
      }`;
      return presign(url, "PUT", PART_URL_TTL_S);
    },
    presignGet(key) {
      return presign(objectUrl(key), "GET", GET_URL_TTL_S);
    },
  };
}

export function r2FromEnv(): R2 {
  const need = (n: string) => {
    const v = Deno.env.get(n);
    if (!v) throw new Error(`missing env: ${n}`);
    return v;
  };
  return createR2({
    accountId: need("R2_ACCOUNT_ID"),
    accessKeyId: need("R2_ACCESS_KEY_ID"),
    secretAccessKey: need("R2_SECRET_ACCESS_KEY"),
    bucket: need("R2_BUCKET"),
  });
}
