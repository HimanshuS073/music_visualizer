package local.music.kotlinplayer

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecommendationPlaybackTest {
    private val first = song("r7qovpFAGrQ", "Old Town Road", "Lil Nas X")
    private val recommended = song("BBBBBBBBBBB", "B (Official Music Video)", "Artist B")
    private val manual = song("YnopHCL1Jk8", "Toca Toca (Official Video)", "Fly Project")
    private val afterManual = song("DDDDDDDDDDD", "D (Official Video)", "Artist D")

    @Before fun keepTestsOffline() {
        UpdateManager::class.java.getDeclaredField("lastAutomaticCheck").apply { isAccessible = true }
            .setLong(null, android.os.SystemClock.elapsedRealtime())
    }

    @Test fun exactIdNextLabelAndHandoffUseTheServiceQueue() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).create()
        val service = controller.get()
        val fetchedIds = java.util.concurrent.CopyOnWriteArrayList<String>()
        try {
            service.resolveStream = { _, _ -> SongStream("asset:///mock-track.mp3") }
            service.relatedVideos = { videoId, _ ->
                fetchedIds.add(videoId)
                if (videoId == first.videoId) listOf(first, recommended, recommended)
                else if (videoId == manual.videoId) listOf(afterManual)
                else emptyList()
            }
            service.select(first)
            await { service.nextSong == recommended }
            assertEquals(listOf("r7qovpFAGrQ"), fetchedIds.toList())
            val screen = MusicScreen(activity.get(), {}, {}, {}, {}, {}, {})
            screen.setNext(service.nextSong, service.canSkipSuggestion)
            assertTrue(labels(screen.controls).contains("Next: B (Official Music Video) · Artist B"))
            assertEquals(View.VISIBLE, screen.skipSuggestion.visibility)

            service.enqueue(manual)
            assertEquals(manual, service.nextSong)
            assertFalse(service.canSkipSuggestion)
            service.trackEnded()
            await { service.song == manual && service.nextSong == afterManual }
            service.trackEnded()
            await { service.song == afterManual }
            assertEquals(listOf("r7qovpFAGrQ", "YnopHCL1Jk8"), fetchedIds.take(2))
        } finally {
            activity.destroy()
            controller.destroy()
        }
    }

    @Test fun finishedTrackWaitsForRecommendationsButADifferentTrackInvalidatesTheResult() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()
        val waiting = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            service.resolveStream = { _, _ -> SongStream("asset:///mock-track.mp3") }
            service.relatedVideos = { videoId, _ ->
                if (videoId == first.videoId) {
                    waiting.countDown()
                    try { release.await(3, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
                    listOf(recommended)
                } else listOf(afterManual)
            }
            service.select(first)
            await { waiting.count == 0L }
            service.trackEnded()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(first, service.song)
            service.select(manual)
            release.countDown()
            await { service.nextSong == afterManual }
            assertEquals(manual, service.song)
        } finally {
            release.countDown()
            controller.destroy()
        }
    }

    private fun await(condition: () -> Boolean) {
        val simulation = shadowOf(Looper.getMainLooper())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (System.nanoTime() < deadline) {
            simulation.idle()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Playback service did not reach expected state")
    }

    private fun labels(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun song(id: String, title: String, artist: String) =
        Song(title, artist, "https://www.youtube.com/watch?v=$id", 120, "", "")
}
