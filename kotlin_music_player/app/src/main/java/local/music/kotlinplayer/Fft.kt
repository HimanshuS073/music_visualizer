// SPDX-FileCopyrightText: 2026 Himanshu_Sharma (HimanshuS073)
// SPDX-License-Identifier: GPL-3.0-or-later

package local.music.kotlinplayer

import kotlin.math.*

class Fft(val size: Int = 1024) {
    private val real: DoubleArray
    private val imaginary: DoubleArray
    private val window: DoubleArray
    private val reverse: IntArray
    private val cosine: DoubleArray
    private val sine: DoubleArray

    init {
        require(size in 256..4096 && size and (size - 1) == 0)
        real = DoubleArray(size)
        imaginary = DoubleArray(size)
        window = DoubleArray(size) { .5 - .5 * cos(2 * PI * it / (size - 1)) }
        val bits = Integer.numberOfTrailingZeros(size)
        reverse = IntArray(size) { Integer.reverse(it) ushr (32 - bits) }
        cosine = DoubleArray(size / 2) { cos(2 * PI * it / size) }
        sine = DoubleArray(size / 2) { -sin(2 * PI * it / size) }
    }

    fun analyze(samples: DoubleArray, rate: Int, count: Int, output: FloatArray) {
        require(samples.size == size && rate > 0 && count in 1..output.size)
        for (i in samples.indices) {
            real[reverse[i]] = (if (samples[i].isFinite()) samples[i] else 0.0) * window[i]
            imaginary[reverse[i]] = 0.0
        }
        var length = 2
        while (length <= size) {
            val half = length / 2
            for (start in 0 until size step length) for (j in 0 until half) {
                val a = start + j
                val b = a + half
                val angle = j * size / length
                val r = cosine[angle] * real[b] - sine[angle] * imaginary[b]
                val im = cosine[angle] * imaginary[b] + sine[angle] * real[b]
                real[b] = real[a] - r
                imaginary[b] = imaginary[a] - im
                real[a] += r
                imaginary[a] += im
            }
            length *= 2
        }
        val maximum = min(10000.0, rate / 2.0).coerceAtLeast(40.0)
        for (bar in 0 until count) {
            val low = (40 * (maximum / 40).pow(bar.toDouble() / count) * size / rate)
                .toInt().coerceIn(1, size / 2 - 1)
            val high = (40 * (maximum / 40).pow((bar + 1).toDouble() / count) * size / rate)
                .toInt().coerceIn(low + 1, size / 2)
            var peak = 0.0
            for (bin in low until high) peak = max(peak, hypot(real[bin], imaginary[bin]) * 4 / size)
            output[bar] = ((20 * log10(max(1e-9, peak)) + 65) / 57).coerceIn(0.0, 1.0).toFloat()
        }
    }
}
