package com.elevate.app.domain.readlab

import com.elevate.app.domain.court.ZoneHit
import com.elevate.app.domain.court.ZoneId
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReadLabTest {
    private val frames = LongArray(100) { it * 20_000L } // 50 fps grid from 0 to 1.98 s
    private val t = "badminton_singles"

    private fun shot(
        id: String = "sh_1",
        landing: List<Double>? = listOf(0.3, 12.8),
        landingState: String? = "observed",
        hitterY: Double? = 1.5,
        hitterState: String? = "observed",
    ) = ReadShot("m_1", id, contactMs = 1_500, contactSigmaMs = 12, hitterYM = hitterY, hitterState = hitterState, landingXyM = landing, landingState = landingState)

    private fun challenge(s: ReadShot = shot()) = (ReadLab.challenge(s, t, frames) as Eligibility.Ok).challenge

    @Test fun `trusted landing becomes the zone truth`() {
        assertThat(ReadLab.truth(shot(), t)).isEqualTo(LandingTruth.Landed(ZoneHit.In(ZoneId.parse("B1")!!), "observed"))
        assertThat(ReadLab.truth(shot(landingState = "user"), t)).isInstanceOf(LandingTruth.Landed::class.java)
    }

    @Test fun `missing or untrusted evidence is unscored with its reason`() {
        assertThat(ReadLab.truth(shot(landing = null), t)).isEqualTo(LandingTruth.Unavailable(UnscoredReason.NO_LANDING))
        assertThat(ReadLab.truth(shot(landingState = "candidate"), t)).isEqualTo(LandingTruth.Unavailable(UnscoredReason.UNTRUSTED_LANDING))
        assertThat(ReadLab.truth(shot(hitterState = "candidate"), t)).isEqualTo(LandingTruth.Unavailable(UnscoredReason.UNKNOWN_HITTER_SIDE))
        assertThat(ReadLab.truth(shot(), "tennis_singles")).isEqualTo(LandingTruth.Unavailable(UnscoredReason.NO_COURT))
    }

    @Test fun `exact adjacent wrong and out`() {
        val truth = ReadLab.truth(shot(), t)
        assertThat(ReadLab.evaluate(ZoneId.parse("B1")!!, truth)).isEqualTo(ReadResult.Exact)
        assertThat(ReadLab.evaluate(ZoneId.parse("M2")!!, truth)).isEqualTo(ReadResult.Adjacent)
        assertThat(ReadLab.evaluate(ZoneId.parse("F3")!!, truth)).isEqualTo(ReadResult.Wrong)
        assertThat(ReadLab.evaluate(ZoneId.parse("B1")!!, ReadLab.truth(shot(landing = listOf(6.0, 12.0)), t)))
            .isEqualTo(ReadResult.Unscored(UnscoredReason.LANDED_OUT))
        assertThat(ReadResult.Adjacent.wire).isEqualTo("adjacent")
    }

    @Test fun `nothing past the ladder frame is playable before lock`() {
        var a = ReadAttempt(challenge())
        while (true) {
            assertThat(a.playableUntilUs).isLessThan((1_500L - 12) * 1000)
            if (!a.canRevealMore) break
            a = a.revealMore()
        }
        assertThat(a.revealMore()).isEqualTo(a)
        a = a.choose(ZoneId.parse("B1")!!).lock()
        assertThat(a.playableUntilUs).isEqualTo(Long.MAX_VALUE)
    }

    @Test fun `lock needs a pick, happens once, and freezes the answer`() {
        val a = ReadAttempt(challenge())
        assertThat(a.lock()).isEqualTo(a)
        val locked = a.choose(ZoneId.parse("M2")!!).lock()
        assertThat(locked.result).isEqualTo(ReadResult.Adjacent)
        assertThat(locked.choose(ZoneId.parse("B1")!!)).isEqualTo(locked)
        assertThat(locked.lock()).isEqualTo(locked)
        assertThat(locked.revealMore()).isEqualTo(locked)
    }

    @Test fun `read margin is the unseen time before contact`() {
        val c = challenge()
        assertThat(c.ladderUs).containsExactly(1_240_000L, 1_340_000L, 1_420_000L).inOrder()
        val early = ReadAttempt(c).choose(ZoneId.parse("B1")!!).lock()
        val late = ReadAttempt(c).revealMore().revealMore().choose(ZoneId.parse("B1")!!).lock()
        assertThat(early.readMarginMs).isEqualTo(260)
        assertThat(late.readMarginMs).isEqualTo(80)
    }

    @Test fun `no safe frame means not offered`() {
        assertThat(ReadLab.challenge(shot().copy(contactMs = 10), t, frames)).isEqualTo(Eligibility.NoSafeFrame)
    }

    @Test fun `summary counts first attempts only and repeats separately`() {
        val c1 = challenge()
        val c2 = challenge(shot(id = "sh_2", landingState = "candidate"))
        val attempts = listOf(
            ReadAttempt(c1).choose(ZoneId.parse("B1")!!).lock(),
            ReadAttempt(c1).choose(ZoneId.parse("F3")!!).lock(), // repeat: not scored again
            ReadAttempt(c2).choose(ZoneId.parse("B1")!!).lock(),
            ReadAttempt(c1), // never locked
        )
        val s = ReadSummary.of(attempts)
        assertThat(s).isEqualTo(ReadSummary(completed = 2, exact = 1, adjacent = 0, wrong = 0, unscored = 1, repeats = 1, medianScoredMarginMs = 260))
        assertThat(s.scored).isEqualTo(1)
        assertThat(ReadSummary.of(emptyList()).medianScoredMarginMs).isNull()
    }
}
