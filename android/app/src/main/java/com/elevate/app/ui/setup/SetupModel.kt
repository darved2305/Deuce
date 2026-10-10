package com.elevate.app.ui.setup

import com.elevate.app.domain.calibration.Corner
import com.elevate.app.domain.calibration.CourtCheck
import com.elevate.app.domain.calibration.CourtGeometry
import com.elevate.app.domain.calibration.ImagePoint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Contract `sport` (contracts §2). Squash, padel and pickleball are not listed at all (§9). */
@Serializable
enum class Sport(val label: String) {
    @SerialName("badminton") Badminton("Badminton"),
    @SerialName("table_tennis") TableTennis("Table tennis"),
    @SerialName("tennis") Tennis("Tennis"),
    @SerialName("free_play") FreePlay("Free play"),
}

/** Contract `format`. Free play is always [None]. */
@Serializable
enum class Format(val label: String) {
    @SerialName("singles") Singles("Singles"),
    @SerialName("doubles") Doubles("Doubles"),
    @SerialName("none") None("None"),
}

/** Contract `mode`. Free play is always [FreePlay]. */
@Serializable
enum class Mode(val label: String) {
    @SerialName("match") Match("Match"),
    @SerialName("practice") Practice("Practice"),
    @SerialName("free_play") FreePlay("Free play"),
}

@Serializable
data class Ruleset(val id: String, val version: String)

/** What this build knows per sport. Nothing here is guessed: unknown means not offered. */
object SetupCatalog {
    /**
     * Ruleset id + version per sport. Only badminton has a pinned version (contracts §2 example);
     * §10 names `ittf_11`, `tennis_bo3_tb`, `free_play` but no version, so they are not sent.
     */
    val RULESETS: Map<Sport, Ruleset> = mapOf(Sport.Badminton to Ruleset("bwf_3x21", "2025-04"))

    /** Phase 2 analyses badminton only (table tennis has no tracker, §9). */
    val ANALYSABLE: Set<Sport> = setOf(Sport.Badminton)

    fun formats(sport: Sport): List<Format> = when (sport) {
        Sport.FreePlay -> listOf(Format.None)
        else -> listOf(Format.Singles, Format.Doubles)
    }

    fun modes(sport: Sport): List<Mode> = when (sport) {
        Sport.FreePlay -> listOf(Mode.FreePlay)
        else -> listOf(Mode.Match, Mode.Practice)
    }

    /** Court template; always matches the format (contracts §2). Null where no court is tapped. */
    fun template(sport: Sport, format: Format): String? = when {
        sport == Sport.Badminton && format == Format.Singles -> "badminton_singles"
        sport == Sport.Badminton && format == Format.Doubles -> "badminton_doubles"
        else -> null
    }
}

/**
 * Two screens: the court (sport, format and corners together; free play: the ball), then who is
 * you. The last step finishes the flow: there is no separate review screen.
 */
enum class Step { Court, Ball, WhoIsMe }

sealed interface SetupAction {
    data class SelectSport(val sport: Sport) : SetupAction
    data class SelectFormat(val format: Format) : SetupAction
    data class SelectMode(val mode: Mode) : SetupAction
    /** A tap on the picture, already in source px. */
    data class Tap(val p: ImagePoint) : SetupAction
    /** A drag of [corner] begins: the position before it becomes one undo step. */
    data class DragStart(val corner: Corner) : SetupAction
    data class Drag(val corner: Corner, val p: ImagePoint) : SetupAction
    data object Undo : SetupAction
    data object Reset : SetupAction
    data object Next : SetupAction
    data object Back : SetupAction
}

/**
 * The whole "Set up analysis" flow as plain data. [srcW]/[srcH] are the upright source size,
 * [frameMs] the PTS (ms) of the frame being tapped on: every tap is stamped with it.
 */
data class SetupState(
    val srcW: Double,
    val srcH: Double,
    val frameMs: Long,
    val sport: Sport = Sport.Badminton,
    val format: Format = Format.Singles,
    val mode: Mode = Mode.Match,
    val step: Step = Step.Court,
    val corners: Map<Corner, ImagePoint> = emptyMap(),
    /** Undo stack of earlier corner sets, newest last. */
    val history: List<Map<Corner, ImagePoint>> = emptyList(),
    val me: ImagePoint? = null,
    val ball: ImagePoint? = null,
    val analysable: Set<Sport> = SetupCatalog.ANALYSABLE,
    val rulesets: Map<Sport, Ruleset> = SetupCatalog.RULESETS,
) {
    /** A sport is offered only when this build analyses it and knows its ruleset version. */
    fun isEnabled(s: Sport) = s in analysable && s in rulesets

    val template: String? get() = SetupCatalog.template(sport, format)

    /** Free play has no court to tap: the ball step replaces it. */
    val steps: List<Step>
        get() = if (sport == Sport.FreePlay) listOf(Step.Ball, Step.WhoIsMe) else listOf(Step.Court, Step.WhoIsMe)

    /** Sport, format and mode are chosen on the first screen only. */
    val onFirstStep: Boolean get() = step == steps.first()

    val nextCorner: Corner? get() = Corner.entries.firstOrNull { it !in corners }

    val courtCheck: CourtCheck get() = CourtGeometry.check(corners, srcW, srcH)

    /** Why Next is off on this step; null when it is on. */
    val blocker: String?
        get() = when (step) {
            Step.Court -> if (!isEnabled(sport)) "${sport.label} isn't analysed in this build" else (courtCheck as? CourtCheck.Problem)?.text
            Step.Ball -> if (!isEnabled(sport)) "${sport.label} isn't analysed in this build" else if (ball == null) "Tap the ball" else null
            Step.WhoIsMe -> if (me == null) "Tap yourself" else if (result() == null) "Setup is incomplete" else null
        }

    val canNext: Boolean get() = blocker == null

    val canUndo: Boolean
        get() = when (step) {
            Step.Court -> history.isNotEmpty()
            Step.Ball -> ball != null
            Step.WhoIsMe -> me != null
        }

    /** The session inputs this flow produces, or null until every step is complete and valid. */
    fun result(): SetupResult? {
        if (!isEnabled(sport)) return null
        val ruleset = rulesets[sport] ?: return null
        val who = me ?: return null
        val calibration = if (sport == Sport.FreePlay) {
            null
        } else {
            val t = template ?: return null
            if (courtCheck != CourtCheck.Ok) return null
            CalibrationInput(
                method = "tap",
                plane = "court",
                template = t,
                pointsPx = PointsPx(
                    nearLeft = xy(corners.getValue(Corner.NearLeft)),
                    nearRight = xy(corners.getValue(Corner.NearRight)),
                    farRight = xy(corners.getValue(Corner.FarRight)),
                    farLeft = xy(corners.getValue(Corner.FarLeft)),
                ),
                frameMs = frameMs,
            )
        }
        val seed = if (sport == Sport.FreePlay) TimedPoint(xy(ball ?: return null), frameMs) else null
        return SetupResult(sport, format, ruleset, mode, calibration, TimedPoint(xy(who), frameMs), seed)
    }

    private fun xy(p: ImagePoint) = listOf(round1(p.x), round1(p.y))
    private fun round1(v: Double) = Math.round(v * 10) / 10.0
}

/** Pure reducer: every user action on the setup flow. Unknown or disallowed actions change nothing. */
fun reduce(s: SetupState, a: SetupAction): SetupState = when (a) {
    is SetupAction.SelectSport -> when {
        !s.onFirstStep || !s.isEnabled(a.sport) || a.sport == s.sport -> s
        // The court (and its corners) depend on the sport: start the taps over.
        else -> s.copy(
            sport = a.sport,
            step = if (a.sport == Sport.FreePlay) Step.Ball else Step.Court,
            format = SetupCatalog.formats(a.sport).first(),
            mode = SetupCatalog.modes(a.sport).first(),
            corners = emptyMap(), history = emptyList(), ball = null,
        )
    }
    is SetupAction.SelectFormat -> when {
        !s.onFirstStep || a.format !in SetupCatalog.formats(s.sport) || a.format == s.format -> s
        // Singles and doubles courts have different corners: earlier taps no longer apply.
        else -> s.copy(format = a.format, corners = emptyMap(), history = emptyList())
    }
    is SetupAction.SelectMode ->
        if (s.onFirstStep && a.mode in SetupCatalog.modes(s.sport)) s.copy(mode = a.mode) else s
    is SetupAction.Tap -> {
        val p = clamp(s, a.p)
        when (s.step) {
            Step.Court -> s.nextCorner?.let { c -> s.copy(corners = s.corners + (c to p), history = s.history + listOf(s.corners)) } ?: s
            Step.Ball -> s.copy(ball = p)
            Step.WhoIsMe -> s.copy(me = p)
        }
    }
    is SetupAction.DragStart ->
        if (s.step == Step.Court && a.corner in s.corners) s.copy(history = s.history + listOf(s.corners)) else s
    is SetupAction.Drag ->
        if (s.step == Step.Court && a.corner in s.corners) s.copy(corners = s.corners + (a.corner to clamp(s, a.p))) else s
    SetupAction.Undo -> when (s.step) {
        Step.Court -> if (s.history.isEmpty()) s else s.copy(corners = s.history.last(), history = s.history.dropLast(1))
        Step.Ball -> s.copy(ball = null)
        Step.WhoIsMe -> s.copy(me = null)
    }
    SetupAction.Reset -> when (s.step) {
        // Reset is itself one undo step, so a slip of the finger loses nothing.
        Step.Court -> if (s.corners.isEmpty()) s else s.copy(corners = emptyMap(), history = s.history + listOf(s.corners))
        Step.Ball -> s.copy(ball = null)
        Step.WhoIsMe -> s.copy(me = null)
    }
    SetupAction.Next -> {
        val i = s.steps.indexOf(s.step)
        if (!s.canNext || i < 0 || i == s.steps.lastIndex) s else s.copy(step = s.steps[i + 1])
    }
    SetupAction.Back -> {
        val i = s.steps.indexOf(s.step)
        if (i <= 0) s else s.copy(step = s.steps[i - 1])
    }
}

private fun clamp(s: SetupState, p: ImagePoint) = ImagePoint(p.x.coerceIn(0.0, s.srcW), p.y.coerceIn(0.0, s.srcH))

/** Instruction line for the current step (shown on the frame). */
fun SetupState.instruction(): String = when (step) {
    Step.Court -> nextCorner?.let { "Tap the ${it.label.lowercase().replace(' ', '-')} corner" }
        ?: "Drag a corner to adjust"
    Step.Ball -> if (ball == null) "Tap the ball" else "Tap again to move it"
    Step.WhoIsMe -> if (me == null) "Tap yourself" else "Tap again to move it"
}

/** Which lines bound the tapped court, so the corners match the template. */
fun SetupState.courtHint(): String? = when (template) {
    "badminton_singles" -> "Singles court: inner side lines, outer back lines"
    "badminton_doubles" -> "Doubles court: outer side lines, outer back lines"
    else -> null
}
