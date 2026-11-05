package local.music.kotlinplayer

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

class FftTest {
    @Test fun rejectsUnsupportedSizes() {
        for (size in listOf(0, 255, 300, 8192))
            assertThrows(IllegalArgumentException::class.java) { Fft(size) }
    }
    @Test fun silenceHasZeroTargets() {
        val output = FloatArray(128)
        Fft().analyze(DoubleArray(1024), 44100, 48, output)
        assertTrue(output.all { it == 0f })
    }
    @Test fun findsOneKilohertzTone() {
        val fft = Fft()
        val output = FloatArray(64)
        fft.analyze(DoubleArray(1024) { .1 * sin(2 * PI * 1000 * it / 44100) }, 44100, 64, output)
        val peak = output.indices.maxBy { output[it] }
        val low = 40 * 250.0.pow(peak.toDouble() / 64)
        val high = 40 * 250.0.pow((peak + 1).toDouble() / 64)
        assertTrue(output[peak] > .5f)
        assertTrue("Peak band $low–$high Hz", low < 1100 && high > 900)
    }
    @Test fun supportsDifferentSampleRatesAndBarCounts() {
        for (rate in listOf(8000, 22050, 44100, 48000, 96000)) for (count in listOf(8, 48, 128)) {
            val output = FloatArray(count)
            Fft().analyze(DoubleArray(1024) { .1 * sin(2 * PI * 1000 * it / rate) }, rate, count, output)
            assertTrue(output.all { it.isFinite() && it in 0f..1f })
            assertTrue(output.any { it > .1f })
        }
    }
    @Test fun invalidSamplesCannotProduceNanBars() {
        val samples = DoubleArray(1024)
        samples[10] = Double.NaN
        samples[20] = Double.POSITIVE_INFINITY
        val output = FloatArray(48)
        Fft().analyze(samples, 44100, 48, output)
        assertTrue(output.all { it == 0f })
    }
    @Test fun silenceClearsPreviousTone() {
        val fft = Fft()
        val output = FloatArray(48)
        fft.analyze(DoubleArray(1024) { sin(2 * PI * 1000 * it / 44100) }, 44100, 48, output)
        fft.analyze(DoubleArray(1024), 44100, 48, output)
        assertTrue(output.all { it == 0f })
    }
    @Test fun rejectsInvalidInputs() {
        val fft = Fft()
        assertThrows(IllegalArgumentException::class.java) { fft.analyze(DoubleArray(10), 44100, 48, FloatArray(48)) }
        assertThrows(IllegalArgumentException::class.java) { fft.analyze(DoubleArray(1024), 0, 48, FloatArray(48)) }
        assertThrows(IllegalArgumentException::class.java) { fft.analyze(DoubleArray(1024), 44100, 49, FloatArray(48)) }
    }
}
