// SPDX-FileCopyrightText: 2026 Himanshu_Sharma (HimanshuS073)
// SPDX-License-Identifier: GPL-3.0-or-later

package local.music.kotlinplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.media3.common.*
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.Future

@UnstableApi
class PlaybackService : MediaSessionService() {
    companion object {
        const val LOCAL_BIND = "local.music.kotlinplayer.BIND_PLAYBACK"
        private const val KEEP_ALIVE = "local.music.kotlinplayer.KEEP_PLAYING"
        private const val CHANNEL = "music_playback"
        private const val NOTIFICATION_ID = 1001
    }

    inner class LocalBinder : Binder() { val service get() = this@PlaybackService }
    private val binder = LocalBinder()
    private val ui = Handler(Looper.getMainLooper())
    private val resolver = Executors.newSingleThreadExecutor()
    private var task: Future<*>? = null
    private var job = MusicSearch.Job()
    private var version = 0
    private var closed = false
    private var session: MediaSession? = null
    private val samples = SampleRelay()
    private lateinit var preparationLock: StreamPreparationLock
    lateinit var player: ExoPlayer
        private set
    var song: Song? = null
        private set
    var loading = false
        private set
    var desiredPlaying = false
        private set
    var errorMessage: String? = null
        private set

    override fun onCreate() {
        super.onCreate()
        preparationLock = StreamPreparationLock(this)
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean,
                                        enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context).setAudioProcessors(
                    arrayOf<AudioProcessor>(TeeAudioProcessor(samples))).build()
        }
        player = ExoPlayer.Builder(this, renderers).setSeekBackIncrementMs(10000)
            .setSeekForwardIncrementMs(10000).build()
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        player.setHandleAudioBecomingNoisy(true)
        player.setWakeMode(C.WAKE_MODE_NETWORK)
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!loading) desiredPlaying = playWhenReady
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) desiredPlaying = false
            }
            override fun onPlayerError(error: PlaybackException) {
                desiredPlaying = false
                errorMessage = "Playback failed. Select the song again to refresh its stream."
            }
        })
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(NOTIFICATION_ID).setChannelId(CHANNEL).build())
        val mediaSession = MediaSession.Builder(this, player).setSessionActivity(openPlayer()).build()
        session = mediaSession
        addSession(mediaSession)
    }

    override fun onBind(intent: Intent?): IBinder? {
        val mediaBinder = super.onBind(intent)
        return if (intent?.action == LOCAL_BIND) binder else mediaBinder
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        // Keep the loading notification alive until a playable stream is ready.
        if (!loading) super.onUpdateNotification(session, startInForegroundRequired)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    fun select(selected: Song) {
        if (closed) return
        val request = ++version
        job.cancel()
        task?.cancel(true)
        preparationLock.release()
        job = MusicSearch.Job()
        val requestJob = job
        loading = true
        desiredPlaying = true
        errorMessage = null
        song = selected
        player.stop()
        player.clearMediaItems()
        // Promote before resolving, so switching apps during a slow request is safe.
        startForegroundService(Intent(this, PlaybackService::class.java).setAction(KEEP_ALIVE))
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Music playback", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, Notification.Builder(this, CHANNEL)
            .setSmallIcon(local.music.kotlinplayer.R.drawable.music_icon)
            .setContentTitle(selected.title).setContentText("Loading music…")
            .setContentIntent(openPlayer()).setStyle(Notification.MediaStyle().setMediaSession(session!!.platformToken))
            .setOngoing(true).setOnlyAlertOnce(true).build())
        preparationLock.acquire()
        task = resolver.submit {
            try {
                val stream = MusicSearch.resolve(selected, requestJob)
                ui.post {
                    if (!closed && request == version) {
                        val metadata = MediaMetadata.Builder().setTitle(selected.title).setArtist(selected.artist).build()
                        val item = MediaItem.Builder().setMediaId(selected.url).setUri(stream.audio)
                            .setMediaMetadata(metadata).build()
                        val http = DefaultHttpDataSource.Factory().setUserAgent("Mozilla/5.0")
                        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))
                        val audio = factory.createMediaSource(item)
                        val source = if (stream.video.isBlank()) audio else MergingMediaSource(audio,
                            factory.createMediaSource(MediaItem.fromUri(stream.video)))
                        player.setMediaSource(source)
                        loading = false
                        player.playWhenReady = desiredPlaying
                        player.prepare()
                        preparationLock.release()
                    }
                }
            } catch (error: Exception) {
                ui.post {
                    if (!closed && request == version) {
                        loading = false
                        preparationLock.release()
                        desiredPlaying = false
                        errorMessage = "Cannot load this song: ${error.message}"
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
    }

    fun toggle() {
        if (song == null) return
        desiredPlaying = if (loading) !desiredPlaying else !player.playWhenReady
        if (loading) {
            if (desiredPlaying) preparationLock.acquire() else preparationLock.release()
        }
        if (desiredPlaying && player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        if (!loading) player.playWhenReady = desiredPlaying
    }

    fun attachSpectrum(view: SpectrumView?) {
        samples.attach(view)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, view == null).build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        preparationLock.release()
        version++
        job.cancel()
        task?.cancel(true)
        loading = false
        desiredPlaying = false
        player.pause()
        player.stop()
        player.clearMediaItems()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        preparationLock.release()
        closed = true
        version++
        job.cancel()
        task?.cancel(true)
        resolver.shutdownNow()
        ui.removeCallbacksAndMessages(null)
        samples.attach(null)
        session?.release()
        session = null
        player.release()
        super.onDestroy()
    }

    private fun openPlayer() = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    // Only keep the window's audio sink attached while that window is visible.
    private class SampleRelay : TeeAudioProcessor.AudioBufferSink {
        private var sink: TeeAudioProcessor.AudioBufferSink? = null
        private var rate = 44100
        private var channels = 2
        private var encoding = C.ENCODING_PCM_16BIT
        @Synchronized fun attach(value: TeeAudioProcessor.AudioBufferSink?) {
            sink = value
            value?.flush(rate, channels, encoding)
        }
        @Synchronized override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            rate = sampleRateHz
            channels = channelCount
            this.encoding = encoding
            sink?.flush(rate, channels, encoding)
        }
        @Synchronized override fun handleBuffer(buffer: ByteBuffer) { sink?.handleBuffer(buffer) }
    }
}
