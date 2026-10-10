package com.elevate.app.ui.overview

import androidx.compose.ui.graphics.ImageBitmap
import com.elevate.app.data.api.MatchCapability
import com.elevate.app.data.api.MatchRecord
import com.elevate.app.domain.analysis.AnalysisUiState
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
}

data class CourtMark(val xM: Double, val yM: Double, val state: String, val shotId: String, val rallyId: String)

sealed interface CourtState {
    /** Not a badminton match: no court template drawn here yet. */
    data class NotApplicable(val reason: String) : CourtState
    /** Court drawn at true proportions; [marks] only from real landing values. */
    data class Badminton(val singles: Boolean, val marks: List<CourtMark>, val note: String) : CourtState
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
)

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
    ): MatchOverviewState {
        val source = when {
            match != null && recordedMatchDocument != null -> OverviewSource.RecordedMatch(recordedMatchDocument)
            analysis?.isLive == true -> OverviewSource.Live((analysis.source as com.elevate.app.domain.analysis.AnalysisSource.Live).analysisId)
            fixture != null -> OverviewSource.HandLabelledReference(fixture.first, fixture.second.provenance.labelledBy)
            else -> OverviewSource.Nothing
        }
        return MatchOverviewState(
            source = source,
            title = title,
            sourceLine = sourceLine(match),
            thumbnail = thumbnail,
            analysis = analysis,
            selectedPlayer = selectedPlayer(match),
            rallies = rallies(match, sourceFrameTimesUs, sourceDurationMs),
            court = court(match),
            capabilities = match?.capabilities?.let {
                listOf("Court" to it.court, "Ball" to it.ball, "Reviews" to it.reviews, "Score" to it.score)
            }.orEmpty(),
            matchProblem = matchProblem,
            referenceDetail = fixture?.second?.let(::referenceDetail),
            referenceRange = fixture?.second?.let { (it.rangeStartUs / 1000)..((it.rangeEndUs + 999) / 1000) },
        )
    }

    fun sourceLine(match: MatchRecord?): String {
        if (match == null) return "Sport and format not known until the match record arrives"
        val sport = match.sport.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }
        val format = match.format.takeIf { it != "none" }
        return listOfNotNull(sport, format, "${match.ruleset.id} ${match.ruleset.version}").joinToString(" · ")
    }

    fun selectedPlayer(match: MatchRecord?): String {
        val me = match?.players?.firstOrNull { it.label == "you" } ?: return "Player not selected yet"
        val how = when (me.identity) {
            "user_confirmed" -> "confirmed by you"
            "user_named" -> "named by you"
            else -> me.identity
        }
        return "You · ${me.trackIds.size} track${if (me.trackIds.size == 1) "" else "s"} · $how"
    }

    fun rallies(match: MatchRecord?, frameTimesUs: LongArray?, durationMs: Long?): RalliesState {
        if (match == null) return RalliesState.NotAvailable("Rallies not available yet: no match record from the server")
        if (match.rallies.isEmpty()) return RalliesState.NotAvailable("Rallies not available yet: the match record has none")
        val ordered = match.rallies.sortedBy { it.startMs }
        val players = match.players
        val rows = ordered.map { r ->
            val frames = frameTimesUs?.let { countFrames(it, r.startMs * 1000, r.endMs * 1000) }
            val winnerPlayer = players.indexOfFirst { it.playerId == r.winner }
            val who = players.getOrNull(winnerPlayer)?.label ?: r.winner
            val confirmed = r.winnerState == "user"
            RallyRow(
                rallyId = r.rallyId,
                startMs = r.startMs,
                endMs = r.endMs,
                shots = r.shotIds.size,
                ballCoverage = ballCoverageLabel(r.ballCoverage, frames),
                winner = if (confirmed) "Won by $who · confirmed" else "Winner candidate: $who · MODEL ESTIMATE",
                winnerConfirmed = confirmed,
                winnerSide = winnerPlayer,
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

    fun court(match: MatchRecord?): CourtState {
        if (match != null && match.sport != "badminton") {
            return CourtState.NotApplicable("Court view is drawn for badminton only so far")
        }
        val singles = match?.format != "doubles"
        if (match == null) return CourtState.Badminton(singles, emptyList(), "No landings yet")
        if (match.calibration.plane != "court") return CourtState.Badminton(singles, emptyList(), "No landings: calibration is not on the court plane")
        val all = match.shots
        val withLanding = all.filter { s ->
            val xy = s.chosen.landingXyM
            xy.size == 2 && xy[0].isFinite() && xy[1].isFinite()
        }
        val drawable = withLanding.filter { s ->
            val (x, y) = s.chosen.landingXyM
            x in -MARGIN_M..COURT_WIDTH_M + MARGIN_M && y in -MARGIN_M..COURT_LENGTH_M + MARGIN_M
        }
        val marks = drawable.map { CourtMark(it.chosen.landingXyM[0], it.chosen.landingXyM[1], it.chosen.landingState, it.shotId, it.rallyId) }
        val note = when {
            all.isEmpty() -> "No landings yet"
            marks.isEmpty() -> "No landings to draw from ${all.size} shot${if (all.size == 1) "" else "s"}"
            else -> buildString {
                append("${marks.size} landing${if (marks.size == 1) "" else "s"} of ${all.size} shot${if (all.size == 1) "" else "s"}")
                val byState = marks.groupingBy { it.state }.eachCount().toSortedMap()
                append(" (" + byState.entries.joinToString(", ") { "${it.value} ${it.key}" } + ")")
                val off = withLanding.size - drawable.size
                if (off > 0) append(" · $off further than ${MARGIN_M.toInt()} m outside the court, not drawn")
            }
        }
        return CourtState.Badminton(singles, marks, note)
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
