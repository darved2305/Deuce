package com.elevate.app.data.upload

import com.elevate.app.domain.media.Preflight
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * How a file of [bytes] splits into multipart parts of [partSize] (from `session.create.response.v1`;
 * the API uses 8 MiB). Parts are numbered 1..[count]; every part is [partSize] except a shorter last
 * one. Matches the server's `Math.ceil(bytes / PART_SIZE)`.
 */
data class PartPlan(val bytes: Long, val partSize: Long) {
    init {
        require(bytes >= 1) { "an empty file cannot be uploaded" }
        require(bytes <= Preflight.MAX_UPLOAD_BYTES) { "uploads are limited to 300 MiB (G1)" }
        require(partSize >= 1) { "part size must be positive" }
    }

    val count: Int get() = ((bytes + partSize - 1) / partSize).toInt()

    fun offset(n: Int): Long {
        require(n in 1..count) { "part $n outside 1..$count" }
        return (n - 1) * partSize
    }

    fun length(n: Int): Long = minOf(partSize, bytes - offset(n))
}

/** Random access to the bytes being uploaded. */
interface PartSource {
    /** Opens a stream over exactly `[offset, offset + length)`. */
    fun open(offset: Long, length: Long): InputStream
}

/**
 * Reads a byte range of a local file without loading it: the channel is positioned at [offset] and
 * the stream ends after [length] bytes. Used for app-private staging copies and in tests.
 */
class FilePartSource(private val file: File) : PartSource {
    override fun open(offset: Long, length: Long): InputStream {
        val input = FileInputStream(file)
        // The stream reads from the channel's position, so this seeks without reading the skipped bytes.
        input.channel.position(offset)
        return BoundedInputStream(input, length)
    }
}

/** Stops after [limit] bytes; fails if the underlying stream ends early (file changed or truncated). */
class BoundedInputStream(private val inner: InputStream, private val limit: Long) : InputStream() {
    private var read = 0L

    override fun read(): Int {
        if (read >= limit) return -1
        val b = inner.read()
        if (b < 0) throw IOException("file ended $read bytes into a $limit-byte part")
        read++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (read >= limit) return -1
        val n = inner.read(b, off, minOf(len.toLong(), limit - read).toInt())
        if (n < 0) throw IOException("file ended $read bytes into a $limit-byte part")
        read += n
        return n
    }

    override fun close() = inner.close()
}

/** Copies [input] to [out] through one bounded buffer, reporting each chunk written. */
internal fun copyCounting(input: InputStream, out: OutputStream, onBytes: (Int) -> Unit) {
    val buf = ByteArray(64 * 1024)
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        onBytes(n)
    }
}
