import { createRoute, type OpenAPIHono, z } from "@hono/zod-openapi";
import type { Context } from "hono";
import type { SupabaseClient } from "@supabase/supabase-js";
import type { AppEnv } from "../lib/auth.ts";
import {
  errorEnvelopeSchema,
  segmentCompleteResponseSchema,
  segmentManifestSchema,
  segmentRegisterRequestSchema,
  segmentRegisterResponseSchema,
  sessionCreateRequestSchema,
  sessionCreateResponseSchema,
  sessionGetResponseSchema,
  uploadAbortResponseSchema,
  uploadCompleteRequestSchema,
  uploadCompleteResponseSchema,
  uploadPartsRequestSchema,
  uploadPartsResponseSchema,
} from "../lib/contracts_vendor/index.ts";
import { ApiFailure, defaultHook, fail } from "../lib/errors.ts";
import { canonicalHash, idemBegin, idemStore } from "../lib/idempotency.ts";
import { PART_URL_TTL_S, type R2, R2Error } from "../lib/r2.ts";
import {
  addUsage,
  assertCanCreateUpload,
  assertWritesAllowed,
  BUDGET,
  takePartUrls,
  takeSegmentUrl,
} from "../lib/budget.ts";

const ENDPOINT = "POST /v1/sessions";
export const PART_SIZE = 8 * 1024 * 1024;
export const MAX_PARTS_PER_CALL = 50;
const MAX_CONCURRENCY = 2;
const MAX_SEGMENT_BYTES = BUDGET.maxVideoBytes;

const SessionId = z.string().regex(/^s_[A-Za-z0-9_-]+$/);
const IdemHeader = z.object({
  "idempotency-key": z.string().min(1).max(255),
});

function urlExpiresAt(signedUrl: string, expectedTtl: number): string {
  const url = new URL(signedUrl);
  const issued = url.searchParams.get("X-Amz-Date") ?? "";
  const ttl = url.searchParams.get("X-Amz-Expires");
  const match = /^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})Z$/.exec(
    issued,
  );
  if (ttl !== String(expectedTtl) || !match) {
    throw new Error("presigned URL has invalid expiry metadata");
  }
  const issuedAtMs = Date.parse(
    `${match[1]}-${match[2]}-${match[3]}T${match[4]}:${match[5]}:${match[6]}Z`,
  );
  if (!Number.isFinite(issuedAtMs)) {
    throw new Error("presigned URL has an invalid issue time");
  }
  return new Date(issuedAtMs + expectedTtl * 1000).toISOString();
}

const completedResponse = (bytes: number) =>
  uploadCompleteResponseSchema.parse({ status: "completed", bytes });
const abortedResponse = () =>
  uploadAbortResponseSchema.parse({
    status: "aborted",
  });

const createSession = createRoute({
  method: "post",
  path: "/v1/sessions",
  operationId: "createSession",
  request: {
    headers: IdemHeader,
    body: {
      content: { "application/json": { schema: sessionCreateRequestSchema } },
    },
  },
  responses: {
    201: {
      description: "session and match created with the selected upload mode",
      content: {
        "application/json": { schema: sessionCreateResponseSchema },
      },
    },
    422: {
      description: "invalid request",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
  },
});

const getSession = createRoute({
  method: "get",
  path: "/v1/sessions/{id}",
  operationId: "getSession",
  request: { params: z.object({ id: SessionId }) },
  responses: {
    200: {
      description: "session",
      content: {
        "application/json": { schema: sessionGetResponseSchema },
      },
    },
  },
});

const uploadParts = createRoute({
  method: "post",
  path: "/v1/sessions/{id}/upload/parts",
  operationId: "createUploadParts",
  request: {
    params: z.object({ id: SessionId }),
    body: {
      content: { "application/json": { schema: uploadPartsRequestSchema } },
    },
  },
  responses: {
    200: {
      description: "presigned PUT URLs (600 s), in the order requested",
      content: {
        "application/json": { schema: uploadPartsResponseSchema },
      },
    },
    429: {
      description:
        "part URL allowance exceeded; retry after issued URLs expire",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
  },
});

const uploadComplete = createRoute({
  method: "post",
  path: "/v1/sessions/{id}/upload/complete",
  operationId: "completeUpload",
  request: {
    params: z.object({ id: SessionId }),
    body: {
      content: { "application/json": { schema: uploadCompleteRequestSchema } },
    },
  },
  responses: {
    200: {
      description: "upload completed (idempotent)",
      content: {
        "application/json": { schema: uploadCompleteResponseSchema },
      },
    },
    409: {
      description: "uploaded object size does not match source.bytes",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
    503: {
      description: "R2 usage could not be recorded",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
  },
});

const uploadAbort = createRoute({
  method: "post",
  path: "/v1/sessions/{id}/upload/abort",
  operationId: "abortUpload",
  request: { params: z.object({ id: SessionId }) },
  responses: {
    200: {
      description: "upload aborted (idempotent)",
      content: {
        "application/json": { schema: uploadAbortResponseSchema },
      },
    },
  },
});

const registerSegment = createRoute({
  method: "post",
  path: "/v1/sessions/{id}/segments",
  operationId: "registerSegment",
  request: {
    params: z.object({ id: SessionId }),
    body: {
      content: { "application/json": { schema: segmentRegisterRequestSchema } },
    },
  },
  responses: {
    200: {
      description: "segment registered with a presigned PUT URL",
      content: {
        "application/json": { schema: segmentRegisterResponseSchema },
      },
    },
    409: {
      description: "sequence gap or conflicting segment registration",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
    413: {
      description: "segment bytes exceed the G1 cap",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
    429: {
      description: "segment URL or storage budget reached",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
    503: {
      description: "uploads paused or usage could not be recorded",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
  },
});

const completeSegment = createRoute({
  method: "post",
  path: "/v1/sessions/{id}/segments/{seq}/complete",
  operationId: "completeSegment",
  request: {
    params: z.object({
      id: SessionId,
      seq: z.string().transform(Number).pipe(z.number().int().min(0)),
    }),
  },
  responses: {
    200: {
      description: "verified segment manifest (idempotent)",
      content: {
        "application/json": { schema: segmentCompleteResponseSchema },
      },
    },
    409: {
      description: "segment object is missing or has the wrong size",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
    503: {
      description: "R2 usage could not be recorded",
      content: { "application/json": { schema: errorEnvelopeSchema } },
    },
  },
});

type UploadRow = {
  session_id: string;
  r2_key: string;
  r2_upload_id: string;
  part_size: number;
  bytes: number;
  status: "initiated" | "completed" | "aborted";
  completed_at: string | null;
};

type SegmentRow = {
  session_id: string;
  owner_id: string;
  seq: number | string;
  pts_start_us: number | string;
  pts_end_us: number | string;
  starts_on_sync_sample: boolean;
  bytes: number | string;
  sha256: string;
  final: boolean;
  r2_key: string;
  url_requests: number;
  completed_at: string | null;
};

const SEGMENT_COLS =
  "session_id,owner_id,seq,pts_start_us,pts_end_us,starts_on_sync_sample,bytes,sha256,final,r2_key,url_requests,completed_at";
const UPLOAD_COLS =
  "session_id,r2_key,r2_upload_id,part_size,bytes,status,completed_at";

function jwtOf(c: Context): string {
  return (c.req.header("Authorization") ?? "").slice("Bearer ".length);
}

// Loads the caller's upload through the user client, so RLS (owner_id = auth.uid()) decides
// visibility. Another user's session and a missing one are both 404.
async function loadUpload(
  user: SupabaseClient,
  sessionId: string,
): Promise<UploadRow> {
  const res = await user.from("uploads").select(UPLOAD_COLS)
    .eq("session_id", sessionId).maybeSingle();
  if (res.error) throw res.error;
  if (!res.data) throw new ApiFailure(404, "not_found", "session not found");
  return res.data as UploadRow;
}

const partCount = (bytes: number) => Math.ceil(bytes / PART_SIZE);

// Best-effort undo of a half-created session: the match delete cascades to sessions and
// uploads. Admin writes always filter on owner_id so a bug cannot touch another user's rows.
async function rollback(
  admin: SupabaseClient,
  matchId: string,
  ownerId: string,
): Promise<void> {
  const res = await admin.from("matches").delete()
    .eq("id", matchId).eq("owner_id", ownerId);
  if (res.error) console.error("session rollback failed", res.error.code);
}

function segmentManifest(row: SegmentRow) {
  return segmentManifestSchema.parse({
    seq: Number(row.seq),
    pts_start_us: Number(row.pts_start_us),
    pts_end_us: Number(row.pts_end_us),
    starts_on_sync_sample: row.starts_on_sync_sample,
    bytes: Number(row.bytes),
    sha256: row.sha256,
    final: row.final,
    r2_key: row.r2_key,
  });
}

function completedSegment(row: SegmentRow) {
  if (!row.completed_at) {
    throw new ApiFailure(409, "conflict", "segment is not completed");
  }
  return segmentCompleteResponseSchema.parse({
    ...segmentManifest(row),
    completed_at: row.completed_at,
  });
}

function templateMismatch(result: unknown, c: Context): Response | undefined {
  const r = result as {
    success: boolean;
    target?: string;
    error?: { issues?: Array<{ code: string; path: PropertyKey[] }> };
  };
  if (
    !r.success && r.target === "json" &&
    r.error?.issues?.some((i) =>
      i.code === "custom" && i.path.join(".") === "calibration_input.template"
    )
  ) {
    return fail(
      c,
      422,
      "validation_failed",
      "court template does not match format",
      r.error.issues,
    );
  }
  return defaultHook(result, c);
}

async function createUploadRecords(
  deps: { user: SupabaseClient; admin: SupabaseClient; r2: () => R2 },
  ownerId: string,
  body: z.infer<typeof sessionCreateRequestSchema>,
): Promise<z.infer<typeof sessionCreateResponseSchema>> {
  const { user, admin } = deps;
  const match = await user.from("matches").insert({
    owner_id: ownerId,
    sport: body.sport,
    format: body.format,
    ruleset_id: body.ruleset.id,
    ruleset_version: body.ruleset.version,
  }).select("id").single();
  if (match.error) throw match.error;
  const matchId = (match.data as { id: string }).id;
  try {
    const session = await user.from("sessions").insert({
      match_id: matchId,
      owner_id: ownerId,
      mode: body.mode,
      source: body.source,
      calibration_input: body.calibration_input,
      who_is_me: body.who_is_me,
      free_play_seed: body.free_play_seed,
      rally_candidates: body.rally_candidates,
      upload_mode: body.upload_mode,
    }).select("id").single();
    if (session.error) throw session.error;
    const sessionId = (session.data as { id: string }).id;
    if (body.upload_mode === "segments") {
      return sessionCreateResponseSchema.parse({
        session_id: sessionId,
        match_id: matchId,
        upload_mode: "segments",
        segments: {
          max_segment_bytes: MAX_SEGMENT_BYTES,
          max_concurrency: MAX_CONCURRENCY,
          url_ttl_s: PART_URL_TTL_S,
        },
      });
    }
    // Person 1's Modal submit rejects any other key.
    const r2 = deps.r2();
    const r2Key = `orig/${sessionId}/source.mp4`;
    const uploadId = await r2.createMultipart(r2Key);
    await addUsage(admin, 1, 0); // CreateMultipartUpload is Class A
    const ins = await admin.from("uploads").insert({
      session_id: sessionId,
      owner_id: ownerId,
      r2_key: r2Key,
      r2_upload_id: uploadId,
      part_size: PART_SIZE,
      bytes: body.source.bytes,
      sha256: body.source.sha256,
      status: "initiated",
    });
    if (ins.error) {
      await r2.abortMultipart(r2Key, uploadId).catch((e) =>
        console.error("r2 abort after failed insert", (e as Error).message)
      );
      throw ins.error;
    }
    return sessionCreateResponseSchema.parse({
      session_id: sessionId,
      match_id: matchId,
      upload: {
        upload_id: uploadId,
        r2_key: r2Key,
        part_size: PART_SIZE,
        max_concurrency: MAX_CONCURRENCY,
      },
    });
  } catch (err) {
    await rollback(admin, matchId, ownerId);
    throw err;
  }
}

export function registerSessions(api: OpenAPIHono<AppEnv>): void {
  api.openapi(createSession, async (c) => {
    const deps = c.var.deps;
    const body = c.req.valid("json");
    const key = c.req.valid("header")["idempotency-key"];
    const ownerId = c.var.userId;

    if (body.source.bytes > BUDGET.maxVideoBytes) {
      throw new ApiFailure(
        413,
        "upload_too_large",
        `video exceeds ${BUDGET.maxVideoBytes} bytes`,
      );
    }
    if (body.source.bytes < 1) {
      throw new ApiFailure(
        422,
        "validation_failed",
        "source.bytes must be at least 1",
      );
    }

    const admin = deps.admin();
    const hash = await canonicalHash(body);
    const idem = await idemBegin(admin, key, ownerId, ENDPOINT, hash);
    if (idem.kind === "conflict") {
      throw new ApiFailure(
        409,
        "idempotency_conflict",
        "Idempotency-Key was used with a different request",
      );
    }
    if (idem.kind === "replay") {
      return c.json(
        idem.response as z.infer<typeof sessionCreateResponseSchema>,
        201,
      );
    }

    // G9/G10 pause, then G3/G5/G2 budgets. A replay above never reaches here.
    const snap = await assertWritesAllowed(admin, ownerId);
    assertCanCreateUpload(snap, body.source.bytes);
    const out = await createUploadRecords(
      { user: deps.user(jwtOf(c)), admin, r2: deps.r2 },
      ownerId,
      body,
    );
    try {
      await idemStore(admin, key, ownerId, ENDPOINT, hash, 201, out);
    } catch (err) {
      console.error("idempotency store failed", (err as Error).message);
    }
    return c.json(out, 201);
    // The hook may answer with an error Response; its declared type only allows the 201 body.
  }, templateMismatch as never);

  api.openapi(registerSegment, async (c) => {
    const { id } = c.req.valid("param");
    const body = c.req.valid("json");
    if (body.bytes > MAX_SEGMENT_BYTES) {
      throw new ApiFailure(
        413,
        "upload_too_large",
        `segment exceeds ${MAX_SEGMENT_BYTES} bytes`,
      );
    }
    const deps = c.var.deps;
    const user = deps.user(jwtOf(c));
    const session = await user.from("sessions").select("id,upload_mode")
      .eq("id", id).maybeSingle();
    if (session.error) throw session.error;
    if (!session.data) {
      throw new ApiFailure(404, "not_found", "session not found");
    }
    if ((session.data as { upload_mode: string }).upload_mode !== "segments") {
      throw new ApiFailure(409, "conflict", "session does not use segments");
    }

    const admin = deps.admin();
    await assertWritesAllowed(admin, c.var.userId);
    const registered = await admin.rpc("register_segment", {
      p_session_id: id,
      p_owner_id: c.var.userId,
      p_seq: body.seq,
      p_pts_start_us: body.pts_start_us,
      p_pts_end_us: body.pts_end_us,
      p_starts_on_sync_sample: body.starts_on_sync_sample,
      p_bytes: body.bytes,
      p_sha256: body.sha256,
      p_final: body.final,
    });
    if (registered.error) {
      const message = registered.error.message;
      if (registered.error.code === "P0002") {
        throw new ApiFailure(404, "not_found", "session not found");
      }
      if (message === "segment_gap") {
        throw new ApiFailure(
          409,
          "segment_gap",
          "segments must be registered without gaps",
        );
      }
      if (message === "upload_too_large") {
        throw new ApiFailure(
          413,
          "upload_too_large",
          "segment bytes exceed the G1 limit",
        );
      }
      if (message === "storage_budget_reached") {
        throw new ApiFailure(
          429,
          "storage_budget_reached",
          "storage budget reached",
          undefined,
          86400,
        );
      }
      if (
        message === "segment_completed" || message === "segment_conflict" ||
        message === "segment_finalized"
      ) {
        throw new ApiFailure(
          409,
          "conflict",
          "segment registration conflicts with session state",
        );
      }
      throw registered.error;
    }
    if (!registered.data) {
      throw new ApiFailure(
        500,
        "internal",
        "segment registration returned no row",
      );
    }
    const row = registered.data as SegmentRow;
    const putUrl = await deps.r2().presignPut(row.r2_key);
    await takeSegmentUrl(admin, id, body.seq, c.var.userId);
    return c.json(
      segmentRegisterResponseSchema.parse({
        seq: Number(row.seq),
        r2_key: row.r2_key,
        put_url: putUrl,
        expires_at: urlExpiresAt(putUrl, PART_URL_TTL_S),
      }),
      200,
    );
  });

  api.openapi(completeSegment, async (c) => {
    const { id, seq } = c.req.valid("param");
    const deps = c.var.deps;
    const user = deps.user(jwtOf(c));
    const loaded = await user.from("segments").select(SEGMENT_COLS)
      .eq("session_id", id).eq("seq", seq).maybeSingle();
    if (loaded.error) throw loaded.error;
    if (!loaded.data) {
      throw new ApiFailure(404, "not_found", "segment not found");
    }
    const row = loaded.data as SegmentRow;
    if (row.completed_at) return c.json(completedSegment(row), 200);

    const admin = deps.admin();
    const head = await deps.r2().head(row.r2_key);
    await addUsage(admin, 0, 1);
    if (!head) {
      throw new ApiFailure(409, "conflict", "uploaded segment is missing");
    }
    if (head.bytes !== Number(row.bytes)) {
      throw new ApiFailure(
        409,
        "upload_parts_invalid",
        "uploaded segment size does not match declared bytes",
        { expected_bytes: Number(row.bytes), actual_bytes: head.bytes },
      );
    }

    const update = await admin.from("segments").update({
      completed_at: new Date().toISOString(),
    }).eq("session_id", id).eq("owner_id", c.var.userId).eq("seq", seq)
      .is("completed_at", null).select(SEGMENT_COLS).maybeSingle();
    if (update.error) throw update.error;
    if (update.data) {
      return c.json(completedSegment(update.data as SegmentRow), 200);
    }

    const current = await user.from("segments").select(SEGMENT_COLS)
      .eq("session_id", id).eq("seq", seq).maybeSingle();
    if (current.error) throw current.error;
    if (!current.data) {
      throw new ApiFailure(404, "not_found", "segment not found");
    }
    return c.json(completedSegment(current.data as SegmentRow), 200);
  });

  api.openapi(getSession, async (c) => {
    const { id } = c.req.valid("param");
    const reader = c.var.readOnly
      ? c.var.deps.admin()
      : c.var.deps.user(jwtOf(c));
    const sessionQuery = reader.from("sessions")
      .select("id,match_id,mode,upload_mode,source,created_at")
      .eq("id", id);
    const s = await (c.var.readOnly
      ? sessionQuery.eq("owner_id", c.var.userId)
      : sessionQuery).maybeSingle();
    if (s.error) {
      throw s.error;
    }
    if (!s.data) {
      throw new ApiFailure(404, "not_found", "session not found");
    }
    const uploadQuery = reader.from("uploads").select(UPLOAD_COLS)
      .eq("session_id", id);
    const u = await (c.var.readOnly
      ? uploadQuery.eq("owner_id", c.var.userId)
      : uploadQuery).maybeSingle();
    if (u.error) {
      throw u.error;
    }
    const row = s.data as Record<string, unknown> & { id: string };
    const up = u.data as UploadRow | null;
    return c.json(
      sessionGetResponseSchema.parse({
        session_id: row.id,
        match_id: row.match_id,
        mode: row.mode,
        upload_mode: row.upload_mode,
        source: row.source,
        created_at: row.created_at,
        upload: up && {
          status: up.status,
          part_size: up.part_size,
          bytes: Number(up.bytes),
          part_count: partCount(Number(up.bytes)),
          completed_at: up.completed_at,
        },
      }),
      200,
    );
  });

  api.openapi(uploadParts, async (c) => {
    const { id } = c.req.valid("param");
    const { part_numbers } = c.req.valid("json");
    if (part_numbers.length < 1 || part_numbers.length > MAX_PARTS_PER_CALL) {
      throw new ApiFailure(
        422,
        "validation_failed",
        `part_numbers must hold 1 to ${MAX_PARTS_PER_CALL} entries`,
      );
    }
    const up = await loadUpload(c.var.deps.user(jwtOf(c)), id);
    if (up.status !== "initiated") {
      throw new ApiFailure(409, "conflict", `upload is ${up.status}`);
    }
    const count = partCount(Number(up.bytes));
    for (const n of part_numbers) {
      if (n < 1 || n > count) {
        throw new ApiFailure(
          422,
          "validation_failed",
          `part numbers must be in 1..${count}`,
        );
      }
    }
    // G9/G10 pause, then G4 (3 x part count per session); the URLs also count as Class A.
    const admin = c.var.deps.admin();
    await assertWritesAllowed(admin, c.var.userId);
    await takePartUrls(admin, id, c.var.userId, part_numbers.length, count);
    const r2 = c.var.deps.r2();
    const parts = await Promise.all(part_numbers.map(async (n) => {
      const url = await r2.presignPart(up.r2_key, up.r2_upload_id, n);
      return { n, url, expires_at: urlExpiresAt(url, PART_URL_TTL_S) };
    }));
    return c.json(uploadPartsResponseSchema.parse({ parts }), 200);
  });

  api.openapi(uploadComplete, async (c) => {
    const { id } = c.req.valid("param");
    const { parts } = c.req.valid("json");
    const deps = c.var.deps;
    const user = deps.user(jwtOf(c));
    const up = await loadUpload(user, id);
    if (up.status === "completed") {
      return c.json(completedResponse(Number(up.bytes)), 200);
    }
    if (up.status === "aborted") {
      throw new ApiFailure(409, "conflict", "upload was aborted");
    }

    const count = partCount(Number(up.bytes));
    const sorted = [...parts].sort((a, b) => a.n - b.n);
    for (const p of sorted) {
      if (p.n < 1 || p.n > count) {
        throw new ApiFailure(
          422,
          "validation_failed",
          `part numbers must be in 1..${count}`,
        );
      }
    }
    const missing: number[] = [];
    for (let n = 1; n <= count; n++) {
      if (!sorted.some((p) => p.n === n)) missing.push(n);
    }
    const dup = sorted.some((p, i) => i > 0 && sorted[i - 1].n === p.n);
    if (missing.length > 0 || dup) {
      throw new ApiFailure(
        409,
        "conflict",
        "parts must list every part 1..N exactly once",
        { expected_parts: count, missing },
      );
    }

    const r2 = deps.r2();
    const admin = deps.admin();

    // Records the finished upload. The stored object size is what Modal will read, so it
    // wins over the declared size. Only one caller can move initiated -> completed.
    const finalize = async () => {
      const head = await r2.head(up.r2_key);
      await addUsage(admin, 0, 1); // HeadObject is Class B
      if (!head) {
        throw new ApiFailure(
          409,
          "conflict",
          "the uploaded object is missing",
        );
      }
      // The stored object must exactly match source.bytes, not only fit in the last part.
      if (head.bytes !== Number(up.bytes)) {
        await r2.deleteObject(up.r2_key).catch((e) =>
          console.error("r2 delete size mismatch failed", (e as Error).message)
        );
        const aborted = await admin.from("uploads").update({
          status: "aborted",
        })
          .eq("session_id", id).eq("owner_id", c.var.userId)
          .eq("status", "initiated").select("session_id").maybeSingle();
        if (aborted.error) throw aborted.error;
        throw new ApiFailure(
          409,
          "upload_parts_invalid",
          "uploaded object size does not match source.bytes",
          { expected_bytes: Number(up.bytes), actual_bytes: head.bytes },
        );
      }
      const upd = await admin.from("uploads").update({
        status: "completed",
        parts: sorted,
        bytes: head.bytes,
        completed_at: new Date().toISOString(),
      }).eq("session_id", id).eq("owner_id", c.var.userId)
        .eq("status", "initiated").select("bytes").maybeSingle();
      if (upd.error) throw upd.error;
      if (upd.data) {
        return c.json(completedResponse(head.bytes), 200);
      }
      const cur = await loadUpload(user, id);
      if (cur.status !== "completed") {
        throw new ApiFailure(
          409,
          "conflict",
          `upload is ${cur.status}`,
        );
      }
      return c.json(completedResponse(Number(cur.bytes)), 200);
    };

    try {
      await r2.completeMultipart(up.r2_key, up.r2_upload_id, sorted);
      await addUsage(admin, 1, 0); // CompleteMultipartUpload is Class A
    } catch (err) {
      if (!(err instanceof R2Error)) throw err;
      if (err.code === "NoSuchUpload") {
        // A concurrent complete won, or an earlier attempt completed in R2 but failed before
        // the row was updated. Recover from the stored object instead of sticking at initiated.
        const cur = await loadUpload(user, id);
        if (cur.status === "completed") {
          return c.json(completedResponse(Number(cur.bytes)), 200);
        }
        const exists = cur.status === "initiated"
          ? await r2.head(up.r2_key)
          : null;
        if (cur.status === "initiated") await addUsage(admin, 0, 1);
        if (exists) {
          return await finalize();
        }
        throw new ApiFailure(
          409,
          "conflict",
          "the multipart upload no longer exists",
        );
      }
      if (
        err.code === "InvalidPart" || err.code === "InvalidPartOrder" ||
        err.code === "EntityTooSmall"
      ) {
        throw new ApiFailure(409, "conflict", err.message);
      }
      throw err;
    }
    return await finalize();
  });

  api.openapi(uploadAbort, async (c) => {
    const { id } = c.req.valid("param");
    const deps = c.var.deps;
    const up = await loadUpload(deps.user(jwtOf(c)), id);
    if (up.status === "aborted") {
      return c.json(abortedResponse(), 200);
    }
    if (up.status === "completed") {
      throw new ApiFailure(409, "conflict", "upload is completed");
    }
    try {
      await deps.r2().abortMultipart(up.r2_key, up.r2_upload_id);
    } catch (err) {
      if (!(err instanceof R2Error) || err.code !== "NoSuchUpload") throw err;
    }
    const upd = await deps.admin().from("uploads").update({ status: "aborted" })
      .eq("session_id", id).eq("owner_id", c.var.userId)
      .eq("status", "initiated").select("session_id").maybeSingle();
    if (upd.error) throw upd.error;
    if (!upd.data) {
      // Lost a race with complete: report the real state.
      const cur = await loadUpload(deps.user(jwtOf(c)), id);
      if (cur.status !== "aborted") {
        throw new ApiFailure(
          409,
          "conflict",
          `upload is ${cur.status}`,
        );
      }
    }
    return c.json(abortedResponse(), 200);
  });
}
