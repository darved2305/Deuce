package com.elevate.app.domain.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import java.util.Random

/**
 * Algorithm unit tests on signals built in code (impulses with decaying ringing, white noise,
 * sustained tones). They check the maths of [OnsetDetector]; they are NOT real footage and say
 * nothing about accuracy on real matches (that is measured on device, see SOUND_RALLIES.md).
 */
class OnsetDetectorTest {

    private val sr = 48_000

    /** White noise floor at [noiseAmp] plus, at each time in [hitsMs], a 4 kHz ring decaying with a 15 ms time constant. */
    private fun signal(lengthMs: Int, hitsMs: List<Int>, hitAmp: Double = 0.5, noiseAmp: Double = 0.003, seed: Long = 7): FloatArray {
        val n = sr * lengthMs / 1000
        val rnd = Random(seed)
        val x = FloatArray(n) { (rnd.nextGaussian() * noiseAmp).toFloat() }
        for (h in hitsMs) {
            val s0 = sr.toLong() * h / 1000
            for (k in 0 until sr / 10) {
                val i = (s0 + k).toInt()
                if (i >= n) break
                val t = k.toDouble() / sr
                x[i] += (hitAmp * exp(-t / 0.015) * sin(2 * PI * 4_000 * t + PI / 2)).toFloat()
            }
        }
        return x
    }

    /** Feeds [x] in decoder-like chunks of 1024 samples with presentation times from [startUs]. */
    private fun detect(x: FloatArray, startUs: Long = 0, rate: Int = sr): List<Onset> {
        val d = OnsetDetector(rate)
        var i = 0
        while (i < x.size) {
            val n = minOf(1024, x.size - i)
            d.accept(x.copyOfRange(i, i + n), n, startUs + i * 1_000_000L / rate)
            i += n
        }
        return d.finish()
    }

    private val hits = listOf(500, 1_300, 2_050, 3_005, 3_900, 5_123, 6_400)

    @Test fun impulsesAreFoundWithin10ms() {
        val onsets = detect(signal(8_000, hits))
        assertThat(onsets).hasSize(hits.size)
        onsets.zip(hits).forEach { (o, h) -> assertThat(o.tMs.toDouble()).isWithin(10.0).of(h.toDouble()) }
        onsets.forEach { assertThat(it.strength).isIn(com.google.common.collect.Range.closed(0f, 1f)) }
    }

    @Test fun timesFollowBufferPresentationTime() {
        val onsets = detect(signal(4_000, listOf(1_000, 2_000, 3_000)), startUs = 60_000_000)
        assertThat(onsets.map { it.tMs }).hasSize(3)
        onsets.zip(listOf(61_000L, 62_000L, 63_000L)).forEach { (o, t) -> assertThat(o.tMs.toDouble()).isWithin(10.0).of(t.toDouble()) }
    }

    @Test fun gainInvariant() {
        val loud = signal(8_000, hits)
        val quiet = FloatArray(loud.size) { loud[it] * 0.1f }
        val a = detect(loud)
        val b = detect(quiet)
        assertThat(b.map { it.tMs }).isEqualTo(a.map { it.tMs })
        a.zip(b).forEach { (x, y) -> assertThat(y.strength).isWithin(0.011f).of(x.strength) }
    }

    @Test fun noiseOnlyGivesNoOnsetsAndNoCandidates() {
        val onsets = detect(signal(10_000, emptyList(), noiseAmp = 0.05, seed = 3))
        assertThat(onsets).isEmpty()
        assertThat(RallyCandidates.group(onsets)).isEmpty()
    }

    @Test fun sustainedTonesGiveAtMostTheirStartsAndNoCandidates() {
        // Music-like: three overlapping tones (440 Hz + 3 kHz partial) with 300 ms raised-cosine fades.
        val n = sr * 10
        val rnd = Random(11)
        val x = FloatArray(n) { (rnd.nextGaussian() * 0.003).toFloat() }
        val starts = listOf(1_000, 4_000, 7_000)
        for (s in starts) {
            for (k in 0 until sr * 3) {
                val i = sr * s / 1000 + k
                if (i >= n) break
                val t = k.toDouble() / sr
                val fade = if (t < 0.3) 0.5 - 0.5 * kotlin.math.cos(PI * t / 0.3) else 1.0
                x[i] += (fade * (0.3 * sin(2 * PI * 440 * t) + 0.1 * sin(2 * PI * 3_000 * t))).toFloat()
            }
        }
        val onsets = detect(x)
        assertThat(onsets.size).isAtMost(starts.size)
        assertThat(RallyCandidates.group(onsets)).isEmpty()
    }

    @Test fun steadyToneGivesNoOnsets() {
        val x = FloatArray(sr * 5) { (0.4 * sin(2 * PI * 3_000 * it / sr)).toFloat() }
        assertThat(detect(x)).isEmpty()
    }

    @Test fun hitsCloserThanMinGapAreOne() {
        // 40 ms apart: one onset, not two.
        val onsets = detect(signal(3_000, listOf(1_000, 1_040)))
        assertThat(onsets).hasSize(1)
        assertThat(onsets[0].tMs.toDouble()).isWithin(50.0).of(1_020.0)
    }

    @Test fun otherSampleRatesWork() {
        val rate = 44_100
        val n = rate * 3
        val rnd = Random(5)
        val x = FloatArray(n) { (rnd.nextGaussian() * 0.003).toFloat() }
        for (h in listOf(700, 1_900)) for (k in 0 until rate / 10) {
            val t = k.toDouble() / rate
            x[rate * h / 1000 + k] += (0.5 * exp(-t / 0.015) * sin(2 * PI * 4_000 * t + PI / 2)).toFloat()
        }
        val onsets = detect(x, rate = rate)
        assertThat(onsets.map { it.tMs }).hasSize(2)
        assertThat(onsets[0].tMs.toDouble()).isWithin(10.0).of(700.0)
        assertThat(onsets[1].tMs.toDouble()).isWithin(10.0).of(1_900.0)
    }

    @Test fun emptyAndTinyStreams() {
        assertThat(OnsetDetector(sr).finish()).isEmpty()
        val d = OnsetDetector(sr)
        d.accept(FloatArray(100), 100, 0)
        assertThat(d.finish()).isEmpty()
        assertThat(d.frameCount).isEqualTo(0)
    }

    @Test fun digitalSilenceThenHitIsOneOnset() {
        val x = FloatArray(sr * 2)
        val s0 = sr
        for (k in 0 until sr / 10) {
            val t = k.toDouble() / sr
            x[s0 + k] = (0.5 * exp(-t / 0.015) * sin(2 * PI * 4_000 * t + PI / 2)).toFloat()
        }
        val onsets = detect(x)
        assertThat(onsets).hasSize(1)
        assertThat(onsets[0].tMs.toDouble()).isWithin(10.0).of(1_000.0)
    }
}
