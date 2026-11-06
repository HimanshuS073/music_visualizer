package local.music.kotlinplayer

import android.content.ComponentName
import android.content.Intent
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlaybackServiceTest {
    @Before fun keepLifecycleTestsOffline() {
        UpdateManager::class.java.getDeclaredField("lastAutomaticCheck").apply { isAccessible = true }
            .setLong(null, android.os.SystemClock.elapsedRealtime())
    }

    @Test fun sessionAndLocalBindingUseTheSamePlayer() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()
        try {
            val binder = service.onBind(Intent().setAction(PlaybackService.LOCAL_BIND)) as PlaybackService.LocalBinder
            assertSame(service, binder.service)
            assertEquals(1, service.sessions.size)
            assertSame(service.player, service.sessions.single().player)
            assertEquals("Music Room", service.applicationInfo.loadLabel(service.packageManager).toString())
        } finally { controller.destroy() }
    }

    @Test fun hidingAndDestroyingWindowKeepsPlaybackOwnedByService() {
        val serviceController = Robolectric.buildService(PlaybackService::class.java).create()
        val service = serviceController.get()
        shadowOf(RuntimeEnvironment.getApplication()).setComponentNameAndServiceForBindService(
            ComponentName(service, PlaybackService::class.java),
            service.onBind(Intent().setAction(PlaybackService.LOCAL_BIND))!!)
        val window = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        val activity = window.get()
        try {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            service.player.setMediaItem(MediaItem.fromUri("https://example.invalid/test.mp3"))
            service.player.playWhenReady = true
            val power = service.getSystemService(android.os.PowerManager::class.java)
            shadowOf(power).setIsInteractive(false)
            service.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
            window.pause().stop()
            assertFalse(power.isInteractive)
            assertTrue(service.player.playWhenReady)
            assertEquals(1, service.player.mediaItemCount)
            assertTrue(service.player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO))
            window.destroy()
            assertTrue(service.player.playWhenReady)
            assertEquals(1, service.player.mediaItemCount)
        } finally { serviceController.destroy() }
    }

    @Test fun windowRecreationPreservesWhetherResultsWereHidden() {
        val serviceController = Robolectric.buildService(PlaybackService::class.java).create()
        val service = serviceController.get()
        shadowOf(RuntimeEnvironment.getApplication()).setComponentNameAndServiceForBindService(
            ComponentName(service, PlaybackService::class.java),
            service.onBind(Intent().setAction(PlaybackService.LOCAL_BIND))!!)
        fun screen(activity: MainActivity) = MainActivity::class.java.getDeclaredField("screen")
            .apply { isAccessible = true }.get(activity) as MusicScreen
        val first = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            screen(first.get()).setResultsHidden(true)
            val state = android.os.Bundle()
            first.saveInstanceState(state).pause().stop().destroy()
            assertTrue(state.getBoolean("results_hidden"))
            val second = Robolectric.buildActivity(MainActivity::class.java).create(state).start().resume()
            try {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                assertTrue(screen(second.get()).resultsHidden)
                screen(second.get()).showSearching("new song")
                val nextState = android.os.Bundle()
                second.saveInstanceState(nextState)
                assertFalse(nextState.getBoolean("results_hidden"))
            } finally { second.pause().stop().destroy() }
        } finally { serviceController.destroy() }
    }

    @Test fun removingTaskStopsPlaybackAndServiceWithoutRestart() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()
        try {
            val preparation = PlaybackService::class.java.getDeclaredField("preparationLock")
                .apply { isAccessible = true }.get(service) as StreamPreparationLock
            preparation.acquire()
            val lock = StreamPreparationLock::class.java.getDeclaredField("lock")
                .apply { isAccessible = true }.get(preparation) as android.os.PowerManager.WakeLock
            assertTrue(lock.isHeld)
            service.player.setMediaItem(MediaItem.fromUri("https://example.invalid/test.mp3"))
            service.player.playWhenReady = true
            service.onTaskRemoved(null)
            assertFalse(lock.isHeld)
            assertFalse(service.player.playWhenReady)
            assertFalse(service.desiredPlaying)
            assertEquals(0, service.player.mediaItemCount)
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        } finally { controller.destroy() }
    }

    @Test fun pausingDuringLoadingAndDestroyingServiceReleasePreparationLock() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()
        fun setField(name: String, value: Any) {
            PlaybackService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
        }
        val preparation = PlaybackService::class.java.getDeclaredField("preparationLock")
            .apply { isAccessible = true }.get(service) as StreamPreparationLock
        val lock = StreamPreparationLock::class.java.getDeclaredField("lock")
            .apply { isAccessible = true }.get(preparation) as android.os.PowerManager.WakeLock
        try {
            setField("song", Song("Song", "Artist", "https://example.invalid/song", 120, "", ""))
            setField("loading", true)
            setField("desiredPlaying", true)
            preparation.acquire()
            service.toggle()
            assertFalse(service.desiredPlaying)
            assertFalse(lock.isHeld)
            service.toggle()
            assertTrue(service.desiredPlaying)
            assertTrue(lock.isHeld)
        } finally { controller.destroy() }
        assertFalse(lock.isHeld)
    }
}
