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
    private val spectrumAnalyzer = BatSpectrumAnalyzer(4096)
    private val spectrumMagnitudes = FloatArray(2048)

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
        spectrumAnalyzer.analyze(visualizerSamples, spectrumMagnitudes)
        val spectrum = FloatArray(16) { band ->
            (spectrumMagnitudes[spectrumBins[band]] * 2f).coerceIn(0f, 1f)
        }
        return AudioVisualizerFrame(waveform, spectrum)
    }
    fun settingsSnapshot() = currentSettings

    companion object {
        private val spectrumBins = intArrayOf(3, 5, 8, 11, 16, 23, 31, 43, 60, 84, 117, 163, 227, 315, 440, 700)
    }
}
