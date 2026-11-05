// SPDX-FileCopyrightText: 2026 Himanshu_Sharma (HimanshuS073)
// SPDX-License-Identifier: GPL-3.0-or-later

package local.music.kotlinplayer

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

data class Song(val title: String, val artist: String, val url: String,
                val duration: Long, val thumbnail: String, val preview: String)
data class SongStream(val audio: String, val video: String = "")

object MusicSearch {
    class Job {
        @Volatile var cancelled = false
            private set
        private val calls = mutableSetOf<Call>()
        @Synchronized fun add(call: Call) {
            if (cancelled) call.cancel() else calls.add(call)
        }
        @Synchronized fun remove(call: Call) { calls.remove(call) }
        @Synchronized fun cancel() {
            cancelled = true
            calls.forEach { it.cancel() }
            calls.clear()
        }
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS).build()
    private val currentJob = ThreadLocal<Job>()

    init {
        NewPipe.init(object : Downloader() {
            override fun execute(request: Request): Response {
                val builder = okhttp3.Request.Builder().url(request.url())
                    .header("User-Agent", "Mozilla/5.0")
                    .method(request.httpMethod(), request.dataToSend()?.toRequestBody())
                request.headers().forEach { (name, values) ->
                    builder.removeHeader(name)
                    values.forEach { builder.addHeader(name, it) }
                }
                val call = http.newCall(builder.build())
                val job = currentJob.get()
                job?.add(call)
                try {
                    call.execute().use { result ->
                        return Response(result.code, result.message, result.headers.toMultimap(),
                            result.body?.string().orEmpty(), result.request.url.toString())
                    }
                } finally { job?.remove(call) }
            }
        })
    }

    private inline fun <T> withJob(job: Job, work: () -> T): T {
        if (job.cancelled) throw IOException("Request cancelled.")
        currentJob.set(job)
        return try { work() } finally { currentJob.remove() }
    }

    fun search(name: String, job: Job = Job()): List<Song> = withJob(job) {
        if (name.isBlank()) return@withJob emptyList()
        val extractor = ServiceList.YouTube.getSearchExtractor(name.trim())
        extractor.fetchPage()
        extractor.initialPage.items.filterIsInstance<StreamInfoItem>().take(20).map { item ->
            val images = item.thumbnails
            val cover = images.maxByOrNull { it.width }?.url.orEmpty()
            val preview = images.filter { it.width > 0 }.minByOrNull { kotlin.math.abs(it.width - 240) }?.url
                ?: images.firstOrNull()?.url.orEmpty()
            Song(item.name ?: "Untitled", item.uploaderName ?: "Unknown channel", item.url,
                item.duration, cover, preview)
        }
    }

    fun resolve(song: Song, job: Job = Job()): SongStream = withJob(job) {
        val info = StreamInfo.getInfo(ServiceList.YouTube, song.url)
        val combined = info.videoStreams.filter { it.isUrl }.minByOrNull { height(it) }
        if (combined != null) return@withJob SongStream(combined.content)
        val audio = info.audioStreams.filter { it.isUrl }.maxByOrNull { it.averageBitrate }
            ?: throw IOException("No playable stream. Try another result.")
        val video = info.videoOnlyStreams.filter { it.isUrl }.minByOrNull { height(it) }
        SongStream(audio.content, video?.content.orEmpty())
    }

    fun image(url: String, job: Job = Job()): ByteArray {
        val call = http.newCall(okhttp3.Request.Builder().url(url).build())
        job.add(call)
        try {
            call.execute().use { response ->
                val body = response.body ?: throw IOException("Image unavailable.")
                if (!response.isSuccessful) throw IOException("Image unavailable.")
                val limit = 4 * 1024 * 1024
                if (body.contentLength() > limit) throw IOException("Image is too large.")
                body.byteStream().use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val size = input.read(buffer)
                        if (size == -1) break
                        if (output.size() + size > limit) throw IOException("Image is too large.")
                        output.write(buffer, 0, size)
                    }
                    return output.toByteArray()
                }
            }
        } finally { job.remove(call) }
    }

    private fun height(stream: VideoStream) =
        stream.getResolution().filter { it.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE
}
