package com.elevate.app.domain.tactics

import com.elevate.app.domain.calibration.CourtModel
import com.elevate.app.domain.calibration.Homography
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Tactics on real engine output, read from Person 1's committed eval run (no downloads):
 * h13g_singles.mp4 31–91 s, D-FINE person boxes (`h13g_31_91_persons`), engine hit candidates
 * (`h13g_31_91_hits`) and the court calibration from the real A55 taps (`s3df758_calibration`).
 * Feet are the box bottom-centre through H (the engine's own rule); near/far by court half.
 * Writes what it found to build/reports/tactics_real.txt.
 */
class TacticsRealDataTest {

    private val root = File("../../engine/eval/runs")
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun tacticsOnRealH13gRun() {
        val personsF = File(root, "h13g_31_91_persons/persons.json")
        val hitsF = File(root, "h13g_31_91_hits/hits.json")
        val calF = File(root, "s3df758_calibration/calibration.json")
        assumeTrue("engine eval run not present", personsF.exists() && hitsF.exists() && calF.exists())

        val cal = json.parseToJsonElement(calF.readText()).jsonObject
        val template = cal["template"]!!.jsonPrimitive.content
        val h = Homography.fromImageToCourt(cal["H"]!!.jsonArray.map { it.jsonPrimitive.double }, 1920, 1080)!!
        val (l, r) = CourtModel.courtBounds(template)!!

        // Player rows: per frame, the strongest box whose feet land on each half (within 1.5 m of the court).
        val rows = ArrayList<PlayerRow>()
        val windows = json.parseToJsonElement(personsF.readText()).jsonObject["windows"]!!.jsonArray
        for (w in windows) {
            val byT = w.jsonObject["rows"]!!.jsonArray.map { it.jsonArray }.groupBy { it[0].jsonPrimitive.long }
            for ((t, boxes) in byT) {
                val placed = boxes.mapNotNull { b ->
                    val x1 = b[1].jsonPrimitive.double; val y1 = b[2].jsonPrimitive.double
                    val x2 = b[3].jsonPrimitive.double; val y2 = b[4].jsonPrimitive.double
                    val p = h.projectToCourt((x1 + x2) / 2, y2)
                    if (p[2] <= 0) return@mapNotNull null
                    val fx = p[0] / p[2]; val fy = p[1] / p[2]
                    if (fx < l - 1.5 || fx > r + 1.5 || fy < -1.5 || fy > CourtModel.LENGTH_M + 1.5) return@mapNotNull null
                    Triple(PlayerRow(t, if (fy < CourtModel.NET_Y_M) "near" else "far", x1, y1, x2, y2, fx, fy), b[5].jsonPrimitive.double, fy)
                }
                placed.groupBy { it.first.trackId }.values.forEach { side -> rows += side.maxBy { it.second }.first }
            }
        }
        val hits = json.parseToJsonElement(hitsF.readText()).jsonObject["events"]!!.jsonArray.map { it.jsonObject }
            .filter { it["type"]!!.jsonPrimitive.content == "hit" }
            .map { e ->
                val xy = (e["image_xy"] as? JsonArray)?.map { it.jsonPrimitive.double }
                TacHit(e["event_id"]!!.jsonPrimitive.content, e["t_ms"]!!.jsonPrimitive.long, xy?.let { it[0] to it[1] })
            }

        val s = Tactics.compute(template, hits, landings = emptyList(), players = rows, youTrackId = "near")

        val report = StringBuilder()
        fun line(x: String) { report.append(x).append('\n') }
        line("player rows: ${rows.size} (near ${rows.count { it.trackId == "near" }}, far ${rows.count { it.trackId == "far" }})")
        line("hits: ${hits.size}, rallies: ${s.rallies}, shots with a hitter: ${s.shots.size}")
        line("shots by near: ${s.shots.count { it.hitter == "near" }}, by far: ${s.shots.count { it.hitter == "far" }}")
        line("shots with destination: ${s.shots.count { it.dest != null }} (intercept ${s.shots.count { it.destKind == DestKind.INTERCEPT }})")
        listOf("you (near)" to s.you, "opponent (far)" to s.opponent).forEach { (who, rd) ->
            line("$who readability: shots=${rd?.shots} repeated=${rd?.repeatedShots} usual=${rd?.usualTarget}")
            rd?.patterns?.forEach { p -> line("  from ${p.from} -> ${p.to}: ${p.count} of ${p.total} at ${p.hitTimesMs}") }
        }
        line("ghost moments: ${s.ghosts.size}")
        s.ghosts.sortedByDescending { it.offM }.take(5).forEach { g ->
            line(String.format(Locale.ROOT, "  t=%d you=(%.2f,%.2f) ghost=(%.2f,%.2f) off=%.2f m basis=%d", g.tMs, g.you.xM, g.you.yM, g.ghost.xM, g.ghost.yM, g.offM, g.basis))
        }
        line("autopsies: ${s.autopsies.size}; limits: ${s.limits}")
        File("build/reports").mkdirs()
        File("build/reports/tactics_real.txt").writeText(report.toString())

        // Sanity on real data: most hits get a hitter, shots get real destinations, ghosts are on court.
        assertThat(s.shots.size).isAtLeast((hits.size * 0.6).toInt())
        assertThat(s.shots.count { it.hitter == "near" }).isGreaterThan(0)
        assertThat(s.shots.count { it.hitter == "far" }).isGreaterThan(0)
        assertThat(s.shots.count { it.destKind == DestKind.INTERCEPT }).isAtLeast(hits.size / 2)
        // Singles hits alternate: the split between the two players stays close within each rally.
        val near = s.shots.count { it.hitter == "near" }
        val far = s.shots.count { it.hitter == "far" }
        assertThat(kotlin.math.abs(near - far)).isAtMost(s.rallies)
        s.ghosts.forEach { g ->
            assertThat(g.offM).isLessThan(CourtModel.LENGTH_M)
            assertThat(g.ghost.xM).isIn(com.google.common.collect.Range.closed(l - 0.5, r + 0.5))
        }
        // No landings in this run: no rally outcome is invented.
        assertThat(s.autopsies).isEmpty()
    }
}
