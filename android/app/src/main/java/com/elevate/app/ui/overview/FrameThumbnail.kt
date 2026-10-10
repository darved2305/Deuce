package com.elevate.app.ui.overview

import android.content.ContentResolver
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.scale

/**
 * One real decoded frame of the source, for the overview hero. `OPTION_CLOSEST` decodes the frame
 * at (or nearest to) the time asked, not the previous sync frame. The bitmap is upright: on the A55
 * MediaMetadataRetriever already applies the rotation (FrameGrabRotationTest, real clips).
 * Blocking: call on an IO dispatcher.
 */
object FrameThumbnail {

    fun load(resolver: ContentResolver, uri: Uri, atMs: Long, maxLongSidePx: Int = 1280): ThumbnailState {
        val r = MediaMetadataRetriever()
        return try {
            val pfd = resolver.openFileDescriptor(uri, "r") ?: return ThumbnailState.Unavailable("the video could not be opened")
            pfd.use { r.setDataSource(it.fileDescriptor) }
            val bmp = r.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: return ThumbnailState.Unavailable("no frame could be decoded at ${clock(atMs)}")
            val long = maxOf(bmp.width, bmp.height)
            val scaled = if (long > maxLongSidePx) {
                val k = maxLongSidePx.toFloat() / long
                bmp.scale((bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), filter = true)
                    .also { if (it !== bmp) bmp.recycle() }
            } else {
                bmp
            }
            ThumbnailState.Ready(scaled.asImageBitmap(), atMs)
        } catch (e: RuntimeException) {
            ThumbnailState.Unavailable("frame not readable: ${e.javaClass.simpleName}")
        } catch (e: java.io.IOException) {
            ThumbnailState.Unavailable("frame not readable: ${e.javaClass.simpleName}")
        } finally {
            r.release()
        }
    }
}
