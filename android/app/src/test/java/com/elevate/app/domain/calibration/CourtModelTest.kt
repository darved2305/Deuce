package com.elevate.app.domain.calibration

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CourtModelTest {

    private fun segment(template: String, name: String) =
        CourtModel.lines(template).single { it.name == name }

    private fun assertLine(seg: Segment, x0: Double, y0: Double, x1: Double, y1: Double) {
        assertThat(seg.start.first).isWithin(1e-9).of(x0)
        assertThat(seg.start.second).isWithin(1e-9).of(y0)
        assertThat(seg.end.first).isWithin(1e-9).of(x1)
        assertThat(seg.end.second).isWithin(1e-9).of(y1)
    }

    @Test fun bothTemplatesDrawThirteenNamedLines() {
        for (template in listOf("badminton_doubles", "badminton_singles")) {
            val lines = CourtModel.lines(template)
            assertThat(lines).hasSize(13)
            assertThat(lines.map { it.name }).containsNoDuplicates()
        }
    }

    @Test fun bwfDistancesHoldForDoubles() {
        val t = "badminton_doubles"
        assertLine(segment(t, "baseline_near"), 0.0, 0.0, 6.10, 0.0)
        assertLine(segment(t, "baseline_far"), 0.0, 13.40, 6.10, 13.40)
        assertLine(segment(t, "sideline_doubles_left"), 0.0, 0.0, 0.0, 13.40)
        assertLine(segment(t, "sideline_doubles_right"), 6.10, 0.0, 6.10, 13.40)
        assertLine(segment(t, "sideline_singles_left"), 0.46, 0.0, 0.46, 13.40)
        assertLine(segment(t, "sideline_singles_right"), 5.64, 0.0, 5.64, 13.40)
        assertLine(segment(t, "short_service_near"), 0.0, 4.72, 6.10, 4.72)
        assertLine(segment(t, "short_service_far"), 0.0, 8.68, 6.10, 8.68)
        assertLine(segment(t, "long_service_doubles_near"), 0.0, 0.76, 6.10, 0.76)
        assertLine(segment(t, "long_service_doubles_far"), 0.0, 12.64, 6.10, 12.64)
        // Centre line: from each short service line to its back boundary, at mid-width.
        assertLine(segment(t, "centre_near"), 3.05, 4.72, 3.05, 0.0)
        assertLine(segment(t, "centre_far"), 3.05, 8.68, 3.05, 13.40)
        // Net at mid-court, strung past the sidelines.
        val net = segment(t, "net")
        assertThat(net.start.second).isWithin(1e-9).of(6.70)
        assertThat(net.end.second).isWithin(1e-9).of(6.70)
        assertThat(net.start.first).isLessThan(0.0)
        assertThat(net.end.first).isGreaterThan(6.10)
    }

    @Test fun bwfDistancesHoldForSingles() {
        // Tapped quad is the singles rectangle: the painted doubles lines sit 0.46 m outside it.
        val t = "badminton_singles"
        assertLine(segment(t, "sideline_doubles_left"), -0.46, 0.0, -0.46, 13.40)
        assertLine(segment(t, "sideline_doubles_right"), 5.64, 0.0, 5.64, 13.40)
        assertLine(segment(t, "sideline_singles_left"), 0.0, 0.0, 0.0, 13.40)
        assertLine(segment(t, "sideline_singles_right"), 5.18, 0.0, 5.18, 13.40)
        assertLine(segment(t, "baseline_near"), -0.46, 0.0, 5.64, 0.0)
        assertLine(segment(t, "baseline_far"), -0.46, 13.40, 5.64, 13.40)
        assertLine(segment(t, "short_service_near"), -0.46, 4.72, 5.64, 4.72)
        assertLine(segment(t, "short_service_far"), -0.46, 8.68, 5.64, 8.68)
        assertLine(segment(t, "long_service_doubles_near"), -0.46, 0.76, 5.64, 0.76)
        assertLine(segment(t, "long_service_doubles_far"), -0.46, 12.64, 5.64, 12.64)
        assertLine(segment(t, "centre_near"), 2.59, 4.72, 2.59, 0.0)
        assertLine(segment(t, "centre_far"), 2.59, 8.68, 2.59, 13.40)
        val net = segment(t, "net")
        assertThat(net.start.second).isWithin(1e-9).of(6.70)
        assertThat(net.end.second).isWithin(1e-9).of(6.70)
    }

    @Test fun outerCornersAreTheTappedQuadInContractOrder() {
        assertThat(CourtModel.outerCorners("badminton_doubles")).containsExactly(
            Pair(0.0, 0.0), Pair(6.10, 0.0), Pair(6.10, 13.40), Pair(0.0, 13.40),
        ).inOrder()
        assertThat(CourtModel.outerCorners("badminton_singles")).containsExactly(
            Pair(0.0, 0.0), Pair(5.18, 0.0), Pair(5.18, 13.40), Pair(0.0, 13.40),
        ).inOrder()
    }

    @Test fun unknownTemplateHasNoCourt() {
        assertThat(CourtModel.lines("table_tennis")).isEmpty()
        assertThat(CourtModel.outerCorners("table_tennis")).isNull()
        assertThat(CourtModel.courtBounds("table_tennis")).isNull()
    }
}
