package com.batmusic.app.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/**
 * Streaming BS.1770-style loudness meter.
 *
 * Feed interleaved, finite PCM samples in playback order. Call reset() at each
 * media-item boundary. The meter reports momentary loudness (400 ms) and
 * integrated loudness with the -70 LUFS absolute and -10 LU relative gates.
 *
 * This is a measurement component only: it never changes playback gain.
 */
class LoudnessMeter(
    private val sampleRate: Int,
    private val channelCount: Int
) {
    init {
        require(sampleRate >= 8_000) { "sampleRate must be at least 8 kHz" }
        require(channelCount in 1..8) { "channelCount must be between 1 and 8" }
    }

    @Volatile
    var momentaryLufs: Float = SILENCE_LUFS
        private set

    @Volatile
    var integratedLufs: Float = SILENCE_LUFS
        private set

    @Volatile
    var measuredBlocks: Int = 0
        private set

    private val shelf = Array(channelCount) { Biquad() }
    private val highPass = Array(channelCount) { Biquad() }
    private val weights = DoubleArray(channelCount) { channelWeight(it, channelCount) }
    private val blockFrames = maxOf(1, sampleRate / 10) // 100 ms
    private val windowBlocks = 4 // 400 ms momentary window
    private val recentEnergy = DoubleArray(windowBlocks)
    private var recentWrite = 0
    private var recentCount = 0
    private var recentEnergySum = 0.0
    private var framesInBlock = 0
    private val channelEnergy = DoubleArray(channelCount)
    private val integratedEnergyByBin = DoubleArray(701)
    private val integratedCountByBin = LongArray(701)

    private val shelfCoefficients = highShelf(
        sampleRate.toDouble(), 1681.974450955533, 3.999843853973347, 0.7071752369554196
    )
    private val highPassCoefficients = makeHighPassCoefficients(
        sampleRate.toDouble(), 38.13547087602444, 0.5003270373238773
    )

    /** Accepts interleaved PCM floats; invalid samples are treated as silence. */
    fun process(interleaved: FloatArray, frameCount: Int = interleaved.size / channelCount) {
        require(frameCount >= 0 && frameCount * channelCount <= interleaved.size)
        for (frame in 0 until frameCount) {
            val offset = frame * channelCount
            for (channel in 0 until channelCount) {
                val raw = interleaved[offset + channel]
                val finite = if (raw.isFinite()) raw.toDouble() else 0.0
                val weighted = highPass[channel].process(shelf[channel].process(finite, shelfCoefficients), highPassCoefficients)
                channelEnergy[channel] += weighted * weighted
            }
            framesInBlock++
            if (framesInBlock >= blockFrames) finish100MsBlock()
        }
    }

    /** Clears filter history and all accumulated track measurements. */
    fun reset() {
        shelf.forEach(Biquad::reset)
        highPass.forEach(Biquad::reset)
        channelEnergy.fill(0.0)
        recentEnergy.fill(0.0)
        recentWrite = 0
        recentCount = 0
        recentEnergySum = 0.0
        framesInBlock = 0
        integratedEnergyByBin.fill(0.0)
        integratedCountByBin.fill(0L)
        measuredBlocks = 0
        momentaryLufs = SILENCE_LUFS
        integratedLufs = SILENCE_LUFS
    }

    private fun finish100MsBlock() {
        var energy = 0.0
        for (channel in 0 until channelCount) {
            energy += weights[channel] * channelEnergy[channel] / framesInBlock.coerceAtLeast(1)
            channelEnergy[channel] = 0.0
        }
        framesInBlock = 0

        if (recentCount == windowBlocks) recentEnergySum -= recentEnergy[recentWrite]
        else recentCount++
        recentEnergy[recentWrite] = energy
        recentEnergySum += energy
        recentWrite = (recentWrite + 1) % windowBlocks

        if (recentCount == windowBlocks) {
            val windowEnergy = recentEnergySum / windowBlocks
            momentaryLufs = toLufs(windowEnergy)
            appendIntegratedEnergy(windowEnergy)
            updateIntegrated()
        }
    }

    private fun appendIntegratedEnergy(energy: Double) {
        measuredBlocks++
        val loudness = toLufs(energy)
        if (loudness < ABSOLUTE_GATE_LUFS) return
        val bin = ((loudness - ABSOLUTE_GATE_LUFS) * 10.0).toInt().coerceIn(0, 700)
        integratedEnergyByBin[bin] += energy
        integratedCountByBin[bin]++
    }

    private fun updateIntegrated() {
        var absoluteSum = 0.0
        var absoluteCount = 0L
        for (bin in integratedEnergyByBin.indices) {
            absoluteSum += integratedEnergyByBin[bin]
            absoluteCount += integratedCountByBin[bin]
        }
        if (absoluteCount == 0L) {
            integratedLufs = SILENCE_LUFS
            return
        }
        val preliminary = absoluteSum / absoluteCount
        val relativeGate = toLufs(preliminary) - RELATIVE_GATE_LU
        val threshold = maxOf(ABSOLUTE_GATE_LUFS, relativeGate)
        var gatedSum = 0.0
        var gatedCount = 0L
        for (bin in integratedEnergyByBin.indices) {
            val binCenterLufs = ABSOLUTE_GATE_LUFS + (bin + 0.5) / 10.0
            if (binCenterLufs >= threshold) {
                gatedSum += integratedEnergyByBin[bin]
                gatedCount += integratedCountByBin[bin]
            }
        }
        integratedLufs = if (gatedCount > 0L) toLufs(gatedSum / gatedCount) else SILENCE_LUFS
    }

    private class Biquad {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun process(x: Double, c: Coefficients): Double {
            val y = c.b0 * x + c.b1 * x1 + c.b2 * x2 - c.a1 * y1 - c.a2 * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = if (y.isFinite()) y else 0.0
            return y1
        }

        fun reset() { x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0 }
    }

    private data class Coefficients(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

    companion object {
        const val SILENCE_LUFS = -120f
        const val ABSOLUTE_GATE_LUFS = -70.0
        const val RELATIVE_GATE_LU = 10.0
        private const val LUFS_OFFSET = -0.691

        private fun toLufs(energy: Double): Float =
            if (energy <= 1e-20 || !energy.isFinite()) SILENCE_LUFS
            else (LUFS_OFFSET + 10.0 * log10(energy)).toFloat()

        private fun channelWeight(index: Int, channels: Int): Double {
            // BS.1770: LFE is excluded; surround channels receive +1.5 dB.
            return when {
                channels == 6 && index == 3 -> 0.0
                channels == 6 && index >= 4 -> 1.41
                channels == 8 && index == 3 -> 0.0
                channels == 8 && index >= 4 -> 1.41
                else -> 1.0
            }
        }

        private fun makeHighPassCoefficients(fs: Double, frequency: Double, q: Double): Coefficients {
            val w = 2.0 * PI * frequency / fs
            val c = cos(w)
            val alpha = sin(w) / (2.0 * q)
            return normalize((1.0 + c) / 2.0, -(1.0 + c), (1.0 + c) / 2.0,
                1.0 + alpha, -2.0 * c, 1.0 - alpha)
        }

        private fun highShelf(fs: Double, frequency: Double, gainDb: Double, slope: Double): Coefficients {
            val a = 10.0.pow(gainDb / 40.0)
            val w = 2.0 * PI * frequency / fs
            val c = cos(w)
            val s = sin(w)
            val alpha = s / 2.0 * kotlin.math.sqrt((a + 1.0 / a) * (1.0 / slope - 1.0) + 2.0)
            val beta = 2.0 * kotlin.math.sqrt(a) * alpha
            val b0 = a * ((a + 1.0) + (a - 1.0) * c + beta)
            val b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * c)
            val b2 = a * ((a + 1.0) + (a - 1.0) * c - beta)
            val a0 = (a + 1.0) - (a - 1.0) * c + beta
            val a1 = 2.0 * ((a - 1.0) - (a + 1.0) * c)
            val a2 = (a + 1.0) - (a - 1.0) * c - beta
            return normalize(b0, b1, b2, a0, a1, a2)
        }

        private fun normalize(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
            Coefficients(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }
}

/** Small radix-2 spectrum analyzer. Returns linear magnitudes for bins [0, N/2). */
class BatSpectrumAnalyzer(size: Int = 1024) {
    val fftSize: Int = size.also { require(it >= 64 && (it and (it - 1)) == 0) }
    private val real = DoubleArray(fftSize)
    private val imag = DoubleArray(fftSize)

    fun analyze(monoSamples: FloatArray, destination: FloatArray) {
        require(monoSamples.size >= fftSize)
        require(destination.size >= fftSize / 2)
        for (i in 0 until fftSize) {
            val window = 0.5 - 0.5 * cos(2.0 * PI * i / (fftSize - 1))
            real[i] = (if (monoSamples[i].isFinite()) monoSamples[i].toDouble() else 0.0) * window
            imag[i] = 0.0
        }
        var j = 0
        for (i in 1 until fftSize) {
            var bit = fftSize shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val r = real[i]; real[i] = real[j]; real[j] = r
                val im = imag[i]; imag[i] = imag[j]; imag[j] = im
            }
        }
        var length = 2
        while (length <= fftSize) {
            val angle = -2.0 * PI / length
            val wLenR = cos(angle)
            val wLenI = sin(angle)
            var start = 0
            while (start < fftSize) {
                var wr = 1.0
                var wi = 0.0
                for (k in 0 until length / 2) {
                    val even = start + k
                    val odd = even + length / 2
                    val tr = real[odd] * wr - imag[odd] * wi
                    val ti = real[odd] * wi + imag[odd] * wr
                    real[odd] = real[even] - tr
                    imag[odd] = imag[even] - ti
                    real[even] += tr
                    imag[even] += ti
                    val nextWr = wr * wLenR - wi * wLenI
                    wi = wr * wLenI + wi * wLenR
                    wr = nextWr
                }
                start += length
            }
            length = length shl 1
        }
        val scale = 2.0 / fftSize
        for (bin in 0 until fftSize / 2) {
            destination[bin] = (kotlin.math.sqrt(real[bin] * real[bin] + imag[bin] * imag[bin]) * scale).toFloat()
        }
    }
}
