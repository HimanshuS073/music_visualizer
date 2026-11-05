package local.music.kotlinplayer

import android.graphics.*
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.InsetDrawable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherIconTest {
    private fun icon(): AdaptiveIconDrawable {
        val app = RuntimeEnvironment.getApplication()
        return app.applicationInfo.loadIcon(app.packageManager) as AdaptiveIconDrawable
    }
    private fun render(shape: String, size: Int = 512): Bitmap {
        val drawable = icon()
        drawable.setBounds(0, 0, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val path = Path()
        when (shape) {
            "circle" -> path.addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW)
            "rounded-square" -> path.addRoundRect(0f, 0f, size.toFloat(), size.toFloat(), size * .22f, size * .22f, Path.Direction.CW)
            else -> path.addRect(0f, 0f, size.toFloat(), size.toFloat(), Path.Direction.CW)
        }
        canvas.clipPath(path)
        drawable.background.draw(canvas)
        drawable.foreground.draw(canvas)
        return bitmap
    }
    private fun ink(bitmap: Bitmap): Int {
        var count = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val color = bitmap.getPixel(x, y)
            if (Color.alpha(color) > 200 && maxOf(Color.red(color), Color.green(color), Color.blue(color)) > 70) count++
        }
        return count
    }
    @Test fun manifestIconContainsTheProvidedLandscapeLogo() {
        val app = RuntimeEnvironment.getApplication()
        assertEquals(R.mipmap.ic_launcher, app.applicationInfo.icon)
        val foreground = icon().foreground as InsetDrawable
        val bitmap = (foreground.drawable as BitmapDrawable).bitmap
        assertEquals(1536, bitmap.width)
        assertEquals(1024, bitmap.height)
    }
    @Test fun circleAndRoundedSquareKeepAllVisibleArtwork() {
        val full = ink(render("square"))
        assertTrue("Logo was not rendered", full > 5000)
        for (shape in listOf("circle", "rounded-square")) {
            val bitmap = render(shape)
            assertEquals("Logo cropped by $shape", full.toDouble(), ink(bitmap).toDouble(), full * .005)
            val directory = System.getProperty("music.previewDir").orEmpty()
            if (directory.isNotBlank()) {
                val file = File(directory, "music-room-launcher-$shape.png").apply { parentFile!!.mkdirs() }
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            assertTrue("Small icon is blank", ink(render(shape, 48)) > 25)
        }
    }
}
