package com.elevate.app.data.history

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Ids and numbers below are from the live EuroGames run in PHASE2_STATUS.md (25,603 ms clip). */
class MatchHistoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file() = File(tmp.root, "history/matches.json")

    private fun entry(id: String, createdAt: Long, state: String? = "queued") = MatchHistoryEntry(
        analysisId = id,
        sessionId = "s_$id",
        matchId = "m_$id",
        sha256 = "3669343e1837befefe9f77ae3b6a740767e4b6e59054d54b9cbbac1042d786ed",
        title = "eurogames_bern_1",
        sourceUri = "content://media/picker/0/com.android.providers.media.photopicker/media/1000219389",
        durationMs = 25_603,
        width = 848,
        height = 464,
        sport = "badminton",
        createdAt = createdAt,
        lastState = state,
    )

    @Test fun `empty when no file`() {
        assertThat(MatchHistory(file()).list()).isEmpty()
    }

    @Test fun `upsert persists and a new instance reads it back`() {
        val e = entry("a_fc8800d5cd054f7090f94e4493ecfe4f", 1_000)
        MatchHistory(file()).upsert(e)
        val again = MatchHistory(file())
        assertThat(again.list()).containsExactly(e)
        assertThat(again.get(e.analysisId)).isEqualTo(e)
        assertThat(File(file().parentFile, "matches.json.tmp").exists()).isFalse()
    }

    @Test fun `upsert by analysisId replaces and keeps the original createdAt`() {
        val h = MatchHistory(file())
        h.upsert(entry("a1", 1_000))
        h.upsert(entry("a1", 9_000, state = "partial"))
        assertThat(h.list()).hasSize(1)
        assertThat(h.list()[0].createdAt).isEqualTo(1_000)
        assertThat(h.list()[0].lastState).isEqualTo("partial")
    }

    @Test fun `newest first`() {
        val h = MatchHistory(file())
        h.upsert(entry("old", 1_000))
        h.upsert(entry("new", 3_000))
        h.upsert(entry("mid", 2_000))
        assertThat(MatchHistory(file()).list().map { it.analysisId }).containsExactly("new", "mid", "old").inOrder()
    }

    @Test fun `remove`() {
        val h = MatchHistory(file())
        h.upsert(entry("a1", 1_000))
        h.upsert(entry("a2", 2_000))
        assertThat(h.remove("a1")).isTrue()
        assertThat(h.remove("a1")).isFalse()
        assertThat(MatchHistory(file()).list().map { it.analysisId }).containsExactly("a2")
    }

    @Test fun `capped, oldest dropped`() {
        val h = MatchHistory(file(), cap = 50)
        for (i in 1..53) h.upsert(entry("a$i", i * 1_000L))
        val ids = MatchHistory(file()).list().map { it.analysisId }
        assertThat(ids).hasSize(50)
        assertThat(ids.first()).isEqualTo("a53")
        assertThat(ids).containsNoneOf("a1", "a2", "a3")
    }

    @Test fun `update changes only the target and reports no-op`() {
        val h = MatchHistory(file())
        h.upsert(entry("a1", 1_000))
        h.upsert(entry("a2", 2_000))
        val changed = h.update("a1") { it.copy(lastState = "completed", createdAt = 99) }
        assertThat(changed!!.lastState).isEqualTo("completed")
        assertThat(changed.createdAt).isEqualTo(1_000)
        assertThat(h.update("a1") { it }).isNull()
        assertThat(h.update("missing") { it.copy(lastState = "x") }).isNull()
        assertThat(MatchHistory(file()).get("a2")!!.lastState).isEqualTo("queued")
    }

    @Test fun `corrupt or foreign file reads as empty and is replaced on the next write`() {
        file().parentFile!!.mkdirs()
        file().writeText("{not json")
        assertThat(MatchHistory(file()).list()).isEmpty()
        file().writeText("""{"format":"something.else","entries":[]}""")
        assertThat(MatchHistory(file()).list()).isEmpty()
        val h = MatchHistory(file())
        h.upsert(entry("a1", 1_000))
        assertThat(MatchHistory(file()).list().map { it.analysisId }).containsExactly("a1")
    }

    @Test fun `status from the recorded state only`() {
        val e = entry("a1", 1_000, state = "partial")
        assertThat(e.status()).isEqualTo(HistoryStatus.Analysing)
        // 5 windows covering 0–25.6 s of the 25,603 ms clip, server still `partial` (no finalize yet).
        assertThat(e.copy(windowsDone = 5, coverageMs = 25_600).status()).isEqualTo(HistoryStatus.ReadyFromWindows)
        assertThat(e.copy(windowsDone = 4, coverageMs = 24_000).status()).isEqualTo(HistoryStatus.Analysing)
        assertThat(e.copy(windowsDone = 0, coverageMs = 25_603).status()).isEqualTo(HistoryStatus.Analysing)
        assertThat(e.copy(durationMs = null, windowsDone = 5, coverageMs = 25_600).status()).isEqualTo(HistoryStatus.Analysing)
        assertThat(e.copy(lastState = "completed").status()).isEqualTo(HistoryStatus.Ready)
        assertThat(e.copy(lastState = "failed", windowsDone = 5, coverageMs = 25_600).status()).isEqualTo(HistoryStatus.Failed)
    }
}
