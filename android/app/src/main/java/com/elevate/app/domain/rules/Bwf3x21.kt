package com.elevate.app.domain.rules

// Rules source: BWF Statutes, Section 4.1, Laws of Badminton:
// https://system.bwfbadminton.com/documents/folder_1_81/Statutes/CHAPTER-4---RULES-OF-THE-GAME/SECTION%204.1-%20Laws%20of%20Badminton.pdf
// Kotlin port of packages/rules/src/bwf_3x21.ts (Laws 7.1-7.6, 8.1, 10.1/10.3, 11.1/11.3/11.4/11.6);
// both run the same golden vectors in packages/rules/vectors/bwf_3x21.json.
object Bwf3x21 {

    const val RULESET_ID = "bwf_3x21"
    const val RULESET_VERSION = "2025-04"

    private val PLAYER_ID = Regex("p_[A-Za-z0-9_-]+")

    /** Build the opening state for the BWF 3 x 21 scoring ruleset. */
    fun initialState(input: InitialStateInput): RulesState {
        if (input.ruleset.id != RULESET_ID || input.ruleset.version != RULESET_VERSION) {
            throw RulesError.InvalidInputError("Unsupported ruleset: ${input.ruleset.id}@${input.ruleset.version}.")
        }
        if (input.format != "singles" && input.format != "doubles") {
            throw RulesError.InvalidInputError("Unsupported badminton format: ${input.format}.")
        }
        if (input.teams.size != 2) {
            throw RulesError.InvalidInputError("Exactly two teams are required.")
        }

        val playersPerTeam = if (input.format == "singles") 1 else 2
        for (team in input.teams) {
            if (team.size != playersPerTeam || team.any { !isPlayerId(it) }) {
                throw RulesError.InvalidInputError("${input.format} requires $playersPerTeam valid player ID(s) per team.")
            }
        }
        val allPlayers = input.teams[0] + input.teams[1]
        if (allPlayers.toSet().size != allPlayers.size) {
            throw RulesError.InvalidInputError("Each player ID must be unique within the match.")
        }
        if (input.firstServer !in allPlayers) {
            throw RulesError.InvalidInputError("firstServer must identify a player on one of the teams.")
        }
        if (input.ends.team0 != "near" && input.ends.team0 != "far") {
            throw RulesError.InvalidInputError("ends.team0 must be near or far.")
        }

        var doublesRightPlayer: List<String>? = null
        if (input.format == "doubles") {
            val servingTeam = findPlayerTeam(input.teams, input.firstServer)
            val receivingTeam = otherTeam(servingTeam)
            // BWF Laws 11.1.1 and 11.1.4: the first server starts on the right; the receiver
            // is diagonally opposite. The contract has no firstReceiver input, so team order
            // makes the initial receiver deterministic while preserving the legal positions.
            doublesRightPlayer = MutableList(2) { "" }.also {
                it[servingTeam] = input.firstServer
                it[receivingTeam] = input.teams[receivingTeam][1]
            }
        }

        val state = RulesState(
            rulesetId = RULESET_ID,
            rulesetVersion = RULESET_VERSION,
            sport = "badminton",
            format = input.format,
            teams = listOf(input.teams[0].toList(), input.teams[1].toList()),
            doublesRightPlayer = doublesRightPlayer,
            games = emptyList(),
            current = listOf(0, 0),
            server = input.firstServer,
            serviceCourt = "right",
            ends = Ends(team0 = input.ends.team0),
            status = "in_progress",
        )
        assertState(state)
        return state
    }

    /** Apply one confirmed rally winner and return a new state without mutating the input. */
    fun applyPoint(state: RulesState, winningTeam: Int): RulesState {
        assertState(state)
        if (state.status == "match_over") throw RulesError.MatchOverError()
        if (winningTeam != 0 && winningTeam != 1) {
            throw RulesError.InvalidInputError("winningTeam must be 0 or 1.")
        }

        var next = copyState(state)
        if (next.status == "game_over") {
            // The completed score is already in `games`; the winning side is ready to serve
            // from the right court in the next game (BWF Laws 7.6 and 11.6).
            next = next.copy(status = "in_progress")
        }

        val currentGame = next.games.size + 1
        val servingTeam = findPlayerTeam(next.teams, next.server)
        val previousScore = next.current
        val score = next.current.toMutableList()
        score[winningTeam] += 1

        var doublesRightPlayer = next.doublesRightPlayer
        if (next.format == "doubles" && winningTeam == servingTeam) {
            // BWF Laws 11.3.1 and 11.1.5: the same server serves again from the alternate
            // service court; only the serving side changes its player positions on a point.
            doublesRightPlayer = doublesRightPlayer!!.toMutableList().also {
                it[servingTeam] = if (score[servingTeam] % 2 == 0) {
                    next.server
                } else {
                    otherPlayer(next.teams, servingTeam, next.server)
                }
            }
        }

        var nextServer = next.server
        if (winningTeam != servingTeam) {
            // BWF Laws 10.3.2 and 11.3.2: service passes to the rally winner. In doubles,
            // Laws 11.1.3-11.1.5 keep their positions, Law 11.1.6 matches the score to a
            // court, and Laws 11.4.1-11.4.5 define the doubles service rotation.
            nextServer = playerForScoreCourt(next, doublesRightPlayer, winningTeam, score[winningTeam])
        }

        val nextCourt = courtForScore(score[findPlayerTeam(next.teams, nextServer)])
        var nextEnds = next.ends
        if (currentGame == 3 && previousScore[0] < 11 && previousScore[1] < 11 &&
            (score[0] >= 11 || score[1] >= 11)
        ) {
            // BWF Law 8.1.3: change ends when a side first reaches 11 in the third game.
            nextEnds = nextEnds.copy(team0 = oppositeEnd(nextEnds.team0))
        }

        val winner = gameWinner(score)
        var status = "in_progress"
        if (winner != null) {
            val gameWins = (next.games + listOf(score)).map { gameWinner(it) }
            // BWF Law 7.1: the first side to win two of at most three games wins the match.
            val winnerCount = gameWins.count { it == winner }
            status = if (winnerCount >= 2) "match_over" else "game_over"

            if (currentGame == 1 || (currentGame == 2 && status != "match_over")) {
                // BWF Laws 8.1.1-8.1.2: change ends after game one and after game two only
                // when a third game will be played.
                nextEnds = nextEnds.copy(team0 = oppositeEnd(nextEnds.team0))
            }
        }

        var completedGames = next.games
        var resultCurrent = score.toList()
        var resultServer = nextServer
        var resultCourt = nextCourt
        if (winner != null) {
            // BWF Laws 7.6 and 11.6: the game winner serves first in the next game.
            // Record the completed score and stage that server at 0-0 on the right court.
            completedGames = next.games + listOf(score.toList())
            resultCurrent = listOf(0, 0)
            resultServer = if (next.format == "singles") next.teams[winner][0] else rightPlayer(doublesRightPlayer, winner)
            resultCourt = "right"
        }

        val result = next.copy(
            doublesRightPlayer = doublesRightPlayer,
            games = completedGames,
            current = resultCurrent,
            server = resultServer,
            serviceCourt = resultCourt,
            ends = nextEnds,
            status = status,
        )
        assertState(result)
        return result
    }

    /** Restore the latest prior snapshot; history entries are states before each action. */
    fun undo(state: RulesState, history: List<RulesState>): RulesState {
        val previous = history.lastOrNull() ?: throw RulesError.EmptyHistoryError()
        assertState(previous)
        return copyState(previous)
    }

    fun server(state: RulesState): String = state.server

    fun courtSide(state: RulesState): String = state.serviceCourt

    fun isGameOver(state: RulesState): Boolean = state.status == "game_over" || state.status == "match_over"

    fun isMatchOver(state: RulesState): Boolean = state.status == "match_over"

    private fun gameWinner(score: List<Int>): Int? {
        // BWF Laws 7.2, 7.4 and 7.5: 21 with a two-point lead, except the 30-point cap.
        if (score[0] == 30 || (score[0] >= 21 && score[0] - score[1] >= 2)) return 0
        if (score[1] == 30 || (score[1] >= 21 && score[1] - score[0] >= 2)) return 1
        return null
    }

    private fun courtForScore(score: Int): String {
        // BWF Laws 10.1.1-10.1.2 and 11.1.1-11.1.2: even, including zero, is right.
        return if (score % 2 == 0) "right" else "left"
    }

    private fun playerForScoreCourt(state: RulesState, doublesRightPlayer: List<String>?, team: Int, score: Int): String {
        if (state.format == "singles") return state.teams[team][0]
        val right = rightPlayer(doublesRightPlayer, team)
        return if (score % 2 == 0) right else otherPlayer(state.teams, team, right)
    }

    private fun rightPlayer(doublesRightPlayer: List<String>?, team: Int): String {
        val player = doublesRightPlayer?.getOrNull(team)
        if (player.isNullOrEmpty()) throw RulesError.InvalidStateError("Doubles state is missing doubles_right_player.")
        return player
    }

    private fun otherPlayer(teams: List<List<String>>, team: Int, player: String): String {
        return teams[team].firstOrNull { it != player }
            ?: throw RulesError.InvalidStateError("Player $player has no doubles partner on team $team.")
    }

    private fun findPlayerTeam(teams: List<List<String>>, player: String): Int {
        if (player in teams[0]) return 0
        if (player in teams[1]) return 1
        throw RulesError.InvalidStateError("Server $player is not a member of either team.")
    }

    private fun otherTeam(team: Int): Int = if (team == 0) 1 else 0

    private fun oppositeEnd(end: String): String = if (end == "near") "far" else "near"

    private fun copyState(state: RulesState): RulesState = state.copy(
        teams = state.teams.map { it.toList() },
        doublesRightPlayer = state.doublesRightPlayer?.toList(),
        games = state.games.map { it.toList() },
        current = state.current.toList(),
        ends = state.ends.copy(),
    )

    private fun isPlayerId(value: String): Boolean = value.matches(PLAYER_ID)

    private fun assertState(state: RulesState) {
        if (state.rulesetId != RULESET_ID || state.rulesetVersion != RULESET_VERSION ||
            state.sport != "badminton"
        ) {
            throw RulesError.InvalidStateError("State does not identify bwf_3x21@2025-04 badminton rules.")
        }
        if (state.format != "singles" && state.format != "doubles") {
            throw RulesError.InvalidStateError("State format must be singles or doubles.")
        }
        val playersPerTeam = if (state.format == "singles") 1 else 2
        if (state.teams.any { it.size != playersPerTeam || it.any { id -> !isPlayerId(id) } }) {
            throw RulesError.InvalidStateError("State teams must contain $playersPerTeam valid player ID(s) each.")
        }
        if (state.current.size != 2 || state.current.any { it < 0 }) {
            throw RulesError.InvalidStateError("Current game score must contain two non-negative integers.")
        }
        if (state.games.size > 3 || state.games.any {
            it.size != 2 || it.any { s -> s < 0 } || gameWinner(it) == null
        }
        ) {
            throw RulesError.InvalidStateError("Completed games must contain winning scores, with at most three games.")
        }
        if (state.status != "in_progress" && state.status != "game_over" && state.status != "match_over") {
            throw RulesError.InvalidStateError("State has an invalid status.")
        }
        if (state.ends.team0 != "near" && state.ends.team0 != "far") {
            throw RulesError.InvalidStateError("ends.team0 must be near or far.")
        }
        if (state.serviceCourt != "right" && state.serviceCourt != "left") {
            throw RulesError.InvalidStateError("service_court must be right or left.")
        }

        val servingTeam = findPlayerTeam(state.teams, state.server)
        if (state.serviceCourt != courtForScore(state.current[servingTeam])) {
            throw RulesError.InvalidStateError("service_court must match the server's score parity.")
        }
        if (state.format == "doubles") {
            val positions = state.doublesRightPlayer
            if (positions == null || positions.size != 2 || positions[0] == positions[1] ||
                positions[0] !in state.teams[0] || positions[1] !in state.teams[1]
            ) {
                throw RulesError.InvalidStateError("Doubles state must identify one right-court player for each team.")
            }
            val serverIsInCourt = if (state.serviceCourt == "right") {
                positions[servingTeam] == state.server
            } else {
                positions[servingTeam] != state.server
            }
            if (!serverIsInCourt) {
                throw RulesError.InvalidStateError("Doubles server must occupy the court matching the service score parity.")
            }
        }
        if ((state.status == "game_over" || state.status == "match_over") &&
            (state.current[0] != 0 || state.current[1] != 0)
        ) {
            throw RulesError.InvalidStateError("A completed game or match must have a reset current score.")
        }
        if (state.status == "game_over") {
            val lastGameWinner = if (state.games.isNotEmpty()) gameWinner(state.games.last()) else null
            if (lastGameWinner == null || state.games.size > 2 ||
                state.games.count { gameWinner(it) == 0 } >= 2 ||
                state.games.count { gameWinner(it) == 1 } >= 2 ||
                findPlayerTeam(state.teams, state.server) != lastGameWinner
            ) {
                throw RulesError.InvalidStateError("game_over requires an unfinished match and the last game winner ready to serve.")
            }
        }
        if (state.status == "in_progress" && (state.games.size > 2 || gameWinner(state.current) != null ||
            state.games.count { gameWinner(it) == 0 } >= 2 ||
            state.games.count { gameWinner(it) == 1 } >= 2)
        ) {
            throw RulesError.InvalidStateError("in_progress requires an unfinished game and an unfinished match.")
        }
        if (state.status == "match_over" && (state.games.size < 2 ||
            (state.games.count { gameWinner(it) == 0 } < 2 &&
                state.games.count { gameWinner(it) == 1 } < 2))
        ) {
            throw RulesError.InvalidStateError("match_over status requires a team to have won two games.")
        }
        if (state.status == "match_over") {
            val lastGameWinner = gameWinner(state.games[state.games.size - 1])
            if (lastGameWinner == null || findPlayerTeam(state.teams, state.server) != lastGameWinner) {
                throw RulesError.InvalidStateError("The match winner must be staged as the next server.")
            }
        }
    }
}
