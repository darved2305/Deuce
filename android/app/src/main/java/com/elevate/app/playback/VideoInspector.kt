package com.elevate.app.playback

import android.content.ContentResolver
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.media.MediaFormat
import com.elevate.app.domain.repository.VideoFingerprint
import java.io.IOException
import java.security.MessageDigest

/** Facts about a picked recording, read from the file itself. Call off the main thread. */
class VideoInspector(private val resolver: ContentResolver) {

    data class Info(
        val displayName: String?,
        val bytes: Long?,
        val width: Int?,
        val height: Int?,
        val rotation: Int?,
        val durationMs: Long?,
        /** HLG or PQ transfer (HDR). Media3 1.11.1 cannot draw a canvas overlay on HDR frames. */
        val isHdr: Boolean = false,
    )

    /** Throws [IOException] if the URI cannot be opened (revoked grant, deleted file). */
    fun info(uri: Uri): Info {
        var name: String? = null
        var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)
                size = if (c.isNull(1)) null else c.getLong(1)
            }
        }
        val r = MediaMetadataRetriever()
        try {
            resolver.openFileDescriptor(uri, "r")?.use { r.setDataSource(it.fileDescriptor) }
                ?: throw IOException("cannot open $uri")
            fun int(key: Int) = r.extractMetadata(key)?.toIntOrNull()
            return Info(
                displayName = name,
                bytes = size,
                width = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
                height = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
                rotation = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION),
                durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                isHdr = Build.VERSION.SDK_INT >= 30 &&
                    int(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER) in HDR_TRANSFERS,
            )
        } catch (e: RuntimeException) {
            // MediaMetadataRetriever throws IllegalArgumentException/RuntimeException on files it cannot parse.
            throw IOException("not a readable video: ${e.message}", e)
        } finally {
            r.release()
        }
    }

    /** sha256 of the file's bytes: the identity annotations are matched against. */
    fun fingerprint(uri: Uri): VideoFingerprint {
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        (resolver.openInputStream(uri) ?: throw IOException("cannot open $uri")).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                total += n
            }
        }
        return VideoFingerprint(md.digest().joinToString("") { "%02x".format(it) }, total)
    }

    private companion object {
        val HDR_TRANSFERS = setOf(MediaFormat.COLOR_TRANSFER_HLG, MediaFormat.COLOR_TRANSFER_ST2084)
    }
}
