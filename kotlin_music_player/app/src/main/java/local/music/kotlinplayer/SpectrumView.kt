// SPDX-FileCopyrightText: 2026 Himanshu_Sharma (HimanshuS073)
// SPDX-License-Identifier: GPL-3.0-or-later

package local.music.kotlinplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.exp

@UnstableApi
class SpectrumView(context: Context) : View(context), TeeAudioProcessor.AudioBufferSink {
    private val fft = Fft()
    private val samples = DoubleArray(fft.size)
    private val targets = FloatArray(128)
    private val heights = FloatArray(128)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val random = Random()
    private var palette = intArrayOf(Color.rgb(92, 185, 174), Color.rgb(129, 155, 205))
    private val colors = IntArray(128) { palette[it % palette.size] }
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private var rate = 44100
    private var filled = 0
    private var playing = false
    private var previous = 0L
    private var nextColor = 0L
    @Volatile var count = 48
        set(value) { field = value.coerceIn(8, 128) }
    var colorSpeed = 10f
        set(value) { field = if (value.isFinite()) value.coerceIn(.1f, 60f) else 10f }

    fun setPlaying(value: Boolean) {
        playing = value
        postInvalidateOnAnimation()
    }
    fun setPalette(value: IntArray) { if (value.isNotEmpty()) palette = value }
    fun reset() {
        synchronized(targets) { targets.fill(0f); heights.fill(0f) }
        invalidate()
    }
    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        rate = sampleRateHz.coerceAtLeast(1)
        channels = channelCount.coerceAtLeast(1)
        this.encoding = encoding
        filled = 0
    }
    override fun handleBuffer(buffer: ByteBuffer) {
        if (encoding != C.ENCODING_PCM_FLOAT && encoding != C.ENCODING_PCM_16BIT) return
        val data = buffer.duplicate().order(ByteOrder.nativeOrder())
        val bytes = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        while (data.remaining() >= channels * bytes) {
            var sample = 0.0
            repeat(channels) { sample += if (bytes == 4) data.float.toDouble() else data.short / 32768.0 }
            samples[filled++] = sample / channels
            if (filled == fft.size) {
                synchronized(targets) { fft.analyze(samples, rate, count, targets) }
                filled = 0
            }
        }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val dt = if (previous == 0L) .016f else ((now - previous) / 1000f).coerceIn(0f, .1f)
        previous = now
        val bars = count
        if (playing && now >= nextColor) {
            for (i in 0 until bars) colors[i] = palette[random.nextInt(palette.size)]
            nextColor = now + (1000 / colorSpeed).toLong()
        }
        val barWidth = width.toFloat() / bars
        var moving = false
        synchronized(targets) {
            for (i in 0 until bars) {
                val value = if (playing) targets[i] else 0f
                val speed = if (value > heights[i]) 40f else 15f
                heights[i] += (value - heights[i]) * (1 - exp(-speed * dt))
                if (heights[i] > .001f) moving = true
                paint.color = colors[i]
                paint.alpha = 185
                val gap = barWidth.coerceAtMost(4f) / 4
                canvas.drawRoundRect(i * barWidth + gap, height * (1 - heights[i]),
                    (i + 1) * barWidth - gap, height.toFloat(), 2f, 2f, paint)
            }
        }
        if (playing || moving) postInvalidateOnAnimation()
    }
}
