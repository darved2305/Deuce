package com.elevate.app.data

import com.elevate.app.TestVectors
import com.elevate.app.TestVectors.doc
import com.elevate.app.data.fixture.TrailLabelsParser
import com.elevate.app.data.fixture.TrailLabelsParser.InvalidFixture
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class TrailLabelsParserTest {

    @Test fun `parses a valid document and keeps provenance`() {
        val t = TrailLabelsParser.parse(doc(TestVectors.FRAMES_50FPS))
        assertThat(t.labelledFrames).isEqualTo(5)
        assertThat(t.framesInRange).isEqualTo(6)
        assertThat(t.provenance.isHandLabelled).isTrue()
        assertThat(t.clip.acceptedFileSha256).containsExactly(TestVectors.SHA_A)
        assertThat(t.x[3].isNaN()).isTrue()
    }

    @Test fun `sorts out-of-order frames`() {
        val t = TrailLabelsParser.parse(doc("""{"t_us": 40020000, "ball": [2, 2]}, {"t_us": 40000000, "ball": [1, 1]}"""))
        assertThat(t.tUs.toList()).containsExactly(40_000_000L, 40_020_000L).inOrder()
        assertThat(t.x[0]).isEqualTo(1f)
    }

    @Test fun `identical duplicate is collapsed, conflicting duplicate rejects the document`() {
        val same = TrailLabelsParser.parse(doc("""{"t_us": 40000000, "ball": [1, 1]}, {"t_us": 40000000, "ball": [1, 1]}"""))
        assertThat(same.labelledFrames).isEqualTo(1)
        assertThrows(InvalidFixture::class.java) {
            TrailLabelsParser.parse(doc("""{"t_us": 40000000, "ball": [1, 1]}, {"t_us": 40000000, "ball": [9, 9]}"""))
        }
    }

    @Test fun `empty or broken JSON is rejected`() {
        assertThrows(InvalidFixture::class.java) { TrailLabelsParser.parse("") }
        assertThrows(InvalidFixture::class.java) { TrailLabelsParser.parse("{not json") }
        assertThrows(InvalidFixture::class.java) { TrailLabelsParser.parse("{}") }
    }

    @Test fun `wrong schema is rejected`() {
        assertThrows(InvalidFixture::class.java) { TrailLabelsParser.parse(doc(TestVectors.FRAMES_50FPS, schema = "window_result.v1")) }
    }

    @Test fun `coordinates outside the frame or not finite are rejected`() {
        assertThrows(InvalidFixture::class.java) { TrailLabelsParser.parse(doc("""{"t_us": 40000000, "ball": [1921, 10]}""")) }
        assertThrows(InvalidFixture::class.java) { TrailLabelsParser.parse(doc("""{"t_us": 40000000, "ball": [-1, 10]}""")) }
        assertThrows(InvalidFixture::class.java) { TrailLabelsParser.parse(doc("""{"t_us": 40000000, "ball": [10]}""")) }
    }

    @Test fun `frame outside the declared range is rejected`() {
        assertThrows(InvalidFixture::class.java) { TrailLabelsParser.parse(doc("""{"t_us": 39980000, "ball": [1, 1]}""")) }
    }

    @Test fun `a document that cannot be tied to a file is rejected`() {
        assertThrows(InvalidFixture::class.java) { TrailLabelsParser.parse(doc(TestVectors.FRAMES_50FPS, sha = "")) }
        assertThrows(InvalidFixture::class.java) { TrailLabelsParser.parse(doc(TestVectors.FRAMES_50FPS, sha = "\"abc\"")) }
    }

    @Test fun `more labels than frames in range is rejected`() {
        assertThrows(InvalidFixture::class.java) { TrailLabelsParser.parse(doc(TestVectors.FRAMES_50FPS, count = 4)) }
    }

    @Test fun `provenance must be a contract state, and hand labels must say so in both fields`() {
        val base = doc(TestVectors.FRAMES_50FPS)
        assertThrows(InvalidFixture::class.java) { TrailLabelsParser.parse(base.replace("\"state\": \"user\"", "\"state\": \"guess\"")) }
        assertThrows(InvalidFixture::class.java) { TrailLabelsParser.parse(base.replace("\"engine_version\": \"hand-labelled\"", "\"engine_version\": \"0.3.0\"")) }
    }
}
