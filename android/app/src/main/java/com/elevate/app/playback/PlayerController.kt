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
import androidx.media3.effect.TextureOverlay
import androidx.media3.exoplayer.ExoPlayer
import com.elevate.app.domain.analysis.LiveHit
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
    /** Engine hit candidates (dashed "HIT?" rings); drawn above the trail. */
    val hitOverlay = HitOverlay()

    /** Overlays drawn under the trail (e.g. court lines), set by [setUnderlays]. */
    @Volatile private var underlays: List<TextureOverlay> = emptyList()

    /** Effect list, bottom to top: underlays, trail, hits (the last overlay draws on top). */
    private fun overlayEffects() = listOf(OverlayEffect(underlays + listOf<TextureOverlay>(overlay, hitOverlay)))

    /**
     * presentationTimeUs of the frame on screen (-1 before the first). Updated for every frame while
     * playing and for the one frame a paused seek renders; NOT when a recreated or resized surface
     * re-renders a frame decoded ahead. That frame is wrong, and [reassertPausedFrame] seeks back.
     * Written on the playback thread.
     */
    @Volatile private var heldFrameUs = -1L
    @Volatile private var acceptNextFrame = true
    @Volatile private var playing = false

    private fun buildPlayer(withOverlay: Boolean): ExoPlayer = ExoPlayer.Builder(appContext).build().apply {
        setVideoEffects(if (withOverlay) overlayEffects() else emptyList())
        setVideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
            if (playing || acceptNextFrame) {
                heldFrameUs = presentationTimeUs
                if (!playing) acceptNextFrame = false
            }
        }
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
        override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
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
        hitOverlay.setHits(emptyList())
        heldFrameUs = -1
        acceptNextFrame = true
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
     * already decoded ahead of the paused one. Seek back to the frame that was on screen
     * ([heldFrameUs]), so pausing mid-frame never moves the picture.
     */
    private fun reassertPausedFrame() {
        if (player.playWhenReady || player.playbackState != Player.STATE_READY) return
        seekToHeldFrame()
    }
    fun replay() { acceptNextFrame = true; player.seekTo(0); player.play() }
    fun seekTo(ms: Long) { acceptNextFrame = true; player.seekTo(ms.coerceIn(0, maxOf(0, _state.value.durationMs))) }
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

    /**
     * Overlays drawn under the trail and hits (bottom first), kept across player rebuilds. Applied
     * at once to an overlay-capable (SDR) player; an HDR player keeps no effects at all.
     * `setVideoEffects` may run after `prepare()` (media3 1.11.1 ExoPlayer doc).
     */
    fun setUnderlays(overlays: List<TextureOverlay>) {
        underlays = overlays.toList()
        if (!playerIsForHdr) player.setVideoEffects(overlayEffects())
    }

    /**
     * Engine hit candidates for the open file (empty = none). [imageW] x [imageH] is the space of
     * their `image_xy` (source pixels after rotation); null = the decoded frame size.
     */
    fun setHits(hits: List<LiveHit>, imageW: Int? = null, imageH: Int? = null) {
        hitOverlay.setHits(hits, imageW, imageH)
        redrawIfPaused()
    }

    fun setHitsVisible(visible: Boolean) {
        hitOverlay.visible = visible
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
        // playWhenReady (not isPlaying): while buffering towards playback the next frame redraws anyway.
        if (player.playWhenReady) return
        seekToHeldFrame()
    }

    /** Exact seek that lands on the held frame again (see [redrawIfPaused] for the target rule). */
    private fun seekToHeldFrame() {
        val us = heldFrameUs
        if (us < 0 || player.currentMediaItem == null) return
        var targetMs = Math.floorDiv(us, 1000L)
        if (targetMs == player.currentPosition) targetMs = maxOf(0, targetMs - 1)
        acceptNextFrame = true
        player.seekTo(targetMs)
    }

    fun release() {
        player.removeListener(listener)
        player.release()
    }
}
