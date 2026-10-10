export const ANALYSIS_STATES = [
  "awaiting_upload",
  "uploaded",
  "queued",
  "processing",
  "partial",
  "finalizing",
  "completed",
  "failed",
] as const;

export type AnalysisState = typeof ANALYSIS_STATES[number];

export const MODAL_EVENT_TYPES = [
  "analysis.started",
  "window.completed",
  "window.failed",
  "analysis.failed",
] as const;

export function isTerminal(state: string): boolean {
  return state === "completed" || state === "failed";
}
