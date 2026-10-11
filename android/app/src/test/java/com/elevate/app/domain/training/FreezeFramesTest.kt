package com.elevate.app.domain.training

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class FreezeFramesTest {
    /** Real presentation times (µs) of the hand-labelled frames of `h13g_singles` (fixtures/fixture_trail.json). */
    private val realFrames: LongArray by lazy {
        val repo = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "fixtures/fixture_trail.json").isFile }
        Regex("\"t_us\":\\s*(\\d+)").findAll(File(repo, "fixtures/fixture_trail.json").readText())
            .map { it.groupValues[1].toLong() }.toList().sorted().toLongArray()
    }

    @Test fun `ladder on real h13g frames never reaches the contact and only uses real frames`() {
        assertThat(realFrames.size).isGreaterThan(100)
        val real = realFrames.toSet()
        for (contactMs in 40_300L..43_900L step 37) for (sigma in listOf(0L, 9L, 20L, 45L)) {
            val ladder = FreezeFrames.ladder(realFrames, contactMs, sigma)
            assertThat(ladder).isNotEmpty()
            assertThat(ladder).isInStrictOrder()
            ladder.forEach {
                assertThat(it).isLessThan((contactMs - sigma) * 1000)
                assertThat(real).contains(it)
            }
        }
    }

    @Test fun `50 fps ladder picks the frame at or before each offset`() {
        // h13g frames are 20 ms apart; contact 41 000 ms ± 9 ms.
        assertThat(FreezeFrames.ladder(realFrames, 41_000, 9)).containsExactly(40_740_000L, 40_840_000L, 40_920_000L).inOrder()
    }

    @Test fun `frame exactly at contact minus sigma is not safe`() {
        val frames = longArrayOf(900_000, 980_000, 1_000_000)
        assertThat(FreezeFrames.frameAtOrBefore(frames, 2_000_000, FreezeFrames.safeLimitUs(1_000, 20))).isEqualTo(900_000)
    }

    @Test fun `low frame rate drops repeated steps so each reveal shows new footage`() {
        val frames = longArrayOf(0, 200_000, 400_000, 600_000, 800_000, 1_000_000)
        assertThat(FreezeFrames.ladder(frames, 1_000, 10)).containsExactly(600_000L, 800_000L).inOrder()
    }

    @Test fun `variable frame rate uses the real irregular times`() {
        val frames = longArrayOf(0, 33_000, 70_000, 101_000, 140_000, 166_000, 205_000, 233_000)
        assertThat(FreezeFrames.ladder(frames, 300, 5, listOf(250, 150, 70))).containsExactly(33_000L, 140_000L, 205_000L).inOrder()
    }

    @Test fun `no safe frame before the contact gives an empty ladder`() {
        assertThat(FreezeFrames.ladder(longArrayOf(500_000, 520_000), 500, 10)).isEmpty()
        assertThat(FreezeFrames.ladder(longArrayOf(), 500, 10)).isEmpty()
    }
}
