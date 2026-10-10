package com.elevate.app.data.fixture

import com.elevate.app.domain.model.TrailTrack
import com.elevate.app.domain.repository.VideoFingerprint

/**
 * Real hand-labelled annotations bundled with the app (assets/fixtures). Every document is parsed
 * and validated once; a document that fails validation is reported, never partly used.
 *
 * Phase 1 holds shuttle trails only: `fixture_match.json` and `fixture_events.jsonl` do not exist
 * yet (android/PHASE1_BLOCKERS.md), so this source offers no rallies or events.
 */
class FixtureSource(private val documents: () -> List<Pair<String, String>>) {

    sealed interface Result {
        data class Found(val track: TrailTrack, val document: String) : Result
        data object NotFound : Result
        data class Invalid(val document: String, val reason: String) : Result
    }

    private val loaded: List<Pair<String, Any>> by lazy {
        documents().map { (name, text) ->
            name to try {
                TrailLabelsParser.parse(text)
            } catch (e: TrailLabelsParser.InvalidFixture) {
                e
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
