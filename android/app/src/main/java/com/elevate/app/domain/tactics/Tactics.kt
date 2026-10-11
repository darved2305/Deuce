package com.elevate.app.domain.tactics

import com.elevate.app.domain.calibration.CourtModel
import com.elevate.app.domain.court.CourtSide
import com.elevate.app.domain.court.ZoneGrid
import com.elevate.app.domain.court.ZoneHit
import com.elevate.app.domain.court.ZoneId
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** A hit candidate as the engine gives it (`type: "hit"`), from match.v1 or a live window. */
data class TacHit(val id: String, val tMs: Long, val imageXy: Pair<Double, Double>?)

/** A landing on the court plane in the calibration template's metres. */
data class TacLanding(val id: String, val tMs: Long, val xM: Double, val yM: Double, val state: String)

/**
 * One window player row (contracts §5): `[t_ms, track_id, x1, y1, x2, y2, foot_x_m, foot_y_m, conf]`.
 * The box is in source pixels; feet are court metres or null when the engine withheld them.
 */
data class PlayerRow(
    val tMs: Long,
    val trackId: String,
    val x1: Double, val y1: Double, val x2: Double, val y2: Double,
    val footXm: Double?, val footYm: Double?,
)

data class Pos(val xM: Double, val yM: Double) {
    fun dist(o: Pos) = hypot(xM - o.xM, yM - o.yM)
}

enum class DestKind { INTERCEPT, LANDING }

/**
 * One shot: who hit it, from where, and where it went. [dest] is the receiver's feet at their next
 * contact (INTERCEPT) or the landing (LANDING); null when neither was observed.
 */
data class Shot(
    val hit: TacHit,
    val rally: Int,
    val hitter: String,
    val hitterSide: CourtSide,
    val hitterPos: Pos,
    /** The hitter's own position as a cell of their own half, from their own view (F = near the net). */
    val fromZone: ZoneId,
    val dest: Pos?,
    val destZone: ZoneId?,
    val destKind: DestKind?,
    /** The other player's feet at this contact (where they stood when the shot was struck). */
    val receiverPos: Pos?,
)

/** A repeated choice: from [from], [count] of [total] shots went to [to]. Clips are the hits' times. */
data class Pattern(val from: ZoneId, val to: ZoneId, val count: Int, val total: Int, val hitTimesMs: List<Long>)

data class Readability(
    val trackId: String,
    /** Shots with a known origin and destination cell. */
    val shots: Int,
    /** Shots that came from a cell the player hit from at least [Tactics.MIN_FROM] times. */
    val repeatedShots: Int,
    /** Of [repeatedShots], how many went to that cell's usual target. */
    val usualTarget: Int,
    val patterns: List<Pattern>,
)

/** At one opponent contact: where you stood vs the ghost base, and what the ghost was built from. */
data class GhostMoment(
    val tMs: Long,
    val rally: Int,
    val you: Pos,
    val ghost: Pos,
    val offM: Double,
    /** Opponent shots from the same cell the ghost averages (leave-one-out); 0 = whole-match fallback. */
    val basis: Int,
    val opponentZone: ZoneId,
)

enum class RallyOutcome { YOU_WON, YOU_LOST, UNKNOWN }

/** A rally you lost (estimate) and where it turned: the opponent contact you were furthest from base. */
data class Autopsy(
    val rally: Int,
    val startMs: Long,
    val endMs: Long,
    val turnMs: Long?,
    val offM: Double?,
    val shotsAfterTurn: Int,
    val lastLanding: Pos?,
    val reason: String,
)

data class TacticsState(
    val youTrackId: String?,
    val opponentTrackId: String?,
    val shots: List<Shot>,
    val you: Readability?,
    val opponent: Readability?,
    val ghosts: List<GhostMoment>,
    val autopsies: List<Autopsy>,
    val rallies: Int,
    val ralliesLostEstimated: Int,
    /** Why something is missing, in plain words; null when everything could be computed. */
    val limits: List<String>,
)

/**
 * Tactics from real engine output only: hits, landings and player rows. Pure and cheap, so it is
 * recomputed whenever a new analysis window arrives (live) and again from the finished match.v1.
 * Every number keeps its denominator; outcomes of rallies are estimates from the last landing.
 */
object Tactics {
    const val RALLY_GAP_MS = 2_500L
    const val NEAR_MS = 160L
    const val LANDING_AFTER_MS = 3_000L
    const val MIN_FROM = 4
    const val MIN_GHOST_BASIS = 3
    const val LINE_MARGIN_M = 0.10
    const val TURN_OFF_M = 1.0

    fun compute(
        template: String,
        hits: List<TacHit>,
        landings: List<TacLanding>,
        players: List<PlayerRow>,
        youTrackId: String?,
    ): TacticsState {
        val limits = ArrayList<String>()
        val grid = ZoneGrid.forTemplate(template)
        val byTrack = players.groupBy { it.trackId }.mapValues { (_, r) -> r.sortedBy { it.tMs } }
        if (grid == null) limits += "No court zone grid for $template"
        if (byTrack.isEmpty()) limits += "No player positions in this analysis (re-analyse on the current engine)"
        val sortedHits = hits.sortedBy { it.tMs }
        val rallies = rallies(sortedHits)
        if (grid == null || byTrack.isEmpty()) {
            return TacticsState(youTrackId, null, emptyList(), null, null, emptyList(), emptyList(), rallies.size, 0, limits)
        }
        // Singles: the two tracks seen most often.
        val main = byTrack.entries.sortedByDescending { it.value.size }.take(2).map { it.key }
        val you = youTrackId?.takeIf { it in main } ?: run {
            if (youTrackId != null) limits += "Your track $youTrackId was not seen; showing the first player"
            main.firstOrNull()
        }
        val opp = main.firstOrNull { it != you }

        val hitters = sortedHits.associate { h -> h.id to hitterOf(h, main, byTrack) }
        val shots = ArrayList<Shot>()
        val sortedLandings = landings.sortedBy { it.tMs }
        rallies.forEachIndexed { r, rally ->
            rally.forEachIndexed { i, h ->
                val hitter = hitters[h.id] ?: return@forEachIndexed
                val hp = feetAt(byTrack[hitter], h.tMs) ?: return@forEachIndexed
                val side = CourtSide.of(hp.yM) ?: return@forEachIndexed
                val other = main.firstOrNull { it != hitter }
                val receiverNow = other?.let { feetAt(byTrack[it], h.tMs) }
                val next = rally.getOrNull(i + 1)
                var dest: Pos? = null
                var kind: DestKind? = null
                if (next != null && hitters[next.id] == other && other != null) {
                    feetAt(byTrack[other], next.tMs)?.let { dest = it; kind = DestKind.INTERCEPT }
                }
                if (dest == null) {
                    val until = minOf(next?.tMs ?: Long.MAX_VALUE, h.tMs + LANDING_AFTER_MS)
                    sortedLandings.firstOrNull { it.tMs > h.tMs && it.tMs <= until }?.let { dest = Pos(it.xM, it.yM); kind = DestKind.LANDING }
                }
                val dz = dest?.let { (grid.hit(it.xM, it.yM, side) as? ZoneHit.In)?.zone }
                shots += Shot(h, r, hitter, side, hp, ownCell(grid, hp, side), dest, dz, kind, receiverNow)
            }
        }
        val youRead = you?.let { readability(it, shots) }
        val oppRead = opp?.let { readability(it, shots) }
        val ghosts = if (you != null && opp != null) ghosts(shots, you, opp) else emptyList()
        val outcomes = rallies.indices.associateWith { r -> outcome(shots.filter { it.rally == r }, you, template) }
        val autopsies = outcomes.filterValues { it == RallyOutcome.YOU_LOST }.keys.sorted().map { r ->
            autopsy(r, rallies[r], shots.filter { it.rally == r }, ghosts.filter { it.rally == r })
        }
        if (sortedLandings.isEmpty()) limits += "No landings yet, so rally winners can't be estimated"
        return TacticsState(you, opp, shots, youRead, oppRead, ghosts, autopsies, rallies.size, autopsies.size, limits)
    }

    fun rallies(sorted: List<TacHit>): List<List<TacHit>> {
        val out = ArrayList<MutableList<TacHit>>()
        for (h in sorted) {
            val cur = out.lastOrNull()
            if (cur == null || h.tMs - cur.last().tMs > RALLY_GAP_MS) out += mutableListOf(h) else cur += h
        }
        return out.filter { it.size >= 2 }
    }

    /** The player whose box (grown upward for overhead contacts) is closest to the shuttle at contact. */
    fun hitterOf(h: TacHit, tracks: List<String>, byTrack: Map<String, List<PlayerRow>>): String? {
        val (x, y) = h.imageXy ?: return null
        var best: String? = null
        var bestD = Double.MAX_VALUE
        for (t in tracks) {
            val r = nearest(byTrack[t], h.tMs) ?: continue
            val bh = r.y2 - r.y1
            val top = r.y1 - 0.6 * bh
            val dx = max(max(r.x1 - x, 0.0), x - r.x2)
            val dy = max(max(top - y, 0.0), y - r.y2)
            val d = hypot(dx, dy) / max(bh, 1.0)
            if (d < bestD) { bestD = d; best = t }
        }
        return best.takeIf { bestD <= 1.0 }
    }

    fun feetAt(rows: List<PlayerRow>?, tMs: Long): Pos? {
        val r = nearest(rows?.filter { it.footXm != null && it.footYm != null }, tMs) ?: return null
        return Pos(r.footXm!!, r.footYm!!)
    }

    private fun nearest(rows: List<PlayerRow>?, tMs: Long): PlayerRow? =
        rows?.minByOrNull { abs(it.tMs - tMs) }?.takeIf { abs(it.tMs - tMs) <= NEAR_MS }

    /** The cell the hitter stands in on their own half, from their own view (row F nearest the net). */
    fun ownCell(grid: ZoneGrid, p: Pos, side: CourtSide): ZoneId {
        // cellOfDefender gives the cell on [side]'s half seen from the opponent; mirror the column.
        val z = grid.cellOfDefender(p.xM, p.yM, side.other) ?: grid.cellOfDefender(p.xM, if (side == CourtSide.NEAR) 0.1 else CourtModel.LENGTH_M - 0.1, side.other)!!
        return ZoneId(z.row, 4 - z.col)
    }

    fun readability(track: String, shots: List<Shot>): Readability {
        val known = shots.filter { it.hitter == track && it.destZone != null }
        val patterns = known.groupBy { it.fromZone }.mapNotNull { (from, s) ->
            if (s.size < MIN_FROM) return@mapNotNull null
            val (to, hits) = s.groupBy { it.destZone!! }.maxBy { it.value.size }
            Pattern(from, to, hits.size, s.size, hits.map { it.hit.tMs })
        }.sortedWith(compareByDescending<Pattern> { it.count.toDouble() / it.total }.thenByDescending { it.total })
        return Readability(track, known.size, patterns.sumOf { it.total }, patterns.sumOf { it.count }, patterns)
    }

    /**
     * Ghost base at each opponent contact: the geometric median of where this opponent sent the
     * shuttle from the same cell in this match (leave-one-out), else from all their shots.
     */
    fun ghosts(shots: List<Shot>, you: String, opp: String): List<GhostMoment> {
        val oppShots = shots.filter { it.hitter == opp && it.dest != null }
        return shots.filter { it.hitter == opp && it.receiverPos != null }.mapNotNull { s ->
            val youPos = s.receiverPos!!
            val same = oppShots.filter { it.fromZone == s.fromZone && it.hit.id != s.hit.id }.map { it.dest!! }
            val pts = if (same.size >= MIN_GHOST_BASIS) same else oppShots.filter { it.hit.id != s.hit.id }.map { it.dest!! }
            if (pts.size < MIN_GHOST_BASIS) return@mapNotNull null
            val g = clampToHalf(geometricMedian(pts), CourtSide.of(youPos.yM) ?: return@mapNotNull null)
            GhostMoment(s.hit.tMs, s.rally, youPos, g, youPos.dist(g), if (same.size >= MIN_GHOST_BASIS) same.size else 0, s.fromZone)
        }
    }

    fun geometricMedian(pts: List<Pos>): Pos {
        var x = pts.sumOf { it.xM } / pts.size
        var y = pts.sumOf { it.yM } / pts.size
        repeat(50) {
            var wx = 0.0; var wy = 0.0; var w = 0.0
            for (p in pts) {
                val d = max(hypot(p.xM - x, p.yM - y), 1e-6)
                wx += p.xM / d; wy += p.yM / d; w += 1 / d
            }
            x = wx / w; y = wy / w
        }
        return Pos(x, y)
    }

    private fun clampToHalf(p: Pos, side: CourtSide): Pos {
        val net = CourtModel.NET_Y_M
        val y = if (side == CourtSide.NEAR) p.yM.coerceIn(0.0, net - 0.3) else p.yM.coerceIn(net + 0.3, CourtModel.LENGTH_M)
        return Pos(p.xM, y)
    }

    /** Winner estimate from the last shot's landing: in → the hitter won, out → the hitter lost. */
    fun outcome(rallyShots: List<Shot>, you: String?, template: String): RallyOutcome {
        val last = rallyShots.lastOrNull() ?: return RallyOutcome.UNKNOWN
        if (last.destKind != DestKind.LANDING || last.dest == null || you == null) return RallyOutcome.UNKNOWN
        val (l, r) = CourtModel.courtBounds(template) ?: return RallyOutcome.UNKNOWN
        val p = last.dest
        val edge = minOf(abs(p.xM - l), abs(p.xM - r), abs(p.yM), abs(p.yM - CourtModel.LENGTH_M))
        if (edge < LINE_MARGIN_M) return RallyOutcome.UNKNOWN
        val inside = p.xM > l && p.xM < r && p.yM > 0 && p.yM < CourtModel.LENGTH_M && CourtSide.of(p.yM) == last.hitterSide.other
        val hitterWon = inside
        val youHit = last.hitter == you
        return if (hitterWon == youHit) RallyOutcome.YOU_WON else RallyOutcome.YOU_LOST
    }

    fun autopsy(r: Int, rally: List<TacHit>, rallyShots: List<Shot>, rallyGhosts: List<GhostMoment>): Autopsy {
        val start = rally.first().tMs
        val end = rally.last().tMs
        val last = rallyShots.lastOrNull()?.dest
        val worst = rallyGhosts.takeLast(4).maxByOrNull { it.offM }
        if (worst == null || worst.offM < TURN_OFF_M) {
            return Autopsy(r, start, end, null, worst?.offM, 0, last, "Lost on the final exchange: you were near base each time")
        }
        val after = rally.count { it.tMs > worst.tMs }
        return Autopsy(r, start, end, worst.tMs, worst.offM, after, last,
            String.format(java.util.Locale.ROOT, "The point turned here: you were %.1f m from base when they hit", worst.offM))
    }
}
