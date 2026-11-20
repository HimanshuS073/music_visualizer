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
    private val first = song("r7qovpFAGrQ", "Old Town Road", "Fixture Artist")
    private val recommended = song("BBBBBBBBBBB", "B (Official Music Video)", "Fixture Artist")
    private val manual = song("YnopHCL1Jk8", "Toca Toca (Official Video)", "Fixture Artist")
    private val afterManual = song("DDDDDDDDDDD", "D (Official Video)", "Fixture Artist")

    @Before fun keepTestsOffline() {
        UpdateManager::class.java.getDeclaredField("lastAutomaticCheck").apply { isAccessible = true }
            .setLong(null, android.os.SystemClock.elapsedRealtime())
    }

    @Test fun exactIdNextLabelAndHandoffUseTheServiceQueue() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).create()
        val service = controller.get().apply { searchRecommendations = { _, _ -> emptyList() } }
        val fetchedIds = java.util.concurrent.CopyOnWriteArrayList<String>()
        try {
            service.resolveStream = { _, _ -> SongStream("asset:///mock-track.mp3") }
            service.relatedVideos = { videoId, _ ->
                fetchedIds.add(videoId)
                if (videoId == first.videoId) listOf(first, recommended, recommended, afterManual)
                else if (videoId == manual.videoId) listOf(afterManual)
                else emptyList()
            }
            service.select(first)
            await { service.nextSong == recommended }
            assertEquals(listOf("r7qovpFAGrQ"), fetchedIds.toList())
            val screen = MusicScreen(activity.get(), {}, {}, {}, {}, {}, {})
            screen.setNext(service.nextSong, service.song != null)
            assertTrue(labels(screen.controls).contains("Next: B (Official Music Video) · Fixture Artist"))
            assertEquals(View.VISIBLE, screen.suggestionsButton.visibility)

            service.enqueue(manual)
            assertEquals(manual, service.nextSong)
            assertEquals(listOf(recommended, afterManual), service.suggestedSongs())
            service.trackEnded()
            await { service.song == manual && service.nextSong == afterManual }
            service.trackEnded()
            val looper = shadowOf(Looper.getMainLooper())
            looper.idle()
            assertTrue(service.waitingForAutoplay)
            looper.idleFor(4999, TimeUnit.MILLISECONDS)
            assertEquals(manual, service.song)
            looper.idleFor(1, TimeUnit.MILLISECONDS)
            assertEquals(afterManual, service.song)
            assertEquals(listOf("r7qovpFAGrQ", "YnopHCL1Jk8"), fetchedIds.take(2))
        } finally {
            activity.destroy()
            controller.destroy()
        }
    }

    @Test fun finishedTrackWaitsForRecommendationsButADifferentTrackInvalidatesTheResult() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get().apply { searchRecommendations = { _, _ -> emptyList() } }
        val waiting = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            service.resolveStream = { _, _ -> SongStream("asset:///mock-track.mp3") }
            service.relatedVideos = { videoId, _ ->
                if (videoId == first.videoId) {
                    waiting.countDown()
                    try { release.await(3, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
                    listOf(recommended)
                } else listOf(afterManual, recommended)
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
            service.trackEnded()
            val looper = shadowOf(Looper.getMainLooper())
            looper.idle()
            assertTrue(service.waitingForAutoplay)
            service.select(service.suggestedSongs()[1])
            assertFalse(service.waitingForAutoplay)
            looper.idleFor(5, TimeUnit.SECONDS)
            assertEquals(recommended, service.song)
        } finally {
            release.countDown()
            controller.destroy()
        }
    }

    @Test fun suggestionDialogScrollsToTheTwentiethTrack() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get().apply { searchRecommendations = { _, _ -> emptyList() } }
        shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).setComponentNameAndServiceForBindService(
            android.content.ComponentName(service, PlaybackService::class.java),
            service.onBind(android.content.Intent().setAction(PlaybackService.LOCAL_BIND))!!
        )
        val window = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        val activity = window.get()
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(activity)
        try {
            service.resolveStream = { _, _ -> SongStream("asset:///mock-track.mp3") }
            service.relatedVideos = { _, _ ->
                (1..20).map { song("track" + it.toString().padStart(6, '0'), "Track $it (Official Video)", "Fixture Artist") }
            }
            service.select(first)
            await { service.suggestedSongs().size == 20 }
            val screen = field("screen") as MusicScreen
            screen.suggestionsButton.performClick()
            val list = field("suggestionsList") as android.widget.LinearLayout
            val scroll = list.parent as android.widget.ScrollView
            val width = View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY)
            val height = View.MeasureSpec.makeMeasureSpec(scroll.layoutParams.height, View.MeasureSpec.EXACTLY)
            scroll.measure(width, height)
            scroll.layout(0, 0, scroll.measuredWidth, scroll.measuredHeight)
            assertTrue(scroll.canScrollVertically(1))
            scroll.scrollTo(0, list.height)
            assertTrue(scroll.scrollY > 0)
            assertTrue(list.getChildAt(19).bottom <= scroll.scrollY + scroll.height)
            assertTrue(labels(list.getChildAt(19)).contains("20. Track 20 (Official Video)"))
        } finally {
            window.pause().stop().destroy()
            controller.destroy()
        }
    }

    @Test fun fillsShortRelatedQueuesWithoutRepeatingTracks() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()
        try {
            service.resolveStream = { _, _ -> SongStream("asset:///mock-track.mp3") }
            service.relatedVideos = { _, _ -> listOf(first, recommended, recommended) }
            val searches = java.util.concurrent.atomic.AtomicInteger()
            service.searchRecommendations = { _, _ ->
                val round = searches.getAndIncrement()
                listOf(first, recommended) + (1..7).map {
                    val number = round * 7 + it
                    song("track" + number.toString().padStart(6, '0'),
                        "Track $number (Official Video)", "FixtureArtistVEVO")
                }
            }
            service.select(first)
            await { service.suggestedSongs().size == 20 }
            val ids = service.suggestedSongs().map { it.videoId }
            assertEquals(20, ids.distinct().size)
            assertFalse(first.videoId in ids)
            assertEquals(3, searches.get())
            assertEquals(recommended, service.nextSong)
        } finally { controller.destroy() }
    }

    @Test fun keepsMatchingLanguageAndDropsRemixes() {
        val seed = song("AAAAAAAAAAA", "First (Hindi Song)", "Singer")
        val matching = song("BBBBBBBBBBB", "Second (Hindi Song) Official Video", "Another singer")
        val different = song("CCCCCCCCCCC", "Third (English Song) Official Video", "Singer")
        val remix = song("DDDDDDDDDDD", "Fourth (Hindi Song) Official Video Remix", "Singer")
        val unknown = song("EEEEEEEEEEE", "Fifth (Official Video)", "Another singer")
        val recommendations = Recommendations()
        recommendations.feedback(seed, different, 1.0)
        assertEquals(listOf(matching), recommendations.rank(seed, listOf(different, remix, unknown, matching)))
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
