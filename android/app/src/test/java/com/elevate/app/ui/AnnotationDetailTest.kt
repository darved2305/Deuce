package com.elevate.app.ui

import com.elevate.app.TestVectors
import com.elevate.app.data.fixture.TrailLabelsParser
import com.elevate.app.ui.screens.annotationDetail
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AnnotationDetailTest {
    @Test fun `denominator is labelled frames, and unlabelled frames are counted separately`() {
        // 6 frames in range, 5 labelled (4 seen, 1 not visible), 1 never labelled.
        val t = TrailLabelsParser.parse(TestVectors.doc(TestVectors.FRAMES_50FPS))
        assertThat(annotationDetail(t)).isEqualTo("40.00–40.10 s · shuttle seen in 4 of 5 labelled frames · 1 of 6 not labelled")
    }

    @Test fun `fully labelled range has no unlabelled clause`() {
        val t = TrailLabelsParser.parse(TestVectors.doc(TestVectors.FRAMES_50FPS, count = 5))
        assertThat(annotationDetail(t)).isEqualTo("40.00–40.10 s · shuttle seen in 4 of 5 labelled frames")
    }
}
