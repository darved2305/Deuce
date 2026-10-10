package com.elevate.app.data.fixture

import com.elevate.app.data.api.AnalysisEvent
import com.elevate.app.data.events.EventReplay
import com.elevate.app.domain.model.TrailTrack
import com.elevate.app.domain.repository.VideoFingerprint

/**
 * Real annotations bundled with the app (assets/fixtures). Every document is parsed and validated
 * once; a document that fails validation is reported, never partly used.
 *
 * `*.json` = `elevate.trail_labels.v1` trails. `*.jsonl` = recorded `analysis.event.v1` sequences
 * (`fixture_events.jsonl`, contracts §11) for [EventReplay]. Person 4's event and match fixtures do
 * not exist yet (android/PHASE1_BLOCKERS.md), so today [recordedEvents] is empty.
 */
class FixtureSource(private val documents: () -> List<Pair<String, String>>) {

    sealed interface Result {
        data class Found(val track: TrailTrack, val document: String) : Result
        data object NotFound : Result
        data class Invalid(val document: String, val reason: String) : Result
    }

    sealed interface EventsResult {
        data class Found(val document: String, val events: List<AnalysisEvent>) : EventsResult
        data class Invalid(val document: String, val reason: String) : EventsResult
    }

    private val all: List<Pair<String, String>> by lazy { documents() }

    private val loaded: List<Pair<String, Any>> by lazy {
        all.filter { it.first.endsWith(".json") }.map { (name, text) ->
            name to try {
                TrailLabelsParser.parse(text)
            } catch (e: TrailLabelsParser.InvalidFixture) {
                e
            }
        }
    }

    /** Every bundled recorded event sequence, parsed once; invalid files are reported, not skipped. */
    val recordedEvents: List<EventsResult> by lazy {
        all.filter { it.first.endsWith(".jsonl") }.map { (name, text) ->
            try {
                EventsResult.Found(name, EventReplay.parseJsonl(text))
            } catch (e: EventReplay.InvalidEvents) {
                EventsResult.Invalid(name, e.message ?: "invalid")
            }
        }
    }

    /** Trail labelled on exactly this file, matched by sha256 of its bytes. */
    fun trailFor(video: VideoFingerprint): Result {
        for ((name, value) in loaded) {
            if (value is TrailTrack && video.sha256 in value.clip.acceptedFileSha256) return Result.Found(value, name)
        }
        // An invalid document might have been meant for this video: say so rather than "none".
        loaded.firstOrNull { it.second is TrailLabelsParser.InvalidFixture }?.let { (name, e) ->
            return Result.Invalid(name, (e as Exception).message ?: "invalid")
        }
        return Result.NotFound
    }
}
