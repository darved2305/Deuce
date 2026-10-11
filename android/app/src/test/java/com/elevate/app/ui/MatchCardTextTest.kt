package com.elevate.app.ui

import com.elevate.app.data.history.HistoryStatus
import com.elevate.app.data.history.MatchHistoryEntry
import com.elevate.app.ui.screens.MatchCard
import com.elevate.app.ui.screens.matchCardFooter
import com.elevate.app.ui.screens.matchCardLine
import com.elevate.app.ui.screens.matchCardOf
import com.elevate.app.ui.screens.matchStatusLabel
import com.elevate.app.ui.screens.matchesEyebrow
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class MatchCardTextTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val at0530 = ZonedDateTime.of(2026, 10, 11, 5, 30, 0, 0, zone).toInstant().toEpochMilli()

    private val entry = MatchHistoryEntry(
        analysisId = "a_fc8800d5cd054f7090f94e4493ecfe4f", sessionId = "s_de11aa34d9ed4834b4ab307e690e5cc2",
        sha256 = "3669343e", title = "eurogames_bern_1", sourceUri = "content://x/1", sport = "badminton",
        durationMs = 25_603, width = 848, height = 464, createdAt = at0530 - 60_000, updatedAt = at0530,
        lastState = "partial", windowsDone = 5, coverageMs = 25_600, engineVersion = "0.1.0",
    )

    private fun card(e: MatchHistoryEntry = entry): MatchCard = matchCardOf(e)

    @Test fun `line shows sport, clock and real coverage of the real duration`() {
        assertThat(matchCardLine(card())).isEqualTo("Badminton · 0:25 · Fully analysed")
        assertThat(matchCardLine(card(entry.copy(coverageMs = 6_000, windowsDone = 1)))).isEqualTo("Badminton · 0:25 · 6.0 s of 25.6 s analysed")
    }

    @Test fun `line never invents a denominator`() {
        assertThat(matchCardLine(card(entry.copy(durationMs = null, sport = null)))).isEqualTo("25.6 s analysed")
        assertThat(matchCardLine(card(entry.copy(lastState = "failed", windowsDone = 0, coverageMs = 0)))).isEqualTo("Badminton · 0:25")
    }

    @Test fun `footer says when it was analysed and by which engine`() {
        assertThat(matchCardFooter(card(), zone, nowMs = at0530 + 3_600_000)).isEqualTo("Analysed 05:30")
        assertThat(matchCardFooter(card(), zone, nowMs = at0530 + 2 * 86_400_000L)).isEqualTo("Analysed Sun 11 Oct 05:30")
        val running = card(entry.copy(windowsDone = 1, coverageMs = 6_000, engineVersion = null))
        assertThat(running.status).isEqualTo(HistoryStatus.Analysing)
        assertThat(matchCardFooter(running, zone, nowMs = at0530)).isEqualTo("Started 05:29")
        assertThat(matchCardFooter(card(entry.copy(lastState = "failed")), zone, nowMs = at0530)).isEqualTo("Stopped 05:30")
    }

    @Test fun `chip labels and eyebrow`() {
        assertThat(matchStatusLabel(card().status)).isEqualTo("Ready")
        assertThat(matchStatusLabel(card(entry.copy(lastState = "completed")).status)).isEqualTo("Ready")
        assertThat(matchStatusLabel(HistoryStatus.Analysing)).isEqualTo("Analysing")
        assertThat(matchStatusLabel(HistoryStatus.Failed)).isEqualTo("Failed")
        assertThat(matchesEyebrow(1)).isEqualTo("1 match on this phone")
        assertThat(matchesEyebrow(3)).isEqualTo("3 matches on this phone")
    }
}
