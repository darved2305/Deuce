package com.elevate.app.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.exoplayer.ExoPlayer
import com.elevate.app.domain.model.TrailTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Player facts the UI renders. Position is read on demand ([positionMs]), not stored here. */
data class PlaybackUiState(
    val hasMedia: Boolean = false,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val buffering: Boolean = false,
    val ended: Boolean = false,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    /** Decoder or source failure, verbatim from Media3 (error code name + message). */
    val error: String? = null,
)

/**
 * Owns the single ExoPlayer for the app process (created once, released in [release]).
 * The trail overlay is installed with `setVideoEffects` before the first `prepare()`, as Media3
 * requires; switching videos reuses the same player and pipeline.
 */
@UnstableApi
class PlayerController(context: Context) {

    private val appContext = context.applicationContext
    val overlay = TrailOverlay()

    /** presentationTimeUs of the last frame released to the screen (playback thread writes). */
    @Volatile private var lastRenderedUs = -1L

    private fun buildPlayer(withOverlay: Boolean): ExoPlayer = ExoPlayer.Builder(appContext).build().apply {
        setVideoEffects(if (withOverlay) listOf(OverlayEffect(listOf(overlay))) else emptyList())
        setVideoFrameMetadataListener { presentationTimeUs, _, _, _ -> lastRenderedUs = presentationTimeUs }
    }

    /** Colour class (HDR or not) the current player's effect pipeline was configured for. */
    private var playerIsForHdr = false

    /**
     * The current player. It is replaced when the next video changes between SDR and HDR: the
     * effect pipeline keeps its first output colour space, and an SDR stream after an HDR one fails
     * DefaultVideoFrameProcessor.checkColors (seen on the A55). Observe [playerFlow] in the UI.
     */
    var player: ExoPlayer = buildPlayer(withOverlay = true)
        private set
    private val _playerFlow = MutableStateFlow(player)
    val playerFlow: StateFlow<ExoPlayer> = _playerFlow.asStateFlow()

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync()
        override fun onPlayerError(error: PlaybackException) {
            _state.update { it.copy(error = "${error.errorCodeName}: ${error.message ?: "playback failed"}") }
        }
        override fun onVideoSizeChanged(videoSize: VideoSize) = sync()
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = sync()
        // A new or resized surface (resume, rotation) while paused: put the paused frame back.
        override fun onSurfaceSizeChanged(width: Int, height: Int) {
            if (width > 0 && height > 0) reassertPausedFrame()
        }
    }

    init {
        player.addListener(listener)
    }

    private fun sync() {
        val p = player
        _state.update {
            it.copy(
                hasMedia = p.currentMediaItem != null,
                isPlaying = p.isPlaying,
                playWhenReady = p.playWhenReady,
                buffering = p.playbackState == Player.STATE_BUFFERING,
                ended = p.playbackState == Player.STATE_ENDED,
                durationMs = p.duration.takeIf { d -> d != C.TIME_UNSET } ?: 0,
                speed = p.playbackParameters.speed,
                videoWidth = p.videoSize.width,
                videoHeight = p.videoSize.height,
            )
        }
    }

    /**
     * [withOverlay] = false (HDR) plays without the effect: on HDR input Media3 1.11.1's
     * OverlayShaderProgram treats every BitmapOverlay as Ultra HDR and requires a gainmap
     * (checkArgument at OverlayShaderProgram.java:129), so a canvas trail cannot be drawn there.
     * Switching between HDR and SDR rebuilds the player (see [player]).
     */
    fun open(uri: Uri, withOverlay: Boolean = true) {
        val forHdr = !withOverlay
        if (forHdr != playerIsForHdr) {
            player.removeListener(listener)
            player.release()
            player = buildPlayer(withOverlay).also { it.addListener(listener) }
            playerIsForHdr = forHdr
            _playerFlow.value = player
        }
        overlay.setTrack(null)
        lastRenderedUs = -1
        _state.value = PlaybackUiState()
        player.setMediaItem(MediaItem.fromUri(uri))
        player.setPlaybackSpeed(1f)
        player.prepare()
        player.playWhenReady = true
    }

    fun positionMs(): Long = player.currentPosition

    fun togglePlay() {
        when {
            player.playbackState == Player.STATE_ENDED -> { player.seekTo(0); player.play() }
            player.playWhenReady -> player.pause()
            else -> player.play()
        }
    }

    fun pause() = player.pause()

    /**
     * After the surface comes back or is resized while paused, the decoder may show a frame it had
     * already decoded ahead of the paused position. Re-seek so the held frame is the one at the
     * position again. 1 ms earlier, because ExoPlayer skips a seek to the current position and an
     * exact seek lands on the first frame at or after the target.
     */
    private fun reassertPausedFrame() {
        if (player.playWhenReady || player.currentMediaItem == null || player.playbackState != Player.STATE_READY) return
        player.seekTo(maxOf(0, player.currentPosition - 1))
    }
    fun replay() { player.seekTo(0); player.play() }
    fun seekTo(ms: Long) = player.seekTo(ms.coerceIn(0, maxOf(0, _state.value.durationMs)))
    fun setSpeed(speed: Float) = player.setPlaybackSpeed(speed)

    /** Debug aid: stamp every frame with its own presentation time (drawn by the overlay). */
    fun setPresentationTimeStamp(enabled: Boolean) {
        overlay.stampPresentationTime = enabled
        redrawIfPaused()
    }

    fun setTrack(track: TrailTrack?) {
        overlay.setTrack(track)
        redrawIfPaused()
    }

    fun setOverlayVisible(visible: Boolean) {
        overlay.visible = visible
        redrawIfPaused()
    }

    /**
     * While playing, every new frame redraws the overlay. While paused, re-render the held frame
     * by an exact seek to that frame's own time, which sends it through the effect pipeline again.
     *
     * Public API only: `setVideoEffects(REDRAW)` needs Media3's replayable cache, which 1.11.1
     * exposes only through the library-restricted PlaybackVideoGraphWrapper. A seek to
     * `currentPosition` is skipped by ExoPlayer when it equals the current position, so the target
     * is the held frame's PTS floored to ms (an exact seek lands on the first frame at or after it,
     * i.e. that frame whenever frames are more than 1 ms apart), stepped back 1 ms if it collides.
     */
    private fun redrawIfPaused() {
        val us = lastRenderedUs
        // playWhenReady (not isPlaying): while buffering towards playback the next frame redraws anyway.
        if (player.playWhenReady || us < 0 || player.currentMediaItem == null) return
        var targetMs = Math.floorDiv(us, 1000L)
        if (targetMs == player.currentPosition) targetMs = maxOf(0, targetMs - 1)
        player.seekTo(targetMs)
    }

    fun release() {
        player.removeListener(listener)
        player.release()
    }
}
