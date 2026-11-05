// SPDX-FileCopyrightText: 2026 Himanshu_Sharma (HimanshuS073)
// SPDX-License-Identifier: GPL-3.0-or-later

package local.music.kotlinplayer

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.ImageButton
import android.widget.TextView

object RoomStyle {
    val background = Color.rgb(19, 17, 23)
    val surface = Color.rgb(33, 29, 38)
    val border = Color.rgb(57, 50, 63)
    val text = Color.rgb(247, 242, 235)
    val muted = Color.rgb(165, 157, 174)
    val mint = Color.rgb(183, 224, 204)
    val peach = Color.rgb(226, 180, 153)
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    fun shape(context: Context, color: Int, radius: Int = 16, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(context, radius).toFloat()
        if (stroke != null) setStroke(dp(context, 1), stroke)
    }
    fun label(context: Context, value: String, size: Int, color: Int = text, medium: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        includeFontPadding = false
    }
    fun round(view: View, radius: Int) {
        view.background = shape(view.context, surface, radius)
        view.clipToOutline = true
    }
}

enum class RoomIcon { SEARCH, ARROW, SETTINGS, PLAY, PAUSE, BACK, FORWARD, NOTE, LEVELS, HEADPHONES }

class RoomIconDrawable(kind: RoomIcon, color: Int = RoomStyle.text) : Drawable() {
    var kind = kind
        set(value) { if (field != value) { field = value; invalidateSelf() } }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        strokeWidth = 1.7f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        val scale = minOf(bounds.width(), bounds.height()) / 24f
        canvas.translate(bounds.exactCenterX() - 12 * scale, bounds.exactCenterY() - 12 * scale)
        canvas.scale(scale, scale)
        paint.style = Paint.Style.STROKE
        when (kind) {
            RoomIcon.SEARCH -> { canvas.drawCircle(10.5f, 10.5f, 6.5f, paint); canvas.drawLine(15.5f, 15.5f, 21f, 21f, paint) }
            RoomIcon.ARROW -> {
                canvas.drawLine(5f, 12f, 19f, 12f, paint)
                path.reset(); path.moveTo(13f, 6f); path.lineTo(19f, 12f); path.lineTo(13f, 18f); canvas.drawPath(path, paint)
            }
            RoomIcon.SETTINGS -> {
                for ((y, x) in listOf(6f to 9f, 12f to 16f, 18f to 7f)) {
                    canvas.drawLine(4f, y, x - 2f, y, paint); canvas.drawLine(x + 2f, y, 20f, y, paint)
                    canvas.drawCircle(x, y, 2f, paint)
                }
            }
            RoomIcon.PLAY -> {
                paint.style = Paint.Style.FILL
                path.reset(); path.moveTo(8f, 4.5f); path.lineTo(20f, 12f); path.lineTo(8f, 19.5f); path.close()
                canvas.drawPath(path, paint)
            }
            RoomIcon.PAUSE -> {
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(6f, 5f, 10f, 19f, 1f, 1f, paint)
                canvas.drawRoundRect(14f, 5f, 18f, 19f, 1f, 1f, paint)
            }
            RoomIcon.BACK, RoomIcon.FORWARD -> {
                if (kind == RoomIcon.FORWARD) {
                    canvas.drawArc(3f, 3f, 21f, 21f, -70f, 285f, false, paint)
                    path.reset(); path.moveTo(15f, 1f); path.lineTo(16f, 5f); path.lineTo(12f, 6f)
                } else {
                    canvas.drawArc(3f, 3f, 21f, 21f, -110f, -285f, false, paint)
                    path.reset(); path.moveTo(9f, 1f); path.lineTo(8f, 5f); path.lineTo(12f, 6f)
                }
                canvas.drawPath(path, paint)
                paint.style = Paint.Style.FILL; paint.textAlign = Paint.Align.CENTER
                paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); paint.textSize = 8.5f
                canvas.drawText("10", 12f, 15.5f, paint)
            }
            RoomIcon.NOTE -> {
                path.reset(); path.moveTo(10f, 17f); path.lineTo(10f, 5f); path.lineTo(20f, 3f); path.lineTo(20f, 15f)
                canvas.drawPath(path, paint); paint.style = Paint.Style.FILL
                canvas.drawOval(4f, 15f, 10.8f, 20f, paint); canvas.drawOval(14f, 13f, 20.8f, 18f, paint)
            }
            RoomIcon.LEVELS -> {
                for ((x, y) in listOf(5f to 10f, 10f to 4f, 15f to 7f, 20f to 12f))
                    canvas.drawLine(x, y, x, 20f, paint)
            }
            RoomIcon.HEADPHONES -> {
                canvas.drawArc(4f, 3f, 20f, 20f, 180f, 180f, false, paint)
                canvas.drawRoundRect(3.5f, 12f, 7.5f, 20f, 2f, 2f, paint)
                canvas.drawRoundRect(16.5f, 12f, 20.5f, 20f, 2f, 2f, paint)
            }
        }
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Required by Drawable on older Android versions")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

class RoomIconButton(context: Context, icon: RoomIcon, description: String,
                     fill: Int = Color.TRANSPARENT, ink: Int = RoomStyle.text,
                     onClick: () -> Unit) : ImageButton(context) {
    constructor(context: Context) : this(context, RoomIcon.PLAY, "Play", onClick = {})

    private val glyph = RoomIconDrawable(icon, ink)
    var icon: RoomIcon
        get() = glyph.kind
        set(value) { glyph.kind = value }
    init {
        setImageDrawable(glyph)
        contentDescription = description
        val padding = RoomStyle.dp(context, 14)
        setPadding(padding, padding, padding, padding)
        background = RippleDrawable(ColorStateList.valueOf(0x226FFFFF),
            RoomStyle.shape(context, fill, 100), RoomStyle.shape(context, Color.WHITE, 100))
        scaleType = ScaleType.FIT_CENTER
        minimumWidth = RoomStyle.dp(context, 48)
        minimumHeight = RoomStyle.dp(context, 48)
        isFocusable = true
        setOnClickListener { onClick() }
    }
}
