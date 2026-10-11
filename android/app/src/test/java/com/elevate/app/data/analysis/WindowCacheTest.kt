package com.elevate.app.data.analysis

import com.elevate.app.data.api.WindowQuality
import com.elevate.app.data.api.WindowResult
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The per-analysis window cache: round trip, and anything unreadable or foreign is ignored. */
class WindowCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private val id = "a_cache"

    private fun window(seq: Int, analysis: String = id) = WindowResult(
        "window_result.v1", analysis, seq, listOf(seq * 6_000_000L, (seq + 1) * 6_000_000L + 750_000), listOf(seq * 6_000_000L, (seq + 1) * 6_000_000L),
        listOf("tracknet_v3"),
        listOf(JsonArray(listOf(JsonPrimitive(seq * 6_000 + 16), JsonPrimitive(10.5), JsonPrimitive(20.25), JsonNull, JsonPrimitive("inferred")))),
        emptyList(), emptyList(), emptyList(), WindowQuality(0.5, 0.0, 0.0),
    )

    @Test fun `windows and match round trip by window_seq, under analysis windows id`() {
        val files = tmp.newFolder("files")
        val c = WindowCache.forAnalysis(files, id) { 42L }
        assertThat(c.dir).isEqualTo(File(files, "analysis/windows/$id"))
        assertThat(c.windows()).isEmpty()
        c.put(window(1), "art_1", "0.3.0")
        c.put(window(0), "art_0", null)
        val back = WindowCache.forAnalysis(files, id).windows()
        assertThat(back.map { it.windowSeq }).containsExactly(0, 1).inOrder()
        assertThat(back[1].result).isEqualTo(window(1))
        assertThat(back[1].artifactId).isEqualTo("art_1")
        assertThat(back[1].engineVersion).isEqualTo("0.3.0")
        assertThat(back[0].engineVersion).isNull()
        assertThat(back[0].cachedAtMs).isEqualTo(42L)
        assertThat(c.match()).isNull()
    }

    @Test fun `a corrupt, foreign or mismatched file is ignored and removed`() {
        val files = tmp.newFolder("files")
        val c = WindowCache.forAnalysis(files, id)
        c.put(window(0), "art_0", "0.3.0")
        c.dir.resolve("w_1.json").writeText("{not json")
        // Saved under the wrong window_seq name.
        c.dir.resolve("w_0.json").copyTo(c.dir.resolve("w_2.json"))
        // Another analysis' file copied into this folder.
        WindowCache.forAnalysis(files, "a_other").apply { put(window(3, "a_other"), "art_x", null) }.dir.resolve("w_3.json").copyTo(c.dir.resolve("w_3.json"))
        c.dir.resolve(WindowCache.MATCH_FILE).writeText("[]")
        assertThat(c.windows().map { it.windowSeq }).containsExactly(0)
        assertThat(c.dir.resolve("w_1.json").exists()).isFalse()
        assertThat(c.dir.resolve("w_2.json").exists()).isFalse()
        assertThat(c.dir.resolve("w_3.json").exists()).isFalse()
        assertThat(c.match()).isNull()
    }
}
