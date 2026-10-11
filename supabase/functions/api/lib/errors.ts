import type { Context } from "hono";
import { HTTPException } from "hono/http-exception";
import { knownErrorCodes } from "./contracts_vendor/api.ts";

export type ErrorCode =
  | keyof typeof knownErrorCodes
  | "analysis_limit_reached"
  | "upload_parts_invalid"
  | "read_only_key"
  | "storage_busy"
  | "invalid_input";
export type ErrorStatus =
  | typeof knownErrorCodes[keyof typeof knownErrorCodes]
  | 403
  | 409
  | 429
  | 503;

const LOCAL_ERROR_CODES = {
  analysis_limit_reached: 429,
  upload_parts_invalid: 409,
  read_only_key: 403,
  storage_busy: 503,
  invalid_input: 422,
} as const;

const ERROR_STATUSES: Record<ErrorCode, ErrorStatus> = {
  ...knownErrorCodes,
  ...LOCAL_ERROR_CODES,
};

function assertErrorContract(
  status: ErrorStatus,
  code: ErrorCode,
  retryAfterS?: number,
): void {
  if (ERROR_STATUSES[code] !== status) {
    throw new TypeError(`error code ${code} does not use HTTP ${status}`);
  }
  if (
    status === 429 &&
    ((code !== "analysis_limit_reached" && retryAfterS === undefined) ||
      (retryAfterS !== undefined &&
        (!Number.isFinite(retryAfterS) || retryAfterS < 0)))
  ) {
    throw new TypeError("429 errors require a non-negative retry_after_s");
  }
  if (status !== 429 && retryAfterS !== undefined) {
    throw new TypeError("retry_after_s is only valid on 429 errors");
  }
}

export class ApiFailure extends Error {
  constructor(
    readonly status: ErrorStatus,
    readonly code: ErrorCode,
    message: string,
    readonly details?: unknown,
    readonly retryAfterS?: number,
  ) {
    super(message);
    assertErrorContract(status, code, retryAfterS);
  }
}

// retry_after_s is a sibling of `error` (error.envelope.v1).
export function errorBody(
  code: ErrorCode,
  message: string,
  details?: unknown,
  retryAfterS?: number,
) {
  return {
    error: { code, message, ...(details === undefined ? {} : { details }) },
    ...(retryAfterS === undefined ? {} : { retry_after_s: retryAfterS }),
  };
}

export function fail(
  c: Context,
  status: ErrorStatus,
  code: ErrorCode,
  message: string,
  details?: unknown,
  retryAfterS?: number,
) {
  if (retryAfterS !== undefined) c.header("Retry-After", String(retryAfterS));
  assertErrorContract(status, code, retryAfterS);
  return c.json(errorBody(code, message, details, retryAfterS), status);
}

const STATUS_CODES: Partial<Record<number, ErrorCode>> = {
  401: "unauthorized",
  403: "read_only_key",
  404: "not_found",
  409: "conflict",
  413: "upload_too_large",
  422: "validation_failed",
  500: "internal",
  503: "engine_unavailable",
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
    return fail(
      c,
      err.status,
      err.code,
      err.message,
      err.details,
      err.retryAfterS,
    );
  }
  if (err instanceof HTTPException) {
    if (err.status === 400) {
      return fail(c, 422, "invalid_body", err.message);
    }
    const code = STATUS_CODES[err.status] ?? "internal";
    if (ERROR_STATUSES[code] === err.status) {
      return fail(c, err.status as ErrorStatus, code, err.message);
    }
    return fail(c, 500, "internal", "internal error");
  }
  console.error(err);
  return fail(c, 500, "internal", "internal error");
}
