package com.elevate.app.data.history

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.net.toUri
import com.elevate.app.data.upload.LocalVideoStore
import com.elevate.app.ui.overview.FrameThumbnail
import com.elevate.app.ui.overview.ThumbnailState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Process-wide history (`filesDir/history/matches.json`) and its recorder. Holds no Context. */
class MatchHistoryRuntime private constructor(filesDir: File) {
    val history = MatchHistory(File(File(filesDir, "history"), "matches.json"))
    val recorder = MatchHistoryRecorder(history)

    /** Videos kept for history (`filesDir/videos`). */
    val videos = LocalVideoStore.inFilesDir(filesDir)

    private val thumbDir = File(File(filesDir, "history"), "thumbs")
    private val thumbs = ConcurrentHashMap<String, ImageBitmap>()

    init {
        // Copies no history entry refers to any more (dropped by the cap, or an analysis never started),
        // once unused for a day.
        runCatching { videos.prune(history.list().map { it.sha256 }.toSet()) }
    }

    /**
     * A real frame of the match's video for its history card (middle of the clip), or null when the
     * video cannot be read. Decoded with [FrameThumbnail.load] on IO from [readableSource]; cached per
     * analysisId in memory and on disk (`filesDir/history/thumbs/<analysisId>.jpg`), so it still shows
     * after the video is gone.
     */
    suspend fun thumbnail(context: Context, entry: MatchHistoryEntry): ImageBitmap? {
        thumbs[entry.analysisId]?.let { return it }
        return withContext(Dispatchers.IO) {
            val file = File(thumbDir, safe(entry.analysisId) + ".jpg")
            val fromDisk = file.takeIf { it.isFile }?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() }
            val img = fromDisk?.asImageBitmap() ?: run {
                val uri = readableSource(context, entry) ?: return@withContext null
                val r = FrameThumbnail.load(context.contentResolver, uri, (entry.durationMs ?: 0) / 2, THUMB_LONG_SIDE_PX)
                val ready = (r as? ThumbnailState.Ready)?.image ?: return@withContext null
                saveJpeg(ready.asAndroidBitmap(), file)
                ready
            }
            thumbs[entry.analysisId] = img
            img
        }
    }

    private fun saveJpeg(bmp: Bitmap, file: File) {
        try {
            thumbDir.mkdirs()
            val tmp = File(thumbDir, file.name + ".tmp")
            FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: IOException) {
            // Not fatal: decoded again next time.
        }
    }

    companion object {
        private const val THUMB_LONG_SIDE_PX = 480

        @Volatile private var instance: MatchHistoryRuntime? = null

        fun get(context: Context): MatchHistoryRuntime =
            instance ?: synchronized(this) { instance ?: MatchHistoryRuntime(context.applicationContext.filesDir).also { instance = it } }

        private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")

        /**
         * A Uri this process can still read for [entry], or null, in this order: the app-private copy
         * kept for history ([MatchHistoryEntry.localCopyPath], else found by sha256 in `filesDir/videos`
         * and then recorded), the upload's staging copy if it still exists, else the picked Uri when a
         * persistable read grant is held for it. A Photo Picker Uri without a persisted grant stops
         * being readable once the picking activity is gone (UPLOAD_CLIENT.md). Blocking.
         */
        fun readableSource(context: Context, entry: MatchHistoryEntry): Uri? {
            val rt = get(context)
            val kept = entry.localCopyPath?.let(::File)?.takeIf { it.isFile && it.canRead() }
                ?: rt.videos.find(entry.sha256)?.takeIf { it.canRead() }?.also { f ->
                    runCatching { rt.history.update(entry.analysisId) { it.copy(localCopyPath = f.path) } }
                }
            if (kept != null) {
                rt.videos.touch(kept)
                return Uri.fromFile(kept)
            }
            entry.stagedPath?.let { p -> File(p).takeIf { it.isFile && it.canRead() }?.let { return Uri.fromFile(it) } }
            val uri = runCatching { entry.sourceUri.toUri() }.getOrNull() ?: return null
            if (uri.scheme == "file") return uri.takeIf { File(uri.path.orEmpty()).canRead() }
            val held = context.contentResolver.persistedUriPermissions.any { it.isReadPermission && it.uri == uri }
            return uri.takeIf { held }
        }
    }
}
