export function isTerminal(state: string): boolean {
  return state === "completed" || state === "failed";
}
