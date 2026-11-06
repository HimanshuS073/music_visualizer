// SPDX-FileCopyrightText: 2026 Himanshu_Sharma (HimanshuS073)
// SPDX-License-Identifier: GPL-3.0-or-later

package local.music.kotlinplayer

import android.content.Context
import android.os.PowerManager

internal class StreamPreparationLock(context: Context) {
    private val lock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MusicRoom:StreamPreparation")
        .apply { setReferenceCounted(false) }

    fun acquire() {
        // ExoPlayer takes over once prepared; don't keep a stalled request awake forever.
        lock.acquire(120_000L)
    }

    fun release() {
        if (lock.isHeld) lock.release()
    }
}
