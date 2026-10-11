package com.elevate.app.domain.profile

import com.elevate.app.ui.profile.ProfileName
import com.elevate.app.ui.profile.ReadLabResults
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReadLabResultsCodecTest {
    @Test
    fun roundTrips() {
        val s = ReadLabStat(3, 1, 5)
        assertThat(ReadLabResults.decode(ReadLabResults.encode(s))).isEqualTo(s)
    }

    @Test
    fun rejectsCorruptValues() {
        assertThat(ReadLabResults.decode(null)).isNull()
        assertThat(ReadLabResults.decode("a,b,c")).isNull()
        assertThat(ReadLabResults.decode("1,2")).isNull()
        assertThat(ReadLabResults.decode("4,4,5")).isNull()
        assertThat(ReadLabResults.decode("-1,0,5")).isNull()
    }

    @Test
    fun avatarInitial() {
        assertThat(ProfileName.initial("  hetansh")).isEqualTo("H")
        assertThat(ProfileName.initial("")).isEqualTo("Y")
    }
}
