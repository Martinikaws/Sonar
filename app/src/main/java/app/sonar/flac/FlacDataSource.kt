package app.sonar.flac

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import okhttp3.Response
import okio.BufferedSource
import java.io.EOFException
import java.io.IOException

@OptIn(UnstableApi::class)
internal class FlacDataSource(private val context: Context) : BaseDataSource(true) {
    private var response: Response? = null
    private var source: BufferedSource? = null
    private var local: DataSource? = null
    private var openedUri: Uri? = null
    private var remaining = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        val stream = FlacSources.stream(dataSpec.uri.toString()) ?: throw IOException("FLAC selection expired")
        if (!FlacSources(context).enabled(stream.provider)) throw IOException("FLAC source disabled")
        if (stream.provider == FlacProvider.BANDCAMP) {
            val file = DefaultDataSource.Factory(context).createDataSource()
            local = file
            return file.open(dataSpec.withUri(Uri.parse(stream.address)))
        }
        transferInitializing(dataSpec)
        val url = flacHttps(stream.address) ?: throw IOException("Invalid FLAC stream")
        val range = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            "bytes=${dataSpec.position}-${dataSpec.position + dataSpec.length - 1}"
        } else if (dataSpec.position > 0) "bytes=${dataSpec.position}-" else null
        try {
            val opened = FlacSources.request(stream.provider, url, metadata = false, range = range)
            response = opened
            val body = opened.body ?: throw IOException("Empty FLAC audio")
            val bytes = body.source()
            if (opened.code == 206) {
                val start = opened.header("Content-Range")?.let {
                    CONTENT_RANGE.matchEntire(it)?.groupValues?.get(1)?.toLongOrNull()
                }
                if (start != dataSpec.position) throw IOException("Unexpected FLAC range")
            } else if (opened.code != 200 || dataSpec.position != 0L) {
                throw IOException("FLAC stream does not support this position")
            }
            if (dataSpec.position == 0L && (!bytes.request(4) || bytes.peek().readUtf8(4) != "fLaC")) {
                throw IOException("Source did not return FLAC audio")
            }
            val length = body.contentLength()
            remaining = when {
                dataSpec.length == C.LENGTH_UNSET.toLong() -> length
                length < 0 -> dataSpec.length
                else -> minOf(length, dataSpec.length)
            }
            source = bytes
            openedUri = dataSpec.uri
            transferStarted(dataSpec)
            return remaining
        } catch (_: IOException) {
            close()
            throw IOException("Could not open FLAC audio")
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        local?.let { return it.read(buffer, offset, length) }
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count = if (remaining < 0) length else minOf(remaining, length.toLong()).toInt()
        val read = try {
            checkNotNull(source).read(buffer, offset, count)
        } catch (_: IOException) {
            throw IOException("Could not read FLAC audio")
        }
        if (read == -1) {
            if (remaining > 0) throw EOFException("Incomplete FLAC audio")
            return C.RESULT_END_OF_INPUT
        }
        if (remaining > 0) remaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = openedUri

    override fun close() {
        try {
            local?.close()
            response?.close()
        } finally {
            local = null
            response = null
            source = null
            if (openedUri != null) {
                openedUri = null
                transferEnded()
            }
        }
    }

    companion object {
        private val CONTENT_RANGE = Regex("bytes ([0-9]+)-[0-9]+/(?:[0-9]+|\\*)")
    }
}
