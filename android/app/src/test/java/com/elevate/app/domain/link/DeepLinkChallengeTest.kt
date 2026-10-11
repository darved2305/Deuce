package com.elevate.app.domain.link

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeepLinkChallengeTest {
    @Test fun `a challenge link gives the challenge id`() {
        assertThat(DeepLink.parseChallenge("elevate://c/ch_0a1B-z_9")).isEqualTo("ch_0a1B-z_9")
        assertThat(DeepLink.parseChallenge("ELEVATE://c/ch_abc/")).isEqualTo("ch_abc")
        assertThat(DeepLink.parseChallenge("  elevate://c/ch_abc  ")).isEqualTo("ch_abc")
    }

    @Test fun `a match link is not a challenge and a challenge link is not a match`() {
        assertThat(DeepLink.parseChallenge("elevate://m/m_123")).isNull()
        assertThat(DeepLink.parse("elevate://c/ch_abc")).isNull()
        assertThat(DeepLink.parse("elevate://m/m_123")).isNotNull()
    }

    @Test fun `junk is null`() {
        assertThat(DeepLink.parseChallenge(null)).isNull()
        assertThat(DeepLink.parseChallenge("")).isNull()
        assertThat(DeepLink.parseChallenge("https://c/ch_abc")).isNull()
        assertThat(DeepLink.parseChallenge("elevate://c/")).isNull()
        assertThat(DeepLink.parseChallenge("elevate://c/m_abc")).isNull()
        assertThat(DeepLink.parseChallenge("elevate://c/ch_abc/extra")).isNull()
        assertThat(DeepLink.parseChallenge("elevate://c/ch_a b")).isNull()
        assertThat(DeepLink.parseChallenge("not a uri")).isNull()
    }
}
