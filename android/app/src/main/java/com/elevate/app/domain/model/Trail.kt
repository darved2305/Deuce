package com.elevate.app.domain.model

/**
 * Who produced a set of positions. Contract §0: every derived value carries a provenance state;
 * `user` = placed by a person. Hand labels and engine output must never be confused on screen.
 */
data class Provenance(
    val state: String,
    val engineVersion: String,
    val labelledBy: String?,
    val tool: String?,
) {
    val isHandLabelled: Boolean get() = state == "user" && engineVersion == "hand-labelled"
}

/** The recording a set of annotations belongs to. Positions are meaningless on any other file. */
data class ClipIdentity(
    val clipId: String,
    /** sha256 of every file known to carry exactly these frames (e.g. a stream-copy remux). */
    val acceptedFileSha256: Set<String>,
    /** Image space of the labels: source pixels after rotation (contracts §0). */
    val width: Int,
    val height: Int,
)

/**
 * Shuttle positions for a contiguous range of real frames, sorted by source time.
 *
 * Each entry is one labelled frame. `x`/`y` are NaN when the labeller looked at that frame and
 * the shuttle was not visible. Frames inside [rangeUs] that are absent were not labelled. Neither
 * case is ever filled in: a gap is drawn as a gap.
 */
class TrailTrack(
    val clip: ClipIdentity,
    val provenance: Provenance,
    /** First and last frame time of the labelled range, µs of source presentation time. */
    val rangeStartUs: Long,
    val rangeEndUs: Long,
    /** Number of real frames the range contains (labelled or not). */
    val framesInRange: Int,
    val tUs: LongArray,
    val x: FloatArray,
    val y: FloatArray,
) {
    init {
        require(tUs.size == x.size && x.size == y.size) { "column sizes differ" }
    }

    val labelledFrames: Int get() = tUs.size
    val observedFrames: Int = x.count { !it.isNaN() }

    fun isObserved(i: Int): Boolean = !x[i].isNaN()
}

/** What the replay screen may show about annotations for the open video. */
sealed interface AnnotationState {
    data object Checking : AnnotationState
    data class Available(val track: TrailTrack) : AnnotationState
    /** Nothing is known for this recording. Shown as "No annotations for this video". */
    data object NoneForThisVideo : AnnotationState
    data class Failed(val reason: String) : AnnotationState
    /** The recording can play but the overlay cannot be drawn on it (HDR, Phase 1 limitation). */
    data object UnsupportedHdr : AnnotationState
}
