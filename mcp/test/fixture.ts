/**
 * Unit-test fixture: a small match.v1 body that passes the vendored schema. It exists only to
 * exercise input validation and response shaping. It is never shown as a demo result; the demo
 * uses real calls to the deployed API.
 */
const ME = "p_me";
const OPP = "p_opp";

type Zone = "F1" | "F2" | "F3" | "M1" | "B1" | "B3" | "L";

function shot(
  rally: string,
  index: number,
  player: string,
  contactMs: number,
  zone: Zone,
  winner: string,
  state = "observed",
) {
  const role = (id: string) => ({
    player_id: id,
    xy_m: [3, 4],
    state: "observed",
    conf: 0.9,
  });
  return {
    shot_id: `sh_${rally}_${index}`,
    rally_id: rally,
    index,
    player_id: player,
    contact_ms: contactMs,
    contact_sigma_ms: 20,
    phase: "rally",
    reference_plane: "court",
    coordinate_frame_version: 1,
    roles: { focal: role(player), opponent: role(player === ME ? OPP : ME) },
    incoming: {
      source_zone: "M1",
      landing_zone: "F1",
      zones_state: "observed",
      family: "clear",
    },
    chosen: {
      target_zone: zone,
      landing_xy_m: [2, 5],
      landing_state: state,
      family: "drop",
    },
    flight_time_ms: 600,
    flight_time_state: "observed",
    context: {
      score_before: [0, 0],
      pressure_bucket: "low",
      rally_len_so_far: index,
      state: "observed",
    },
    outcome: { point_winner: winner, shots_to_end: 3, state: "observed" },
    quality: { camera_quality_grade: "B" },
    clip_id: `c_sh_${rally}_${index}`,
  };
}

function rally(id: string, start: number, end: number, shots: number, winner: string) {
  return {
    rally_id: id,
    start_ms: start,
    end_ms: end,
    shot_ids: Array.from({ length: shots }, (_, i) => `sh_${id}_${i}`),
    server: ME,
    winner,
    winner_state: "candidate",
    end_reason: "landed_in",
    ball_coverage: 0.8,
    clip_id: `c_r_${id}`,
    state: "candidate",
  };
}

export const MATCH_ID = "m_demo1";

export function matchFixture() {
  const plan: Array<[string, number, number, string, number]> = [
    ["r_a", 1000, 5000, OPP, 2],
    ["r_b", 10000, 30000, ME, 5],
    ["r_c", 40000, 52000, OPP, 3],
  ];
  const rallies = plan.map(([id, s, e, w, n]) => rally(id, s, e, n, w));
  const zones: Zone[] = ["F1", "F1", "B3", "F2", "L"];
  const shots = plan.flatMap(([id, s, , w, n]) =>
    Array.from({ length: n }, (_, i) =>
      shot(id, i, i % 2 === 0 ? ME : OPP, s + i * 1500, zones[i % zones.length], w, i === 4 ? "inferred" : "observed"))
  );
  const moments = [
    ["mo_1", "r_a", "sh_r_a_0", 1000, ["landing"]],
    ["mo_2", "r_b", "sh_r_b_1", 11500, ["long_rally", "landing"]],
    ["mo_3", "r_c", "sh_r_c_2", 43000, ["landing"]],
  ].map(([id, r, sh, t, tags]) => ({
    moment_id: id,
    rally_id: r,
    shot_id: sh,
    start_ms: (t as number) - 500,
    end_ms: (t as number) + 1500,
    contact_ms: t,
    tags,
    clip_id: `c_sh_${r}_0`,
  }));
  return {
    schema: "match.v1",
    match_id: MATCH_ID,
    session_ids: ["s_1"],
    sport: "badminton",
    format: "singles",
    ruleset: { id: "bwf_3x21", version: "2025-04" },
    players: [
      { player_id: ME, track_ids: ["t_1"], label: "You", identity: "user_confirmed" },
      { player_id: OPP, track_ids: ["t_2"], label: "Opponent", identity: "opponent" },
    ],
    calibration: null,
    analysis: {
      analysis_id: "a_1",
      engine_version: "0.3.0",
      state: "completed",
      models: [],
      artifacts: { ball_track: null, player_tracks: null },
    },
    capabilities: {
      court: { tier: "core", status: "unknown" },
      ball: { tier: "core", status: "pass" },
      reviews: { tier: "assisted", status: "unknown" },
      score: { tier: "core", status: "unknown" },
    },
    rallies,
    shots,
    events: [],
    reviews: [],
    confirmed_points: [],
    score: { timeline: [], games: [[1, 2]] },
    metrics: [],
    insights: [
      {
        insight_id: "in_1",
        kind: "hero",
        caption: "You aimed 2 of 6 shots at the front-left corner.",
        count: { num: 2, den: 6 },
        visual: { type: "court_map", filter: { player: ME } },
        evidence_moment_ids: ["mo_2"],
        gate: "gate_ok",
      },
    ],
    moments,
    clips: [],
    corrections: [],
  };
}
