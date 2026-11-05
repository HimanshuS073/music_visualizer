package local.music.kotlinplayer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoBackdropTest {
    private fun frame(color: Int) = Bitmap.createBitmap(96, 54, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    @Test fun blurKeepsConstantFramesAndSpreadsAnEdge() {
        val blur = VideoFrameBlur()
        val solid = frame(Color.RED)
        blur.apply(solid)
        assertEquals(Color.RED, solid.getPixel(48, 27))
        val edge = frame(Color.BLACK)
        for (y in 0 until 54) for (x in 48 until 96) edge.setPixel(x, y, Color.WHITE)
        blur.apply(edge)
        assertTrue(Color.red(edge.getPixel(47, 27)) in 1..254)
        assertTrue(Color.red(edge.getPixel(48, 27)) in 1..254)
        assertEquals(Color.BLACK, edge.getPixel(0, 27))
        assertEquals(Color.WHITE, edge.getPixel(95, 27))
    }
    @Test fun blurRejectsLargeFrames() {
        val large = Bitmap.createBitmap(192, 108, Bitmap.Config.ARGB_8888)
        assertThrows(IllegalArgumentException::class.java) { VideoFrameBlur().apply(large) }
    }
    @Test fun togglingOffRestoresCoverAndNewFramesReplaceOldOnes() {
        val image = ImageView(RuntimeEnvironment.getApplication())
        val backdrop = VideoBackdrop(image)
        val cover = frame(Color.GREEN)
        backdrop.setCover(cover)
        assertFalse(backdrop.enabled)
        assertSame(cover, (image.drawable as BitmapDrawable).bitmap)
        backdrop.setEnabled(true)
        assertTrue(backdrop.needsFrame)
        val red = frame(Color.RED)
        backdrop.showFrame(red)
        assertFalse(backdrop.needsFrame)
        assertSame(red, (image.drawable as BitmapDrawable).bitmap)
        val blue = frame(Color.BLUE)
        backdrop.showFrame(blue)
        assertSame(blue, (image.drawable as BitmapDrawable).bitmap)
        assertFalse(red.isRecycled)
        backdrop.setEnabled(false)
        assertSame(cover, (image.drawable as BitmapDrawable).bitmap)
        assertEquals(.19f, image.alpha, .01f)
    }
    @Test fun changingTracksClearsPreviousVideoAndWaitsForNewFrame() {
        val image = ImageView(RuntimeEnvironment.getApplication())
        val backdrop = VideoBackdrop(image)
        backdrop.setEnabled(true)
        backdrop.showFrame(frame(Color.RED))
        backdrop.setCover(null)
        assertNull(image.drawable)
        assertTrue(backdrop.needsFrame)
        val cover = frame(Color.GREEN)
        backdrop.setCover(cover)
        assertSame(cover, (image.drawable as BitmapDrawable).bitmap)
    }
    @Test fun backgroundFramesAreBlurredAndUseDimColorFilter() {
        val image = ImageView(RuntimeEnvironment.getApplication())
        val backdrop = VideoBackdrop(image)
        backdrop.setEnabled(true)
        backdrop.showFrame(frame(Color.WHITE))
        assertNotNull(image.colorFilter)
        assertEquals(.6f, image.alpha, .01f)
        assertFalse(backdrop.needsFrame)
    }
    @Test fun disabledBackgroundDoesNotKeepVideoFrames() {
        val image = ImageView(RuntimeEnvironment.getApplication())
        val backdrop = VideoBackdrop(image)
        val value = frame(Color.BLUE)
        backdrop.showFrame(value)
        assertTrue(value.isRecycled)
        assertNull(image.drawable)
    }
}
