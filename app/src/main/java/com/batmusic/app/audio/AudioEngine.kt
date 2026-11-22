package com.batmusic.app.audio

import androidx.media3.common.util.UnstableApi

data class AudioEngineMeter(
    val peak: Float,
    val limiterActive: Boolean,
    val limitedBlocks: Long,
    val limiterGainReductionDb: Float,
    val compressorGainReductionDb: Float,
    val preLimiterPeak: Float,
    val preLimiterRmsDb: Float,
    val outputRmsDb: Float,
    val momentaryLufs: Float,
    val integratedLufs: Float
)
data class AudioVisualizerFrame(val waveform: FloatArray, val spectrum: FloatArray)

/** Owns the single PCM processor and exposes an immutable snapshot to the service/UI layer. */
@UnstableApi
class AudioEngine {
    val processor = BatAudioProcessor()
    @Volatile private var currentSettings = BatAudioSettings()
    @Volatile var visualizerMode: String = "OFF"
        private set
    private val visualizerSamples = FloatArray(4096)
    private val windowed = FloatArray(4096)
    private val cosine = Array(16) { band -> FloatArray(4096) { i -> kotlin.math.cos(2.0 * Math.PI * spectrumBins[band] * i / 4096.0).toFloat() } }
    private val sine = Array(16) { band -> FloatArray(4096) { i -> kotlin.math.sin(2.0 * Math.PI * spectrumBins[band] * i / 4096.0).toFloat() } }

    fun setVisualizerMode(mode: String) {
        visualizerMode = mode.takeIf { it in setOf("OFF", "SPECTRUM", "WAVEFORM") } ?: "OFF"
        processor.visualizerEnabled = visualizerMode != "OFF"
    }

    fun configure(settings: BatAudioSettings) {
        currentSettings = settings
        processor.settings = settings
    }

    fun meter() = AudioEngineMeter(
        processor.outputPeak, processor.limiterActive, processor.clippingBlocks,
        processor.limiterGainReductionDb, processor.compressorGainReductionDb,
        processor.preLimiterPeak, processor.preLimiterRmsDb, processor.outputRmsDb,
        processor.momentaryLufs, processor.integratedLufs
    )
    fun visualizerFrame(): AudioVisualizerFrame {
        processor.copyVisualizerSamples(visualizerSamples)
        val waveform = FloatArray(128) { i -> visualizerSamples[i * 32] }
        if (visualizerMode != "SPECTRUM") return AudioVisualizerFrame(waveform, FloatArray(16))
        for (i in windowed.indices) {
            val hann = .5f - .5f * kotlin.math.cos(2f * Math.PI.toFloat() * i / (windowed.size - 1))
            windowed[i] = visualizerSamples[i] * hann
        }
        val spectrum = FloatArray(16)
        for (band in spectrum.indices) {
            var real = 0f
            var imaginary = 0f
            for (i in windowed.indices) {
                real += windowed[i] * cosine[band][i]
                imaginary -= windowed[i] * sine[band][i]
            }
            spectrum[band] = (kotlin.math.sqrt(real * real + imaginary * imaginary) * (4f / windowed.size)).coerceIn(0f, 1f)
        }
        return AudioVisualizerFrame(waveform, spectrum)
    }
    fun settingsSnapshot() = currentSettings

    companion object {
        private val spectrumBins = intArrayOf(3, 5, 8, 11, 16, 23, 31, 43, 60, 84, 117, 163, 227, 315, 440, 700)
    }
}
