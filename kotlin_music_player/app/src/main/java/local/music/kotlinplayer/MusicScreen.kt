// SPDX-FileCopyrightText: 2026 Himanshu_Sharma (HimanshuS073)
// SPDX-License-Identifier: GPL-3.0-or-later

package local.music.kotlinplayer

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.util.UnstableApi
import java.util.Locale
import kotlin.math.max

@UnstableApi
class MusicScreen(
    private val activity: Activity,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    private val onScrub: (Float) -> Unit,
    private val onInteraction: () -> Unit,
) {
    val root = FrameLayout(activity)
    val background = ImageView(activity)
    val query = EditText(activity)
    val message = text("Find something that sounds like you.", 12, RoomStyle.muted)
    val list = ListView(activity)
    val playbackArea = FrameLayout(activity)
    val spectrum = SpectrumView(activity)
    val controls = LinearLayout(activity)
    val cover = ImageView(activity)
    val video = TextureView(activity)
    val trackTitle = text("Nothing playing yet", 15, medium = true)
    val trackArtist = text("Pick a song and settle in.", 12, RoomStyle.muted)
    val pause = RoomIconButton(activity, RoomIcon.PLAY, "Play", RoomStyle.mint, RoomStyle.background) { onInteraction(); onToggle() }
    val seekBar = SeekBar(activity)
    private val back = RoomIconButton(activity, RoomIcon.BACK, "Back 10 seconds") { onInteraction(); onSeek(-10000) }
    private val forward = RoomIconButton(activity, RoomIcon.FORWARD, "Forward 10 seconds") { onInteraction(); onSeek(10000) }
    private val elapsed = text("0:00", 10, RoomStyle.muted)
    private val duration = text("−:−−", 10, RoomStyle.muted)
    private val content = LinearLayout(activity)
    private val body = LinearLayout(activity)
    private val library = LinearLayout(activity)
    private val eyebrow = text("YOUR LISTENING SPACE", 9, RoomStyle.peach, true)
    private val subtitle = text("Find your next favorite.", 13, RoomStyle.muted)
    private val sectionTitle = text("Discover", 17, medium = true)
    private val resultCount = text("", 11, RoomStyle.muted)
    private val empty = LinearLayout(activity)
    private val emptyTitle = text("A little space\nfor your music.", 23, medium = true)
    private val emptyHint = text("Search for a song or artist\nand make yourself at home.", 13, RoomStyle.muted)
    private val loading = ProgressBar(activity)
    private var lastTime = -1L
    private var lastDuration = Long.MIN_VALUE
    private var topInset = 0
    private var bottomInset = 0
    private var imeVisible = false
    private var layoutKey = ""
    private var reveal = 1f
    var seeking = false
        private set
    var spectrumHeight = 72
        private set
    var panelGap = 12
        private set

    init {
        root.setBackgroundColor(RoomStyle.background)
        root.isFocusableInTouchMode = true
        background.scaleType = ImageView.ScaleType.CENTER_CROP
        background.alpha = .19f
        background.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(.25f) })
        if (Build.VERSION.SDK_INT >= 31)
            background.setRenderEffect(RenderEffect.createBlurEffect(24f, 24f, Shader.TileMode.CLAMP))
        root.addView(background, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(activity).apply {
            this.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xF5131117.toInt(), 0xBB131117.toInt(), 0xF5131117.toInt()))
        }, FrameLayout.LayoutParams(-1, -1))
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(20), dp(18), dp(20), dp(14))
        root.addView(content, FrameLayout.LayoutParams(-1, -1))

        val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        val words = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        eyebrow.letterSpacing = .18f
        words.addView(eyebrow)
        words.addView(text("Music Room", 29, medium = true), vertical(-1, -2, top = 5))
        words.addView(subtitle, vertical(-1, -2, top = 5))
        header.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        val settings = RoomIconButton(activity, RoomIcon.SETTINGS, "Settings", RoomStyle.surface) { onSettings() }
        header.addView(settings, vertical(48, 48))
        content.addView(header)

        val searchBar = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = RoomStyle.shape(activity, RoomStyle.surface, 18, RoomStyle.border)
            setPadding(dp(14), dp(4), dp(4), dp(4))
        }
        searchBar.addView(ImageView(activity).apply {
            setImageDrawable(RoomIconDrawable(RoomIcon.SEARCH, RoomStyle.muted))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, vertical(21, 21))
        query.apply {
            setSingleLine()
            textSize = 15f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(RoomStyle.text)
            setHintTextColor(RoomStyle.muted)
            hint = "Search a song or artist"
            contentDescription = "Song or artist to search"
            background = null
            setPadding(dp(12), 0, dp(5), 0)
            minimumWidth = 0
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setOnEditorActionListener { _, action, event ->
                if (action == EditorInfo.IME_ACTION_SEARCH ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)) { onSearch(); true } else false
            }
            setOnFocusChangeListener { _, focused ->
                searchBar.background = RoomStyle.shape(activity, RoomStyle.surface, 18,
                    if (focused) RoomStyle.mint else RoomStyle.border)
            }
        }
        searchBar.addView(query, LinearLayout.LayoutParams(0, -1, 1f))
        searchBar.addView(RoomIconButton(activity, RoomIcon.ARROW, "Search", RoomStyle.mint, RoomStyle.background) { onSearch() }, vertical(48, 48))
        content.addView(searchBar, vertical(-1, 56, top = 20))
        message.setSingleLine()
        message.ellipsize = TextUtils.TruncateAt.END
        content.addView(message, vertical(-1, -2, top = 9, bottom = 5))

        content.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        library.orientation = LinearLayout.VERTICAL
        body.addView(library)
        val section = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        section.addView(sectionTitle, LinearLayout.LayoutParams(0, dp(42), 1f))
        sectionTitle.gravity = Gravity.CENTER_VERTICAL
        loading.indeterminateTintList = ColorStateList.valueOf(RoomStyle.mint)
        loading.visibility = View.GONE
        section.addView(loading, vertical(18, 18))
        section.addView(resultCount, vertical(-2, -2, left = 10))
        library.addView(section)
        val results = FrameLayout(activity)
        library.addView(results, LinearLayout.LayoutParams(-1, 0, 1f))
        list.divider = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        list.dividerHeight = dp(8)
        list.setSelector(android.R.color.transparent)
        list.clipToPadding = false
        list.setPadding(0, dp(2), 0, dp(12))
        results.addView(list, FrameLayout.LayoutParams(-1, -1))
        empty.orientation = LinearLayout.VERTICAL
        empty.gravity = Gravity.CENTER
        empty.setPadding(dp(16), dp(12), dp(16), dp(12))
        empty.addView(ImageView(activity).apply {
            setImageDrawable(RoomIconDrawable(RoomIcon.HEADPHONES, RoomStyle.peach))
            setPadding(dp(22), dp(22), dp(22), dp(22))
            background = RoomStyle.shape(activity, 0xFF2A222D.toInt(), 26, RoomStyle.border)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, vertical(82, 82, bottom = 21))
        emptyTitle.gravity = Gravity.CENTER
        emptyTitle.setLineSpacing(dp(3).toFloat(), 1f)
        empty.addView(emptyTitle)
        emptyHint.gravity = Gravity.CENTER
        emptyHint.setLineSpacing(dp(5).toFloat(), 1f)
        empty.addView(emptyHint, vertical(-1, -2, top = 12))
        results.addView(empty, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        list.emptyView = empty

        body.addView(playbackArea)
        playbackArea.addView(spectrum, FrameLayout.LayoutParams(-1, dp(spectrumHeight), Gravity.BOTTOM))
        controls.orientation = LinearLayout.VERTICAL
        controls.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF302834.toInt(), 0xFF201D26.toInt())).apply {
            cornerRadius = dp(24).toFloat()
            setStroke(dp(1), 0xFF49404E.toInt())
        }
        controls.setPadding(dp(14), dp(14), dp(14), dp(12))
        playbackArea.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        val track = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        val artwork = FrameLayout(activity)
        RoomStyle.round(artwork, 12)
        cover.scaleType = ImageView.ScaleType.CENTER_CROP
        cover.setImageDrawable(RoomIconDrawable(RoomIcon.NOTE, RoomStyle.peach))
        cover.setPadding(dp(12), dp(12), dp(12), dp(12))
        cover.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        artwork.addView(cover, FrameLayout.LayoutParams(-1, -1))
        // A tiny live preview also supplies the visualizer's video colors.
        video.alpha = .01f
        video.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        artwork.addView(video, FrameLayout.LayoutParams(dp(24), dp(14), Gravity.BOTTOM or Gravity.END))
        track.addView(artwork, vertical(52, 52))
        val info = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        trackTitle.maxLines = 2
        trackTitle.ellipsize = TextUtils.TruncateAt.END
        trackTitle.setLineSpacing(dp(2).toFloat(), 1f)
        trackArtist.setSingleLine()
        trackArtist.ellipsize = TextUtils.TruncateAt.END
        info.addView(trackTitle)
        info.addView(trackArtist, vertical(-1, -2, top = 5))
        track.addView(info, LinearLayout.LayoutParams(0, -2, 1f).apply {
            leftMargin = dp(12); rightMargin = dp(10)
        })
        track.addView(pause, vertical(54, 54))
        controls.addView(track)
        val transport = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        transport.addView(back, vertical(48, 48))
        seekBar.apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(RoomStyle.mint)
            progressBackgroundTintList = ColorStateList.valueOf(0xFF564C60.toInt())
            thumbTintList = ColorStateList.valueOf(RoomStyle.mint)
            splitTrack = false
            contentDescription = "Playback position"
            setPadding(dp(8), 0, dp(8), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) { seeking = true; onInteraction() }
                override fun onStopTrackingTouch(bar: SeekBar) { seeking = false; onScrub(bar.progress / 1000f); onInteraction() }
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    // Accessibility adjusts the slider without a touch gesture.
                    if (fromUser && !seeking) { onScrub(value / 1000f); onInteraction() }
                }
            })
        }
        transport.addView(seekBar, LinearLayout.LayoutParams(0, dp(48), 1f))
        transport.addView(forward, vertical(48, 48))
        controls.addView(transport, vertical(-1, 48, top = 6))
        val times = LinearLayout(activity)
        times.setPadding(dp(56), 0, dp(56), 0)
        times.addView(elapsed, LinearLayout.LayoutParams(0, -2, 1f))
        duration.gravity = Gravity.END
        times.addView(duration, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(times)
        controls.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            fitPlayerHeight()
            positionControls(reveal)
        }
        playbackArea.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionControls(reveal) }
        setTrackAvailable(false)
        updateTimeline(0, -1, false)
        root.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            configureViewport(r - l, b - t - topInset - bottomInset, imeVisible)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            topInset = bars.top
            bottomInset = max(bars.bottom, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            content.setPadding(bars.left + dp(20), topInset + dp(16), bars.right + dp(20), bottomInset + dp(12))
            configureViewport(root.width, root.height - topInset - bottomInset, imeVisible)
            insets
        }
        configureViewport(dp(393), dp(800), false)
        root.requestFocus()
    }

    fun configureViewport(width: Int, height: Int, keyboard: Boolean) {
        if (width <= 0 || height <= 0) return
        imeVisible = keyboard
        val landscape = width >= dp(600) && width > height && !keyboard
        val compact = height < dp(560)
        val key = "$landscape:$compact:$keyboard"
        if (layoutKey == key) return
        layoutKey = key
        eyebrow.visibility = if (compact) View.GONE else View.VISIBLE
        subtitle.visibility = if (compact) View.GONE else View.VISIBLE
        empty.getChildAt(0).visibility = if (compact || keyboard) View.GONE else View.VISIBLE
        emptyTitle.textSize = if (compact) 19f else 23f
        spectrumHeight = if (compact) 40 else 72
        panelGap = if (compact) 8 else 12
        spectrum.layoutParams.height = dp(spectrumHeight)
        body.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        library.layoutParams = if (landscape) LinearLayout.LayoutParams(0, -1, 1f)
            else LinearLayout.LayoutParams(-1, 0, 1f)
        playbackArea.visibility = if (keyboard) View.GONE else View.VISIBLE
        playbackArea.layoutParams = if (landscape)
            LinearLayout.LayoutParams(dp(290), -1).apply { leftMargin = dp(20) }
        else LinearLayout.LayoutParams(-1, dp(spectrumHeight + panelGap) + max(controls.height, dp(154)))
        body.requestLayout()
    }

    fun positionControls(value: Float) {
        reveal = value.coerceIn(0f, 1f)
        val height = controls.height.toFloat()
        spectrum.translationY = -(height + dp(panelGap)) * reveal
        controls.alpha = reveal
        controls.translationY = height * (1 - reveal)
        controls.visibility = if (reveal == 0f) View.INVISIBLE else View.VISIBLE
    }
    fun fitPlayerHeight() {
        val params = playbackArea.layoutParams
        if (body.orientation == LinearLayout.VERTICAL && params.height != controls.height + dp(spectrumHeight + panelGap)) {
            params.height = controls.height + dp(spectrumHeight + panelGap)
            playbackArea.layoutParams = params
        }
    }
    fun showSearching(name: String) {
        sectionTitle.text = "Searching"
        resultCount.text = ""
        emptyTitle.text = "Finding your sound…"
        emptyHint.text = "Looking for “$name”"
        loading.visibility = View.VISIBLE
    }
    fun showResults(count: Int) {
        sectionTitle.text = "Search results"
        resultCount.text = if (count == 1) "1 track" else "$count tracks"
        emptyTitle.text = "No matches this time."
        emptyHint.text = "Try another song title or artist."
        loading.visibility = View.GONE
    }
    fun showSearchError() {
        sectionTitle.text = "Search results"
        emptyTitle.text = "Let's try that again."
        emptyHint.text = "Check your connection, then search again."
        loading.visibility = View.GONE
    }
    fun setTrackAvailable(value: Boolean) {
        listOf(pause, back, forward).forEach { it.isEnabled = value; it.alpha = if (value) 1f else .4f }
    }
    fun setPlaying(value: Boolean) {
        pause.icon = if (value) RoomIcon.PAUSE else RoomIcon.PLAY
        pause.contentDescription = if (value) "Pause" else "Play"
    }
    fun setCover(bitmap: Bitmap?) {
        if (bitmap == null) {
            video.alpha = .01f
            cover.setPadding(dp(12), dp(12), dp(12), dp(12))
            cover.setImageDrawable(RoomIconDrawable(RoomIcon.NOTE, RoomStyle.peach))
        } else {
            cover.setPadding(0, 0, 0, 0)
            cover.setImageBitmap(bitmap)
        }
        background.setImageBitmap(bitmap)
    }
    fun updateTimeline(position: Long, length: Long, seekable: Boolean) {
        seekBar.isEnabled = seekable && length > 0
        seekBar.alpha = if (seekBar.isEnabled) 1f else .4f
        val second = position.coerceAtLeast(0) / 1000
        if (lastTime != second) { elapsed.text = time(position); lastTime = second }
        if (lastDuration != length) { duration.text = if (length > 0) time(length) else "−:−−"; lastDuration = length }
        if (!seeking) seekBar.progress = if (length > 0) (position.toDouble() / length * 1000).toInt().coerceIn(0, 1000) else 0
    }
    private fun text(value: String, size: Int, color: Int = RoomStyle.text, medium: Boolean = false) =
        RoomStyle.label(activity, value, size, color, medium)
    private fun dp(value: Int) = RoomStyle.dp(activity, value)
    private fun vertical(width: Int, height: Int, top: Int = 0, bottom: Int = 0, left: Int = 0) =
        LinearLayout.LayoutParams(if (width < 0) width else dp(width), if (height < 0) height else dp(height)).apply {
            topMargin = dp(top); bottomMargin = dp(bottom); leftMargin = dp(left)
        }
    private fun time(value: Long) = String.format(Locale.ROOT, "%d:%02d", value.coerceAtLeast(0) / 60000, value.coerceAtLeast(0) / 1000 % 60)
}
