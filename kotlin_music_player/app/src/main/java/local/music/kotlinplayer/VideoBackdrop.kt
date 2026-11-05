// SPDX-FileCopyrightText: 2026 Himanshu_Sharma (HimanshuS073)
// SPDX-License-Identifier: GPL-3.0-or-later

package local.music.kotlinplayer

import android.graphics.*
import android.os.Build
import android.widget.ImageView

class VideoBackdrop(private val image: ImageView) {
    private var cover: Bitmap? = null
    private var frame: Bitmap? = null
    private val blur = VideoFrameBlur()
    private val coverFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(.25f) })
    private val videoFilter = ColorMatrixColorFilter(ColorMatrix().apply {
        setSaturation(.45f)
        postConcat(ColorMatrix().apply { setScale(.65f, .65f, .65f, 1f) })
    })
    private var videoVisible: Boolean? = null
    var enabled = false
        private set
    val needsFrame get() = enabled && frame == null

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) frame = null
        display()
    }
    fun setCover(value: Bitmap?) {
        cover = value
        if (value == null) frame = null
        display()
    }
    fun resetFrame() { frame = null; display() }
    fun showFrame(value: Bitmap) {
        if (!enabled) { value.recycle(); return }
        blur.apply(value)
        // Let Android release old frames after its renderer has finished with them.
        frame = value
        display()
    }
    private fun display() {
        val video = enabled && frame != null
        if (videoVisible != video) {
            image.alpha = if (video) .6f else .19f
            image.colorFilter = if (video) videoFilter else coverFilter
            if (Build.VERSION.SDK_INT >= 31) image.setRenderEffect(if (video) null else
                RenderEffect.createBlurEffect(24f, 24f, Shader.TileMode.CLAMP))
            videoVisible = video
        }
        val bitmap = if (video) frame else cover
        if (bitmap == null) image.setImageDrawable(null) else image.setImageBitmap(bitmap)
    }
}

/** A small, reusable box blur for the in-memory video frames. */
class VideoFrameBlur {
    private var pixels = IntArray(0)
    private var scratch = IntArray(0)

    fun apply(bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        require(width <= 160 && height <= 90) { "Background frames must stay small." }
        val size = width * height
        if (pixels.size != size) { pixels = IntArray(size); scratch = IntArray(size) }
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        repeat(2) {
            pass(pixels, scratch, width, height, true)
            pass(scratch, pixels, width, height, false)
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }
    private fun pass(source: IntArray, target: IntArray, width: Int, height: Int, horizontal: Boolean) {
        val length = if (horizontal) width else height
        val lines = if (horizontal) height else width
        val step = if (horizontal) 1 else width
        for (line in 0 until lines) {
            val start = if (horizontal) line * width else line
            var red = 0; var green = 0; var blue = 0
            for (offset in -3..3) {
                val color = source[start + offset.coerceIn(0, length - 1) * step]
                red += color shr 16 and 255; green += color shr 8 and 255; blue += color and 255
            }
            for (position in 0 until length) {
                target[start + position * step] = Color.rgb(red / 7, green / 7, blue / 7)
                val old = source[start + (position - 3).coerceIn(0, length - 1) * step]
                val next = source[start + (position + 4).coerceIn(0, length - 1) * step]
                red += (next shr 16 and 255) - (old shr 16 and 255)
                green += (next shr 8 and 255) - (old shr 8 and 255)
                blue += (next and 255) - (old and 255)
            }
        }
    }
}
