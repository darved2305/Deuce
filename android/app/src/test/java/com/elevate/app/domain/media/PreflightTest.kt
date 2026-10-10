package com.elevate.app.domain.media

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.File

class PreflightTest {

    private val repo = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "packages/contracts/schemas").isDirectory }

    /** h13g_singles: 1080p50 H.264, constant 20 ms (PHASE1_STATUS.md, ffprobe). */
    private val h13g = SourceFacts(
        bytes = 63_698_735, mime = "video/avc", codec = "avc1", width = 1920, height = 1080, rotation = 0,
        durationMs = 60_000, containerFps = 50f, pts = PtsStats.from(LongArray(200) { 40_000_000L + it * 20_000L }),
        hasAudio = true, audioRateHz = 44_100, isHdr = false, decodable = true, lastSampleUs = 59_980_000,
    )

    @Test fun constantFrameRateHasNoGapsAndIsNotVfr() {
        val p = h13g.pts!!
        assertThat(p.medianIntervalUs).isEqualTo(20_000)
        assertThat(p.minUs).isEqualTo(20_000)
        assertThat(p.maxUs).isEqualTo(20_000)
        assertThat(p.gaps).isEqualTo(0)
        assertThat(p.vfr).isFalse()
        assertThat(p.frames).isEqualTo(200)
    }

    @Test fun decodeOrderIsSortedBeforeMeasuring() {
        // B-frames: decode order 0, 60, 20, 40 ms. Presentation spacing is still 20 ms.
        val p = PtsStats.from(longArrayOf(0, 60_000, 20_000, 40_000))!!
        assertThat(p.minUs).isEqualTo(20_000)
        assertThat(p.gaps).isEqualTo(0)
    }

    @Test fun realEuroGamesTimingShowsDroppedFrames() {
        // Real PTS of the EuroGames Bern 2023 WebM (CC BY-SA 4.0), extracted by Person 1 with PyAV.
        val doc = Json.parseToJsonElement(File(repo, "engine/tests/data/eurogames_pts.json").readText()).jsonObject
        val ms = doc["pts"]!!.jsonArray.map { it.jsonPrimitive.long }
        val p = PtsStats.from(LongArray(ms.size) { ms[it] * 1000 })!!
        assertThat(p.medianIntervalUs).isIn(listOf(33_000L, 34_000L))
        assertThat(p.gaps).isGreaterThan(0) // 836 → 903 ms is one of them
        assertThat(p.maxUs).isAtLeast(67_000)
        assertThat(p.vfr).isFalse() // 33/34 ms alternation is rounding, not variable rate
    }

    @Test fun variableRateIsFlagged() {
        val times = LongArray(100)
        for (i in 1 until 100) times[i] = times[i - 1] + if (i % 3 == 0) 25_000 else 33_333
        assertThat(PtsStats.from(times)!!.vfr).isTrue()
    }

    @Test fun fewerThanTwoFramesHasNoStats() {
        assertThat(PtsStats.from(longArrayOf())).isNull()
        assertThat(PtsStats.from(longArrayOf(5))).isNull()
    }

    @Test fun fpsNominalPrefersContainerAndFallsBackToMeasured() {
        assertThat(h13g.fpsNominal).isEqualTo(50.0)
        assertThat(h13g.copy(containerFps = null).fpsNominal).isEqualTo(50.0)
        assertThat(h13g.copy(containerFps = null, pts = PtsStats.from(LongArray(10) { it * 33_333L })).fpsNominal).isEqualTo(30.0)
    }

    @Test fun cleanClipHasNoFindings() {
        assertThat(Preflight.assess(h13g)).isEmpty()
    }

    @Test fun rotationSwapsDisplaySize() {
        val portrait = h13g.copy(width = 3840, height = 2160, rotation = 90)
        assertThat(portrait.displayWidth).isEqualTo(2160)
        assertThat(portrait.displayHeight).isEqualTo(3840)
        val lowPortrait = h13g.copy(width = 848, height = 464, rotation = 270)
        assertThat(codes(lowPortrait)).contains("low_res")
    }

    @Test fun eachProblemProducesItsFinding() {
        assertThat(codes(h13g.copy(isHdr = true))).containsExactly("hdr")
        assertThat(codes(h13g.copy(hasAudio = false, audioRateHz = null))).containsExactly("no_audio")
        assertThat(codes(h13g.copy(bytes = Preflight.MAX_UPLOAD_BYTES + 1))).containsExactly("too_large")
        assertThat(codes(h13g.copy(decodable = false))).containsExactly("no_decoder")
        assertThat(codes(h13g.copy(pts = null))).containsExactly("no_frames")
        assertThat(codes(h13g.copy(lastSampleUs = 20_000_000))).containsExactly("truncated")
        assertThat(codes(h13g.copy(mime = null))).containsExactly("no_video")
    }

    @Test fun blockingFindingsAreMarked() {
        val f = Preflight.assess(h13g.copy(bytes = Preflight.MAX_UPLOAD_BYTES + 1, hasAudio = false))
        assertThat(f.filter { it.severity == Severity.Block }.map { it.code }).containsExactly("too_large")
        assertThat(f.filter { it.severity == Severity.Info }.map { it.code }).containsExactly("no_audio")
    }

    @Test fun exactlyAtTheCapIsAllowed() {
        assertThat(codes(h13g.copy(bytes = Preflight.MAX_UPLOAD_BYTES))).isEmpty()
    }

    @Test fun gapTextIsSingularForOneGap() {
        val one = h13g.copy(pts = h13g.pts!!.copy(gaps = 1))
        assertThat(Preflight.assess(one).single().text).isEqualTo("1 gap in frame timing (dropped frames)")
        assertThat(Preflight.assess(one.copy(pts = one.pts!!.copy(gaps = 3))).single().text).startsWith("3 gaps")
    }

    @Test fun millisecondTimestampsAt60FpsMeasureAbout60() {
        // WebM stores ms: a 60 fps clip alternates 16 and 17 ms (china_open_2025_final: median 17000).
        val t = LongArray(601)
        for (i in 1 until t.size) t[i] = t[i - 1] + if (i % 3 == 0) 16_000 else 17_000
        val p = PtsStats.from(t)!!
        assertThat(p.medianIntervalUs).isEqualTo(17_000)
        assertThat(p.fpsMeasured!!).isWithin(0.5).of(60.0)
        assertThat(h13g.copy(containerFps = null, pts = p).fpsNominal!!).isWithin(0.5).of(60.0)
    }

    @Test fun duplicateTimestampsDoNotMakeEveryIntervalAGap() {
        val p = PtsStats.from(longArrayOf(0, 0, 20_000, 40_000, 60_000))!!
        assertThat(p.medianIntervalUs).isEqualTo(20_000)
        assertThat(p.gaps).isEqualTo(0)
        assertThat(PtsStats.from(longArrayOf(5, 5, 5))).isNull()
    }

    @Test fun aFileWithoutDurationButWithFramesIsNotBlocked() {
        val f = h13g.copy(durationMeasured = true)
        assertThat(Preflight.assess(f).map { it.code }).containsExactly("no_duration")
        assertThat(Preflight.assess(f).none { it.severity == Severity.Block }).isTrue()
    }

    @Test fun noPhoneDecoderWarnsButDoesNotBlockCloudAnalysis() {
        assertThat(Preflight.assess(h13g.copy(decodable = false)).single().severity).isEqualTo(Severity.Warn)
    }

    @Test fun cappedWalkIsSaidAndNotCalledTruncated() {
        val f = h13g.copy(samplesCapped = true, lastSampleUs = 10_000_000)
        assertThat(codes(f)).containsExactly("timing_capped")
    }

    @Test fun justOverTheCapNeverReadsAsExactly300() {
        val text = Preflight.assess(h13g.copy(bytes = Preflight.MAX_UPLOAD_BYTES + 1)).single().text
        assertThat(text).startsWith("300.1 MiB")
    }

    @Test fun vfrFindingHasItsCode() {
        val t = LongArray(100)
        for (i in 1 until 100) t[i] = t[i - 1] + if (i % 3 == 0) 25_000 else 33_333
        assertThat(codes(h13g.copy(pts = PtsStats.from(t)))).containsExactly("vfr")
    }

    private fun codes(f: SourceFacts) = Preflight.assess(f).map { it.code }
}
