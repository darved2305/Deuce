package com.elevate.app.domain.rules

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `rules.state.v1` (contracts §10, `rulesStateSchema` in packages/contracts/src/sport.ts):
 * the scoring state a confirmed rally moves forward. Field names are the contract's snake_case;
 * [doublesRightPlayer] exists only for doubles and is omitted from the JSON when null.
 */
@Serializable
data class RulesState(
    @SerialName("ruleset_id") val rulesetId: String,
    @SerialName("ruleset_version") val rulesetVersion: String,
    val sport: String,
    /** "singles" or "doubles". */
    val format: String,
    val teams: List<List<String>>,
    /** Final scores of completed games. */
    val games: List<List<Int>>,
    /** Score in the active game, or [0, 0] between games and after match end. */
    val current: List<Int>,
    val server: String,
    /** "right" or "left". */
    @SerialName("service_court") val serviceCourt: String,
    val ends: Ends,
    /** "in_progress", "game_over" or "match_over". */
    val status: String,
    /** Doubles only: the player in each side's right service court; the partner is on the left. */
    @SerialName("doubles_right_player") val doublesRightPlayer: List<String>? = null,
)

@Serializable
data class Ends(val team0: String)

data class RulesetRef(val id: String, val version: String)

data class InitialStateInput(
    val ruleset: RulesetRef,
    val format: String,
    val teams: List<List<String>>,
    val firstServer: String,
    val ends: Ends,
)

/** Mirror of the rules engine's errors in packages/rules/src/bwf_3x21.ts; [code] is stable. */
sealed class RulesError(val code: String, message: String) : Exception(message) {

    class MatchOverError : RulesError("match_over", "Cannot apply a point after the match is over.")

    class InvalidInputError(message: String) : RulesError("invalid_input", message)

    class InvalidStateError(message: String) : RulesError("invalid_state", message)

    class EmptyHistoryError : RulesError("empty_history", "Cannot undo without a previous state in history.")
}
