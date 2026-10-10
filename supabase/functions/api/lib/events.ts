import type { SupabaseClient } from "@supabase/supabase-js";
import { ApiFailure } from "./errors.ts";

export type EventRow = {
  event_id: string;
  analysis_id: string;
  match_id: string;
  seq: number;
  schema_version: number;
  type: string;
  window_seq: number | null;
  decode_start_us: number | string | null;
  decode_end_us: number | string | null;
  emit_start_us: number | string | null;
  emit_end_us: number | string | null;
  artifact_id: string | null;
  reason: string | null;
  engine_version: string | null;
  created_at: string;
};

export type EventWire = {
  event_id: string;
  analysis_id: string;
  match_id: string;
  seq: number;
  schema_version: number;
  type: string;
  window_seq: number | null;
  decode_window_us: [number, number] | null;
  emit_range_us: [number, number] | null;
  artifact_id: string | null;
  reason: string | null;
  engine_version: string | null;
  created_at: string;
};

export type InternalEventBody = {
  type: string;
  window_seq?: number;
  decode_window_us?: [number, number];
  emit_range_us?: [number, number];
  artifact_id?: string;
  reason?: string;
  engine_version?: string;
};

function pair(
  a: number | string | null,
  b: number | string | null,
): [number, number] | null {
  return a === null || b === null ? null : [Number(a), Number(b)];
}

export function rowToWire(r: EventRow): EventWire {
  return {
    event_id: r.event_id,
    analysis_id: r.analysis_id,
    match_id: r.match_id,
    seq: r.seq,
    schema_version: r.schema_version,
    type: r.type,
    window_seq: r.window_seq,
    decode_window_us: pair(r.decode_start_us, r.decode_end_us),
    emit_range_us: pair(r.emit_start_us, r.emit_end_us),
    artifact_id: r.artifact_id,
    reason: r.reason,
    engine_version: r.engine_version,
    created_at: r.created_at,
  };
}

export function fieldsToRpc(b: InternalEventBody): Record<string, unknown> {
  const f: Record<string, unknown> = {};
  if (b.window_seq !== undefined) f.window_seq = b.window_seq;
  if (b.decode_window_us) {
    f.decode_start_us = b.decode_window_us[0];
    f.decode_end_us = b.decode_window_us[1];
  }
  if (b.emit_range_us) {
    f.emit_start_us = b.emit_range_us[0];
    f.emit_end_us = b.emit_range_us[1];
  }
  if (b.artifact_id !== undefined) f.artifact_id = b.artifact_id;
  if (b.reason !== undefined) f.reason = b.reason;
  if (b.engine_version !== undefined) f.engine_version = b.engine_version;
  return f;
}

export async function appendEvent(
  admin: SupabaseClient,
  analysisId: string,
  type: string,
  fields: Record<string, unknown> = {},
): Promise<EventRow> {
  const { data, error } = await admin.rpc("append_event", {
    p_analysis_id: analysisId,
    p_type: type,
    p_fields: fields,
  });
  if (error) {
    if (error.code === "P0002") {
      throw new ApiFailure(404, "not_found", "analysis not found");
    }
    if (error.message === "invalid_transition") {
      throw new ApiFailure(
        409,
        "invalid_transition",
        "event not allowed in the current state",
        { detail: error.details },
      );
    }
    throw new Error(`append_event failed: ${error.code} ${error.message}`);
  }
  return data as EventRow;
}

export async function listEvents(
  user: SupabaseClient,
  analysisId: string,
  afterSeq: number,
  limit: number,
): Promise<{
  events: EventWire[];
  next_after_seq: number;
  has_more: boolean;
  state: string;
}> {
  const run = await user.from("analysis_runs").select("id,state")
    .eq("id", analysisId).maybeSingle();
  if (run.error) throw run.error;
  if (!run.data) throw new ApiFailure(404, "not_found", "analysis not found");
  const res = await user.from("analysis_events").select("*")
    .eq("analysis_id", analysisId).gt("seq", afterSeq)
    .order("seq", { ascending: true }).limit(limit + 1);
  if (res.error) throw res.error;
  const rows = (res.data ?? []) as EventRow[];
  const page = rows.slice(0, limit);
  return {
    events: page.map(rowToWire),
    next_after_seq: page.length ? page[page.length - 1].seq : afterSeq,
    has_more: rows.length > limit,
    state: (run.data as { state: string }).state,
  };
}
