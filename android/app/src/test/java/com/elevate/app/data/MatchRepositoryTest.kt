package com.elevate.app.data

import com.elevate.app.TestVectors
import com.elevate.app.TestVectors.doc
import com.elevate.app.data.api.ApiSource
import com.elevate.app.data.fixture.FixtureSource
import com.elevate.app.domain.model.AnnotationState
import com.elevate.app.domain.repository.VideoFingerprint
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MatchRepositoryTest {

    @Test fun `trail is returned only for the exact file it was labelled on`() = runTest {
        val r = DefaultMatchRepository(FixtureSource { listOf("a.json" to doc(TestVectors.FRAMES_50FPS)) }, ApiSource(""), StandardTestDispatcher(testScheduler))
        val hit = r.annotationsFor(VideoFingerprint(TestVectors.SHA_A, 1))
        assertThat(hit).isInstanceOf(AnnotationState.Available::class.java)
        val miss = r.annotationsFor(VideoFingerprint(TestVectors.SHA_B, 1))
        assertThat(miss).isEqualTo(AnnotationState.NoneForThisVideo)
    }

    @Test fun `no fixtures means no annotations, never a fallback trail`() = runTest {
        val r = DefaultMatchRepository(FixtureSource { emptyList() }, ApiSource(""), StandardTestDispatcher(testScheduler))
        assertThat(r.annotationsFor(VideoFingerprint(TestVectors.SHA_A, 1))).isEqualTo(AnnotationState.NoneForThisVideo)
    }

    @Test fun `an invalid fixture is reported, not partly used`() = runTest {
        val r = DefaultMatchRepository(FixtureSource { listOf("bad.json" to "{broken") }, ApiSource(""), StandardTestDispatcher(testScheduler))
        val s = r.annotationsFor(VideoFingerprint(TestVectors.SHA_A, 1))
        assertThat(s).isInstanceOf(AnnotationState.Failed::class.java)
        assertThat((s as AnnotationState.Failed).reason).contains("bad.json")
    }

    @Test fun `a valid fixture still wins when another document is broken`() = runTest {
        val r = DefaultMatchRepository(
            FixtureSource { listOf("bad.json" to "{broken", "good.json" to doc(TestVectors.FRAMES_50FPS)) },
            ApiSource(""), StandardTestDispatcher(testScheduler),
        )
        assertThat(r.annotationsFor(VideoFingerprint(TestVectors.SHA_A, 1))).isInstanceOf(AnnotationState.Available::class.java)
    }

    @Test fun `api source reports not connected instead of inventing a result`() {
        assertThat(ApiSource("").analysisFor(VideoFingerprint(TestVectors.SHA_A, 1))).isEqualTo(ApiSource.Result.NotConnected)
    }

    @Test fun `fixture documents are parsed once, not per lookup`() {
        var reads = 0
        val src = FixtureSource { reads++; listOf("a.json" to doc(TestVectors.FRAMES_50FPS)) }
        repeat(3) { src.trailFor(VideoFingerprint(TestVectors.SHA_A, 1)) }
        assertThat(reads).isEqualTo(1)
    }
}
