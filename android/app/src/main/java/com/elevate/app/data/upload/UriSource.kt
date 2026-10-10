package com.elevate.app.data.upload

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StatFs
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Reads byte ranges of a content Uri: `openFileDescriptor` + `FileInputStream.channel.position()`,
 * so a part never loads the file (64 KiB buffer in [copyCounting]). A `file:` Uri (staging copy)
 * reads the file directly.
 */
class UriPartSource(private val resolver: ContentResolver, private val uri: Uri) : PartSource {
    override fun open(offset: Long, length: Long): InputStream {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return FilePartSource(File(requireNotNull(uri.path))).open(offset, length)
        val pfd = resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException("cannot open the video")
        val input = FileInputStream(pfd.fileDescriptor)
        input.channel.position(offset)
        return object : java.io.FilterInputStream(BoundedInputStream(input, length)) {
            override fun close() {
                try { super.close() } finally { pfd.close() }
            }
        }
    }
}

/** How the background job will read the picked video after the app's process may have died. */
sealed interface SourceAccess {
    /** The read grant was persisted: the job reads the original Uri. */
    data class Persisted(val uri: Uri) : SourceAccess

    /** The grant is not persistable: an app-private copy (sha256 verified) is read instead. */
    data class Staged(val file: File) : SourceAccess {
        val uri: Uri get() = Uri.fromFile(file)
    }
}

class InsufficientStorageException(val neededBytes: Long, val availableBytes: Long) :
    IOException("not enough free space to stage the video: need $neededBytes, have $availableBytes")

/**
 * Photo Picker and `GET_CONTENT` grants usually end with the process; a background upload may run
 * after it died. First try `takePersistableUriPermission` (works for `ACTION_OPEN_DOCUMENT` Uris
 * and, per the Photo Picker docs, picker Uris on supported versions: UNVERIFIED on our phones).
 * If that throws, copy the video to `filesDir/upload-staging/` after checking free space with
 * [StatFs] (bounded by the 300 MiB cap), hashing while copying; a hash that differs from the
 * fingerprint the app showed means the file changed and the copy is refused.
 */
object UriAccess {
    private const val MARGIN_BYTES = 64L * 1024 * 1024

    fun prepare(context: Context, uri: Uri, bytes: Long, expectedSha256: String): SourceAccess {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return SourceAccess.Persisted(uri)
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            return SourceAccess.Persisted(uri)
        } catch (_: SecurityException) {
            // Not persistable: stage below.
        }
        val dir = File(context.filesDir, "upload-staging").apply { mkdirs() }
        val available = StatFs(dir.path).availableBytes
        if (available < bytes + MARGIN_BYTES) throw InsufficientStorageException(bytes + MARGIN_BYTES, available)
        val out = File(dir, "source-${System.currentTimeMillis()}.mp4")
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("cannot open the video")
            input.use { inp ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        copied += n
                        if (copied > bytes) throw IOException("the video is larger than when it was checked")
                    }
                    o.fd.sync()
                }
            }
            val hex = digest.digest().joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }
            if (copied != bytes || !hex.equals(expectedSha256, ignoreCase = true)) {
                throw IOException("the video changed since it was checked")
            }
            return SourceAccess.Staged(out)
        } catch (e: Exception) {
            out.delete()
            throw e
        }
    }
}
