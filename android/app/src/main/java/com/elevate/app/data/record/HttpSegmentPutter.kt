package com.elevate.app.data.record

import com.elevate.app.data.session.HttpRequest
import com.elevate.app.data.session.HttpTransport
import com.elevate.app.data.session.RequestBody
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

/**
 * PUTs a piece to its presigned R2 URL: fixed length, streamed from the file, no auth headers
 * (the signature is in the URL), nothing logged with the URL. A non-2xx answer is [PutResult.Rejected];
 * transport failures throw (the uploader decides whether they count as a try).
 */
class HttpSegmentPutter(
    private val transport: HttpTransport,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SegmentPutter {

    override suspend fun put(url: String, segment: RecordedSegment): PutResult = withContext(io) {
        val res = transport.execute(HttpRequest("PUT", url, emptyMap(), FileBody(segment.file, segment.bytes)))
        if (res.status in 200..299) PutResult.Ok else PutResult.Rejected(res.status)
    }

    private class FileBody(private val file: File, override val contentLength: Long) : RequestBody {
        override val contentType: String = "video/mp4"

        override fun writeTo(out: OutputStream) {
            var left = contentLength
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw java.io.IOException("piece ${file.name} is shorter than its registered size")
                    out.write(buf, 0, n)
                    left -= n
                }
            }
        }
    }
}
