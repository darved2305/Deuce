/**
 * Pure, deterministic shaping of a real match.v1 record into coach-ready answers.
 * No I/O and no model: every number is a count taken from the record, with its denominator,
 * so an assistant quotes the API and never computes statistics itself.
 *
 * The deep link format is `elevate://m/{match}/r/{rally}?t_ms={t}` (docs/lanes/person-3-spec.md
 * section 11). The Android app opens the analysed video at that match time.
 */
// Structural view of the match.v1 fields used here (the full schema lives in the vendored
// contracts and is enforced by the server before data reaches these functions).
type State = string;
export type Match = {
  match_id: string;
  players: Array<{ player_id: string; label: string; identity: string }>;
  rallies: Array<{
    rally_id: string;
    start_ms: number;
    end_ms: number;
    shot_ids: string[];
    winner: string;
    winner_state: State;
    end_reason: string;
  }>;
  shots: Array<{
    shot_id: string;
    rally_id: string;
    index: number;
    player_id: string;
    contact_ms: number;
    chosen: { target_zone: string; landing_state: State };
  }>;
  moments: Array<{
    moment_id: string;
    rally_id: string;
    shot_id: string;
    start_ms: number;
    end_ms: number;
    contact_ms: number;
    tags: string[];
    clip_id: string;
  }>;
  insights: Array<{
    kind: string;
    caption: string;
    count: { num: number; den: number };
    evidence_moment_ids: string[];
  }>;
};
export type MatchEvent = {
  event_id: string;
  type: string;
  t_ms: number;
  t_sigma_ms?: number | null;
  state: State;
  cues?: string[];
};
type Rally = Match["rallies"][number];
type Shot = Match["shots"][number];
type Moment = Match["moments"][number];


export function deepLink(
  matchId: string,
  rallyId: string,
  tMs: number,
): string {
  return `elevate://m/${matchId}/r/${rallyId}?t_ms=${Math.max(0, Math.round(tMs))}`;
}

/** The player the signed-in account is mapped to (identity `user_confirmed`), if any. */
export function focalPlayerId(match: Match): string | null {
  return match.players.find((p) => p.identity === "user_confirmed")
    ?.player_id ?? null;
}

const byStart = (a: Rally, b: Rally) => a.start_ms - b.start_ms;

export type MomentFilters = {
  tags?: string[];
  player?: string;
  outcome?: "won" | "lost";
  limit?: number;
};

/** Moments of one match with typed filters. `player` is the player whose shot is the moment. */
export function filterMoments(match: Match, filters: MomentFilters) {
  const shots = new Map(match.shots.map((s) => [s.shot_id, s]));
  const rallies = new Map(match.rallies.map((r) => [r.rally_id, r]));
  const wanted = filters.tags?.map((t) => t.toLowerCase()) ?? [];
  const items = match.moments
    .filter((m) => {
      const shot = shots.get(m.shot_id);
      const rally = rallies.get(m.rally_id);
      if (wanted.length > 0) {
        const have = m.tags.map((t) => t.toLowerCase());
        if (!wanted.every((t) => have.includes(t))) return false;
      }
      if (filters.player && shot?.player_id !== filters.player) return false;
      if (filters.outcome) {
        // "won" and "lost" are from the point of view of the shot's player.
        if (!shot || !rally) return false;
        const won = rally.winner === shot.player_id;
        if ((filters.outcome === "won") !== won) return false;
      }
      return true;
    })
    .sort((a, b) => a.contact_ms - b.contact_ms)
    .slice(0, filters.limit ?? 20)
    .map((m: Moment) => ({
      ...m,
      match_id: match.match_id,
      deep_link: deepLink(match.match_id, m.rally_id, m.contact_ms),
    }));
  return { items, total_matching: items.length, next_cursor: null };
}

export function rallyDetail(match: Match, rallyId: string) {
  const rally = match.rallies.find((r) => r.rally_id === rallyId);
  if (!rally) return null;
  const order = new Map(rally.shot_ids.map((id, i) => [id, i]));
  const shots = match.shots
    .filter((s) => s.rally_id === rallyId)
    .sort((a, b) => (order.get(a.shot_id) ?? a.index) - (order.get(b.shot_id) ?? b.index));
  return {
    ...rally,
    shots,
    deep_link: deepLink(match.match_id, rally.rally_id, rally.start_ms),
  };
}

type ZoneRow = { zone: string; shots: number; share: { num: number; den: number } };

/** Where a player's shots were aimed (`chosen.target_zone`), as counts and shares. */
export function landingZones(match: Match, playerId?: string) {
  const players = playerId
    ? match.players.filter((p) => p.player_id === playerId)
    : match.players;
  return players.map((p) => {
    const shots = match.shots.filter((s) => s.player_id === p.player_id);
    const zones = new Map<string, number>();
    const states: Record<string, number> = {};
    for (const s of shots) {
      zones.set(s.chosen.target_zone, (zones.get(s.chosen.target_zone) ?? 0) + 1);
      states[s.chosen.landing_state] = (states[s.chosen.landing_state] ?? 0) + 1;
    }
    const rows: ZoneRow[] = [...zones]
      .map(([zone, n]) => ({ zone, shots: n, share: { num: n, den: shots.length } }))
      .sort((a, b) => b.shots - a.shots || a.zone.localeCompare(b.zone));
    return {
      player_id: p.player_id,
      label: p.label,
      identity: p.identity,
      shots_total: shots.length,
      landing_state_counts: states,
      zones: rows,
    };
  });
}

/** Rally rhythm: duration and shot-count facts, all taken from the rally list. */
export function rallyRhythm(match: Match) {
  const rallies = [...match.rallies].sort(byStart);
  const durations = rallies.map((r) => r.end_ms - r.start_ms).sort((a, b) => a - b);
  const lengths = rallies.map((r) => r.shot_ids.length).sort((a, b) => a - b);
  const median = (xs: number[]) =>
    xs.length === 0
      ? null
      : xs.length % 2
      ? xs[(xs.length - 1) / 2]
      : (xs[xs.length / 2 - 1] + xs[xs.length / 2]) / 2;
  return {
    rallies: rallies.length,
    median_duration_ms: median(durations),
    longest_duration_ms: durations.at(-1) ?? null,
    median_shots: median(lengths),
    longest_shots: lengths.at(-1) ?? null,
  };
}

export type RewatchFocus = "longest_rallies" | "lost_rallies" | "landings";

/**
 * Rewatch picks with a reason a player can check. Rallies are ranked by shot count, then by
 * duration. `lost_rallies` keeps only rallies the focal player did not win.
 */
export function rewatchPicks(
  match: Match,
  opts: { focus?: RewatchFocus; limit?: number; player?: string } = {},
) {
  const focus = opts.focus ?? "longest_rallies";
  const limit = opts.limit ?? 3;
  const me = opts.player ?? focalPlayerId(match);
  const rallies = [...match.rallies].filter((r) =>
    focus === "lost_rallies" ? me !== null && r.winner !== me : true
  );
  rallies.sort((a, b) =>
    b.shot_ids.length - a.shot_ids.length ||
    (b.end_ms - b.start_ms) - (a.end_ms - a.start_ms) ||
    a.start_ms - b.start_ms
  );
  const rhythm = rallyRhythm(match);
  const picks = rallies.slice(0, limit).map((r, i) => {
    const shots: Shot[] = match.shots.filter((s) => s.rally_id === r.rally_id);
    const mine = me ? shots.filter((s) => s.player_id === me) : [];
    const hotZone = topZone(mine);
    const seconds = Math.round((r.end_ms - r.start_ms) / 100) / 10;
    const reasons = [
      `${r.shot_ids.length} shots over ${seconds} s (match median ${rhythm.median_shots} shots)`,
    ];
    if (me && r.winner !== me) {
      reasons.push(`you lost it (${r.end_reason}; winner is ${r.winner_state})`);
    }
    if (hotZone) {
      reasons.push(
        `${hotZone.shots} of your ${mine.length} shots here aimed at zone ${hotZone.zone}`,
      );
    }
    return {
      rank: i + 1,
      rally_id: r.rally_id,
      start_ms: r.start_ms,
      end_ms: r.end_ms,
      shots: r.shot_ids.length,
      end_reason: r.end_reason,
      winner: r.winner,
      winner_state: r.winner_state,
      reasons,
      deep_link: deepLink(match.match_id, r.rally_id, r.start_ms),
    };
  });
  const evidenceMoment = (id: string) =>
    match.moments.find((m) => m.moment_id === id);
  const insights = match.insights.slice(0, 3).map((ins) => ({
    kind: ins.kind,
    caption: ins.caption,
    count: ins.count,
    deep_links: ins.evidence_moment_ids.flatMap((id) => {
      const m = evidenceMoment(id);
      return m ? [deepLink(match.match_id, m.rally_id, m.contact_ms)] : [];
    }),
  }));
  return {
    match_id: match.match_id,
    focus,
    focal_player: me,
    focal_player_known: me !== null,
    rhythm,
    picks,
    insights,
    note: "Counts come from the match record; zones and winners keep their provenance state.",
  };
}

function topZone(shots: Shot[]): { zone: string; shots: number } | null {
  const zones = new Map<string, number>();
  for (const s of shots) {
    zones.set(s.chosen.target_zone, (zones.get(s.chosen.target_zone) ?? 0) + 1);
  }
  const top = [...zones].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))[0];
  return top ? { zone: top[0], shots: top[1] } : null;
}

type ProgressEvent = {
  type: string;
  seq: number;
  window_seq?: number;
  windows_expected?: number;
  artifact_id?: string;
  reason?: string;
};

/** Windows done, windows expected and the latest artifact, from the events cursor. */
export function windowProgress(events: ProgressEvent[]) {
  let expected: number | null = null;
  const done = new Set<number>();
  const failed = new Set<number>();
  const artifacts: string[] = [];
  let failure: string | null = null;
  for (const e of events) {
    // The contract puts the total on analysis.started; the deployed engine repeats it on every
    // window.completed. Take it from whichever event carries it.
    if (e.windows_expected !== undefined) expected = e.windows_expected;
    if (e.type === "window.completed" && e.window_seq !== undefined) {
      done.add(e.window_seq);
      if (e.artifact_id) artifacts.push(e.artifact_id);
    }
    if (e.type === "window.failed" && e.window_seq !== undefined) {
      failed.add(e.window_seq);
    }
    if (e.type === "analysis.failed") failure = e.reason ?? "unknown";
  }
  return {
    windows_expected: expected,
    windows_done: done.size,
    windows_failed: failed.size,
    window_artifact_ids: artifacts,
    failure_reason: failure,
    last_seq: events.reduce((m, e) => Math.max(m, e.seq), 0),
  };
}

/** Counts `hit` events in a window_result JSON body (as fetched from a signed artifact URL). */
export function countHits(windowResult: unknown): number {
  if (typeof windowResult !== "object" || windowResult === null) return 0;
  const events = (windowResult as { events?: unknown }).events;
  if (!Array.isArray(events)) return 0;
  return events.filter((e) =>
    typeof e === "object" && e !== null && (e as { type?: unknown }).type === "hit"
  ).length;
}

export type EventFilters = { type?: string; state?: string; limit?: number };

/**
 * The engine's detections on the match timeline (hits, landings, ...), exactly as recorded:
 * counts per type and provenance state over the whole record, plus the first `limit` items in
 * time order that pass the filters. Nothing is promoted: a "candidate" stays a candidate.
 */
export function eventSummary(events: MatchEvent[], filters: EventFilters = {}) {
  const counts = new Map<string, number>();
  for (const e of events) {
    const key = `${e.type}/${e.state}`;
    counts.set(key, (counts.get(key) ?? 0) + 1);
  }
  const matching = events
    .filter((e) =>
      (filters.type === undefined || e.type === filters.type) &&
      (filters.state === undefined || e.state === filters.state)
    )
    .sort((a, b) => a.t_ms - b.t_ms);
  const limit = filters.limit ?? 20;
  return {
    total_events: events.length,
    counts: [...counts.entries()].sort().map(([key, count]) => {
      const [type, state] = key.split("/");
      return { type, state, count };
    }),
    matching: matching.length,
    items: matching.slice(0, limit).map((e) => ({
      event_id: e.event_id,
      type: e.type,
      state: e.state,
      t_ms: e.t_ms,
      t_sigma_ms: e.t_sigma_ms ?? null,
      cues: e.cues ?? [],
    })),
  };
}
