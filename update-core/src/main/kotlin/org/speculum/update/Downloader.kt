package org.speculum.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import java.io.File

/**
 * Streams release assets to disk. Streaming (not `body<ByteArray>()`) is
 * mandatory: packages bundle a full JRE (~100 MB) and the mirror JVM runs with
 * `-Xmx160m`, so the file must never be buffered whole in memory.
 */
class Downloader(private val client: HttpClient = downloadClient()) {

    /** Downloads [url] to [dest], reporting (bytesRead, totalBytesOrNull). */
    suspend fun download(url: String, dest: File, onProgress: (Long, Long?) -> Unit = { _, _ -> }) {
        client.prepareGet(url) { header("User-Agent", "Speculum-UpdateNotifier") }.execute { response ->
            // Reject error responses (e.g. a 404 whose "Not Found" body would
            // otherwise be saved verbatim as the downloaded file). CIO follows
            // redirects, so this is the final response's status.
            if (!response.status.isSuccess())
                throw IllegalStateException("download failed: HTTP ${response.status}")
            val total = response.contentLength()
            val channel = response.bodyAsChannel()
            var read = 0L
            dest.outputStream().buffered().use { out ->
                while (!channel.isClosedForRead) {
                    val packet = channel.readRemaining(64 * 1024L)
                    val bytes = packet.readByteArray()
                    if (bytes.isNotEmpty()) {
                        out.write(bytes)
                        read += bytes.size
                        onProgress(read, total)
                    }
                }
            }
        }
    }

    companion object {
        /**
         * A client tuned for large downloads: CIO's default 15s whole-request
         * timeout would abort a ~100 MB package over a slow link, so disable it
         * (`requestTimeout = 0`) and rely on a socket timeout to catch real
         * stalls instead.
         */
        fun downloadClient(): HttpClient = HttpClient(CIO) {
            // CIO's own 15s whole-request cap would abort a big download.
            engine { requestTimeout = 0 }
            // No requestTimeoutMillis: the download can take minutes; the engine
            // cap above is already off. Keep connect + socket stall detection.
            install(HttpTimeout) {
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 120_000 // abort only after a 2-min stall
            }
        }
    }
}
