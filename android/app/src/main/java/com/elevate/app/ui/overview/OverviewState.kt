package com.elevate.app.ui.overview

import androidx.compose.ui.graphics.ImageBitmap
import com.elevate.app.data.api.MatchCalibration
import com.elevate.app.data.api.MatchCapability
import com.elevate.app.data.api.MatchPlayer
import com.elevate.app.data.api.MatchRecord
import com.elevate.app.domain.analysis.AnalysisUiState
import com.elevate.app.domain.analysis.HitCandidate
import com.elevate.app.domain.analysis.HitRallies
import com.elevate.app.domain.analysis.RallyCandidate
import com.elevate.app.domain.calibration.CourtModel
import com.elevate.app.domain.model.TrailTrack
import java.util.Locale
import kotlin.math.roundToInt

/** Where the overview's data came from. Anything but [Live] is labelled on screen. */
sealed interface OverviewSource {
    data class Live(val analysisId: String) : OverviewSource
    /** `fixture_trail.json`: hand labels by a person, not engine output. */
    data class HandLabelledReference(val document: String, val labelledBy: String?) : OverviewSource
    /** A bundled `fixture_match.json` (contracts §11), if one ever ships. */
    data class RecordedMatch(val document: String) : OverviewSource
    data object Nothing : OverviewSource

    val badge: String?
        get() = when (this) {
            is Live -> null
            is HandLabelledReference -> "Hand-labelled reference" + (labelledBy?.let { " · $it" } ?: "")
            is RecordedMatch -> "Recorded match file · not a live analysis"
            Nothing -> null
        }
}

sealed interface ThumbnailState {
    data object Loading : ThumbnailState
    /** A real decoded frame of the source at [atMs], upright (MediaMetadataRetriever). */
    data class Ready(val image: ImageBitmap, val atMs: Long) : ThumbnailState
    data class Unavailable(val reason: String) : ThumbnailState
}

data class RallyRow(
    val rallyId: String,
    val startMs: Long,
    val endMs: Long,
    val shots: Int,
    /** "61% of 430 frames" (frame count known) or "61% of frames". */
    val ballCoverage: String,
    /** e.g. "Won by you · confirmed" or "Winner candidate: opponent · MODEL ESTIMATE". */
    val winner: String?,
    /** True only when the winner was confirmed by a person (`winner_state` = user). */
    val winnerConfirmed: Boolean,
    /** Index into the players list (0 = you) for colour + pattern in the barcode, -1 unknown. */
    val winnerSide: Int,
    /** "observed", "candidate"… verbatim from the record. */
    val state: String,
)

sealed interface RalliesState {
    data class NotAvailable(val reason: String) : RalliesState
    data class Available(val rows: List<RallyRow>, val sourceLabel: String, val timelineMs: Long) : RalliesState
    /** `match.v1` has no rallies: candidates grouped from its hit events (or the windows'), never confirmed. */
    data class FromHits(val rows: List<LiveRallyRow>, val sourceLabel: String) : RalliesState
}

/**
 * One landing as the record gives it: [xM]/[yM] in the calibration template's court metres, [id] the
 * landing event's (or shot's) id, [rallyId] null until rallies are segmented.
 */
data class CourtMark(val xM: Double, val yM: Double, val state: String, val id: String, val rallyId: String?)

sealed interface CourtState {
    /** Not a badminton match: no court template drawn here yet. */
    data class NotApplicable(val reason: String) : CourtState
    /**
     * Court drawn at true proportions; [marks] only from real landing values. [frameOffsetM] is added
     * to a mark's x to place it on the drawing (the doubles rectangle, x = 0 at the left doubles
     * sideline): 0.46 m for `badminton_singles`, whose x = 0 is the left singles sideline.
     */
    data class Badminton(
        val singles: Boolean,
        val marks: List<CourtMark>,
        val note: String,
        val frameOffsetM: Double = 0.0,
        /** "Court from your taps · tier B"; null without a match record. */
        val chip: String? = null,
        /** "92% of the court in frame · fit error 1.8 px". */
        val detail: String? = null,
    ) : CourtState
}

/**
 * Track T: the engine's hit candidates and the rally candidates grouped from them. Candidates only.
 * [windowsRead] of [windowsTotal] is the denominator: windows whose result file is on this phone out
 * of the windows the engine said it will emit ([totalIsExpected]); until it says, out of the windows
 * the server has reported on so far.
 */
data class HitTimelineState(
    val hits: List<HitCandidate> = emptyList(),
    val rallies: List<RallyCandidate> = emptyList(),
    val windowsRead: Int = 0,
    val windowsTotal: Int = 0,
    /** True when [windowsTotal] is the engine's `windows_expected`, false when it is only "reported so far". */
    val totalIsExpected: Boolean = false,
    /** Length of the timeline: the clip when its length is known, else what the windows cover. */
    val timelineMs: Long = 0,
    /** Merged `emit_range_us` of the windows read, in ms: what the ticks can claim to cover. */
    val coveredMs: List<LongRange> = emptyList(),
    /** Set when the hits are `match.v1` events ("the match record · engine 0.4.0"); windows are then not the denominator. */
    val recordSource: String? = null,
) {
    companion object {
        val EMPTY = HitTimelineState()
    }
}

data class MatchOverviewState(
    val source: OverviewSource,
    val title: String,
    /** "Badminton · singles · BWF 3×21" — only fields that are known. */
    val sourceLine: String,
    val thumbnail: ThumbnailState = ThumbnailState.Loading,
    val analysis: AnalysisUiState? = null,
    val selectedPlayer: String,
    val rallies: RalliesState,
    val court: CourtState,
    val capabilities: List<Pair<String, MatchCapability>> = emptyList(),
    /** Why the match record could not be read (route missing, contract mismatch), else null. */
    val matchProblem: String? = null,
    /** Hand-labelled range text when fed from the fixture trail, else null. */
    val referenceDetail: String? = null,
    /** The hand-labelled range in source ms (first..last labelled frame), for its replay button. */
    val referenceRange: LongRange? = null,
    val hitTimeline: HitTimelineState = HitTimelineState.EMPTY,
    /** Built from the live windows while `match.v1` is missing; null once the record is read. */
    val live: LiveOverviewState? = null,
    /** "Opponent · no track linked yet"; null when the record names no opponent. */
    val opponent: String? = null,
    /** Score from confirmed points only; null without a match record. */
    val score: ScoreLine? = null,
)

/** The score as people confirmed it (D-007): never from a model estimate. */
data class ScoreLine(val headline: String, val detail: String?, val confirmed: Boolean)

/** Pure derivations: every displayed count with its denominator, nothing invented. */
object OverviewDerivation {

    /** Court geometry (BWF): 13.40 × 6.10 m, singles 5.18 m wide. */
    const val COURT_LENGTH_M = 13.40
    const val COURT_WIDTH_M = 6.10
    const val SINGLES_WIDTH_M = 5.18
    /** How far outside the court a landing may be and still be drawn. */
    const val MARGIN_M = 1.0

    fun derive(
        title: String,
        match: MatchRecord?,
        analysis: AnalysisUiState?,
        fixture: Pair<String, TrailTrack>? = null,
        recordedMatchDocument: String? = null,
        matchProblem: String? = null,
        /** Sorted real frame times of the source, when probed; gives frame-count denominators. */
        sourceFrameTimesUs: LongArray? = null,
        sourceDurationMs: Long? = null,
        thumbnail: ThumbnailState = ThumbnailState.Loading,
        hitTimeline: HitTimelineState = HitTimelineState.EMPTY,
        /** Live-window view; dropped when [match] is known (the record is the better source). */
        live: LiveOverviewState? = null,
    ): MatchOverviewState {
        val source = when {
            match != null && recordedMatchDocument != null -> OverviewSource.RecordedMatch(recordedMatchDocument)
            analysis?.isLive == true -> OverviewSource.Live((analysis.source as com.elevate.app.domain.analysis.AnalysisSource.Live).analysisId)
            fixture != null -> OverviewSource.HandLabelledReference(fixture.first, fixture.second.provenance.labelledBy)
            else -> OverviewSource.Nothing
        }
        val hits = match?.let { recordHitTimeline(it, sourceDurationMs) } ?: hitTimeline
        return MatchOverviewState(
            source = source,
            title = title,
            sourceLine = sourceLine(match),
            thumbnail = thumbnail,
            analysis = analysis,
            selectedPlayer = selectedPlayer(match),
            rallies = rallies(match, sourceFrameTimesUs, sourceDurationMs, hits),
            court = court(match),
            capabilities = match?.capabilities?.let {
                listOf("Court" to it.court, "Ball" to it.ball, "Reviews" to it.reviews, "Score" to it.score)
            }.orEmpty(),
            matchProblem = matchProblem,
            referenceDetail = fixture?.second?.let(::referenceDetail),
            referenceRange = fixture?.second?.let { (it.rangeStartUs / 1000)..((it.rangeEndUs + 999) / 1000) },
            hitTimeline = hits,
            live = live.takeIf { match == null },
            opponent = opponent(match),
            score = match?.let(::score),
        )
    }

    /**
     * The record's hit events (`type: "hit"`) as the timeline, with rally candidates grouped from
     * them (#72). Null when the record has no hits, so the window timeline (labelled as such) stays.
     */
    fun recordHitTimeline(match: MatchRecord, sourceDurationMs: Long?): HitTimelineState? {
        val hits = match.events.filter { it.type == "hit" }.map { e ->
            HitCandidate(
                tMs = e.tMs,
                sigmaMs = e.tSigmaMs,
                trackId = e.trackId,
                imageXy = e.imageXy?.takeIf { it.size == 2 && it.all(Double::isFinite) }?.let { it[0] to it[1] },
                cues = e.cues,
                state = e.state,
            )
        }.sortedBy { it.tMs }
        if (hits.isEmpty()) return null
        return HitTimelineState(
            hits = hits,
            rallies = HitRallies.group(hits),
            timelineMs = maxOf(sourceDurationMs ?: 0, hits.last().tMs),
            recordSource = "the match record · engine ${match.analysis.engineVersion}",
        )
    }

    /** The player the user confirmed as themselves (`who_is_me`), else the one labelled "you". */
    fun you(match: MatchRecord?): MatchPlayer? =
        match?.players?.firstOrNull { it.identity == "user_confirmed" } ?: match?.players?.firstOrNull { it.label == "you" }

    /** Label shown for a player id: "you", the record's label, or the id when the record does not name it. */
    private fun who(match: MatchRecord, playerId: String): String =
        if (playerId == you(match)?.playerId) "you" else match.players.firstOrNull { it.playerId == playerId }?.label ?: playerId

    private fun identityText(identity: String): String = when (identity) {
        "user_confirmed" -> "confirmed by you"
        "user_named" -> "named by you"
        "engine" -> "from the engine"
        else -> identity
    }

    private fun tracksText(n: Int): String = if (n == 0) "no track linked yet" else "$n track${if (n == 1) "" else "s"}"

    fun opponent(match: MatchRecord?): String? {
        if (match == null) return null
        val me = you(match)
        val others = match.players.filter { it.playerId != me?.playerId }
        if (others.isEmpty()) return null
        return others.joinToString(" · ") { p ->
            val name = if (p.label.equals("opponent", ignoreCase = true)) "Opponent" else "Opponent: ${p.label}"
            // An engine record with no track is only the format's second player, not a detection (#92).
            if (p.identity == "engine" && p.trackIds.isEmpty()) "$name · ${tracksText(0)}"
            else "$name · ${identityText(p.identity)} · ${tracksText(p.trackIds.size)}"
        }
    }

    /**
     * Points per player from `confirmed_points` only (contracts §7: the score changes only from
     * them); games from `score.games` as recorded. No confirmation, no score.
     */
    fun score(match: MatchRecord): ScoreLine {
        val points = match.confirmedPoints
        if (points.isEmpty()) return ScoreLine("No confirmed points yet", "The score counts only points you confirm", confirmed = false)
        val me = you(match)
        val order = listOfNotNull(me?.playerId) + match.players.map { it.playerId }.filter { it != me?.playerId } +
            points.map { it.winner }
        val counts = points.groupingBy { it.winner }.eachCount()
        val headline = order.distinct().joinToString(" · ") { id ->
            "${who(match, id).replaceFirstChar { it.titlecase(Locale.ROOT) }} ${counts[id] ?: 0}"
        }
        val rallies = match.rallies.size.takeIf { it > 0 }?.let { " of $it rall${if (it == 1) "y" else "ies"}" }.orEmpty()
        val games = match.score?.games.orEmpty().filter { it.size == 2 }
        val gamesText = if (games.isEmpty()) {
            null
        } else {
            val youFirst = me != null && match.players.size == 2 && match.players[0].playerId == me.playerId
            (if (youFirst) "games (you first) " else "games as recorded ") + games.joinToString(", ") { "${it[0]}–${it[1]}" }
        }
        val detail = listOfNotNull("${points.size} confirmed point${if (points.size == 1) "" else "s"}$rallies", gamesText).joinToString(" · ")
        return ScoreLine(headline, detail, confirmed = true)
    }

    /**
     * Hit candidates from the downloaded windows, grouped into rally candidates. The total is
     * [windowsExpected] when the engine gave it, else [windowsReported]; never below [windowsRead]
     * (a window cannot be read before it is reported).
     */
    fun hitTimeline(
        hits: List<HitCandidate>,
        windowsRead: Int,
        windowsReported: Int,
        windowsExpected: Int?,
        coverageUs: List<LongRange>,
        sourceDurationMs: Long?,
    ): HitTimelineState {
        val sorted = hits.sortedBy { it.tMs }
        val coveredMs = coverageUs.map { (it.first / 1000)..((it.last + 999) / 1000) }
        val timeline = maxOf(sourceDurationMs ?: 0, coveredMs.maxOfOrNull { it.last } ?: 0, sorted.lastOrNull()?.tMs ?: 0)
        return HitTimelineState(
            hits = sorted,
            rallies = HitRallies.group(sorted),
            windowsRead = windowsRead,
            windowsTotal = maxOf(windowsExpected ?: windowsReported, windowsRead),
            totalIsExpected = windowsExpected != null,
            timelineMs = timeline,
            coveredMs = coveredMs,
        )
    }

    fun sourceLine(match: MatchRecord?): String {
        if (match == null) return "Sport and format not known until the match record arrives"
        val sport = match.sport.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }
        val format = match.format.takeIf { it != "none" }
        return listOfNotNull(sport, format, "${match.ruleset.id} ${match.ruleset.version}").joinToString(" · ")
    }

    fun selectedPlayer(match: MatchRecord?): String {
        val me = you(match) ?: return "Player not selected yet"
        val tracks = if (me.trackIds.isEmpty()) null else tracksText(me.trackIds.size)
        return listOfNotNull("You", tracks, identityText(me.identity)).joinToString(" · ")
    }

    fun rallies(match: MatchRecord?, frameTimesUs: LongArray?, durationMs: Long?, hits: HitTimelineState? = null): RalliesState {
        if (match == null) return RalliesState.NotAvailable("Rallies not available yet: no match record from the server")
        if (match.rallies.isEmpty()) {
            if (hits != null && hits.rallies.isNotEmpty()) {
                val n = hits.hits.size
                val where = hits.recordSource ?: "the engine windows"
                return RalliesState.FromHits(
                    LiveOverview.rallyRows(hits),
                    "Grouped from $n hit candidate${if (n == 1) "" else "s"} in $where · the record has no rallies yet · not confirmed",
                )
            }
            return RalliesState.NotAvailable("Rallies not available yet: the match record has none")
        }
        val ordered = match.rallies.sortedBy { it.startMs }
        val me = you(match)
        val rows = ordered.map { r ->
            val frames = frameTimesUs?.let { countFrames(it, r.startMs * 1000, r.endMs * 1000) }
            val known = match.players.any { it.playerId == r.winner }
            val side = when {
                me != null && r.winner == me.playerId -> 0
                known -> 1
                else -> -1
            }
            val who = who(match, r.winner)
            val confirmed = r.winnerState == "user"
            RallyRow(
                rallyId = r.rallyId,
                startMs = r.startMs,
                endMs = r.endMs,
                shots = r.shotIds.size,
                ballCoverage = ballCoverageLabel(r.ballCoverage, frames),
                winner = if (confirmed) "Won by $who · confirmed" else "Winner candidate: $who · MODEL ESTIMATE",
                winnerConfirmed = confirmed,
                winnerSide = side,
                state = r.state,
            )
        }
        val label = "${rows.size} rall${if (rows.size == 1) "y" else "ies"} from the match record · engine ${match.analysis.engineVersion}"
        val timeline = maxOf(durationMs ?: 0, ordered.maxOf { it.endMs })
        return RalliesState.Available(rows, label, timeline)
    }

    /** `ball_coverage` is a fraction of the rally's frames; the denominator is the frame count when known. */
    fun ballCoverageLabel(fraction: Double, frames: Int?): String {
        if (!fraction.isFinite() || fraction < 0 || fraction > 1) return "ball coverage invalid in record"
        val pct = (fraction * 100).roundToInt()
        return if (frames != null && frames > 0) "ball seen in $pct% of $frames frames" else "ball seen in $pct% of frames"
    }

    /** "Court from your taps · tier B" (method `tap`), else the method named verbatim. */
    fun courtChip(cal: MatchCalibration?): String {
        if (cal == null) return "Court not calibrated"
        val how = if (cal.method == "tap") "Court from your taps" else "Court from ${cal.method}"
        return "$how · tier ${cal.tier}"
    }

    /** "92% of the court in frame · fit error 1.8 px"; parts with invalid values are left out. */
    fun courtDetail(cal: MatchCalibration?): String? {
        if (cal == null) return null
        val parts = listOfNotNull(
            cal.visibleFraction.takeIf { it.isFinite() && it in 0.0..1.0 }?.let { "${(it * 100).roundToInt()}% of the court in frame" },
            cal.residualPx.takeIf { it.isFinite() && it >= 0 }?.let { String.format(Locale.ROOT, "fit error %.1f px", it) },
        )
        return parts.joinToString(" · ").ifEmpty { null }
    }

    /**
     * Landing marks from `events` with `type: "landing"` (contracts §7; `contact_xy_m` in the
     * calibration template's court metres); a record without landing events falls back to its
     * shots' `landing_xy_m`. Every count says what it is out of; nothing is drawn without metres.
     */
    fun court(match: MatchRecord?): CourtState {
        if (match != null && match.sport != "badminton") {
            return CourtState.NotApplicable("Court view is drawn for badminton only so far")
        }
        val singles = match?.format != "doubles"
        if (match == null) return CourtState.Badminton(singles, emptyList(), "No landings yet")
        val cal = match.calibration
        val chip = courtChip(cal)
        val detail = courtDetail(cal)
        val landingEvents = match.events.filter { it.type == "landing" }
        val fromEvents = landingEvents.isNotEmpty()
        val total = if (fromEvents) landingEvents.size else match.shots.size
        fun empty(note: String) = CourtState.Badminton(singles, emptyList(), note, chip = chip, detail = detail)
        if (total == 0) return empty("No landings in this match")
        val noun = if (fromEvents && landingEvents.all { it.state == "candidate" }) "landing candidate" else "landing"
        val count = "$total $noun${if (total == 1) "" else "s"}"
        if (cal == null) return empty("$count · not drawn: the match is not calibrated")
        if (cal.plane != "court") return empty("$count · not drawn: calibration is not on the court plane")
        val bounds = CourtModel.courtBounds(cal.template) ?: return empty("$count · not drawn: no court drawing for template ${cal.template}")
        val offset = 0.0 - bounds.first // not -x: keeps +0.0 for the doubles frame
        val positioned: List<CourtMark> = if (fromEvents) {
            landingEvents.mapNotNull { e ->
                val xy = e.contactXyM
                if (xy == null || xy.size != 2 || !xy.all(Double::isFinite) || (e.plane != null && e.plane != "court")) null
                else CourtMark(xy[0], xy[1], e.state, e.eventId, e.rallyId)
            }
        } else {
            match.shots.mapNotNull { s ->
                val xy = s.chosen.landingXyM
                if (xy.size != 2 || !xy.all(Double::isFinite)) null else CourtMark(xy[0], xy[1], s.chosen.landingState, s.shotId, s.rallyId)
            }
        }
        val marks = positioned.filter { mk ->
            val x = mk.xM + offset
            x in -MARGIN_M..COURT_WIDTH_M + MARGIN_M && mk.yM in -MARGIN_M..COURT_LENGTH_M + MARGIN_M
        }
        fun byState() = " (" + marks.groupingBy { it.state }.eachCount().toSortedMap().entries.joinToString(", ") { "${it.value} ${it.key}" } + ")"
        val off = positioned.size - marks.size
        val offText = if (off > 0) " · $off further than ${MARGIN_M.toInt()} m outside the court, not drawn" else ""
        val shots = "$total shot${if (total == 1) "" else "s"}"
        val note = when {
            fromEvents -> buildString {
                append(count)
                if (marks.size != total) append(" · ${marks.size} drawn")
                if (marks.map { it.state }.distinct().size > 1) append(byState())
                val noMetres = total - positioned.size
                if (noMetres > 0) append(" · $noMetres without a court position")
                append(offText)
            }
            marks.isEmpty() -> "No landings to draw from $shots"
            else -> "${marks.size} landing${if (marks.size == 1) "" else "s"} of $shots" + byState() + offText
        }
        return CourtState.Badminton(singles, marks, note, offset, chip, detail)
    }

    fun referenceDetail(t: TrailTrack): String {
        val range = String.format(Locale.ROOT, "%.2f–%.2f s", t.rangeStartUs / 1e6, t.rangeEndUs / 1e6)
        return "$range · shuttle marked in ${t.observedFrames} of ${t.labelledFrames} labelled frames · ${t.framesInRange} frames in range"
    }

    /** A real time to grab the hero frame at: middle of the first rally, else of the reference, else of the clip. */
    fun thumbnailTimeMs(match: MatchRecord?, fixture: TrailTrack?, durationMs: Long?): Long = when {
        match != null && match.rallies.isNotEmpty() -> match.rallies.minBy { it.startMs }.let { (it.startMs + it.endMs) / 2 }
        fixture != null -> (fixture.rangeStartUs + fixture.rangeEndUs) / 2 / 1000
        durationMs != null && durationMs > 0 -> durationMs / 2
        else -> 0
    }

    private fun countFrames(sorted: LongArray, fromUs: Long, toUs: Long): Int {
        fun lb(key: Long): Int {
            var lo = 0
            var hi = sorted.size
            while (lo < hi) { val m = (lo + hi) ushr 1; if (sorted[m] < key) lo = m + 1 else hi = m }
            return lo
        }
        return lb(toUs + 1) - lb(fromUs)
    }
}

/** "1:02.4" style time used in rally rows. */
internal fun clock(ms: Long): String {
    val t = ms.coerceAtLeast(0)
    return String.format(Locale.ROOT, "%d:%04.1f", t / 60_000, (t % 60_000) / 1000.0)
}
