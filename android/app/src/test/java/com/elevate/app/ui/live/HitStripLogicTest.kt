package com.elevate.app.ui.live

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HitStripLogicTest {

    @Test fun `caption carries the real count and says candidate`() {
        assertThat(HitStripLogic.caption(0)).isEqualTo("0 hit candidates · engine")
        assertThat(HitStripLogic.caption(1)).isEqualTo("1 hit candidate · engine")
        assertThat(HitStripLogic.caption(14)).isEqualTo("14 hit candidates · engine")
    }

    @Test fun `ticks are placed proportionally and clamped to the bar`() {
        assertThat(HitStripLogic.xOf(5_000, 10_000, 200f)).isEqualTo(100f)
        assertThat(HitStripLogic.xOf(20_000, 10_000, 200f)).isEqualTo(200f)
        assertThat(HitStripLogic.xOf(-1, 10_000, 200f)).isEqualTo(0f)
        assertThat(HitStripLogic.xOf(5_000, 0, 200f)).isEqualTo(0f)
    }

    @Test fun `a tap seeks to the nearest tick within the slop, else nothing`() {
        val hits = listOf(1_000L, 5_000L, 5_400L)
        // width 1000 px for 10 s: 1 px = 10 ms.
        assertThat(HitStripLogic.tickAt(505f, hits, 10_000, 1000f, 16f)).isEqualTo(5_000L)
        assertThat(HitStripLogic.tickAt(530f, hits, 10_000, 1000f, 16f)).isEqualTo(5_400L)
        assertThat(HitStripLogic.tickAt(300f, hits, 10_000, 1000f, 16f)).isNull()
        assertThat(HitStripLogic.tickAt(100f, emptyList(), 10_000, 1000f, 16f)).isNull()
        assertThat(HitStripLogic.tickAt(100f, hits, 0, 1000f, 16f)).isNull()
    }
}
