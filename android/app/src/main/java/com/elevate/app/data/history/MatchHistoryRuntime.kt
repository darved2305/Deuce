package com.elevate.app.data.history

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import java.io.File

/** Process-wide history (`filesDir/history/matches.json`) and its recorder. Holds no Context. */
class MatchHistoryRuntime private constructor(filesDir: File) {
    val history = MatchHistory(File(File(filesDir, "history"), "matches.json"))
    val recorder = MatchHistoryRecorder(history)

    companion object {
        @Volatile private var instance: MatchHistoryRuntime? = null

        fun get(context: Context): MatchHistoryRuntime =
            instance ?: synchronized(this) { instance ?: MatchHistoryRuntime(context.applicationContext.filesDir).also { instance = it } }

        /**
         * A Uri this process can still read for [entry], or null: the staging copy if it still exists,
         * else the picked Uri when a persistable read grant is held for it. A Photo Picker Uri without a
         * persisted grant stops being readable once the picking activity is gone (UPLOAD_CLIENT.md), and
         * the upload deletes its staging copy once the server confirms the upload.
         */
        fun readableSource(context: Context, entry: MatchHistoryEntry): Uri? {
            entry.stagedPath?.let { p -> File(p).takeIf { it.isFile && it.canRead() }?.let { return Uri.fromFile(it) } }
            val uri = runCatching { entry.sourceUri.toUri() }.getOrNull() ?: return null
            if (uri.scheme == "file") return uri.takeIf { File(uri.path.orEmpty()).canRead() }
            val held = context.contentResolver.persistedUriPermissions.any { it.isReadPermission && it.uri == uri }
            return uri.takeIf { held }
        }
    }
}
