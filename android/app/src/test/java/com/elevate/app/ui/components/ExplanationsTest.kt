package com.elevate.app.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExplanationsTest {
    @Test fun `every explanation has a title and short plain text`() {
        assertThat(Explanations.all).isNotEmpty()
        Explanations.all.forEach { e ->
            assertThat(e.title).isNotEmpty()
            assertThat(e.text).isNotEmpty()
            assertThat(e.text.length).isLessThan(220)
            assertThat(e.title + e.text).doesNotContain("—")
            assertThat(e.title + e.text).doesNotContain("–")
        }
    }

    @Test fun `titles are unique`() {
        assertThat(Explanations.all.map { it.title }.toSet()).hasSize(Explanations.all.size)
    }
}
