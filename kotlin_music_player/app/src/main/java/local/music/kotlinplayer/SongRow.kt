// SPDX-FileCopyrightText: 2026 Himanshu_Sharma (HimanshuS073)
// SPDX-License-Identifier: GPL-3.0-or-later

package local.music.kotlinplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.*
import java.util.Locale

open class SongRow(context: Context) {
    val root = LinearLayout(context)
    val picture = ImageView(context)
    val name = RoomStyle.label(context, "", 14, medium = true)
    val artist = RoomStyle.label(context, "", 12, RoomStyle.muted)
    private val duration = RoomStyle.label(context, "", 10, RoomStyle.muted)
    private val play = ImageView(context)
    private val placeholder = RoomIconDrawable(RoomIcon.NOTE, RoomStyle.muted)
    private val normal = RoomStyle.shape(context, 0xD9211D26.toInt(), 17, 0xFF302A36.toInt())
    private val selected = RoomStyle.shape(context, 0xED283E35.toInt(), 17, 0xFF537F68.toInt())
    private var active: Boolean? = null
    private fun dp(value: Int) = RoomStyle.dp(root.context, value)
    init {
        root.tag = this
        root.gravity = Gravity.CENTER_VERTICAL
        root.setPadding(dp(11), dp(11), dp(12), dp(11))
        root.minimumHeight = dp(86)
        picture.scaleType = ImageView.ScaleType.CENTER_CROP
        RoomStyle.round(picture, 11)
        picture.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        root.addView(picture, LinearLayout.LayoutParams(dp(60), dp(60)))
        val words = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        name.maxLines = 2
        name.ellipsize = TextUtils.TruncateAt.END
        name.setLineSpacing(dp(2).toFloat(), 1f)
        artist.setSingleLine()
        artist.ellipsize = TextUtils.TruncateAt.END
        words.addView(name)
        words.addView(artist, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        root.addView(words, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12); rightMargin = dp(8) })
        val end = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        end.addView(play, LinearLayout.LayoutParams(dp(16), dp(16)))
        end.addView(duration, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        root.addView(end, LinearLayout.LayoutParams(dp(35), -2))
        play.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    fun bind(song: Song, selected: Boolean) {
        name.text = song.title
        artist.text = song.artist
        duration.text = if (song.duration > 0) String.format(Locale.ROOT, "%d:%02d", song.duration / 60, song.duration % 60) else "LIVE"
        root.contentDescription = "${song.title}, ${song.artist}" + if (selected) ", selected" else ""
        if (active != selected) {
            active = selected
            root.background = RippleDrawable(ColorStateList.valueOf(0x1FFFFFFF),
                if (selected) this.selected else normal, RoomStyle.shape(root.context, Color.WHITE, 17))
            play.setImageDrawable(RoomIconDrawable(if (selected) RoomIcon.LEVELS else RoomIcon.PLAY,
                if (selected) RoomStyle.mint else RoomStyle.muted))
        }
    }
    fun setPicture(bitmap: Bitmap?) {
        if (bitmap != null) {
            picture.setPadding(0, 0, 0, 0)
            picture.setImageBitmap(bitmap)
        } else {
            picture.setPadding(dp(18), dp(18), dp(18), dp(18))
            picture.setImageDrawable(placeholder)
        }
    }
}
