package local.music.kotlinplayer

import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveSearchTest {
    @Test fun searchImagesAndStreamWorkTogether() {
        assumeTrue(System.getProperty("music.liveTests") == "true")
        val songs = MusicSearch.search("toca toca Fly Project")
        assertTrue(songs.isNotEmpty() && songs.size <= 20)
        val song = songs.first()
        assertTrue(song.title.isNotBlank() && song.url.startsWith("https://"))
        assertTrue(MusicSearch.image(song.preview).size in 100..4 * 1024 * 1024)
        assertTrue(MusicSearch.image(song.thumbnail).size in 100..4 * 1024 * 1024)
        val stream = MusicSearch.resolve(song)
        MusicSearch.http.newCall(Request.Builder().url(stream.audio).header("User-Agent", "Mozilla/5.0")
            .header("Range", "bytes=0-1023").build()).execute().use { response ->
            assertTrue(response.isSuccessful)
            val type = response.header("Content-Type").orEmpty()
            assertTrue(type.startsWith("audio/") || type.startsWith("video/") || type.contains("octet-stream"))
            val bytes = ByteArray(1024)
            assertTrue(response.body!!.byteStream().read(bytes) > 0)
        }
    }
}
