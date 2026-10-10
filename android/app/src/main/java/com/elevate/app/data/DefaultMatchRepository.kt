package com.elevate.app.data

import com.elevate.app.data.api.ApiSource
import com.elevate.app.data.fixture.FixtureSource
import com.elevate.app.domain.model.AnnotationState
import com.elevate.app.domain.repository.BackendStatus
import com.elevate.app.domain.repository.MatchRepository
import com.elevate.app.domain.repository.VideoFingerprint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Live results win once they exist (Phase 2); until then only real hand-labelled fixtures. */
class DefaultMatchRepository(
    private val fixtures: FixtureSource,
    private val api: ApiSource,
    private val io: CoroutineDispatcher,
) : MatchRepository {

    override suspend fun annotationsFor(video: VideoFingerprint): AnnotationState = withContext(io) {
        when (api.analysisFor(video)) {
            ApiSource.Result.NotConnected -> Unit
        }
        when (val r = fixtures.trailFor(video)) {
            is FixtureSource.Result.Found -> AnnotationState.Available(r.track)
            is FixtureSource.Result.Invalid -> AnnotationState.Failed("${r.document}: ${r.reason}")
            FixtureSource.Result.NotFound -> AnnotationState.NoneForThisVideo
        }
    }

    override suspend fun backendStatus(): BackendStatus = withContext(io) {
        when (val h = api.health()) {
            ApiSource.Health.NotConfigured -> BackendStatus.NotConfigured
            is ApiSource.Health.Online -> BackendStatus.Online(h.latencyMs)
            is ApiSource.Health.ApiError -> BackendStatus.Offline("HTTP ${h.httpStatus} ${h.code}: ${h.message}")
            is ApiSource.Health.Unreachable -> BackendStatus.Offline(h.reason)
        }
    }
}
