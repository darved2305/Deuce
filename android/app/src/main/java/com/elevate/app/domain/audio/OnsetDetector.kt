package com.elevate.app.domain.audio

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One sound onset in source presentation time: the row `[t_ms, strength]` of contracts §5
 * `onsets`. A sharp rise in high-frequency energy, nothing more: it is not a racket contact,
 * not a shot and not a rally. [strength] is 0..1 (see [OnsetParams.fullScaleRiseDb]).
 */
data class Onset(val tMs: Long, val strength: Float)

/**
 * Parameters of [OnsetDetector]. Defaults are a documented starting point chosen from the
 * physics of the signal, not tuned on real matches (accuracy on real footage is UNVERIFIED).
 */
data class OnsetParams(
    /** Feature hop (and window): 10 ms frames. */
    val hopMs: Double = 10.0,
    /** High-pass cutoff. Racket/shuttle impacts are broadband clicks; voices, music and hall hum sit mostly below. */
    val highPassHz: Double = 2_000.0,
    /** The rise is measured against the mean energy of this many previous frames (30 ms). */
    val referenceFrames: Int = 3,
    /** Adaptive threshold = median + [madK] · MAD of the rise over ±[thresholdHalfWindowMs]. */
    val madK: Double = 6.0,
    val thresholdHalfWindowMs: Long = 1_500,
    /** Statistics are recomputed every this many frames (250 ms) to keep the median cheap. */
    val thresholdBlockFrames: Int = 25,
    /** A rise below this many dB is never an onset, however quiet the surroundings. */
    val minRiseDb: Double = 6.0,
    /** Two onsets closer than this are one onset (the stronger one is kept). */
    val minGapMs: Long = 80,
    /** A rise of this many dB (or more) has strength 1. */
    val fullScaleRiseDb: Double = 40.0,
    /** Mean-square floor (−100 dBFS) so digital silence does not produce infinite rises. */
    val energyFloor: Double = 1e-10,
)

/**
 * Streaming sound-onset detector for mono float PCM (pure Kotlin, no Android types).
 *
 * Method (deliberately simple and explainable):
 *  1. 2nd-order Butterworth high-pass at [OnsetParams.highPassHz] (RBJ biquad), so the short
 *     broadband "crack" of an impact dominates over speech, music and room hum.
 *  2. Mean-square energy per 10 ms frame, in dB.
 *  3. Rise = frame dB − dB of the mean of the previous [OnsetParams.referenceFrames] frames,
 *     clipped at 0. A log ratio is gain-invariant: multiplying the signal by any gain g adds the
 *     same 20·log10(g) to every frame and cancels in the difference (above the −100 dBFS floor).
 *  4. Adaptive threshold: median + k·MAD of the rise in a ±1.5 s window (robust to crowd noise
 *     and to recording level), never below [OnsetParams.minRiseDb].
 *  5. Peak picking: local maxima above threshold, at least [OnsetParams.minGapMs] apart.
 *  6. Onset time = the largest high-passed sample in the peak frame (sub-frame precision);
 *     times come from the buffer presentation times passed to [accept], so gaps in the
 *     stream stay gaps in time.
 *
 * Memory: one float and one long per 10 ms frame (≈ 0.6 MB for 8 minutes), never the PCM.
 */
class OnsetDetector(private val sampleRateHz: Int, private val params: OnsetParams = OnsetParams()) {

    init {
        require(sampleRateHz > 0) { "sample rate must be positive" }
    }

    private val hop = max(1, (sampleRateHz * params.hopMs / 1000.0).roundToInt())

    // Biquad high-pass coefficients (RBJ Audio EQ Cookbook, Q = 1/sqrt(2)).
    private val b0: Double; private val b1: Double; private val b2: Double
    private val a1: Double; private val a2: Double
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

    init {
        // Above Nyquist the filter cannot be built; fall back to just under Nyquist.
        val fc = min(params.highPassHz, sampleRateHz * 0.45)
        val w0 = 2 * Math.PI * fc / sampleRateHz
        val alpha = sin(w0) / (2 * (1 / sqrt(2.0)))
        val cw = cos(w0)
        val a0 = 1 + alpha
        b0 = (1 + cw) / 2 / a0
        b1 = -(1 + cw) / a0
        b2 = (1 + cw) / 2 / a0
        a1 = -2 * cw / a0
        a2 = (1 - alpha) / a0
    }

    // Current frame accumulators.
    private var frameFill = 0
    private var frameSumSq = 0.0
    private var framePeak = -1.0
    private var framePeakUs = 0L

    // Per-frame features (growable).
    private var energyDb = FloatArray(4096)
    private var peakUs = LongArray(4096)
    private var frames = 0

    /** Frames of [OnsetParams.hopMs] seen so far. */
    val frameCount: Int get() = frames

    /**
     * Feeds [count] mono samples whose first sample is presented at [ptsUs] (microseconds,
     * source presentation time). Chunks must arrive in presentation order.
     */
    fun accept(mono: FloatArray, count: Int, ptsUs: Long) {
        require(count in 0..mono.size) { "count out of range" }
        val usPerSample = 1_000_000.0 / sampleRateHz
        for (i in 0 until count) {
            val x = mono[i].toDouble()
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            frameSumSq += y * y
            val a = abs(y)
            if (a > framePeak) {
                framePeak = a
                framePeakUs = ptsUs + (i * usPerSample).roundToLong()
            }
            if (++frameFill == hop) closeFrame()
        }
    }

    private fun closeFrame() {
        if (frames == energyDb.size) {
            energyDb = energyDb.copyOf(frames * 2)
            peakUs = peakUs.copyOf(frames * 2)
        }
        val ms = frameSumSq / frameFill
        energyDb[frames] = (10 * log10(max(ms, params.energyFloor))).toFloat()
        peakUs[frames] = framePeakUs
        frames++
        frameFill = 0; frameSumSq = 0.0; framePeak = -1.0
    }

    /** Ends the stream (a trailing partial frame is dropped) and returns onsets in time order. */
    fun finish(): List<Onset> = detect(riseDb(energyDb, frames, params.referenceFrames), peakUs, params, hop, sampleRateHz)

    companion object {
        /** Rise of each frame over the mean energy of the previous [ref] frames, in dB, clipped at 0. */
        internal fun riseDb(energyDb: FloatArray, n: Int, ref: Int): FloatArray {
            val out = FloatArray(n)
            for (i in 1 until n) {
                var sum = 0.0
                var k = 0
                for (j in max(0, i - ref) until i) { sum += Math.pow(10.0, energyDb[j] / 10.0); k++ }
                val refDb = 10 * log10(sum / k)
                out[i] = max(0.0, energyDb[i] - refDb).toFloat()
            }
            return out
        }

        internal fun detect(rise: FloatArray, peakUs: LongArray, p: OnsetParams, hop: Int, sampleRateHz: Int): List<Onset> {
            val n = rise.size
            if (n < 3) return emptyList()
            val frameMs = hop * 1000.0 / sampleRateHz
            val half = max(1, (p.thresholdHalfWindowMs / frameMs).roundToInt())
            val block = max(1, p.thresholdBlockFrames)
            val thr = FloatArray(n)
            var b = 0
            while (b < n) {
                val centre = min(n - 1, b + block / 2)
                val lo = max(0, centre - half)
                val hi = min(n, centre + half + 1)
                val w = rise.copyOfRange(lo, hi)
                val med = median(w)
                for (i in w.indices) w[i] = abs(w[i] - med)
                val mad = median(w)
                val t = max(p.minRiseDb, med + p.madK * mad).toFloat()
                for (i in b until min(n, b + block)) thr[i] = t
                b += block
            }
            val picked = ArrayList<Onset>()
            var lastUs = Long.MIN_VALUE
            var lastRise = 0f
            for (i in 1 until n - 1) {
                val r = rise[i]
                if (r <= thr[i] || r < rise[i - 1] || r <= rise[i + 1]) continue
                val us = peakUs[i]
                val strength = (r / p.fullScaleRiseDb).coerceIn(0.0, 1.0).toFloat()
                val onset = Onset(Math.round(us / 1000.0), (strength * 100).roundToInt() / 100f)
                if (lastUs != Long.MIN_VALUE && us - lastUs < p.minGapMs * 1000) {
                    if (r > lastRise) { picked[picked.size - 1] = onset; lastUs = us; lastRise = r }
                    continue
                }
                picked += onset
                lastUs = us; lastRise = r
            }
            return picked
        }

        private fun median(a: FloatArray): Float {
            if (a.isEmpty()) return 0f
            val s = a.copyOf()
            s.sort()
            val m = s.size / 2
            return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2f
        }
    }
}
