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
            window.pause().stop()
            assertTrue(service.player.playWhenReady)
            assertEquals(1, service.player.mediaItemCount)
            assertTrue(service.player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO))
            window.destroy()
            assertTrue(service.player.playWhenReady)
            assertEquals(1, service.player.mediaItemCount)
        } finally { serviceController.destroy() }
    }

    @Test fun removingTaskStopsPlaybackAndServiceWithoutRestart() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()
        try {
            service.player.setMediaItem(MediaItem.fromUri("https://example.invalid/test.mp3"))
            service.player.playWhenReady = true
            service.onTaskRemoved(null)
            assertFalse(service.player.playWhenReady)
            assertFalse(service.desiredPlaying)
            assertEquals(0, service.player.mediaItemCount)
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        } finally { controller.destroy() }
    }
}
