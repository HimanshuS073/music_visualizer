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
import android.util.LruCache
import android.view.*
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.io.IOException
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
    private var restoredScreenState = false
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
                screen.trackArtist.text = song.artist
                screen.setTrackAvailable(true)
                if (!restoredScreenState && !searching && songs.isEmpty()) screen.setResultsHidden(true)
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
    private lateinit var screen: MusicScreen
    private lateinit var spectrum: SpectrumView
    private lateinit var video: TextureView
    private lateinit var query: EditText
    private lateinit var message: TextView
    private lateinit var trackTitle: TextView
    private lateinit var adapter: ResultsAdapter
    private lateinit var updates: UpdateManager
    private lateinit var settings: SharedPreferences
    private lateinit var playbackArea: FrameLayout
    private lateinit var controls: LinearLayout
    private var progress = 1f
    private var animation: ValueAnimator? = null
    private var settingsDialog: AlertDialog? = null
    private var videoFps = 8
    private var lastFrame = 0L

    private val hideControls = Runnable {
        val exploring = getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
        if (!closed && active && ::player.isInitialized && player.isPlaying && !exploring && !screen.seeking) animateControls(false)
    }
    private val update = object : Runnable {
        override fun run() {
            if (closed || !active) return
            if (!::player.isInitialized || playback == null) { ui.postDelayed(this, 100); return }
            screen.setPlaying(playback?.desiredPlaying == true)
            if (!searching) {
                playback?.errorMessage?.let { message.text = it }
                if (playback?.loading == true) message.text = "Loading stream…"
            }
            screen.updateTimeline(player.currentPosition, player.duration, player.isCurrentMediaItemSeekable)
            val now = SystemClock.uptimeMillis()
            if ((player.isPlaying || screen.wantsVideoFrame) && video.isAvailable && now - lastFrame >= 1000 / videoFps) {
                lastFrame = now
                val backdrop = screen.videoBackgroundEnabled && screen.videoReady
                video.getBitmap(if (backdrop) 96 else 64, if (backdrop) 54 else 36)?.let { frame ->
                    spectrum.setPalette(IntArray(32) { i ->
                        frame.getPixel((i % 8 * frame.width / 8 + frame.width / 16).coerceAtMost(frame.width - 1),
                            (i / 8 * frame.height / 4 + frame.height / 8).coerceAtMost(frame.height - 1))
                    })
                    if (backdrop) screen.showVideoFrame(frame) else frame.recycle()
                }
            }
            ui.postDelayed(this, if (player.isPlaying) (1000 / videoFps).toLong() else 500L)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        settings = getSharedPreferences("music", MODE_PRIVATE)
        buildLayout()
        restoredScreenState = state?.containsKey("results_hidden") == true
        screen.setResultsHidden(state?.getBoolean("results_hidden", false) ?: false)
        spectrum.count = settings.getInt("bars", 48)
        spectrum.colorSpeed = settings.getFloat("colorSpeed", 10f)
        videoFps = settings.getInt("videoFps", 8).coerceIn(1, 10)
        screen.setVisualizerHeight(settings.getInt("visualizer_height", 72))
        screen.setVideoBackgroundEnabled(settings.getBoolean("video_background", false))
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
                screen.setPlaying(playback?.desiredPlaying == true)
                showControls()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    screen.setPlaying(false)
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
            override fun onRenderedFirstFrame() {
                screen.markVideoReady()
            }
            override fun onPlayerError(error: PlaybackException) {
                screen.setPlaying(false)
                message.text = "Playback failed. Select the song again to refresh its stream."
                showControls()
            }
        }
        playerListener = listener
        player.addListener(listener)
    }

    private fun attachPlayback() {
        if (playback == null || !::player.isInitialized) return
        screen.resetVideoFrame()
        player.setVideoTextureView(video)
        playback?.attachSpectrum(spectrum)
        spectrum.setPlaying(player.isPlaying)
        screen.setPlaying(playback?.desiredPlaying == true)
        showControls()
    }

    private fun buildLayout() {
        screen = MusicScreen(this, ::search, ::showSettings, ::togglePlayback, ::seek,
            onScrub = { fraction ->
                if (::player.isInitialized && player.duration > 0 && player.isCurrentMediaItemSeekable)
                    player.seekTo((player.duration * fraction).toLong())
            }, onInteraction = ::showControls)
        spectrum = screen.spectrum
        video = screen.video
        query = screen.query
        message = screen.message
        trackTitle = screen.trackTitle
        playbackArea = screen.playbackArea
        controls = screen.controls
        adapter = ResultsAdapter()
        screen.list.adapter = adapter
        screen.list.setOnItemClickListener { _, _, index, _ -> select(index) }
        playbackArea.contentDescription = "Show playback controls"
        playbackArea.setOnClickListener { showControls() }
        controls.setOnClickListener { showControls() }
        playbackArea.setOnHoverListener { _, event ->
            if (event.action == MotionEvent.ACTION_HOVER_ENTER || event.action == MotionEvent.ACTION_HOVER_MOVE)
                showControls()
            false
        }
        setContentView(screen.root)
    }

    private fun search() {
        val name = query.text.toString().trim()
        if (name.isEmpty()) {
            message.text = "Type a song or artist to search."
            query.requestFocus()
            return
        }
        hideKeyboard()
        val version = ++searchVersion
        searching = true
        screen.showSearching(name)
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
                        screen.showResults(found.size)
                        adapter.notifyDataSetChanged()
                        message.text = if (found.isEmpty()) "No songs found. Try another name." else "Tap a song to play."
                    }
                }
            } catch (error: Exception) {
                ui.post { if (!closed && version == searchVersion) {
                    searching = false
                    screen.showSearchError()
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
        screen.setResultsHidden(true)
        imageJob.cancel()
        images.queue.clear()
        pendingImages.clear()
        spectrum.reset()
        screen.setPlaying(true)
        playingUrl = song.url
        trackTitle.text = song.title
        screen.trackArtist.text = song.artist
        screen.setTrackAvailable(true)
        screen.updateTimeline(0, song.duration * 1000, false)
        message.text = "Loading stream…"
        adapter.notifyDataSetChanged()
        hideKeyboard()
        showControls()
        loadCover(song, version)
    }

    private fun loadCover(song: Song, version: Int) {
        coverJob.cancel()
        val pictureJob = MusicSearch.Job().also { coverJob = it }
        screen.setCover(null)
        workers.execute {
            try {
                if (song.thumbnail.isNotEmpty()) {
                    val bitmap = loadBitmap(song.thumbnail, 1280, pictureJob)
                    ui.post {
                        if (!closed && version == playVersion) screen.setCover(bitmap) else bitmap.recycle()
                    }
                }
            } catch (_: Exception) { /* A missing cover shouldn't stop playback. */ }
        }
    }

    private fun togglePlayback() {
        playback?.toggle()
        screen.setPlaying(playback?.desiredPlaying == true)
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
    private fun positionControls() { screen.positionControls(progress) }

    private inner class ResultsAdapter : BaseAdapter() {
        override fun getCount() = songs.size
        override fun getItem(position: Int) = songs[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
            val row = recycled?.tag as? ResultRow ?: ResultRow()
            val song = getItem(position)
            row.bind(song, song.url == playingUrl)
            val url = song.preview
            row.setPicture(cache[url])
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
    private inner class ResultRow : SongRow(this@MainActivity)

    private fun showSettings() {
        settingsDialog?.dismiss()
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
            isFocusableInTouchMode = true
        }
        val videoBackground = Switch(this).apply {
            text = "Video background"
            textSize = 16f
            contentDescription = "Video background"
            isChecked = screen.videoBackgroundEnabled
            minimumHeight = dp(48)
            form.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        form.addView(RoomStyle.label(this, "Live video, softly blurred and dimmed.", 12, RoomStyle.muted))
        fun field(text: String, value: String, decimal: Boolean = false): EditText {
            form.addView(label(text, 14))
            return EditText(this).apply {
                setText(value)
                contentDescription = text
                inputType = InputType.TYPE_CLASS_NUMBER or if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0
                minimumHeight = dp(48)
                form.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
        }
        val height = field("Visualizer height (24–320)", screen.visualizerHeightDp.toString())
        val count = field("Bar count (8–128)", spectrum.count.toString())
        val speed = field("Color changes per second (0.1–60)", spectrum.colorSpeed.toString(), true)
        val frames = field("Video updates per second (1–10)", videoFps.toString())
        form.requestFocus()
        val scroll = ScrollView(this).apply { addView(form); isFillViewport = false }
        val dialog = AlertDialog.Builder(this).setTitle("Music Room settings").setView(scroll)
            .setNeutralButton("Check updates") { _, _ -> updates.check() }
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        settingsDialog = dialog
        dialog.setOnDismissListener { if (settingsDialog === dialog) settingsDialog = null }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val tall = height.text.toString().toIntOrNull()
            val bars = count.text.toString().toIntOrNull()
            val color = speed.text.toString().toFloatOrNull()
            val fps = frames.text.toString().toIntOrNull()
            val invalid = when {
                tall == null || tall !in 24..320 -> height to "Use a height from 24 to 320."
                bars == null || bars !in 8..128 -> count to "Use 8 to 128 bars."
                color == null || !color.isFinite() || color !in .1f..60f -> speed to "Use a speed from 0.1 to 60."
                fps == null || fps !in 1..10 -> frames to "Use 1 to 10 updates per second."
                else -> null
            }
            if (invalid != null) {
                invalid.first.error = invalid.second
                invalid.first.requestFocus()
                return@setOnClickListener
            }
            spectrum.count = bars!!
            spectrum.colorSpeed = color!!
            videoFps = fps!!
            screen.setVisualizerHeight(tall!!)
            screen.setVideoBackgroundEnabled(videoBackground.isChecked)
            settings.edit().putInt("bars", spectrum.count).putFloat("colorSpeed", spectrum.colorSpeed)
                .putInt("videoFps", videoFps).putInt("visualizer_height", screen.visualizerHeightDp)
                .putBoolean("video_background", screen.videoBackgroundEnabled).apply()
            ui.removeCallbacks(update)
            ui.post(update)
            dialog.dismiss()
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    private fun label(text: String, size: Int) = RoomStyle.label(this, text, size).apply {
        setPadding(0, dp(4), 0, dp(4))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(query.windowToken, 0)
        query.clearFocus()
        screen.root.requestFocus()
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
        state.putBoolean("results_hidden", screen.resultsHidden)
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
        settingsDialog?.dismiss()
        settingsDialog = null
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
