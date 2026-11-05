// SPDX-FileCopyrightText: 2026 Himanshu_Sharma (HimanshuS073)
// SPDX-License-Identifier: GPL-3.0-or-later

package local.music.kotlinplayer

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class AppUpdate(val code: Long, val name: String, val url: String,
                     val sha256: String, val size: Long) {
    val filename get() = "music-room-v$code.apk"
}

class ReleaseUpdates(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
    .callTimeout(5, TimeUnit.MINUTES).followSslRedirects(false).build()) {
    companion object {
        const val REPOSITORY = "HimanshuS073/music_visualizer"
        const val FEED = "https://api.github.com/repos/$REPOSITORY/releases?per_page=100"
        const val MAX_APK_BYTES = 100L * 1024 * 1024

        fun newest(json: String, installedCode: Long): AppUpdate? {
            val releases = JSONArray(json)
            var newest: AppUpdate? = null
            for (i in 0 until releases.length()) {
                val release = releases.optJSONObject(i) ?: continue
                if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
                val tag = release.optString("tag_name")
                if (!tag.matches(Regex("android-v[0-9]+(?:\\.[0-9]+){1,2}"))) continue
                val assets = release.optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val asset = assets.optJSONObject(j) ?: continue
                    if (asset.optString("state") != "uploaded") continue
                    val filename = asset.optString("name")
                    val code = Regex("music-room-v([1-9][0-9]*)\\.apk").matchEntire(filename)
                        ?.groupValues?.get(1)?.toLongOrNull() ?: continue
                    if (code <= installedCode || code <= (newest?.code ?: 0)) continue
                    val size = asset.optLong("size")
                    val digest = asset.optString("digest").removePrefix("sha256:")
                    val url = asset.optString("browser_download_url")
                    val expected = "https://github.com/$REPOSITORY/releases/download/$tag/$filename"
                    if (url != expected || size !in 1..MAX_APK_BYTES || !digest.matches(Regex("[a-fA-F0-9]{64}"))) continue
                    newest = AppUpdate(code, tag.removePrefix("android-v"), url, digest.lowercase(), size)
                }
            }
            return newest
        }

        fun hash(file: File): String = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(32768)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

        internal fun copyChecked(input: InputStream, output: File, update: AppUpdate, progress: (Int) -> Unit) {
            if (update.size !in 1..MAX_APK_BYTES) throw IOException("Invalid update size.")
            var received = 0L
            var lastPercent = -1
            output.outputStream().use { stream ->
                val buffer = ByteArray(32768)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("Download cancelled.")
                    val read = input.read(buffer)
                    if (read < 0) break
                    received += read
                    if (received > update.size) throw IOException("Update download is larger than expected.")
                    stream.write(buffer, 0, read)
                    val percent = (received * 100 / update.size).toInt()
                    if (percent != lastPercent) { lastPercent = percent; progress(percent) }
                }
            }
            if (received != update.size || hash(output) != update.sha256)
                throw IOException("Update verification failed. Please try again.")
        }
    }

    @Volatile private var call: Call? = null
    fun cancel() { call?.cancel() }

    private fun request(url: String, accept: String): Call {
        val request = Request.Builder().url(url).header("User-Agent", "Music-Room-Android")
            .header("Accept", accept).header("X-GitHub-Api-Version", "2022-11-28").build()
        return client.newCall(request).also {
            call = it
            if (Thread.currentThread().isInterrupted) it.cancel()
        }
    }

    fun check(installedCode: Long): AppUpdate? {
        request(FEED, "application/vnd.github+json").execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub could not be reached (${response.code}). Try again later.")
            val body = response.body ?: throw IOException("Empty update response.")
            val bytes = body.byteStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    if (output.size() + size > 4 * 1024 * 1024) throw IOException("Update response is too large.")
                    output.write(buffer, 0, size)
                }
                output.toByteArray()
            }
            return newest(bytes.toString(Charsets.UTF_8), installedCode)
        }
    }

    fun download(update: AppUpdate, directory: File, progress: (Int) -> Unit): File {
        val prefix = "https://github.com/$REPOSITORY/releases/download/"
        if (!update.url.startsWith(prefix)) throw IOException("Invalid update address.")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create update cache.")
        val complete = File(directory, update.filename)
        if (complete.isFile && complete.length() == update.size && hash(complete) == update.sha256) return complete
        val partial = File.createTempFile("download-", ".part", directory)
        try {
            request(update.url, "application/octet-stream").execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download failed (${response.code}).")
                val body = response.body ?: throw IOException("Empty download.")
                if (body.contentLength() >= 0 && body.contentLength() != update.size)
                    throw IOException("Unexpected update size.")
                body.byteStream().use { copyChecked(it, partial, update, progress) }
            }
            if (Thread.currentThread().isInterrupted) throw IOException("Download cancelled.")
            if (complete.exists() && !complete.delete()) throw IOException("Cannot replace cached update.")
            if (!partial.renameTo(complete)) throw IOException("Cannot save verified update.")
            return complete
        } finally { partial.delete() }
    }
}
