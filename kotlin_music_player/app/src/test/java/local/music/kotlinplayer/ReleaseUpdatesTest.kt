package local.music.kotlinplayer

import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.Protocol
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReleaseUpdatesTest {
    private val bytes = "test APK bytes".toByteArray()
    private val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun release(code: Long = 4, tag: String = "android-v1.3"): JSONObject {
        val asset = JSONObject().put("name", "music-room-v$code.apk").put("state", "uploaded")
            .put("size", bytes.size).put("digest", "sha256:$digest")
            .put("browser_download_url", "https://github.com/${ReleaseUpdates.REPOSITORY}/releases/download/$tag/music-room-v$code.apk")
        return JSONObject().put("tag_name", tag).put("draft", false).put("prerelease", false)
            .put("assets", JSONArray().put(asset))
    }
    private fun update() = ReleaseUpdates.newest(JSONArray().put(release()).toString(), 3)!!
    private fun withDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("music-update-test").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }
    private fun client(code: Int = 200, payload: ByteArray = bytes) = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Test response")
            .body(payload.toResponseBody()).build()
    }.build()

    @Test fun selectsHighestNewAndroidVersionIgnoringDesktopAndPreviews() {
        val json = JSONArray().put(release(3)).put(release(9, "v9.0"))
            .put(release(8).put("prerelease", true)).put(release(7).put("draft", true))
            .put(release(5)).put(release(4))
        assertEquals(5L, ReleaseUpdates.newest(json.toString(), 3)!!.code)
        assertNull(ReleaseUpdates.newest(json.toString(), 5))
        assertNull(ReleaseUpdates.newest("[]", 3))
    }
    @Test fun ignoresUntrustedUrlsMissingDigestsAndInvalidSizes() {
        for ((key, value) in listOf("browser_download_url" to "https://evil.invalid/music.apk",
            "digest" to "", "digest" to "sha256:broken", "size" to 0, "size" to ReleaseUpdates.MAX_APK_BYTES + 1,
            "state" to "new", "name" to "music-room-v0.apk", "name" to "music-room-v999999999999999999999.apk")) {
            val item = release()
            item.getJSONArray("assets").getJSONObject(0).put(key, value)
            assertNull("Accepted $key=$value", ReleaseUpdates.newest(JSONArray().put(item).toString(), 3))
        }
    }
    @Test fun malformedEntriesDoNotHideOtherValidReleases() {
        val json = JSONArray().put(JSONObject()).put(5).put(JSONObject().put("tag_name", "android-v1.2")).put(release())
        assertEquals(4L, ReleaseUpdates.newest(json.toString(), 3)!!.code)
    }
    @Test fun downloadChecksBytesAndReusesVerifiedCache() = withDirectory { directory ->
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Test")
                .body(bytes.toResponseBody()).build()
        }.build()
        val updater = ReleaseUpdates(http)
        val progress = mutableListOf<Int>()
        val file = updater.download(update(), directory) { progress.add(it) }
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(listOf(100), progress)
        assertEquals(file, updater.download(update(), directory) {})
        assertEquals(1, calls)
        assertEquals(listOf(file.name), directory.list()!!.toList())
    }
    @Test fun corruptedCachedFileIsReplaced() = withDirectory { directory ->
        File(directory, update().filename).writeText("broken")
        val file = ReleaseUpdates(client()).download(update(), directory) {}
        assertEquals(digest, ReleaseUpdates.hash(file))
    }
    @Test fun incorrectChecksumLeavesNoPartialApk() = withDirectory { directory ->
        val bad = bytes.copyOf().also { it[0] = 0 }
        assertThrows(IOException::class.java) { ReleaseUpdates(client(payload = bad)).download(update(), directory) {} }
        assertTrue(directory.list()!!.isEmpty())
    }
    @Test fun truncatedAndOversizedDownloadsAreRejected() = withDirectory { directory ->
        for (payload in listOf(bytes.copyOf(bytes.size - 1), bytes + 0)) {
            assertThrows(IOException::class.java) { ReleaseUpdates(client(payload = payload)).download(update(), directory) {} }
        }
        assertTrue(directory.list()!!.isEmpty())
    }
    @Test fun failedHttpDownloadLeavesNoPartialFile() = withDirectory { directory ->
        assertThrows(IOException::class.java) { ReleaseUpdates(client(code = 403)).download(update(), directory) {} }
        assertTrue(directory.list()!!.isEmpty())
    }
    @Test fun githubCheckHandlesResponsesAndNetworkErrors() {
        val json = JSONArray().put(release()).toString().toByteArray()
        assertEquals(4L, ReleaseUpdates(client(payload = json)).check(3)!!.code)
        assertThrows(IOException::class.java) { ReleaseUpdates(client(code = 403)).check(3) }
        assertThrows(Exception::class.java) { ReleaseUpdates(client(payload = "broken".toByteArray())).check(3) }
    }
    @Test fun checksumIsAlsoCheckedOnChunkedStreams() = withDirectory { directory ->
        val file = File(directory, "partial")
        assertThrows(IOException::class.java) {
            ReleaseUpdates.copyChecked((bytes + 0).inputStream(), file, update()) {}
        }
        assertThrows(IOException::class.java) {
            ReleaseUpdates.copyChecked(bytes.copyOf(bytes.size - 1).inputStream(), file, update()) {}
        }
    }
}
