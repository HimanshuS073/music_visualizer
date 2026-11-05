package local.music.kotlinplayer

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Switch
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MainSettingsTest {
    @Before fun keepTestsOffline() {
        UpdateManager::class.java.getDeclaredField("lastAutomaticCheck").apply { isAccessible = true }
            .setLong(null, android.os.SystemClock.elapsedRealtime())
    }
    private fun children(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()
    private fun screen(activity: MainActivity) = MainActivity::class.java.getDeclaredField("screen")
        .apply { isAccessible = true }.get(activity) as MusicScreen
    private fun withWindow(block: (MainActivity) -> Unit) {
        val service = Robolectric.buildService(PlaybackService::class.java).create()
        shadowOf(RuntimeEnvironment.getApplication()).setComponentNameAndServiceForBindService(
            ComponentName(service.get(), PlaybackService::class.java),
            service.get().onBind(Intent().setAction(PlaybackService.LOCAL_BIND))!!)
        val window = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try { shadowOf(android.os.Looper.getMainLooper()).idle(); block(window.get()) }
        finally { window.pause().stop().destroy(); service.destroy() }
    }
    private fun openSettings(activity: MainActivity): AlertDialog {
        MainActivity::class.java.getDeclaredMethod("showSettings").apply { isAccessible = true }.invoke(activity)
        return ShadowAlertDialog.getLatestAlertDialog()
    }
    @Test fun savesAndRestoresHeightWithoutVideoBackgroundOption() {
        withWindow { activity ->
            val dialog = openSettings(activity)
            val views = children(dialog.window!!.decorView)
            assertTrue(views.filterIsInstance<Switch>().isEmpty())
            views.filterIsInstance<EditText>().single { it.contentDescription.toString().startsWith("Visualizer height") }.setText("160")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertFalse(dialog.isShowing)
            assertEquals(160, screen(activity).visualizerHeightDp)
            val prefs = activity.getSharedPreferences("music", 0)
            assertEquals(160, prefs.getInt("visualizer_height", 0))
        }
        withWindow { activity ->
            assertEquals(160, screen(activity).visualizerHeightDp)
        }
    }
    @Test fun invalidHeightKeepsSettingsOpenWithoutSavingChanges() = withWindow { activity ->
        val dialog = openSettings(activity)
        val field = children(dialog.window!!.decorView).filterIsInstance<EditText>()
            .single { it.contentDescription.toString().startsWith("Visualizer height") }
        field.setText("9999")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing)
        assertNotNull(field.error)
        assertEquals(72, screen(activity).visualizerHeightDp)
        assertFalse(activity.getSharedPreferences("music", 0).contains("visualizer_height"))
    }
}
