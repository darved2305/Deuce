package com.elevate.app.data.upload

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StatFs
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

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

/**
 * App-private copies of analysed videos, `filesDir/videos/<sha256>.<ext>`, so a match in the history
 * can still be played after process death (a Photo Picker Uri without a persisted grant cannot be
 * read again, and the upload's staging copy would otherwise be deleted once the server confirmed it).
 * Kept only for uploads the user started, and only when no persisted read grant exists.
 *
 * Bounded: total size at most [capBytes] and at least [minFreeBytes] left free on the volume
 * ([StatFs]); the least recently used copies are evicted first ([plan]). When the limits cannot be
 * met the copy is simply not kept (the history then asks the user to pick the video again).
 */
class LocalVideoStore(
    val dir: File,
    private val freeBytes: (File) -> Long = { availableBytes(it) },
    private val minFreeBytes: Long = MIN_FREE_BYTES,
    private val capBytes: Long = CAP_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** One kept copy, as the planner sees it. */
    data class Item(val name: String, val bytes: Long, val lastUsedMs: Long)

    sealed interface Plan {
        /** Keep the new copy after deleting [evict] (names, least recently used first). */
        data class Keep(val evict: List<String>) : Plan
        data class Refuse(val reason: String) : Plan
    }

    /** The kept copy of [sha256], if any (any extension). */
    @Synchronized
    fun find(sha256: String): File? {
        if (!SHA.matches(sha256)) return null
        val key = sha256.lowercase(Locale.ROOT)
        return dir.listFiles { f -> f.isFile && f.name.substringBeforeLast('.') == key }?.firstOrNull()
    }

    /** Marks a copy as just used, so eviction takes older ones first. */
    fun touch(file: File) {
        file.setLastModified(clock())
    }

    /**
     * Moves [src] (the verified staging copy, same volume) into the store as `<sha256>.<ext>`.
     * Returns the kept file, or null when it is not kept (limits not met, bad name); [src] is then
     * left where it is for the caller to delete.
     */
    @Synchronized
    fun adopt(src: File, sha256: String, ext: String): File? {
        if (!src.isFile) return null
        val target = target(sha256, ext) ?: return null
        if (target.isFile && target.length() == src.length()) {
            touch(target)
            return target
        }
        dir.mkdirs()
        // A rename adds no bytes to the volume: only the cap and the current free space matter.
        if (!applyPlan(src.length(), addedToVolume = 0, keepName = target.name)) return null
        if (!src.renameTo(target)) return null
        touch(target)
        return target
    }

    /**
     * Copies [bytes] bytes from [input] into the store, verifying the sha256 while copying. Returns
     * the kept file or null (limits not met). Throws IOException when the bytes differ.
     */
    @Synchronized
    fun copy(input: InputStream, bytes: Long, sha256: String, ext: String): File? {
        val target = target(sha256, ext) ?: return null.also { input.close() }
        if (target.isFile && target.length() == bytes) {
            input.close()
            touch(target)
            return target
        }
        dir.mkdirs()
        if (!applyPlan(bytes, addedToVolume = bytes, keepName = target.name)) return null.also { input.close() }
        val tmp = File(dir, target.name + ".part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            input.use { inp ->
                FileOutputStream(tmp).use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        copied += n
                        if (copied > bytes) throw IOException("the video is larger than when it was checked")
                        o.write(buf, 0, n)
                        digest.update(buf, 0, n)
                    }
                    o.fd.sync()
                }
            }
            val hex = digest.digest().joinToString("") { String.format(Locale.ROOT, "%02x", it) }
            if (copied != bytes || !hex.equals(sha256, ignoreCase = true)) throw IOException("the video changed since it was checked")
            if (!tmp.renameTo(target)) throw IOException("cannot keep the copy")
            touch(target)
            return target
        } finally {
            tmp.delete()
        }
    }

    /**
     * Deletes copies whose sha256 is not in [keep] and that were not used for [graceMs] (an upload
     * that finished but whose analysis was never started, or a match dropped from the history).
     */
    @Synchronized
    fun prune(keep: Set<String>, graceMs: Long = PRUNE_GRACE_MS) {
        val now = clock()
        val keys = keep.map { it.lowercase(Locale.ROOT) }.toSet()
        dir.listFiles { f -> f.isFile }?.forEach { f ->
            val unused = now - f.lastModified() > graceMs
            val referenced = !f.name.endsWith(".part") && f.name.substringBeforeLast('.') in keys
            if (unused && !referenced) f.delete()
        }
    }

    private fun target(sha256: String, ext: String): File? {
        if (!SHA.matches(sha256)) return null
        val e = ext.lowercase(Locale.ROOT).takeIf { EXT.matches(it) } ?: "mp4"
        return File(dir, sha256.lowercase(Locale.ROOT) + "." + e)
    }

    private fun applyPlan(incoming: Long, addedToVolume: Long, keepName: String): Boolean {
        val items = dir.listFiles { f -> f.isFile && !f.name.endsWith(".part") && f.name != keepName }
            ?.map { Item(it.name, it.length(), it.lastModified()) }.orEmpty()
        return when (val p = plan(items, incoming, addedToVolume, freeBytes(dir), minFreeBytes, capBytes)) {
            is Plan.Refuse -> false
            is Plan.Keep -> {
                p.evict.forEach { File(dir, it).delete() }
                true
            }
        }
    }

    companion object {
        /** Keep at least this much free on the volume (500 MB). */
        const val MIN_FREE_BYTES = 500L * 1000 * 1000
        /** All kept copies together at most 1.5 GB. */
        const val CAP_BYTES = 1500L * 1000 * 1000
        const val PRUNE_GRACE_MS = 24L * 60 * 60 * 1000
        private val SHA = Regex("[0-9a-fA-F]{64}")
        private val EXT = Regex("[a-z0-9]{1,5}")

        /** `filesDir/videos`. */
        fun inFilesDir(filesDir: File): LocalVideoStore = LocalVideoStore(File(filesDir, "videos"))

        /**
         * Pure: which copies to evict (least recently used first) so that, with a new copy of
         * [incomingBytes] that adds [addedToVolume] bytes to the volume, the total stays within
         * [capBytes] and at least [minFreeBytes] stay free. Refuses rather than evicting in vain.
         */
        fun plan(existing: List<Item>, incomingBytes: Long, addedToVolume: Long, freeBytes: Long, minFreeBytes: Long, capBytes: Long): Plan {
            if (incomingBytes > capBytes) return Plan.Refuse("the video is larger than the ${capBytes / 1_000_000} MB kept on this phone")
            var total = existing.sumOf { it.bytes } + incomingBytes
            var free = freeBytes - addedToVolume
            val evict = mutableListOf<String>()
            for (item in existing.sortedBy { it.lastUsedMs }) {
                if (total <= capBytes && free >= minFreeBytes) break
                evict += item.name
                total -= item.bytes
                free += item.bytes
            }
            return if (total <= capBytes && free >= minFreeBytes) {
                Plan.Keep(evict)
            } else {
                Plan.Refuse("not enough free space to keep the video (${minFreeBytes / 1_000_000} MB must stay free)")
            }
        }

        /**
         * After a confirmed upload: moves its staging copy (made because no read grant could be
         * persisted) into `videos/` beside `upload-staging/`. Nothing is copied when the original Uri
         * was readable through a persisted grant (no staging copy exists then).
         */
        fun keepStaged(state: UploadState): File? {
            val staged = state.stagedPath?.let(::File)?.takeIf { it.isFile } ?: return null
            val filesDir = staged.parentFile?.parentFile ?: return null
            return inFilesDir(filesDir).adopt(staged, state.sha256, staged.extension.ifBlank { "mp4" })
        }

        /**
         * Keeps a copy of a still-readable picked [uri] for history, unless a persisted read grant is
         * held for it (then the Uri itself stays readable and nothing is copied). Blocking.
         */
        fun keepFromUri(context: Context, uri: Uri, bytes: Long, sha256: String): File? {
            if (uri.scheme == ContentResolver.SCHEME_FILE) return null
            val held = context.contentResolver.persistedUriPermissions.any { it.isReadPermission && it.uri == uri }
            if (held) return null
            val store = inFilesDir(context.filesDir)
            store.find(sha256)?.let {
                store.touch(it)
                return it
            }
            val input = context.contentResolver.openInputStream(uri) ?: return null
            return store.copy(input, bytes, sha256, "mp4")
        }

        private fun availableBytes(dir: File): Long = try {
            StatFs((dir.takeIf { it.isDirectory } ?: dir.parentFile ?: dir).path).availableBytes
        } catch (e: RuntimeException) {
            // JVM unit tests (no StatFs) or a vanished directory.
            dir.usableSpace.takeIf { it > 0 } ?: dir.parentFile?.usableSpace ?: 0L
        }
    }
}
