package com.batmusic.app.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Stage 1 measurement-only suite. Preset snapshots mirror MainActivity.applyEqualizerPreset
 * at audit commit f102f71. Does not change production DSP or preset values.
 */
class BatBoostStage1ReferenceMeasurementsTest {
    private data class PresetCase(val name: String, val settings: BatAudioSettings)

    private val presets = listOf(
        PresetCase("Original_Flat", BatAudioSettings(enabled = false, batBoost = 100)),
        PresetCase("Bass_Focus", BatAudioSettings(enabled = true, batBoost = 100, equalizerEnabled = true,
            bandGainsDb = listOf(3.5f, 3f, 2f, .5f, -.5f, -1f, -1f, 0f, .5f, 0f), bassBoost = 18)),
        PresetCase("Rock", BatAudioSettings(enabled = true, batBoost = 100, equalizerEnabled = true,
            bandGainsDb = listOf(2.5f, 2f, 1f, -.5f, .5f, 1.5f, 2.5f, 2.5f, 1.5f, .5f))),
        PresetCase("Metal", BatAudioSettings(enabled = true, batBoost = 100, equalizerEnabled = true,
            bandGainsDb = listOf(2f, 1.5f, .5f, -1.5f, -1f, 1f, 3f, 2.5f, .5f, -1f))),
        PresetCase("Classical", BatAudioSettings(enabled = true, batBoost = 100, equalizerEnabled = true,
            bandGainsDb = listOf(0f, 0f, 0f, 0f, 0f, 0f, .5f, 1f, 1f, .5f))),
        PresetCase("Electronic", BatAudioSettings(enabled = true, batBoost = 100, equalizerEnabled = true,
            bandGainsDb = listOf(3f, 2.5f, 1f, 0f, -1f, 0f, 1f, 2f, 2.5f, 2f))),
        PresetCase("Vocal", BatAudioSettings(enabled = true, batBoost = 100, equalizerEnabled = true,
            bandGainsDb = listOf(-1.5f, -1f, -.5f, .5f, 1.5f, 2f, 1.5f, .5f, 0f, -.5f))),
        PresetCase("Alternative_Indie", BatAudioSettings(enabled = true, batBoost = 100, equalizerEnabled = true,
            bandGainsDb = listOf(-.5f, 0f, 1f, 2f, 1.5f, .5f, 1f, 1.5f, .5f, -.5f))),
        PresetCase("BATLOUD", BatAudioSettings(enabled = true, batBoost = 160, equalizerEnabled = true,
            bandGainsDb = listOf(1f, .8f, .4f, 0f, 0f, .5f, 1f, .8f, .4f, 0f),
            preGainDb = 3f, compressorEnabled = true, compressorThresholdDb = -14f,
            compressorRatio = 2.5f, compressorAttackMs = 12f, compressorReleaseMs = 180f,
            compressorMakeupDb = 3f, limiterReleaseMs = 90f, multibandEnabled = true))
    )

    private data class Signal(val name: String, val samples: FloatArray)

    @Test
    fun presetReferenceSignalMatrixPrintsMeasuredMetrics() {
        val sampleRate = 48_000
        val frames = 8_192
        val signals = listOf(
            Signal("sine_1k", FloatArray(frames) { i -> (.22 * sin(2.0 * PI * 1_000 * i / sampleRate)).toFloat() }),
            Signal("pink_noise", pinkNoise(frames, 0x42544cL)),
            Signal("square", FloatArray(frames) { i -> if (sin(2.0 * PI * 997 * i / sampleRate) >= 0) .28f else -.28f }),
            Signal("transient_train", FloatArray(frames) { i -> if (i % 1024 < 8) (0.75 * (1.0 - (i % 8) / 8.0)).toFloat() else 0f }),
            Signal("near_nyquist_20k", FloatArray(frames) { i -> (.18 * sin(2.0 * PI * 20_000 * i / sampleRate + .37)).toFloat() }),
            Signal("crest_bursts", FloatArray(frames) { i -> if (i % 2048 < 256) (.65 * sin(2.0 * PI * 4_000 * i / sampleRate)).toFloat() else (.025 * sin(2.0 * PI * 440 * i / sampleRate)).toFloat() })
        )
        println("STAGE1_PRESET_SIGNAL,name,signal,input_rms_db,output_rms_db,measured_gain_db,sample_peak,true_peak_dbtp,limiter_max_block_gr_db,compressor_max_block_gr_db")
        for (preset in presets) for (signal in signals) {
            val result = runProcessor(preset.settings, sampleRate, signal.samples)
            val inputRms = rms(signal.samples)
            val outputRms = rms(result.samples)
            val gainDb = db(outputRms / inputRms)
            val samplePeak = result.samples.maxOf { abs(it.toDouble()) }
            val tpDbtp = db(independentTruePeak4x(result.samples))
            println("STAGE1_PRESET_SIGNAL,${preset.name},${signal.name},${db(inputRms)},${db(outputRms)},${gainDb},${samplePeak},${tpDbtp},${result.limiterGrMax},${result.compressorGrMax}")
            assertTrue("Non-finite sample in ${preset.name}/${signal.name}", result.samples.all { it.isFinite() })
            assertTrue("Invalid true-peak measurement", tpDbtp.isFinite())
        }
    }

    @Test
    fun limiterIndependentTruePeakPhaseAndSampleRateSweepPrintsResults() {
        val rates = intArrayOf(44_100, 48_000, 96_000)
        val phases = doubleArrayOf(0.0, PI / 8, PI / 4, 3 * PI / 8, PI / 2, 5 * PI / 8, 3 * PI / 4, 7 * PI / 8)
        println("STAGE1_LIMITER_TP,sample_rate,signal,phase_rad,input_sample_peak,output_sample_peak,reference_true_peak_dbtp,block_gr_db")
        for (rate in rates) for (phase in phases) {
            val frames = 8_192
            val input = FloatArray(frames) { i -> sin(2.0 * PI * .25 * i + phase).toFloat() }
            val output = runProcessor(BatAudioSettings(enabled = true, batBoost = 200, compressorEnabled = false), rate, input)
            val tpDbtp = db(independentTruePeak4x(output.samples))
            println("STAGE1_LIMITER_TP,${rate},fs4_sine,${phase},${output.samples.maxOf { abs(it.toDouble()) }},${output.samples.maxOf { abs(it.toDouble()) }},${tpDbtp},${output.limiterGrMax}")
            assertTrue("Non-finite limiter output at $rate Hz phase $phase", output.samples.all { it.isFinite() })
            assertTrue("Non-finite independent TP at $rate Hz phase $phase", tpDbtp.isFinite())
        }
    }

    @Test
    fun accumulatedGainStagesAreMeasuredAndPrintedWithoutChangingBudget() {
        val rate = 48_000
        val input = FloatArray(16_384) { i -> (.025 * sin(2.0 * PI * 997 * i / rate)).toFloat() }
        val cases = listOf(
            "gain_only" to BatAudioSettings(enabled = true, batBoost = 180),
            "pregain_batgain" to BatAudioSettings(enabled = true, preGainDb = 6f, batBoost = 180),
            "eq_stack_bass" to BatAudioSettings(enabled = true, equalizerEnabled = true,
                bandGainsDb = List(10) { 4f }, bassBoost = 50),
            "compressor_makeup" to BatAudioSettings(enabled = true, compressorEnabled = true,
                compressorThresholdDb = -24f, compressorRatio = 4f, compressorMakeupDb = 6f),
            "combined_all_stages" to BatAudioSettings(enabled = true, equalizerEnabled = true,
                bandGainsDb = List(10) { 3f }, bassBoost = 35, preGainDb = 4f, batBoost = 180,
                compressorEnabled = true, compressorThresholdDb = -24f, compressorRatio = 4f,
                compressorMakeupDb = 4f)
        )
        println("STAGE1_GAIN_STACK,scenario,nominal_control_sum_db,measured_rms_gain_db,prelim_limiter_gr_db,compressor_gr_db")
        for ((name, settings) in cases) {
            val output = runProcessor(settings, rate, input)
            val nominal = (settings.preGainDb + settings.bandGainsDb.filter { it > 0f }.sum() +
                settings.bassBoost * .12f + (settings.batBoost.coerceIn(100, 200) - 100) / 100f * 6.0206f +
                if (settings.compressorEnabled) settings.compressorMakeupDb else 0f)
            println("STAGE1_GAIN_STACK,${name},${nominal},${db(rms(output.samples) / rms(input))},${output.limiterGrMax},${output.compressorGrMax}")
            assertTrue("Non-finite accumulated gain result for $name", output.samples.all { it.isFinite() })
        }
    }

    private data class ProcessResult(val samples: FloatArray, val limiterGrMax: Float, val compressorGrMax: Float)

    private fun runProcessor(settings: BatAudioSettings, sampleRate: Int, source: FloatArray): ProcessResult {
        val processor = BatAudioProcessor()
        processor.settings = settings
        processor.configure(AudioProcessor.AudioFormat(sampleRate, 1, C.ENCODING_PCM_FLOAT))
        processor.flush()
        val destination = FloatArray(source.size)
        val input = ByteBuffer.allocateDirect(512 * 4).order(ByteOrder.nativeOrder())
        var inFrame = 0
        var outFrame = 0
        var limiterMax = 0f
        var compressorMax = 0f
        while (inFrame < source.size) {
            val count = minOf(512, source.size - inFrame)
            input.clear()
            for (i in 0 until count) input.putFloat(source[inFrame + i])
            input.flip()
            processor.queueInput(input)
            outFrame += copyOutput(processor, destination, outFrame)
            limiterMax = max(limiterMax, processor.limiterGainReductionDb)
            compressorMax = max(compressorMax, processor.compressorGainReductionDb)
            inFrame += count
        }
        processor.queueEndOfStream()
        outFrame += copyOutput(processor, destination, outFrame)
        check(outFrame == source.size) { "PCM frame mismatch: expected ${source.size}, got $outFrame" }
        return ProcessResult(destination, limiterMax, compressorMax)
    }

    private fun copyOutput(processor: BatAudioProcessor, target: FloatArray, offset: Int): Int {
        val buffer = processor.output.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val count = buffer.remaining()
        buffer.get(target, offset, count)
        return count
    }

    private fun pinkNoise(size: Int, seed: Long): FloatArray {
        var state = seed
        fun random(): Double {
            state = state * 6364136223846793005L + 1442695040888963407L
            return ((state ushr 33).toInt().toDouble() / Int.MAX_VALUE) * 2.0 - 1.0
        }
        var b0 = 0.0; var b1 = 0.0; var b2 = 0.0; var b3 = 0.0; var b4 = 0.0; var b5 = 0.0; var b6 = 0.0
        return FloatArray(size) {
            val white = random()
            b0 = .99886 * b0 + white * .0555179
            b1 = .99332 * b1 + white * .0750759
            b2 = .96900 * b2 + white * .1538520
            b3 = .86650 * b3 + white * .3104856
            b4 = .55000 * b4 + white * .5329522
            b5 = -.7616 * b5 - white * .0168980
            val pink = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * .5362
            b6 = white * .115926
            (pink * .11).toFloat()
        }
    }

    /** Test-only 4x FIR interpolator, deliberately independent from limiter internals. */
    private fun independentTruePeak4x(samples: FloatArray): Double {
        val phases = 4
        val taps = 32
        val half = taps / 2
        var peak = 0.0
        for (center in half until samples.size - half) for (phase in 0 until phases) {
            val frac = phase.toDouble() / phases
            var value = 0.0
            var norm = 0.0
            for (tap in -half + 1..half) {
                val x = frac - tap
                val sinc = if (abs(x) < 1e-12) 1.0 else sin(PI * x) / (PI * x)
                val window = .42 + .5 * cos(PI * x / half) + .08 * cos(2 * PI * x / half)
                val weight = sinc * window
                value += samples[center + tap] * weight
                norm += weight
            }
            peak = max(peak, abs(value / norm))
        }
        return peak
    }

    private fun rms(samples: FloatArray): Double = sqrt(samples.sumOf { it.toDouble() * it.toDouble() } / samples.size)
    private fun db(value: Double): Double = 20.0 * log10(value.coerceAtLeast(1e-12))
}
