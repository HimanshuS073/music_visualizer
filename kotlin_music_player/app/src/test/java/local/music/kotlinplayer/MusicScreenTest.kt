package local.music.kotlinplayer

import android.app.Activity
import android.graphics.*
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.sin

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MusicScreenTest {
    private fun children(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()
    private fun layout(screen: MusicScreen, width: Int = 393, height: Int = 852) {
        repeat(4) {
            children(screen.root).forEach { it.forceLayout() }
            screen.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            screen.root.layout(0, 0, width, height)
            screen.fitPlayerHeight()
        }
    }
    private fun makeScreen(activity: Activity) = MusicScreen(activity, {}, {}, {}, {}, {}, {})
    private fun screenshot(screen: MusicScreen, name: String) {
        val directory = System.getProperty("music.previewDir").orEmpty()
        if (directory.isBlank()) return
        val file = File(directory, "$name.png").apply { parentFile!!.mkdirs() }
        val bitmap = Bitmap.createBitmap(screen.root.width, screen.root.height, Bitmap.Config.ARGB_8888)
        screen.root.draw(Canvas(bitmap))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun fillResults(screen: MusicScreen, activity: Activity) {
        val songs = listOf(
            Song("Midnight City", "M83", "https://example.invalid/1", 244, "", ""),
            Song("Toca Toca", "Fly Project", "https://example.invalid/2", 170, "", ""),
            Song("Space Song", "Beach House", "https://example.invalid/3", 320, "", ""),
            Song("Way Down We Go", "KALEO", "https://example.invalid/4", 219, "", ""),
            Song("Let It Happen", "Tame Impala", "https://example.invalid/5", 467, "", "")
        )
        val art = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        Canvas(art).apply {
            drawColor(Color.rgb(27, 60, 62))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, 0f, 128f, 128f, intArrayOf(0xFFE7BF91.toInt(), 0xFF6AAE94.toInt(), 0xFF204750.toInt()), null, Shader.TileMode.CLAMP)
            }
            drawCircle(64f, 66f, 50f, paint)
            paint.shader = null; paint.color = 0xFF183537.toInt()
            drawRect(0f, 87f, 128f, 128f, paint)
            paint.color = 0xFFB9D2BA.toInt(); paint.textSize = 9f; paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            drawText("AFTER HOURS", 23f, 111f, paint)
        }
        screen.list.adapter = object : BaseAdapter() {
            override fun getCount() = songs.size
            override fun getItem(position: Int) = songs[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
                val row = (recycled?.tag as? SongRow) ?: SongRow(activity)
                row.bind(songs[position], position == 0)
                row.setPicture(if (position == 0) art else null)
                return row.root
            }
        }
        screen.showResults(songs.size)
        screen.query.setText("midnight city")
        screen.message.text = "A few good finds. Pick your next track."
        screen.trackTitle.text = songs[0].title
        screen.trackArtist.text = songs[0].artist
        screen.setCover(art)
        screen.setTrackAvailable(true)
        screen.setPlaying(true)
        screen.updateTimeline(65000, 244000, true)
        val field = SpectrumView::class.java.getDeclaredField("targets").apply { isAccessible = true }
        val values = field.get(screen.spectrum) as FloatArray
        for (i in values.indices) values[i] = (.15 + .75 * kotlin.math.abs(sin(i * .39)) * (1 - i / 180.0)).toFloat()
        screen.spectrum.setPlaying(true)
        screen.spectrum.setPalette(intArrayOf(RoomStyle.mint, RoomStyle.peach, 0xFF8CB9AE.toInt()))
    }

    @Test fun searchButtonAndKeyboardSubmitTheInput() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        try {
            var searches = 0
            val screen = MusicScreen(controller.get(), { searches++ }, {}, {}, {}, {}, {})
            screen.query.setText("Toca Toca")
            children(screen.root).single { it.contentDescription == "Search" }.performClick()
            screen.query.onEditorAction(EditorInfo.IME_ACTION_SEARCH)
            assertEquals(2, searches)
            assertEquals("Toca Toca", screen.query.text.toString())
        } finally { controller.destroy() }
    }
    @Test fun transportAndAccessibleSliderCallTheRightActions() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        try {
            var toggles = 0
            var fraction = -1f
            val seeks = mutableListOf<Long>()
            val screen = MusicScreen(controller.get(), {}, {}, { toggles++ }, { seeks.add(it) }, { fraction = it }, {})
            screen.setTrackAvailable(true)
            screen.updateTimeline(0, 200000, true)
            layout(screen)
            screen.pause.performClick()
            children(screen.root).single { it.contentDescription == "Back 10 seconds" }.performClick()
            children(screen.root).single { it.contentDescription == "Forward 10 seconds" }.performClick()
            assertEquals(1, toggles)
            assertEquals(listOf(-10000L, 10000L), seeks)
            screen.seekBar.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 500f) })
            assertEquals(.5f, fraction, .001f)
        } finally { controller.destroy() }
    }
    @Test fun timelineHandlesUnknownDurationAndKeepsClockAccurate() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        try {
            val screen = makeScreen(controller.get())
            screen.updateTimeline(65000, 200000, true)
            assertEquals(325, screen.seekBar.progress)
            assertTrue(screen.seekBar.isEnabled)
            val labels = children(screen.root).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue("1:05" in labels && "3:20" in labels)
            screen.updateTimeline(0, -1, false)
            assertFalse(screen.seekBar.isEnabled)
            assertEquals(0, screen.seekBar.progress)
        } finally { controller.destroy() }
    }
    @Test fun nativePortraitAndLandscapeLayoutsKeepPlayerVisible() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        try {
            val screen = makeScreen(controller.get())
            controller.get().setContentView(screen.root)
            fillResults(screen, controller.get())
            for ((width, height) in listOf(320 to 640, 393 to 852, 800 to 400)) {
                layout(screen, width, height)
                val box = Rect(0, 0, screen.pause.width, screen.pause.height)
                screen.root.offsetDescendantRectToMyCoords(screen.pause, box)
                assertTrue("Player outside ${width}x$height: $box", box.left >= 0 && box.top >= 0 && box.right <= width && box.bottom <= height)
                assertTrue(screen.pause.width >= 48 && screen.pause.height >= 48)
                assertTrue("No room for results at $width", screen.list.height >= 86)
                screenshot(screen, "music-room-${width}x$height")
            }
        } finally { controller.destroy() }
    }
    @Test fun hidingControlsMovesBarsWithoutChangingTheirHeight() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        try {
            val screen = makeScreen(controller.get())
            layout(screen)
            val height = screen.spectrum.height
            val shownY = screen.spectrum.y
            screen.positionControls(0f)
            assertEquals(height, screen.spectrum.height)
            assertTrue(screen.spectrum.y > shownY)
            assertEquals(View.INVISIBLE, screen.controls.visibility)
            screen.positionControls(.5f)
            assertTrue(screen.spectrum.y > shownY && screen.spectrum.y < screen.playbackArea.height - height)
            screen.positionControls(1f)
            assertEquals(height, screen.spectrum.height)
            assertEquals(shownY, screen.spectrum.y, .1f)
        } finally { controller.destroy() }
    }
    @Test fun keyboardFreesThePlayerSpaceForSearchResults() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        try {
            val screen = makeScreen(controller.get())
            fillResults(screen, controller.get())
            layout(screen)
            val before = screen.list.height
            screen.configureViewport(393, 852, true)
            layout(screen)
            assertEquals(View.GONE, screen.playbackArea.visibility)
            assertTrue(screen.list.height > before)
            screen.configureViewport(393, 852, false)
            layout(screen)
            assertEquals(View.VISIBLE, screen.playbackArea.visibility)
        } finally { controller.destroy() }
    }
    @Test fun selectingTrackHidesResultsUntilAnExplicitNewSearch() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        try {
            val screen = makeScreen(controller.get())
            fillResults(screen, controller.get())
            layout(screen)
            val oldPlayerHeight = screen.playbackArea.height
            screen.setResultsHidden(true)
            layout(screen)
            assertTrue(screen.resultsHidden)
            val library = (screen.list.parent as View).parent as View
            assertEquals(View.GONE, library.visibility)
            assertTrue(screen.playbackArea.height > oldPlayerHeight)
            assertEquals(5, screen.list.adapter.count)
            screen.setPlaying(false)
            screen.showResults(5)
            screen.showSearchError()
            assertEquals(View.GONE, library.visibility)
            screenshot(screen, "music-room-listening")
            screen.showSearching("another song")
            layout(screen)
            assertFalse(screen.resultsHidden)
            assertEquals(View.VISIBLE, library.visibility)
            screen.showResults(5)
            assertFalse(screen.resultsHidden)
        } finally { controller.destroy() }
    }
    @Test fun hiddenResultsStayHiddenWhenResizingOrUsingTheKeyboard() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        try {
            val screen = makeScreen(controller.get())
            fillResults(screen, controller.get())
            screen.setResultsHidden(true)
            for ((width, height) in listOf(320 to 640, 393 to 852, 800 to 400)) {
                layout(screen, width, height)
                assertTrue(screen.resultsHidden)
                assertTrue(screen.playbackArea.width >= width - 50)
                assertTrue(screen.pause.isEnabled)
                val barsHeight = screen.spectrum.height
                screen.positionControls(0f)
                assertEquals(barsHeight, screen.spectrum.height)
                screen.positionControls(1f)
                screenshot(screen, "music-room-listening-${width}x$height")
            }
            screen.configureViewport(800, 400, true)
            layout(screen, 800, 400)
            assertTrue(screen.resultsHidden)
            assertEquals(View.GONE, screen.playbackArea.visibility)
            screen.configureViewport(800, 400, false)
            layout(screen, 800, 400)
            assertEquals(View.VISIBLE, screen.playbackArea.visibility)
            assertTrue(screen.resultsHidden)
        } finally { controller.destroy() }
    }
    @Test fun customVisualizerHeightPersistsAcrossLayoutsAndControls() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        try {
            val screen = makeScreen(controller.get())
            fillResults(screen, controller.get())
            screen.setResultsHidden(true)
            layout(screen)
            screen.setVisualizerHeight(160)
            layout(screen)
            assertEquals(160, screen.visualizerHeightDp)
            assertEquals(160, screen.spectrum.height)
            screen.positionControls(0f)
            assertEquals(160, screen.spectrum.height)
            screen.positionControls(1f)
            layout(screen, 800, 400)
            assertEquals(160, screen.visualizerHeightDp)
            assertTrue(screen.spectrum.height in 16..160)
            layout(screen)
            assertEquals(160, screen.spectrum.height)
            screenshot(screen, "music-room-tall-bars")
        } finally { controller.destroy() }
    }
    @Test fun heightLimitsLeaveRoomForSearchResultsAndControls() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        try {
            val screen = makeScreen(controller.get())
            fillResults(screen, controller.get())
            screen.setVisualizerHeight(5000)
            layout(screen, 320, 640)
            assertEquals(320, screen.visualizerHeightDp)
            assertTrue(screen.list.height >= 86)
            assertTrue(screen.spectrum.height in 16..320)
            screen.setVisualizerHeight(-2)
            layout(screen)
            assertEquals(24, screen.visualizerHeightDp)
            assertEquals(24, screen.spectrum.height)
        } finally { controller.destroy() }
    }
    @Test fun videoBackgroundWaitsForRealFramesAndCanReturnToCover() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        try {
            val screen = makeScreen(controller.get())
            fillResults(screen, controller.get())
            screen.setResultsHidden(true)
            screen.setVisualizerHeight(160)
            val still = (screen.background.drawable as android.graphics.drawable.BitmapDrawable).bitmap
            screen.setVideoBackgroundEnabled(true)
            assertFalse(screen.wantsVideoFrame)
            val early = Bitmap.createBitmap(96, 54, Bitmap.Config.ARGB_8888)
            screen.showVideoFrame(early)
            assertTrue(early.isRecycled)
            screen.markVideoReady()
            assertTrue(screen.wantsVideoFrame)
            val frame = Bitmap.createBitmap(96, 54, Bitmap.Config.ARGB_8888)
            Canvas(frame).drawRect(0f, 0f, 96f, 54f, Paint().apply {
                shader = LinearGradient(0f, 0f, 96f, 54f,
                    intArrayOf(0xFFDA7462.toInt(), 0xFF6FADAA.toInt(), 0xFF9964B7.toInt()), null, Shader.TileMode.CLAMP)
            })
            screen.showVideoFrame(frame)
            assertFalse(screen.wantsVideoFrame)
            assertSame(frame, (screen.background.drawable as android.graphics.drawable.BitmapDrawable).bitmap)
            layout(screen)
            screenshot(screen, "music-room-video-background")
            screen.setVideoBackgroundEnabled(false)
            assertSame(still, (screen.background.drawable as android.graphics.drawable.BitmapDrawable).bitmap)
        } finally { controller.destroy() }
    }
    @Test fun rendersEmptyAndLargeTextStates() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        try {
            val activity = controller.get()
            val screen = makeScreen(activity)
            screen.list.adapter = object : BaseAdapter() {
                override fun getCount() = 0
                override fun getItem(position: Int) = Unit
                override fun getItemId(position: Int) = 0L
                override fun getView(position: Int, recycled: View?, parent: ViewGroup) = View(activity)
            }
            layout(screen)
            assertFalse(screen.pause.isEnabled)
            screenshot(screen, "music-room-empty")
            @Suppress("DEPRECATION")
            run { activity.resources.displayMetrics.scaledDensity = 1.5f * activity.resources.displayMetrics.density }
            val large = makeScreen(activity)
            fillResults(large, activity)
            layout(large)
            assertTrue(large.controls.height > 0 && large.list.height >= 86)
            screenshot(large, "music-room-large-text")
        } finally { controller.destroy() }
    }
}
