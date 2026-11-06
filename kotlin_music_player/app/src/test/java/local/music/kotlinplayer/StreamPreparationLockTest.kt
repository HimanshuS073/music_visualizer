package local.music.kotlinplayer

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StreamPreparationLockTest {
    @Test fun loadingKeepsCpuAwakeWithoutKeepingScreenOn() {
        val preparation = StreamPreparationLock(RuntimeEnvironment.getApplication())
        val lock = ShadowPowerManager.getLatestWakeLock()
        assertFalse(lock.isHeld)
        preparation.acquire()
        assertTrue(lock.isHeld)
        preparation.release()
        assertFalse(lock.isHeld)
    }

    @Test fun replacingOrPausingRequestsCannotLeaveAnExtraLockHeld() {
        val preparation = StreamPreparationLock(RuntimeEnvironment.getApplication())
        val lock = ShadowPowerManager.getLatestWakeLock()
        preparation.acquire()
        preparation.acquire()
        preparation.release()
        preparation.release()
        assertFalse(lock.isHeld)
        preparation.acquire()
        assertTrue(lock.isHeld)
        preparation.release()
        assertFalse(lock.isHeld)
    }
}
