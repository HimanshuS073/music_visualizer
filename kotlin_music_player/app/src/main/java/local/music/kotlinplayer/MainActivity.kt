// SPDX-FileCopyrightText: 2026 Himanshu_Sharma (HimanshuS073)
// SPDX-License-Identifier: GPL-3.0-or-later

package local.music.kotlinplayer

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.os.*
import android.text.InputType
import android.text.TextUtils
import android.util.LruCache
import android.view.*
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor

@UnstableApi
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val searches = Executors.newSingleThreadExecutor()
    private val workers = Executors.newFixedThreadPool(2)
    private val images = Executors.newFixedThreadPool(2) as ThreadPoolExecutor
    private var searchTask: Future<*>? = null
    private var searchJob = MusicSearch.Job()
    private var coverJob = MusicSearch.Job()
    private var imageJob = MusicSearch.Job()
    private var searchVersion = 0
    private var playVersion = 0
    private var closed = false
    private var active = false
    private var searching = false
    private var playingUrl = ""
    private val songs = mutableListOf<Song>()
    private val pendingImages = mutableSetOf<String>()
    private val failedImages = mutableSetOf<String>()
    private val cache = object : LruCache<String, Bitmap>(2 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private lateinit var player: ExoPlayer
    private var playback: PlaybackService? = null
    private var bound = false
    private var playerListener: Player.Listener? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (closed) return
            val service = (binder as PlaybackService.LocalBinder).service
            playback = service
            player = service.player
            connectPlayer()
            service.song?.let { song ->
                playingUrl = song.url
                trackTitle.text = song.title
                loadCover(song, ++playVersion)
                adapter.notifyDataSetChanged()
            }
            if (active) attachPlayback()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            playback = null
            message.text = "Playback service stopped. Reopen the app to reconnect."
        }
    }
    private lateinit var spectrum: SpectrumView
    private lateinit var video: TextureView
    private lateinit var background: ImageView
    private lateinit var query: EditText
    private lateinit var message: TextView
    private lateinit var trackTitle: TextView
    private lateinit var clock: TextView
    private lateinit var pause: Button
    private lateinit var adapter: ResultsAdapter
    private lateinit var updates: UpdateManager
    private lateinit var settings: SharedPreferences
    private lateinit var playbackArea: FrameLayout
    private lateinit var controls: LinearLayout
    private var progress = 1f
    private var animation: ValueAnimator? = null
    private var videoFps = 8
    private var lastFrame = 0L

    private val hideControls = Runnable {
        val exploring = getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
        if (!closed && active && ::player.isInitialized && player.isPlaying && !exploring) animateControls(false)
    }
    private val update = object : Runnable {
        override fun run() {
            if (closed || !active) return
            if (!::player.isInitialized || playback == null) { ui.postDelayed(this, 100); return }
            pause.text = if (playback?.desiredPlaying == true) "Pause" else "Play"
            if (!searching) {
                playback?.errorMessage?.let { message.text = it }
                if (playback?.loading == true) message.text = "Loading stream…"
            }
            clock.text = "${time(player.currentPosition)} / ${time(player.duration)}"
            val now = SystemClock.uptimeMillis()
            if (player.isPlaying && video.isAvailable && now - lastFrame >= 1000 / videoFps) {
                lastFrame = now
                video.getBitmap(64, 36)?.let { frame ->
                    spectrum.setPalette(IntArray(32) { i -> frame.getPixel(i % 8 * 8 + 4, i / 8 * 9 + 4) })
                    frame.recycle()
                }
            }
            ui.postDelayed(this, if (player.isPlaying) (1000 / videoFps).toLong() else 500L)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        settings = getSharedPreferences("music", MODE_PRIVATE)
        buildLayout()
        spectrum.count = settings.getInt("bars", 48)
        spectrum.colorSpeed = settings.getFloat("colorSpeed", 10f)
        videoFps = settings.getInt("videoFps", 8).coerceIn(1, 10)
        bound = bindService(Intent(this, PlaybackService::class.java).setAction(PlaybackService.LOCAL_BIND),
            connection, BIND_AUTO_CREATE)
        if (!bound) message.text = "Could not connect to playback. Reopen the app."
        updates = UpdateManager(this, state)
        updates.check(automatic = true)
    }

    private fun connectPlayer() {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                spectrum.setPlaying(active && isPlaying)
                pause.text = if (playback?.desiredPlaying == true) "Pause" else "Play"
                showControls()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    pause.text = "Play"
                    showControls()
                }
                if (searching) return
                message.text = when (playbackState) {
                    Player.STATE_BUFFERING -> "Loading audio…"
                    Player.STATE_READY -> if (player.isPlaying) "Playing" else "Ready"
                    Player.STATE_ENDED -> "Track finished"
                    else -> message.text
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                pause.text = "Play"
                message.text = "Playback failed. Select the song again to refresh its stream."
                showControls()
            }
        }
        playerListener = listener
        player.addListener(listener)
    }

    private fun attachPlayback() {
        if (playback == null || !::player.isInitialized) return
        player.setVideoTextureView(video)
        playback?.attachSpectrum(spectrum)
        spectrum.setPlaying(player.isPlaying)
        pause.text = if (playback?.desiredPlaying == true) "Pause" else "Play"
        showControls()
    }

    private fun buildLayout() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.rgb(13, 17, 24))
        background = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = .28f
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(.3f) })
            if (Build.VERSION.SDK_INT >= 31)
                setRenderEffect(RenderEffect.createBlurEffect(18f, 18f, Shader.TileMode.CLAMP))
        }
        root.addView(background, FrameLayout.LayoutParams(-1, -1))
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.setPadding(dp(18), dp(14), dp(18), dp(14))
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                content.setPadding(bars.left + dp(18), bars.top + dp(12), bars.right + dp(18), bars.bottom + dp(12))
            } else {
                @Suppress("DEPRECATION")
                content.setPadding(insets.systemWindowInsetLeft + dp(18), insets.systemWindowInsetTop + dp(12),
                    insets.systemWindowInsetRight + dp(18), insets.systemWindowInsetBottom + dp(12))
            }
            insets
        }
        val heading = LinearLayout(this)
        heading.addView(label("MUSIC ROOM", 16).apply { setTextColor(Color.rgb(105, 205, 189)) },
            LinearLayout.LayoutParams(0, dp(46), 1f))
        video = TextureView(this)
        heading.addView(video, LinearLayout.LayoutParams(dp(80), dp(46)))
        heading.addView(button("Settings") { showSettings() })
        content.addView(heading)
        val searchBar = LinearLayout(this)
        query = EditText(this).apply {
            setSingleLine()
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            hint = "Find a song"
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false
            }
        }
        searchBar.addView(query, LinearLayout.LayoutParams(0, dp(52), 1f))
        searchBar.addView(button("Search") { search() })
        content.addView(searchBar)
        message = label("Search for music to start.", 12)
        content.addView(message)
        adapter = ResultsAdapter()
        val list = ListView(this).apply {
            dividerHeight = 1
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, index, _ -> select(index) }
        }
        content.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        playbackArea = FrameLayout(this)
        content.addView(playbackArea, LinearLayout.LayoutParams(-1, dp(220)))
        spectrum = SpectrumView(this)
        playbackArea.addView(spectrum, FrameLayout.LayoutParams(-1, dp(110), Gravity.BOTTOM))
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(225, 13, 17, 24))
            setPadding(0, dp(6), 0, 0)
        }
        playbackArea.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        trackTitle = label("Nothing playing", 15).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        clock = label("0:00 / --:--", 12).apply { gravity = Gravity.CENTER }
        controls.addView(trackTitle)
        controls.addView(clock)
        val buttons = LinearLayout(this).apply { gravity = Gravity.CENTER }
        buttons.addView(button("−10s") { seek(-10000) })
        pause = button("Play") { togglePlayback() }
        buttons.addView(pause)
        buttons.addView(button("+10s") { seek(10000) })
        controls.addView(buttons)
        playbackArea.contentDescription = "Show playback controls"
        playbackArea.setOnClickListener { showControls() }
        controls.setOnClickListener { showControls() }
        playbackArea.setOnHoverListener { _, event ->
            if (event.action == MotionEvent.ACTION_HOVER_ENTER || event.action == MotionEvent.ACTION_HOVER_MOVE)
                showControls()
            false
        }
        controls.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val wantedHeight = dp(110) + controls.height
            if (playbackArea.layoutParams.height != wantedHeight)
                playbackArea.layoutParams = playbackArea.layoutParams.apply { height = wantedHeight }
            positionControls()
        }
        setContentView(root)
    }

    private fun search() {
        val name = query.text.toString().trim()
        if (name.isEmpty()) return
        hideKeyboard()
        val version = ++searchVersion
        searching = true
        message.text = "Searching for “$name”…"
        searchJob.cancel()
        searchTask?.cancel(true)
        val job = MusicSearch.Job().also { searchJob = it }
        imageJob.cancel()
        imageJob = MusicSearch.Job()
        images.queue.clear()
        pendingImages.clear()
        failedImages.clear()
        cache.evictAll()
        songs.clear()
        adapter.notifyDataSetChanged()
        searchTask = searches.submit {
            try {
                val found = MusicSearch.search(name, job)
                ui.post {
                    if (!closed && version == searchVersion) {
                        searching = false
                        songs.addAll(found)
                        adapter.notifyDataSetChanged()
                        message.text = if (found.isEmpty()) "No songs found. Try another name." else "Tap a song to play."
                    }
                }
            } catch (error: Exception) {
                ui.post { if (!closed && version == searchVersion) {
                    searching = false
                    message.text = "Search failed: ${error.message}"
                } }
            }
        }
    }

    private fun select(index: Int) {
        val song = songs.getOrNull(index) ?: return
        val version = ++playVersion
        val service = playback ?: run { message.text = "Playback is still connecting. Try again."; return }
        coverJob.cancel()
        service.select(song)
        spectrum.reset()
        pause.text = "Pause"
        playingUrl = song.url
        trackTitle.text = song.title
        message.text = "Loading stream…"
        adapter.notifyDataSetChanged()
        hideKeyboard()
        showControls()
        loadCover(song, version)
    }

    private fun loadCover(song: Song, version: Int) {
        coverJob.cancel()
        val pictureJob = MusicSearch.Job().also { coverJob = it }
        background.setImageDrawable(null)
        workers.execute {
            try {
                if (song.thumbnail.isNotEmpty()) {
                    val bitmap = loadBitmap(song.thumbnail, 1280, pictureJob)
                    ui.post {
                        if (!closed && version == playVersion) background.setImageBitmap(bitmap) else bitmap.recycle()
                    }
                }
            } catch (_: Exception) { /* A missing cover shouldn't stop playback. */ }
        }
    }

    private fun togglePlayback() {
        playback?.toggle()
        pause.text = if (playback?.desiredPlaying == true) "Pause" else "Play"
        showControls()
    }
    private fun seek(amount: Long) {
        if (playback == null || !::player.isInitialized) return
        val end = if (player.duration > 0) player.duration else Long.MAX_VALUE
        player.seekTo((player.currentPosition + amount).coerceIn(0, end))
    }
    private fun loadBitmap(url: String, maximum: Int, job: MusicSearch.Job): Bitmap {
        val bytes = MusicSearch.image(url, job)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) throw IOException("Invalid image.")
        options.inSampleSize = 1
        while (options.outWidth / options.inSampleSize > maximum || options.outHeight / options.inSampleSize > maximum)
            options.inSampleSize *= 2
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: throw IOException("Invalid image.")
    }

    private fun showControls() {
        if (closed || !active || !::controls.isInitialized) return
        ui.removeCallbacks(hideControls)
        animateControls(true)
        if (active && ::player.isInitialized && player.isPlaying) ui.postDelayed(hideControls, 3000)
    }
    private fun animateControls(visible: Boolean) {
        val target = if (visible) 1f else 0f
        animation?.cancel()
        if (visible) controls.visibility = View.VISIBLE
        if (kotlin.math.abs(progress - target) < .001f) { positionControls(); return }
        animation = ValueAnimator.ofFloat(progress, target).apply {
            duration = 240
            addUpdateListener { progress = it.animatedValue as Float; positionControls() }
            start()
        }
    }
    private fun positionControls() {
        val height = controls.height.toFloat()
        spectrum.translationY = -height * progress
        controls.alpha = progress
        controls.translationY = height * (1 - progress)
        if (progress == 0f) controls.visibility = View.INVISIBLE
    }

    private inner class ResultsAdapter : BaseAdapter() {
        override fun getCount() = songs.size
        override fun getItem(position: Int) = songs[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
            val row = recycled?.tag as? ResultRow ?: ResultRow()
            val song = getItem(position)
            row.name.text = "${position + 1}. ${song.title}"
            row.artist.text = "${song.artist} · ${time(song.duration * 1000)}"
            row.root.setBackgroundColor(if (song.url == playingUrl) Color.argb(120, 30, 88, 82) else Color.TRANSPARENT)
            val url = song.preview
            row.picture.setImageBitmap(cache[url])
            if (!closed && url.isNotEmpty() && url !in failedImages && cache[url] == null && pendingImages.add(url)) {
                val version = searchVersion
                val job = imageJob
                images.execute {
                    val bitmap = try { if (!job.cancelled) loadBitmap(url, 240, job) else null } catch (_: Exception) { null }
                    ui.post {
                        if (closed || version != searchVersion) bitmap?.recycle() else {
                            pendingImages.remove(url)
                            if (bitmap != null) { cache.put(url, bitmap); notifyDataSetChanged() } else failedImages.add(url)
                        }
                    }
                }
            }
            return row.root
        }
    }
    private inner class ResultRow {
        val root = LinearLayout(this@MainActivity)
        val picture = ImageView(this@MainActivity)
        val name = label("", 14)
        val artist = label("", 12)
        init {
            root.tag = this
            root.gravity = Gravity.CENTER_VERTICAL
            root.setPadding(dp(4), dp(7), dp(4), dp(7))
            picture.scaleType = ImageView.ScaleType.CENTER_CROP
            picture.setBackgroundColor(Color.rgb(28, 36, 46))
            picture.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            root.addView(picture, LinearLayout.LayoutParams(dp(80), dp(48)).apply { rightMargin = dp(10) })
            name.maxLines = 2
            name.ellipsize = TextUtils.TruncateAt.END
            artist.setSingleLine()
            artist.ellipsize = TextUtils.TruncateAt.END
            artist.setTextColor(Color.rgb(151, 173, 183))
            val labels = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(name)
            labels.addView(artist)
            root.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private fun showSettings() {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        fun field(text: String, value: String): EditText {
            form.addView(label(text, 14))
            return EditText(this).apply {
                setText(value)
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                form.addView(this)
            }
        }
        val count = field("Bar count (8–128)", spectrum.count.toString())
        val speed = field("Color changes per second (0.1–60)", spectrum.colorSpeed.toString())
        val frames = field("Video color samples per second (1–10)", videoFps.toString())
        AlertDialog.Builder(this).setTitle("Music Room settings").setView(form)
            .setNeutralButton("Check updates") { _, _ -> updates.check() }
            .setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
                val bars = count.text.toString().toIntOrNull()
                val color = speed.text.toString().toFloatOrNull()
                val fps = frames.text.toString().toIntOrNull()
                if (bars == null || color == null || !color.isFinite() || fps == null) {
                    message.text = "Enter valid settings numbers."
                } else {
                    spectrum.count = bars
                    spectrum.colorSpeed = color
                    videoFps = fps.coerceIn(1, 10)
                    settings.edit().putInt("bars", spectrum.count).putFloat("colorSpeed", spectrum.colorSpeed)
                        .putInt("videoFps", videoFps).apply()
                }
            }.show()
    }
    private fun button(text: String, click: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { showControls(); click() }
    }
    private fun label(text: String, size: Int) = TextView(this).apply {
        this.text = text
        textSize = size.toFloat()
        setTextColor(Color.rgb(212, 224, 232))
        setPadding(0, dp(4), 0, dp(4))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private fun time(value: Long) = if (value < 0) "--:--" else String.format(Locale.ROOT, "%d:%02d", value / 60000, value / 1000 % 60)
    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(query.windowToken, 0)
    }
    override fun onStart() {
        super.onStart()
        active = true
        attachPlayback()
        ui.removeCallbacks(update)
        ui.post(update)
        showControls()
    }
    override fun onSaveInstanceState(state: Bundle) {
        updates.saveState(state)
        super.onSaveInstanceState(state)
    }
    override fun onResume() {
        super.onResume()
        updates.onResume()
    }
    override fun onPause() {
        updates.onPause()
        super.onPause()
    }
    override fun onStop() {
        active = false
        ui.removeCallbacks(update)
        ui.removeCallbacks(hideControls)
        playback?.attachSpectrum(null)
        spectrum.setPlaying(false)
        if (::player.isInitialized) player.clearVideoTextureView(video)
        super.onStop()
    }
    override fun onDestroy() {
        closed = true
        updates.close()
        searchVersion++
        playVersion++
        ui.removeCallbacksAndMessages(null)
        listOf(searchJob, coverJob, imageJob).forEach { it.cancel() }
        animation?.cancel()
        searches.shutdownNow()
        workers.shutdownNow()
        images.shutdownNow()
        cache.evictAll()
        if (::player.isInitialized) {
            playerListener?.let { player.removeListener(it) }
            player.clearVideoTextureView(video)
        }
        playback?.attachSpectrum(null)
        playback = null
        if (bound) { unbindService(connection); bound = false }
        super.onDestroy()
    }
}
