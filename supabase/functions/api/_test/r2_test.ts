import assert from "node:assert/strict";
import { ApiFailure } from "../lib/errors.ts";
import { completeBody, createR2, R2Error, xmlTag } from "../lib/r2.ts";

const KEY = "orig/s_abc/source.mp4";

function make(responses: Array<() => Response>) {
  const seen: Array<{ req: Request; body: string }> = [];
  const sleeps: number[] = [];
  let i = 0;
  const r2 = createR2({
    accountId: "acc",
    accessKeyId: "AKID",
    secretAccessKey: "SECRET",
    bucket: "bkt",
    fetchImpl: (async (input: Request) => {
      seen.push({ req: input, body: await input.clone().text() });
      return responses[Math.min(i++, responses.length - 1)]();
    }) as typeof fetch,
    sleep: (ms) => {
      sleeps.push(ms);
      return Promise.resolve();
    },
  });
  return { r2, seen, sleeps };
}

const xml = (status: number, body: string) => () =>
  new Response(body === "" ? null : body, {
    status,
    headers: { "content-type": "application/xml" },
  });
const errXml = (status: number, code: string, msg = "m") =>
  xml(
    status,
    `<?xml version="1.0"?><Error><Code>${code}</Code><Message>${msg}</Message></Error>`,
  );

Deno.test("createMultipart signs a server-side POST ?uploads and parses UploadId", async () => {
  const { r2, seen } = make([
    xml(
      200,
      `<?xml version="1.0"?><InitiateMultipartUploadResult><Bucket>bkt</Bucket><Key>${KEY}</Key><UploadId>VXBsb2Fk+/1==</UploadId></InitiateMultipartUploadResult>`,
    ),
  ]);
  assert.equal(await r2.createMultipart(KEY), "VXBsb2Fk+/1==");
  const { req } = seen[0];
  assert.equal(req.method, "POST");
  assert.equal(
    req.url,
    `https://acc.r2.cloudflarestorage.com/bkt/${KEY}?uploads`,
  );
  const auth = req.headers.get("authorization") ?? "";
  assert.match(
    auth,
    /^AWS4-HMAC-SHA256 Credential=AKID\/\d{8}\/auto\/s3\/aws4_request/,
  );
});

Deno.test("presignPart puts partNumber and uploadId in the signed query, 600 s, host only", async () => {
  const { r2 } = make([xml(200, "")]);
  const url = new URL(await r2.presignPart(KEY, "up/id+=", 2));
  assert.equal(url.origin, "https://acc.r2.cloudflarestorage.com");
  assert.equal(url.pathname, `/bkt/${KEY}`);
  assert.equal(url.searchParams.get("partNumber"), "2");
  assert.equal(url.searchParams.get("uploadId"), "up/id+=");
  assert.equal(url.searchParams.get("X-Amz-Expires"), "600");
  assert.equal(url.searchParams.get("X-Amz-Algorithm"), "AWS4-HMAC-SHA256");
  assert.match(
    url.searchParams.get("X-Amz-Credential") ?? "",
    /^AKID\/\d{8}\/auto\/s3\/aws4_request$/,
  );
  assert.match(url.searchParams.get("X-Amz-Date") ?? "", /^\d{8}T\d{6}Z$/);
  assert.match(url.searchParams.get("X-Amz-Signature") ?? "", /^[0-9a-f]{64}$/);
  // Content-Type must not be signed, or the phone's PUT would need to match it.
  assert.equal(url.searchParams.get("X-Amz-SignedHeaders"), "host");
});

Deno.test("presignGet expires in 300 s", async () => {
  const { r2 } = make([xml(200, "")]);
  const url = new URL(await r2.presignGet("art/x.json"));
  assert.equal(url.searchParams.get("X-Amz-Expires"), "300");
  assert.ok(url.searchParams.get("X-Amz-Signature"));
});

Deno.test("completeMultipart sends the Part list XML to ?uploadId", async () => {
  const { r2, seen } = make([
    xml(
      200,
      "<CompleteMultipartUploadResult><ETag>x-2</ETag></CompleteMultipartUploadResult>",
    ),
  ]);
  await r2.completeMultipart(KEY, "UP1", [
    { n: 1, etag: '"aa"' },
    { n: 2, etag: '"bb"' },
  ]);
  const { req, body } = seen[0];
  assert.equal(req.method, "POST");
  assert.equal(new URL(req.url).searchParams.get("uploadId"), "UP1");
  assert.equal(
    body,
    '<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>"aa"</ETag></Part><Part><PartNumber>2</PartNumber><ETag>"bb"</ETag></Part></CompleteMultipartUpload>',
  );
  assert.equal(completeBody([{ n: 1, etag: "a&b" }]).includes("a&amp;b"), true);
});

Deno.test("InvalidPart on complete surfaces as R2Error with the R2 message", async () => {
  const { r2 } = make([errXml(400, "InvalidPart", "bad etag")]);
  await assert.rejects(
    () => r2.completeMultipart(KEY, "UP1", [{ n: 1, etag: "x" }]),
    (e) =>
      e instanceof R2Error && e.code === "InvalidPart" &&
      e.message === "bad etag",
  );
});

Deno.test("a 200 response carrying an <Error> body is still an error", async () => {
  const { r2 } = make([errXml(200, "InvalidPart", "late")]);
  await assert.rejects(
    () => r2.completeMultipart(KEY, "UP1", [{ n: 1, etag: "x" }]),
    (e) => e instanceof R2Error && e.code === "InvalidPart",
  );
});

Deno.test("abort sends DELETE ?uploadId", async () => {
  const { r2, seen } = make([xml(204, "")]);
  await r2.abortMultipart(KEY, "UP1");
  assert.equal(seen[0].req.method, "DELETE");
  assert.equal(new URL(seen[0].req.url).searchParams.get("uploadId"), "UP1");
});

Deno.test("head returns the stored size, null on 404", async () => {
  const ok = make([
    () =>
      new Response(null, {
        status: 200,
        headers: { "content-length": "20971520" },
      }),
  ]);
  assert.deepEqual(await ok.r2.head(KEY), { bytes: 20971520 });
  assert.equal(ok.seen[0].req.method, "HEAD");
  const miss = make([() => new Response(null, { status: 404 })]);
  assert.equal(await miss.r2.head(KEY), null);
});

Deno.test("429 backs off 250/500/1000 ms then 503 storage_busy", async () => {
  const { r2, sleeps, seen } = make([errXml(429, "SlowDown")]);
  await assert.rejects(
    () => r2.createMultipart(KEY),
    (e) =>
      e instanceof ApiFailure && e.status === 503 && e.code === "storage_busy",
  );
  assert.deepEqual(sleeps, [250, 500, 1000]);
  assert.equal(seen.length, 4);
});

Deno.test("429 then success retries with a fresh signature", async () => {
  const { r2, sleeps, seen } = make([
    errXml(429, "SlowDown"),
    xml(200, "<R><UploadId>U2</UploadId></R>"),
  ]);
  assert.equal(await r2.createMultipart(KEY), "U2");
  assert.deepEqual(sleeps, [250]);
  assert.equal(seen.length, 2);
});

Deno.test("xmlTag unescapes entities", () => {
  assert.equal(xmlTag("<Message>a &amp; b</Message>", "Message"), "a & b");
  assert.equal(xmlTag("<A>x</A>", "B"), null);
});
