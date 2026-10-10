// GENERATED from packages/rules - do not edit.
// Regenerate with supabase/scripts/sync-rules.ts
// Rules source: BWF Statutes, Section 4.1, Laws of Badminton:
// https://system.bwfbadminton.com/documents/folder_1_81/Statutes/CHAPTER-4---RULES-OF-THE-GAME/SECTION%204.1-%20Laws%20of%20Badminton.pdf
// This ruleset implements Laws 7.1-7.6, 8.1, 10.1/10.3, and 11.1/11.3/11.4/11.6.
export type TeamIndex = 0 | 1;
export type CourtSide = "right" | "left";
export type MatchFormat = "singles" | "doubles";
export type PlayerId = string;

export type RulesState = {
  ruleset_id: string;
  ruleset_version: string;
  sport: "badminton";
  format: MatchFormat;
  teams: [PlayerId[], PlayerId[]];
  doubles_right_player?: [PlayerId, PlayerId];
  /** Final scores of completed games. */
  games: Array<[number, number]>;
  /** Score in the active game, or [0, 0] between games and after match end. */
  current: [number, number];
  server: PlayerId;
  service_court: CourtSide;
  ends: { team0: "near" | "far" };
  status: "in_progress" | "game_over" | "match_over";
};

export type RulesetRef = { id: string; version: string };

export type InitialStateInput = {
  ruleset: RulesetRef;
  format: MatchFormat;
  teams: [PlayerId[], PlayerId[]];
  firstServer: PlayerId;
  ends: { team0: "near" | "far" };
};

export class RulesError extends Error {
  readonly code: string;

  constructor(code: string, message: string) {
    super(message);
    this.name = new.target.name;
    this.code = code;
  }
}

export class MatchOverError extends RulesError {
  constructor() {
    super("match_over", "Cannot apply a point after the match is over.");
  }
}

export class InvalidInputError extends RulesError {
  constructor(message: string) {
    super("invalid_input", message);
  }
}

export class InvalidStateError extends RulesError {
  constructor(message: string) {
    super("invalid_state", message);
  }
}

export class EmptyHistoryError extends RulesError {
  constructor() {
    super("empty_history", "Cannot undo without a previous state in history.");
  }
}

const RULESET_ID = "bwf_3x21";
const RULESET_VERSION = "2025-04";

/** Build the opening state for the BWF 3 x 21 scoring ruleset. */
export function initialState(input: InitialStateInput): RulesState {
  if (input.ruleset.id !== RULESET_ID || input.ruleset.version !== RULESET_VERSION) {
    throw new InvalidInputError(`Unsupported ruleset: ${input.ruleset.id}@${input.ruleset.version}.`);
  }
  if (input.format !== "singles" && input.format !== "doubles") {
    throw new InvalidInputError(`Unsupported badminton format: ${String(input.format)}.`);
  }
  if (input.teams.length !== 2) {
    throw new InvalidInputError("Exactly two teams are required.");
  }

  const playersPerTeam = input.format === "singles" ? 1 : 2;
  for (const team of input.teams) {
    if (team.length !== playersPerTeam || team.some((id) => !isPlayerId(id))) {
      throw new InvalidInputError(`${input.format} requires ${playersPerTeam} valid player ID(s) per team.`);
    }
  }
  const allPlayers = [...input.teams[0], ...input.teams[1]];
  if (new Set(allPlayers).size !== allPlayers.length) {
    throw new InvalidInputError("Each player ID must be unique within the match.");
  }
  if (!allPlayers.includes(input.firstServer)) {
    throw new InvalidInputError("firstServer must identify a player on one of the teams.");
  }
  if (input.ends.team0 !== "near" && input.ends.team0 !== "far") {
    throw new InvalidInputError("ends.team0 must be near or far.");
  }

  const state: RulesState = {
    ruleset_id: RULESET_ID,
    ruleset_version: RULESET_VERSION,
    sport: "badminton",
    format: input.format,
    teams: [ [...input.teams[0]], [...input.teams[1]] ],
    games: [],
    current: [0, 0],
    server: input.firstServer,
    service_court: "right",
    ends: { team0: input.ends.team0 },
    status: "in_progress",
  };

  if (input.format === "doubles") {
    const servingTeam = findPlayerTeam(state, input.firstServer);
    const receivingTeam = otherTeam(servingTeam);
    // BWF Laws 11.1.1 and 11.1.4: the first server starts on the right; the receiver
    // is diagonally opposite. The contract has no firstReceiver input, so team order
    // makes the initial receiver deterministic while preserving the legal positions.
    const rightPlayer: [PlayerId, PlayerId] = ["", ""];
    rightPlayer[servingTeam] = input.firstServer;
    rightPlayer[receivingTeam] = state.teams[receivingTeam][1];
    state.doubles_right_player = rightPlayer;
  }

  assertState(state);
  return state;
}

/** Apply one confirmed rally winner and return a new state without mutating the input. */
export function applyPoint(state: RulesState, winningTeam: TeamIndex): RulesState {
  assertState(state);
  if (state.status === "match_over") throw new MatchOverError();
  if (winningTeam !== 0 && winningTeam !== 1) {
    throw new InvalidInputError("winningTeam must be 0 or 1.");
  }

  let next = copyState(state);
  if (next.status === "game_over") {
    // The completed score is already in `games`; the winning side is ready to serve
    // from the right court in the next game (BWF Laws 7.6 and 11.6).
    next = { ...next, status: "in_progress" };
  }

  const currentGame = next.games.length + 1;
  const servingTeam = findPlayerTeam(next, next.server);
  const previousScore: [number, number] = [...next.current];
  const score: [number, number] = [...next.current];
  score[winningTeam] += 1;

  if (next.format === "doubles" && winningTeam === servingTeam) {
    // BWF Laws 11.3.1 and 11.1.5: the same server serves again from the alternate
    // service court; only the serving side changes its player positions on a point.
    next.doubles_right_player![servingTeam] = score[servingTeam] % 2 === 0
      ? next.server
      : otherPlayer(next, servingTeam, next.server);
  }

  let nextServer = next.server;
  if (winningTeam !== servingTeam) {
    // BWF Laws 10.3.2 and 11.3.2: service passes to the rally winner. In doubles,
    // Laws 11.1.3-11.1.5 keep their positions, Law 11.1.6 matches the score to a
    // court, and Laws 11.4.1-11.4.5 define the doubles service rotation.
    nextServer = playerForScoreCourt(next, winningTeam, score[winningTeam]);
  }

  const nextCourt = courtForScore(score[findPlayerTeam(next, nextServer)]);
  let nextEnds = { team0: next.ends.team0 };
  if (currentGame === 3 && previousScore[0] < 11 && previousScore[1] < 11 &&
      (score[0] >= 11 || score[1] >= 11)) {
    // BWF Law 8.1.3: change ends when a side first reaches 11 in the third game.
    nextEnds = { team0: oppositeEnd(nextEnds.team0) };
  }

  const winner = gameWinner(score);
  let status: RulesState["status"] = "in_progress";
  if (winner !== null) {
    const gameWins = [...next.games, score].map((game) => gameWinner(game));
    // BWF Law 7.1: the first side to win two of at most three games wins the match.
    const winnerCount = gameWins.filter((gameWinnerTeam) => gameWinnerTeam === winner).length;
    status = winnerCount >= 2 ? "match_over" : "game_over";

    if (currentGame === 1 || (currentGame === 2 && status !== "match_over")) {
      // BWF Laws 8.1.1-8.1.2: change ends after game one and after game two only
      // when a third game will be played.
      nextEnds = { team0: oppositeEnd(nextEnds.team0) };
    }
  }

  let completedGames = next.games;
  let resultCurrent = score;
  let resultServer = nextServer;
  let resultCourt = nextCourt;
  if (winner !== null) {
    // BWF Laws 7.6 and 11.6: the game winner serves first in the next game.
    // Record the completed score and stage that server at 0-0 on the right court.
    completedGames = [...next.games, score];
    resultCurrent = [0, 0];
    resultServer = next.format === "singles" ? next.teams[winner][0] : rightPlayer(next, winner);
    resultCourt = "right";
  }

  const result: RulesState = {
    ...next,
    games: completedGames,
    current: resultCurrent,
    server: resultServer,
    service_court: resultCourt,
    ends: nextEnds,
    status,
  };
  assertState(result);
  return result;
}

/** Restore the latest prior snapshot; history entries are states before each action. */
export function undo(_state: RulesState, history: readonly RulesState[]): RulesState {
  const previous = history[history.length - 1];
  if (!previous) throw new EmptyHistoryError();
  assertState(previous);
  return copyState(previous);
}

export function server(state: RulesState): PlayerId {
  return state.server;
}

export function courtSide(state: RulesState): CourtSide {
  return state.service_court;
}

export function isGameOver(state: RulesState): boolean {
  return state.status === "game_over" || state.status === "match_over";
}

export function isMatchOver(state: RulesState): boolean {
  return state.status === "match_over";
}

function gameWinner(score: readonly [number, number]): TeamIndex | null {
  // BWF Laws 7.2, 7.4 and 7.5: 21 with a two-point lead, except the 30-point cap.
  if (score[0] === 30 || (score[0] >= 21 && score[0] - score[1] >= 2)) return 0;
  if (score[1] === 30 || (score[1] >= 21 && score[1] - score[0] >= 2)) return 1;
  return null;
}

function courtForScore(score: number): CourtSide {
  // BWF Laws 10.1.1-10.1.2 and 11.1.1-11.1.2: even, including zero, is right.
  return score % 2 === 0 ? "right" : "left";
}

function playerForScoreCourt(state: RulesState, team: TeamIndex, score: number): PlayerId {
  if (state.format === "singles") return state.teams[team][0];
  const right = rightPlayer(state, team);
  return score % 2 === 0 ? right : otherPlayer(state, team, right);
}

function rightPlayer(state: RulesState, team: TeamIndex): PlayerId {
  const player = state.doubles_right_player?.[team];
  if (!player) throw new InvalidStateError("Doubles state is missing doubles_right_player.");
  return player;
}

function otherPlayer(state: RulesState, team: TeamIndex, player: PlayerId): PlayerId {
  const partner = state.teams[team].find((candidate) => candidate !== player);
  if (!partner) throw new InvalidStateError(`Player ${player} has no doubles partner on team ${team}.`);
  return partner;
}

function findPlayerTeam(state: RulesState, player: PlayerId): TeamIndex {
  if (state.teams[0].includes(player)) return 0;
  if (state.teams[1].includes(player)) return 1;
  throw new InvalidStateError(`Server ${player} is not a member of either team.`);
}

function otherTeam(team: TeamIndex): TeamIndex {
  return team === 0 ? 1 : 0;
}

function oppositeEnd(end: "near" | "far"): "near" | "far" {
  return end === "near" ? "far" : "near";
}

function copyState(state: RulesState): RulesState {
  return {
    ...state,
    teams: [[...state.teams[0]], [...state.teams[1]]],
    ...(state.doubles_right_player
      ? { doubles_right_player: [...state.doubles_right_player] as [PlayerId, PlayerId] }
      : {}),
    games: state.games.map((game) => [...game]),
    current: [...state.current],
    ends: { ...state.ends },
  };
}

function isPlayerId(value: unknown): value is PlayerId {
  return typeof value === "string" && /^p_[A-Za-z0-9_-]+$/.test(value);
}

function assertState(state: RulesState): void {
  if (state.ruleset_id !== RULESET_ID || state.ruleset_version !== RULESET_VERSION ||
      state.sport !== "badminton") {
    throw new InvalidStateError("State does not identify bwf_3x21@2025-04 badminton rules.");
  }
  if (state.format !== "singles" && state.format !== "doubles") {
    throw new InvalidStateError("State format must be singles or doubles.");
  }
  const playersPerTeam = state.format === "singles" ? 1 : 2;
  if (state.teams.some((team) => team.length !== playersPerTeam || team.some((id) => !isPlayerId(id)))) {
    throw new InvalidStateError(`State teams must contain ${playersPerTeam} valid player ID(s) each.`);
  }
  if (state.current.length !== 2 || state.current.some((score) => !Number.isSafeInteger(score) || score < 0)) {
    throw new InvalidStateError("Current game score must contain two non-negative integers.");
  }
  if (state.games.length > 3 || state.games.some((game) => game.length !== 2 ||
      game.some((score) => !Number.isSafeInteger(score) || score < 0) || gameWinner(game) === null)) {
    throw new InvalidStateError("Completed games must contain winning scores, with at most three games.");
  }
  if (state.status !== "in_progress" && state.status !== "game_over" && state.status !== "match_over") {
    throw new InvalidStateError("State has an invalid status.");
  }
  if (state.ends.team0 !== "near" && state.ends.team0 !== "far") {
    throw new InvalidStateError("ends.team0 must be near or far.");
  }
  if (state.service_court !== "right" && state.service_court !== "left") {
    throw new InvalidStateError("service_court must be right or left.");
  }

  const servingTeam = findPlayerTeam(state, state.server);
  if (state.service_court !== courtForScore(state.current[servingTeam])) {
    throw new InvalidStateError("service_court must match the server's score parity.");
  }
  if (state.format === "doubles") {
    const positions = state.doubles_right_player;
    if (!positions || positions.length !== 2 || positions[0] === positions[1] ||
        !state.teams[0].includes(positions[0]) || !state.teams[1].includes(positions[1])) {
      throw new InvalidStateError("Doubles state must identify one right-court player for each team.");
    }
    const serverIsInCourt = state.service_court === "right"
      ? positions[servingTeam] === state.server
      : positions[servingTeam] !== state.server;
    if (!serverIsInCourt) {
      throw new InvalidStateError("Doubles server must occupy the court matching the service score parity.");
    }
  }
  if ((state.status === "game_over" || state.status === "match_over") &&
      (state.current[0] !== 0 || state.current[1] !== 0)) {
    throw new InvalidStateError("A completed game or match must have a reset current score.");
  }
  if (state.status === "game_over") {
    const lastGameWinner = state.games.length ? gameWinner(state.games[state.games.length - 1]) : null;
    if (lastGameWinner === null || state.games.length > 2 ||
        state.games.filter((game) => gameWinner(game) === 0).length >= 2 ||
        state.games.filter((game) => gameWinner(game) === 1).length >= 2 ||
        findPlayerTeam(state, state.server) !== lastGameWinner) {
      throw new InvalidStateError("game_over requires an unfinished match and the last game winner ready to serve.");
    }
  }
  if (state.status === "in_progress" && (state.games.length > 2 || gameWinner(state.current) !== null ||
      state.games.filter((game) => gameWinner(game) === 0).length >= 2 ||
      state.games.filter((game) => gameWinner(game) === 1).length >= 2)) {
    throw new InvalidStateError("in_progress requires an unfinished game and an unfinished match.");
  }
  if (state.status === "match_over" && (state.games.length < 2 ||
      (state.games.filter((game) => gameWinner(game) === 0).length < 2 &&
       state.games.filter((game) => gameWinner(game) === 1).length < 2))) {
    throw new InvalidStateError("match_over status requires a team to have won two games.");
  }
  if (state.status === "match_over") {
    const lastGameWinner = gameWinner(state.games[state.games.length - 1]);
    if (lastGameWinner === null || findPlayerTeam(state, state.server) !== lastGameWinner) {
      throw new InvalidStateError("The match winner must be staged as the next server.");
    }
  }
}
