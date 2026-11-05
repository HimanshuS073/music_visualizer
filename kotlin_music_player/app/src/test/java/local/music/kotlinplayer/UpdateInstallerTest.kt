package local.music.kotlinplayer

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UpdateInstallerTest {
    private fun app(code: Int, name: String = "local.music.kotlinplayer", key: Byte = 1) = PackageInfo().apply {
        packageName = name
        versionCode = code
        signatures = arrayOf(Signature(byteArrayOf(key, 2, 3)))
        applicationInfo = ApplicationInfo().apply { minSdkVersion = 26 }
    }
    @Test fun acceptsSameApplicationAndKeyWithHigherVersion() {
        UpdateInstaller.requireCompatible(app(3), app(4), 4)
    }
    @Test fun rejectsAnotherPackage() {
        assertThrows(IllegalArgumentException::class.java) {
            UpdateInstaller.requireCompatible(app(3), app(4, "another.application"), 4)
        }
    }
    @Test fun rejectsDowngradesSameVersionAndWrongReleaseCode() {
        for ((actual, expected) in listOf(2 to 2L, 3 to 3L, 4 to 5L)) {
            assertThrows(IllegalArgumentException::class.java) {
                UpdateInstaller.requireCompatible(app(3), app(actual), expected)
            }
        }
    }
    @Test fun rejectsDifferentAndMissingSigningKeys() {
        assertThrows(IllegalArgumentException::class.java) {
            UpdateInstaller.requireCompatible(app(3), app(4, key = 9), 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpdateInstaller.requireCompatible(app(3), app(4).apply { signatures = null }, 4)
        }
    }
    @Test fun rejectsUnsupportedAndroidVersion() {
        assertThrows(IllegalArgumentException::class.java) {
            UpdateInstaller.requireCompatible(app(3), app(4).apply { applicationInfo!!.minSdkVersion = 29 }, 4)
        }
    }
    @Test fun permissionIntentOnlyOpensThisAppsInstallSettings() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        try {
            val intent = UpdateInstaller.permissionIntent(controller.get())
            assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
            assertEquals("package:local.music.kotlinplayer", intent.data.toString())
        } finally { controller.destroy() }
    }
    @Test fun installerSharesOnlyUpdateCacheWithReadPermission() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        try {
            val activity = controller.get()
            val apk = File(activity.cacheDir, "updates/music-room-v4.apk").apply { parentFile!!.mkdirs(); writeText("test") }
            val intent = UpdateInstaller.installIntent(activity, apk)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("content", intent.data!!.scheme)
            assertEquals("local.music.kotlinplayer.updates", intent.data!!.authority)
            assertEquals("application/vnd.android.package-archive", intent.type)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(intent.data, intent.clipData!!.getItemAt(0).uri)
            assertThrows(IllegalArgumentException::class.java) {
                UpdateInstaller.installIntent(activity, File(activity.filesDir, "private-file"))
            }
        } finally { controller.destroy() }
    }
}
