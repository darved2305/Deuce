package com.elevate.app.domain.repository

import com.elevate.app.domain.model.AnnotationState

/** Fingerprint of a picked recording, measured from its bytes (never from its file name). */
data class VideoFingerprint(val sha256: String, val bytes: Long)

/**
 * The one place screens ask for match data. Phase 1 answers from real hand-labelled fixtures;
 * Phase 2 adds live analysis results through [com.elevate.app.data.api.ApiSource].
 */
interface MatchRepository {
    /** Annotations recorded for exactly this recording, or why there are none. */
    suspend fun annotationsFor(video: VideoFingerprint): AnnotationState

    /** Live state of the backend, from a real request (never assumed). */
    suspend fun backendStatus(): BackendStatus
}

sealed interface BackendStatus {
    data object NotConfigured : BackendStatus
    data class Online(val latencyMs: Long) : BackendStatus
    data class Offline(val reason: String) : BackendStatus
}
