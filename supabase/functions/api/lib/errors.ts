import type { Context } from "hono";
import { HTTPException } from "hono/http-exception";

export type ErrorCode =
  | "unauthorized"
  | "not_found"
  | "read_only_key"
  | "validation_failed"
  | "calibration_template_mismatch"
  | "invalid_part_number"
  | "upload_too_large"
  | "upload_not_complete"
  | "upload_parts_invalid"
  | "invalid_transition"
  | "already_finalized"
  | "idempotency_conflict"
  | "storage_busy"
  | "engine_unavailable"
  | "quota_exhausted"
  | "internal";

export type ErrorStatus =
  | 400
  | 401
  | 403
  | 404
  | 409
  | 413
  | 422
  | 429
  | 500
  | 502
  | 503;

export class ApiFailure extends Error {
  constructor(
    readonly status: ErrorStatus,
    readonly code: ErrorCode,
    message: string,
    readonly details?: unknown,
  ) {
    super(message);
  }
}

export function errorBody(code: ErrorCode, message: string, details?: unknown) {
  return {
    error: { code, message, ...(details === undefined ? {} : { details }) },
  };
}

export function fail(
  c: Context,
  status: ErrorStatus,
  code: ErrorCode,
  message: string,
  details?: unknown,
) {
  return c.json(errorBody(code, message, details), status);
}

const STATUS_CODES: Partial<Record<number, ErrorCode>> = {
  400: "validation_failed",
  401: "unauthorized",
  403: "read_only_key",
  404: "not_found",
  422: "validation_failed",
  429: "quota_exhausted",
};

export function defaultHook(result: unknown, c: Context): Response | undefined {
  const r = result as { success: boolean; error?: { issues?: unknown } };
  if (!r.success) {
    return fail(
      c,
      422,
      "validation_failed",
      "request validation failed",
      r.error?.issues ?? [],
    );
  }
}

export function notFound(c: Context): Response {
  return fail(c, 404, "not_found", "route not found");
}

export function onError(err: Error, c: Context): Response {
  if (err instanceof ApiFailure) {
    return fail(c, err.status, err.code, err.message, err.details);
  }
  if (err instanceof HTTPException) {
    const code = STATUS_CODES[err.status] ?? "internal";
    return fail(c, err.status as ErrorStatus, code, err.message);
  }
  console.error(err);
  return fail(c, 500, "internal", "internal error");
}
