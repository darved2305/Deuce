package com.elevate.app.data.upload

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import kotlin.random.Random

class PartPlanTest {

    @get:Rule val tmp = TemporaryFolder()
    private val mib8 = 8L * 1024 * 1024

    @Test fun `exact multiple - every part full size`() {
        val p = PartPlan(3 * mib8, mib8)
        assertThat(p.count).isEqualTo(3)
        assertThat((1..3).map { p.length(it) }).containsExactly(mib8, mib8, mib8)
        assertThat((1..3).map { p.offset(it) }).containsExactly(0L, mib8, 2 * mib8).inOrder()
    }

    @Test fun `short final part`() {
        val p = PartPlan(7_717_311 + mib8, mib8)
        assertThat(p.count).isEqualTo(2)
        assertThat(p.length(2)).isEqualTo(7_717_311)
        // A clip smaller than one part is a single short part (the live clip: 7,717,311 bytes).
        assertThat(PartPlan(7_717_311, mib8).count).isEqualTo(1)
        assertThat(PartPlan(1, mib8).length(1)).isEqualTo(1)
    }

    @Test fun `0 bytes and over 300 MiB are refused, 300 MiB exactly is 38 parts`() {
        assertThrows(IllegalArgumentException::class.java) { PartPlan(0, mib8) }
        assertThrows(IllegalArgumentException::class.java) { PartPlan(300L * 1024 * 1024 + 1, mib8) }
        assertThat(PartPlan(300L * 1024 * 1024, mib8).count).isEqualTo(38)
        assertThrows(IllegalArgumentException::class.java) { PartPlan(10, mib8).offset(2) }
    }

    @Test fun `file source streams exactly the byte range, and a truncated file fails`() {
        val bytes = ByteArray(2500) { (it % 251).toByte() }
        val f = tmp.newFile().apply { writeBytes(bytes) }
        val src = FilePartSource(f)
        assertThat(src.open(1000, 1000).use { it.readBytes() }).isEqualTo(bytes.copyOfRange(1000, 2000))
        assertThat(src.open(2000, 500).use { it.readBytes() }).isEqualTo(bytes.copyOfRange(2000, 2500))
        assertThrows(IOException::class.java) { src.open(2000, 1000).use { it.readBytes() } }
    }

    @Test fun `backoff - exponential, equal jitter, capped`() {
        val zero = object : Random() { override fun nextBits(bitCount: Int) = 0 }
        val p = RetryPolicy(random = zero)
        assertThat((2..4).map { p.backoffMs(it) }).containsExactly(500L, 1000L, 2000L).inOrder()
        assertThat(RetryPolicy(random = zero).backoffMs(20)).isEqualTo(15_000L)
        val max = object : Random() {
            override fun nextBits(bitCount: Int) = 0
            override fun nextDouble() = 0.999999
        }
        assertThat(RetryPolicy(random = max).backoffMs(2)).isIn(990L..1000L)
        assertThat(RetryPolicy().maxAttempts).isEqualTo(4)
    }

    @Test fun `rate is measured over a sliding window and unknown until 1 s of samples`() {
        var now = 0L
        val m = RateMeter({ now })
        m.record(0)
        now = 500; m.record(1_000_000)
        assertThat(m.bytesPerSecond()).isNull()
        now = 2_000; m.record(4_000_000)
        assertThat(m.bytesPerSecond()).isEqualTo(2_000_000.0)
        now = 30_000; m.record(4_000_000)
        now = 31_000; m.record(5_000_000)
        // Old samples fell out of the 10 s window: only the last second's 1 MB counts.
        assertThat(m.bytesPerSecond()).isEqualTo(1_000_000.0)
    }
}
