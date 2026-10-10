package com.elevate.app.data.api

import com.elevate.app.domain.repository.VideoFingerprint

/**
 * Boundary to Person 3's API (`{SUPABASE_URL}/functions/v1/api`, contracts §1). Phase 1 has no
 * deployed route the app can use without a session and analysis (contracts §2–3, Phase 2), so
 * this reports that it is not connected. It never fabricates a response.
 */
class ApiSource {
    sealed interface Result {
        /** No API configured or no analysis exists for this recording yet. */
        data object NotConnected : Result
    }

    @Suppress("UNUSED_PARAMETER")
    fun analysisFor(video: VideoFingerprint): Result = Result.NotConnected
}
