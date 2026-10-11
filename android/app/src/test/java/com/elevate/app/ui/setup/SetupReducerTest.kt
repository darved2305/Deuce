package com.elevate.app.ui.setup

import com.elevate.app.domain.calibration.Corner
import com.elevate.app.domain.calibration.ImagePoint
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SetupReducerTest {

    private fun pt(x: Number, y: Number) = ImagePoint(x.toDouble(), y.toDouble())

    /** Same trapezoid as CourtGeometryTest: a court seen from behind the near baseline, 1920×1080. */
    private val court = listOf(pt(300, 1000), pt(1620, 1000), pt(1250, 300), pt(670, 300))

    private fun start(vararg extra: Sport) = SetupState(
        srcW = 1920.0, srcH = 1080.0, frameMs = 1200,
        analysable = SetupCatalog.ANALYSABLE + extra,
    )

    private fun SetupState.run(vararg a: SetupAction) = a.fold(this, ::reduce)
    private fun SetupState.tapAll(ps: List<ImagePoint>) = ps.fold(this) { s, p -> reduce(s, SetupAction.Tap(p)) }
    private fun atCourt() = start().also { assertThat(it.step).isEqualTo(Step.Court) }

    @Test fun tapsPlaceCornersInContractOrder() {
        var s = atCourt()
        assertThat(s.nextCorner).isEqualTo(Corner.NearLeft)
        assertThat(s.instruction()).isEqualTo("Tap the near-left corner")
        s = s.tapAll(court)
        assertThat(s.corners.keys.toList()).containsExactly(Corner.NearLeft, Corner.NearRight, Corner.FarRight, Corner.FarLeft).inOrder()
        assertThat(s.corners[Corner.FarRight]).isEqualTo(pt(1250, 300))
        assertThat(s.nextCorner).isNull()
        assertThat(s.instruction()).isEqualTo("Drag a corner to adjust")
        // A fifth tap does not move anything: corners are fine-tuned by dragging.
        assertThat(reduce(s, SetupAction.Tap(pt(10, 10)))).isEqualTo(s)
    }

    @Test fun undoRemovesTheLastTapAndResetIsUndoable() {
        val three = atCourt().tapAll(court.take(3))
        val two = reduce(three, SetupAction.Undo)
        assertThat(two.corners.keys).containsExactly(Corner.NearLeft, Corner.NearRight)
        assertThat(two.nextCorner).isEqualTo(Corner.FarRight)

        val cleared = reduce(three, SetupAction.Reset)
        assertThat(cleared.corners).isEmpty()
        assertThat(reduce(cleared, SetupAction.Undo).corners).isEqualTo(three.corners)

        val none = atCourt()
        assertThat(none.canUndo).isFalse()
        assertThat(reduce(none, SetupAction.Undo)).isEqualTo(none)
        assertThat(reduce(none, SetupAction.Reset)).isEqualTo(none)
    }

    @Test fun aDragMovesOneCornerAndUndoesAsOneStep() {
        val placed = atCourt().tapAll(court)
        val dragged = placed.run(
            SetupAction.DragStart(Corner.FarLeft),
            SetupAction.Drag(Corner.FarLeft, pt(660, 310)),
            SetupAction.Drag(Corner.FarLeft, pt(650, 320)),
        )
        assertThat(dragged.corners[Corner.FarLeft]).isEqualTo(pt(650, 320))
        assertThat(dragged.corners - Corner.FarLeft).isEqualTo(placed.corners - Corner.FarLeft)
        assertThat(reduce(dragged, SetupAction.Undo).corners).isEqualTo(placed.corners)
        // Dragged past the picture: pinned to the frame edge.
        val out = dragged.run(SetupAction.DragStart(Corner.NearLeft), SetupAction.Drag(Corner.NearLeft, pt(-40, 5000)))
        assertThat(out.corners[Corner.NearLeft]).isEqualTo(pt(0, 1080))
        // A corner that is not placed cannot be dragged.
        val partial = atCourt().tapAll(court.take(1))
        assertThat(partial.run(SetupAction.DragStart(Corner.FarLeft), SetupAction.Drag(Corner.FarLeft, pt(5, 5)))).isEqualTo(partial)
    }

    @Test fun nextNeedsAValidCourtAndSaysWhy() {
        var s = atCourt().tapAll(court.take(3))
        assertThat(s.canNext).isFalse()
        assertThat(s.blocker).isEqualTo("Place all four corners")
        assertThat(reduce(s, SetupAction.Next).step).isEqualTo(Step.Court)
        // Far corners swapped: the check's own Problem text is shown.
        s = atCourt().tapAll(listOf(court[0], court[1], court[3], court[2]))
        assertThat(s.blocker).isEqualTo("The corners cross over: check left and right")
        assertThat(reduce(s, SetupAction.Next).step).isEqualTo(Step.Court)
        s = atCourt().tapAll(court)
        assertThat(s.blocker).isNull()
        assertThat(reduce(s, SetupAction.Next).step).isEqualTo(Step.WhoIsMe)
    }

    @Test fun fullBadmintonPathProducesTheContractFields() {
        val s = atCourt().tapAll(court).run(SetupAction.Next)
        assertThat(s.canNext).isFalse()
        assertThat(s.result()).isNull()
        val done = s.run(SetupAction.Tap(pt(128.04, 150.06)), SetupAction.Next)
        // Two screens only: who-is-me is the last step and finishes the flow with a full result.
        assertThat(done.step).isEqualTo(Step.WhoIsMe)
        assertThat(done.steps).containsExactly(Step.Court, Step.WhoIsMe).inOrder()
        assertThat(done.canNext).isTrue()
        val r = done.result()!!
        assertThat(r.sport).isEqualTo(Sport.Badminton)
        assertThat(r.format).isEqualTo(Format.Singles)
        assertThat(r.mode).isEqualTo(Mode.Match)
        assertThat(r.ruleset).isEqualTo(Ruleset("bwf_3x21", "2025-04"))
        val cal = r.calibrationInput!!
        assertThat(cal.template).isEqualTo("badminton_singles")
        assertThat(cal.frameMs).isEqualTo(1200)
        assertThat(cal.pointsPx.nearLeft).containsExactly(300.0, 1000.0).inOrder()
        assertThat(cal.pointsPx.farLeft).containsExactly(670.0, 300.0).inOrder()
        assertThat(r.whoIsMe).isEqualTo(TimedPoint(listOf(128.0, 150.1), 1200))
        assertThat(r.freePlaySeed).isNull()
        // Next does not run past the last step; Back walks back.
        assertThat(reduce(done, SetupAction.Next)).isEqualTo(done)
        assertThat(reduce(done, SetupAction.Back).step).isEqualTo(Step.Court)
    }

    @Test fun templateFollowsFormatAndChangingItClearsTheCorners() {
        var s = start()
        assertThat(s.template).isEqualTo("badminton_singles")
        s = s.run(SetupAction.SelectFormat(Format.Doubles))
        assertThat(s.format).isEqualTo(Format.Doubles)
        assertThat(s.template).isEqualTo("badminton_doubles")
        // Place corners, switch format on the same screen: the doubles corners are not singles corners.
        s = s.tapAll(court).run(SetupAction.SelectFormat(Format.Singles))
        assertThat(s.corners).isEmpty()
        assertThat(s.history).isEmpty()
        assertThat(s.template).isEqualTo("badminton_singles")
        // Badminton has no "none" format; the mode can be practice.
        assertThat(s.run(SetupAction.SelectFormat(Format.None)).format).isEqualTo(Format.Singles)
        assertThat(s.run(SetupAction.SelectMode(Mode.Practice)).mode).isEqualTo(Mode.Practice)
        assertThat(s.run(SetupAction.SelectMode(Mode.FreePlay)).mode).isEqualTo(Mode.Match)
    }

    @Test fun onlyBadmintonIsOfferedInThisBuild() {
        val s = start()
        assertThat(Sport.entries.filter { s.isEnabled(it) }).containsExactly(Sport.Badminton)
        for (other in listOf(Sport.TableTennis, Sport.Tennis, Sport.FreePlay)) {
            assertThat(reduce(s, SetupAction.SelectSport(other))).isEqualTo(s)
        }
        // Squash, padel and pickleball are not listed at all (contracts §9).
        assertThat(Sport.entries.map { it.name }).containsExactly("Badminton", "TableTennis", "Tennis", "FreePlay")
        // A sport with no pinned ruleset version is never offered, even if a tracker existed.
        val tennis = start(Sport.Tennis)
        assertThat(tennis.isEnabled(Sport.Tennis)).isFalse()
        assertThat(SetupCatalog.RULESETS.keys).containsExactly(Sport.Badminton)
    }

    @Test fun freePlaySkipsTheCourtAndSeedsTheBall() {
        // Free play is not offered in this build (no pinned ruleset version); enabled here to test the path.
        val rulesets = SetupCatalog.RULESETS + (Sport.FreePlay to Ruleset("free_play", "unpinned-test"))
        var s = start(Sport.FreePlay).copy(rulesets = rulesets).run(SetupAction.SelectSport(Sport.FreePlay))
        assertThat(s.format).isEqualTo(Format.None)
        assertThat(s.mode).isEqualTo(Mode.FreePlay)
        assertThat(s.template).isNull()
        assertThat(s.steps).containsExactly(Step.Ball, Step.WhoIsMe).inOrder()
        assertThat(s.step).isEqualTo(Step.Ball)
        assertThat(s.run(SetupAction.SelectFormat(Format.Singles)).format).isEqualTo(Format.None)

        assertThat(s.canNext).isFalse()
        s = s.run(SetupAction.Tap(pt(812, 431)))
        assertThat(s.ball).isEqualTo(pt(812, 431))
        assertThat(s.run(SetupAction.Undo).ball).isNull()
        s = s.run(SetupAction.Next, SetupAction.Tap(pt(500, 600)))
        assertThat(s.step).isEqualTo(Step.WhoIsMe)
        val r = s.result()!!
        assertThat(r.calibrationInput).isNull()
        assertThat(r.freePlaySeed).isEqualTo(TimedPoint(listOf(812.0, 431.0), 1200))
        assertThat(r.format).isEqualTo(Format.None)
        assertThat(r.mode).isEqualTo(Mode.FreePlay)
    }

    @Test fun switchingSportClearsTaps() {
        val rulesets = SetupCatalog.RULESETS + (Sport.FreePlay to Ruleset("free_play", "unpinned-test"))
        val s = start(Sport.FreePlay).copy(rulesets = rulesets)
            .tapAll(court).run(SetupAction.SelectSport(Sport.FreePlay))
        assertThat(s.corners).isEmpty()
        val back = s.run(SetupAction.SelectSport(Sport.Badminton))
        assertThat(back.step).isEqualTo(Step.Court)
        assertThat(back.format).isEqualTo(Format.Singles)
        assertThat(back.mode).isEqualTo(Mode.Match)
    }

    @Test fun tapsOutsideThePictureArePinnedAndSportIsFixedAfterTheCourt() {
        val who = atCourt().tapAll(court).run(SetupAction.Next, SetupAction.Tap(pt(2000, -3)))
        assertThat(who.me).isEqualTo(pt(1920, 0))
        // On the second screen, sport, format and mode can no longer change (they are on the first).
        assertThat(who.run(SetupAction.SelectFormat(Format.Doubles))).isEqualTo(who)
        assertThat(who.run(SetupAction.SelectMode(Mode.Practice))).isEqualTo(who)
        // Who-is-me taps do not touch the corners.
        assertThat(who.corners).isEqualTo(atCourt().tapAll(court).corners)
    }
}
