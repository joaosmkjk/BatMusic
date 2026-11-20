package com.batmusic.app.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Offline measurement harness for the current BatAudioProcessor. Run its main() or JUnit test. */
object BatAudioBaselineHarness {
    private val bandHz = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f)
    private val eqGains = listOf(3f, 2f, 1f, 0f, -1f, 0.5f, 1.5f, -2f, 2f, -1f)

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "distortion") println(measureDistortionContributions())
        else println(measureAll())
    }

    /** Focused control runs that separate linear gain from the active peak limiter. */
    fun measureDistortionContributions(): String {
        val sampleRate = 48_000
        val frames = 24_576
        fun sine(amplitude: Double) = FloatArray(frames) { i -> (amplitude * sin(2.0 * PI * 1_000.0 * i / sampleRate)).toFloat() }
        val cases = listOf(
            Triple("unity_gain_no_limiter", BatAudioSettings(enabled = true, batBoost = 100, compressorEnabled = false), sine(0.5)),
            Triple("batboost_gain_below_limiter_threshold", BatAudioSettings(enabled = true, batBoost = 200, compressorEnabled = false), sine(0.45)),
            Triple("baseline_fullscale_plus_batboost_and_limiter", BatAudioSettings(enabled = true, batBoost = 200, compressorEnabled = false), sine(1.0))
        )
        return buildString {
            appendLine("DISTORTION_DECOMPOSITION_V1; eq=disabled; compressor=disabled; frequency=1000Hz")
            for ((name, settings, input) in cases) {
                val output = process(settings, sampleRate, input)
                appendLine("$name: input_peak=${fmt(input.maxOf { abs(it.toDouble()) })}; output_peak=${fmt(output.maxOf { abs(it.toDouble()) })}; thdn_coherent_db=${fmt(thdn(output, 1_000.0, sampleRate))}; thdn_old_noncoherent_db=${fmt(thdnWithStart(output, 1_000.0, sampleRate, 2_048))}")
                if (name == "baseline_fullscale_plus_batboost_and_limiter") {
                    appendLine("baseline_steady_state_cycle_offset_2016=${output.drop(2_016).take(48).joinToString(",") { fmt(it.toDouble()) }}")
                }
            }
        }
    }

    fun measureAll(): String {
        val eq = measureEqResponse()
        val bypassBitExact = measureBypassBitExact()
        val fullScale = limiterSignal("sine_0dbfs_1khz", 48_000) { i -> sin(2.0 * PI * 1_000.0 * i / 48_000.0).toFloat() }
        val interSample = limiterSignal("sine_fs4_phase45", 48_000) { i -> sin(2.0 * PI * (48_000.0 / 4.0) * i / 48_000.0 + PI / 4.0).toFloat() }
        val square = limiterSignal("square_clipped", 48_000) { i -> if (sin(2.0 * PI * 997.0 * i / 48_000.0) >= 0.0) 1f else -1f }
        val squarePhaseSweep = measureSquarePhaseSweep()
        val rateSafety = measureRateSafety()
        val stereo = measureStereoLinking()
        val pcm16BypassBitExact = measurePcm16BypassBitExact()
        val tailPreserved = measureTailPreserved()
        val gainLinearityDb = measureGainLinearityDb()
        val compressorImpactDb = measureCompressorImpactDb()
        val meterRms = measureMeterRms()
        val pcm16ProcessedSafe = measurePcm16ProcessedSafe()
        val latencies = intArrayOf(44_100, 48_000, 96_000).map { it to measureImpulseLatency(it) }
        val allocations = measureAllocations()
        return buildString {
            appendLine("BATBOOST_BASELINE_HARNESS_V1")
            appendLine("eq_sample_rates_hz=44100,48000,96000; frequencies_hz=${eq.frequencies.joinToString(",")}; gains_db=${eqGains.joinToString(",")}")
            appendLine("eq_max_abs_error_db=${fmt(eq.maxErrorDb)}; eq_mean_abs_error_db=${fmt(eq.meanErrorDb)}")
            appendLine("bypass_pcm_float_bit_exact=$bypassBitExact")
            appendLine("bypass_pcm16_bit_exact=$pcm16BypassBitExact")
            appendLine("limiter_input=batBoost_200_percent(+6.0206dB), compressor_disabled, float_pcm, ceiling_code=0.985, internal_margin_db=0.5")
            listOf(fullScale, interSample, square).forEach { metric ->
                appendLine("${metric.name}: input_peak=${fmt(metric.inputPeak)} output_peak=${fmt(metric.outputPeak)} true_peak_8x=${fmt(metric.truePeak8x)} peak_reduction_db=${fmt(metric.peakReductionDb)} samples_abs_ge_1=${metric.samplesAtOrAboveOne}")
            }
            appendLine("square_phase_sweep_true_peak_8x_min=${fmt(squarePhaseSweep.first)}; max=${fmt(squarePhaseSweep.second)}; phase_count=8")
            rateSafety.forEach { appendLine("${it.name}: true_peak_8x=${fmt(it.truePeak8x)}; output_peak=${fmt(it.outputPeak)}") }
            appendLine("stereo_linked_true_peak_8x=${fmt(stereo.truePeak)}; channel_peak_ratio=${fmt(stereo.channelPeakRatio)}")
            appendLine("batboost_200_gain_error_db=${fmt(gainLinearityDb)}")
            appendLine("compressor_default_enabled=${BatAudioSettings().compressorEnabled}")
            appendLine("compressor_on_vs_transparent_rms_delta_db=${fmt(compressorImpactDb)}")
            appendLine("meter_pre_limiter_rms_db=${fmt(meterRms.first.toDouble())}; meter_output_rms_db=${fmt(meterRms.second.toDouble())}")
            appendLine("pcm16_processed_output_safe=$pcm16ProcessedSafe")
            appendLine("eos_last_sample_preserved=$tailPreserved")
            appendLine("sine_0dbfs_1khz_thdn_db=${fmt(fullScale.thdnDb)}")
            appendLine("impulse_latency_samples=${latencies.joinToString(",") { "${it.first}:${it.second}" }}")
            appendLine("queueInput_allocation_measurement=${allocations.allocationMeasurement}; queueInput_ns_per_block=${fmt(allocations.nsPerBlock)}; measured_blocks=${allocations.blocks}; warmup_blocks=${allocations.warmupBlocks}; block_frames=${allocations.frames}")
        }
    }

    private fun measureBypassBitExact(): Boolean {
        val processor = configuredProcessor(BatAudioSettings(enabled = false), 48_000)
        val bits = intArrayOf(0x00000000, 0x80000000.toInt(), 0x3eaaaaab, 0xbf123456.toInt(), 0x7fc01234)
        val input = ByteBuffer.allocateDirect(bits.size * 4).order(ByteOrder.nativeOrder())
        bits.forEach(input::putInt)
        input.flip()
        processor.queueInput(input)
        processor.queueEndOfStream()
        val output = processor.output.order(ByteOrder.nativeOrder())
        if (output.remaining() != bits.size * 4) return false
        for (expected in bits) if (output.int != expected) return false
        return true
    }

    private fun measurePcm16BypassBitExact(): Boolean {
        val processor = configuredProcessor(BatAudioSettings(enabled = false), 48_000, encoding = C.ENCODING_PCM_16BIT)
        val values = shortArrayOf(Short.MIN_VALUE, -12345, -1, 0, 1, 23456, Short.MAX_VALUE)
        val input = ByteBuffer.allocateDirect(values.size * 2).order(ByteOrder.nativeOrder())
        values.forEach(input::putShort)
        input.flip()
        processor.queueInput(input)
        processor.queueEndOfStream()
        val output = processor.output.order(ByteOrder.nativeOrder())
        if (output.remaining() != values.size * 2) return false
        for (expected in values) if (output.short != expected) return false
        return true
    }

    private data class StereoResult(val truePeak: Double, val channelPeakRatio: Double)

    private fun measureStereoLinking(): StereoResult {
        val frames = 24_576
        val input = FloatArray(frames * 2) { sample ->
            val frame = sample / 2
            val amplitude = if (sample % 2 == 0) 1.0 else 0.5
            (amplitude * sin(2.0 * PI * 1_000.0 * frame / 48_000.0)).toFloat()
        }
        val output = process(BatAudioSettings(enabled = true, batBoost = 200, compressorEnabled = false), 48_000, input, channels = 2)
        val left = FloatArray(frames) { output[it * 2] }
        val right = FloatArray(frames) { output[it * 2 + 1] }
        return StereoResult(max(truePeak8x(left), truePeak8x(right)), left.maxOf { abs(it.toDouble()) } / right.maxOf { abs(it.toDouble()) })
    }

    private fun measureGainLinearityDb(): Double {
        val input = FloatArray(24_576) { i -> (0.45 * sin(2.0 * PI * 1_000.0 * i / 48_000.0)).toFloat() }
        val output = process(BatAudioSettings(enabled = true, batBoost = 200, compressorEnabled = false), 48_000, input)
        val start = 2_016
        val inputRms = rms(input, start)
        val outputRms = rms(output, start)
        return 20.0 * log10(outputRms / inputRms) - 6.0206
    }


    /** Positive result means the old default compressor made BATGAIN quieter. */
    private fun measureCompressorImpactDb(): Double {
        val sampleRate = 48_000
        val input = FloatArray(24_576) { i -> (0.8 * sin(2.0 * PI * 1_000.0 * i / sampleRate)).toFloat() }
        val transparent = process(
            BatAudioSettings(enabled = true, batBoost = 200, compressorEnabled = false),
            sampleRate, input
        )
        val compressed = process(
            BatAudioSettings(enabled = true, batBoost = 200, compressorEnabled = true),
            sampleRate, input
        )
        val start = 2_016
        return 20.0 * log10(rms(transparent, start) / rms(compressed, start))
    }


    private fun measureMeterRms(): Pair<Float, Float> {
        val processor = configuredProcessor(
            BatAudioSettings(enabled = true, batBoost = 150, compressorEnabled = false), 48_000
        )
        val frames = 512
        val input = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        repeat(frames) { frame -> input.putFloat(0.2f * sin(2.0 * PI * 1_000.0 * frame / 48_000.0).toFloat()) }
        input.flip()
        processor.queueInput(input)
        return processor.preLimiterRmsDb to processor.outputRmsDb
    }


    private fun measurePcm16ProcessedSafe(): Boolean {
        val frames = 1_024
        val samples = ShortArray(frames) { frame ->
            (12_000.0 * sin(2.0 * PI * 440.0 * frame / 48_000.0)).toInt().toShort()
        }
        val processor = configuredProcessor(
            BatAudioSettings(enabled = true, batBoost = 150, compressorEnabled = false),
            48_000, encoding = C.ENCODING_PCM_16BIT
        )
        val input = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach(input::putShort)
        input.flip()
        processor.queueInput(input)
        val first = processor.output.order(ByteOrder.nativeOrder()).asShortBuffer()
        val firstCount = first.remaining()
        while (first.hasRemaining()) if (first.get().toInt() !in -32768..32767) return false
        processor.queueEndOfStream()
        val tail = processor.output.order(ByteOrder.nativeOrder()).asShortBuffer()
        val tailCount = tail.remaining()
        while (tail.hasRemaining()) if (tail.get().toInt() !in -32768..32767) return false
        return firstCount + tailCount == samples.size
    }

    private data class EqResult(val frequencies: List<Int>, val maxErrorDb: Double, val meanErrorDb: Double)

    private fun measureEqResponse(): EqResult {
        val frequencies = (0 until 31).map { index -> (31.0 * (16_000.0 / 31.0).pow(index / 30.0)).toInt() }
        var maxError = 0.0
        var errorSum = 0.0
        var count = 0
        for (sampleRate in intArrayOf(44_100, 48_000, 96_000)) {
            val settings = BatAudioSettings(
                enabled = true,
                batBoost = 100,
                equalizerEnabled = true,
                bandGainsDb = eqGains,
                compressorEnabled = false
            )
            for (frequency in frequencies) {
                val frameCount = sampleRate / 2
                val input = FloatArray(frameCount) { frame -> (0.1 * sin(2.0 * PI * frequency * frame / sampleRate)).toFloat() }
                val output = process(settings, sampleRate, input)
                val start = frameCount * 3 / 4
                val inputRms = rms(input, start)
                val outputRms = rms(output, start)
                val measuredDb = 20.0 * log10(outputRms / inputRms)
                val referenceDb = eqGains.indices.sumOf { band ->
                    biquadMagnitudeDb(frequency.toDouble(), bandHz[band].toDouble(), eqGains[band].toDouble(), sampleRate.toDouble(), 1.0)
                }
                val error = abs(measuredDb - referenceDb)
                maxError = max(maxError, error)
                errorSum += error
                count++
            }
        }
        return EqResult(frequencies, maxError, errorSum / count)
    }

    private data class LimiterResult(
        val name: String,
        val inputPeak: Double,
        val outputPeak: Double,
        val truePeak8x: Double,
        val peakReductionDb: Double,
        val samplesAtOrAboveOne: Int,
        val thdnDb: Double
    )

    private fun limiterSignal(name: String, sampleRate: Int, signal: (Int) -> Float): LimiterResult {
        val settings = BatAudioSettings(enabled = true, batBoost = 200, compressorEnabled = false)
        val input = FloatArray(24_576, signal)
        val output = process(settings, sampleRate, input)
        val inputPeak = input.maxOf { abs(it.toDouble()) }
        val outputPeak = output.maxOf { abs(it.toDouble()) }
        return LimiterResult(
            name = name,
            inputPeak = inputPeak,
            outputPeak = outputPeak,
            truePeak8x = truePeak8x(output),
            peakReductionDb = 20.0 * log10(outputPeak / (inputPeak * 10.0.pow(6.0206 / 20.0))),
            samplesAtOrAboveOne = output.count { abs(it) >= 1f },
            thdnDb = if (name == "sine_0dbfs_1khz") thdn(output, 1_000.0, sampleRate) else Double.NaN
        )
    }

    private fun measureImpulseLatency(sampleRate: Int): Int {
        val input = FloatArray(512)
        input[0] = 0.5f
        val processor = configuredProcessor(BatAudioSettings(enabled = true, batBoost = 100, compressorEnabled = false), sampleRate)
        val buffer = ByteBuffer.allocateDirect(input.size * 4).order(ByteOrder.nativeOrder())
        input.forEach(buffer::putFloat)
        buffer.flip()
        processor.queueInput(buffer)
        val outputFrames = processor.output.remaining() / 4
        return input.size - outputFrames
    }

    private fun measureSquarePhaseSweep(): Pair<Double, Double> {
        val measured = DoubleArray(8) { phaseIndex ->
            limiterSignal("square_phase_$phaseIndex", 48_000) { frame ->
                val phase = phaseIndex * PI / 4.0
                if (sin(2.0 * PI * 997.0 * frame / 48_000.0 + phase) >= 0.0) 1f else -1f
            }.truePeak8x
        }
        return measured.minOrNull()!! to measured.maxOrNull()!!
    }

    private fun measureRateSafety(): List<LimiterResult> {
        val results = ArrayList<LimiterResult>(4)
        for (sampleRate in intArrayOf(44_100, 96_000)) {
            results += limiterSignal("sine_fs4_phase45_${sampleRate}hz", sampleRate) { frame ->
                sin(2.0 * PI * 0.25 * frame + PI / 4.0).toFloat()
            }
            results += limiterSignal("square_clipped_${sampleRate}hz", sampleRate) { frame ->
                if (sin(2.0 * PI * 997.0 * frame / sampleRate) >= 0.0) 1f else -1f
            }
        }
        return results
    }

    private fun measureTailPreserved(): Boolean {
        val input = FloatArray(127)
        input[input.lastIndex] = 0.5f
        val output = process(BatAudioSettings(enabled = true, batBoost = 100, compressorEnabled = false), 48_000, input)
        return output.size == input.size && output.last().toRawBits() == input.last().toRawBits()
    }

    private data class AllocationResult(
        val allocationMeasurement: String,
        val nsPerBlock: Double,
        val blocks: Int,
        val warmupBlocks: Int,
        val frames: Int
    )

    /**
     * Android's unit-test compilation does not expose the HotSpot-specific
     * com.sun.management.ThreadMXBean API. Keep the processing-time diagnostic,
     * but report allocation measurement as unavailable rather than guessing.
     */
    private fun measureAllocations(): AllocationResult {
        val frames = 256
        val input = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        val processor = configuredProcessor(BatAudioSettings(enabled = true, batBoost = 100, compressorEnabled = false), 48_000)
        val warmupBlocks = 2_000
        repeat(warmupBlocks) { runBlock(processor, input, frames, it) }
        val iterations = 1_000
        var elapsed = 0L
        repeat(iterations) { index ->
            prepareBlock(input, frames, index + warmupBlocks)
            val beforeTime = System.nanoTime()
            processor.queueInput(input)
            elapsed += System.nanoTime() - beforeTime
            processor.output
        }
        return AllocationResult("NOT_MEASURED_ANDROID", elapsed.toDouble() / iterations, iterations, warmupBlocks, frames)
    }

    private fun runBlock(processor: BatAudioProcessor, input: ByteBuffer, frames: Int, offset: Int) {
        prepareBlock(input, frames, offset)
        processor.queueInput(input)
        processor.output
    }

    private fun prepareBlock(input: ByteBuffer, frames: Int, offset: Int) {
        input.clear()
        repeat(frames) { frame -> input.putFloat(0.1f * sin(2.0 * PI * 440.0 * (frame + offset * frames) / 48_000.0).toFloat()) }
        input.flip()
    }

    private fun process(settings: BatAudioSettings, sampleRate: Int, samples: FloatArray, channels: Int = 1): FloatArray {
        require(samples.size % channels == 0)
        val processor = configuredProcessor(settings, sampleRate, channelCount = channels)
        val result = FloatArray(samples.size)
        val blockFrames = 512
        val input = ByteBuffer.allocateDirect(blockFrames * channels * 4).order(ByteOrder.nativeOrder())
        val totalFrames = samples.size / channels
        var offsetFrames = 0
        var outputOffset = 0
        while (offsetFrames < totalFrames) {
            val frames = minOf(blockFrames, totalFrames - offsetFrames)
            val sampleCount = frames * channels
            input.clear()
            repeat(sampleCount) { input.putFloat(samples[offsetFrames * channels + it]) }
            input.flip()
            processor.queueInput(input)
            outputOffset += readOutput(processor, result, outputOffset)
            offsetFrames += frames
        }
        processor.queueEndOfStream()
        outputOffset += readOutput(processor, result, outputOffset)
        check(outputOffset == samples.size) { "Expected ${samples.size} total output samples, got $outputOffset" }
        return result
    }

    private fun readOutput(processor: BatAudioProcessor, destination: FloatArray, offset: Int): Int {
        val output = processor.output.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val count = output.remaining()
        output.get(destination, offset, count)
        return count
    }

    private fun configuredProcessor(
        settings: BatAudioSettings,
        sampleRate: Int,
        channelCount: Int = 1,
        encoding: Int = C.ENCODING_PCM_FLOAT
    ): BatAudioProcessor {
        val processor = BatAudioProcessor()
        processor.settings = settings
        processor.configure(AudioProcessor.AudioFormat(sampleRate, channelCount, encoding))
        processor.flush()
        return processor
    }

    private fun rms(values: FloatArray, start: Int): Double {
        var sum = 0.0
        for (i in start until values.size) sum += values[i] * values[i]
        return sqrt(sum / (values.size - start))
    }

    private fun biquadMagnitudeDb(frequency: Double, center: Double, gainDb: Double, sampleRate: Double, q: Double): Double {
        if (gainDb == 0.0) return 0.0
        val a = 10.0.pow(gainDb / 40.0)
        val w = 2.0 * PI * center / sampleRate
        val alpha = sin(w) / (2.0 * q)
        val c = cos(w)
        val a0 = 1.0 + alpha / a
        val b0 = (1.0 + alpha * a) / a0
        val b1 = (-2.0 * c) / a0
        val b2 = (1.0 - alpha * a) / a0
        val a1 = (-2.0 * c) / a0
        val a2 = (1.0 - alpha / a) / a0
        val phase = 2.0 * PI * frequency / sampleRate
        val nr = b0 + b1 * cos(phase) + b2 * cos(2.0 * phase)
        val ni = -b1 * sin(phase) - b2 * sin(2.0 * phase)
        val dr = 1.0 + a1 * cos(phase) + a2 * cos(2.0 * phase)
        val di = -a1 * sin(phase) - a2 * sin(2.0 * phase)
        return 20.0 * log10(hypot(nr, ni) / hypot(dr, di))
    }

    private fun thdn(values: FloatArray, frequency: Double, sampleRate: Int): Double {
        // 2,016 is 42 exact cycles at 1 kHz / 48 kHz. The remaining record is 470 cycles.
        return thdnWithStart(values, frequency, sampleRate, 2_016)
    }

    private fun thdnWithStart(values: FloatArray, frequency: Double, sampleRate: Int, start: Int): Double {
        val n = values.size - start
        var mean = 0.0
        for (i in start until values.size) mean += values[i]
        mean /= n
        var sinProjection = 0.0
        var cosProjection = 0.0
        for (i in start until values.size) {
            val phase = 2.0 * PI * frequency * i / sampleRate
            sinProjection += (values[i] - mean) * sin(phase)
            cosProjection += (values[i] - mean) * cos(phase)
        }
        val sinCoefficient = 2.0 * sinProjection / n
        val cosCoefficient = 2.0 * cosProjection / n
        val fundamentalPower = (sinCoefficient * sinCoefficient + cosCoefficient * cosCoefficient) / 2.0
        var residualPower = 0.0
        for (i in start until values.size) {
            val phase = 2.0 * PI * frequency * i / sampleRate
            val residual = values[i] - mean - sinCoefficient * sin(phase) - cosCoefficient * cos(phase)
            residualPower += residual * residual
        }
        return 10.0 * log10((residualPower / n) / fundamentalPower)
    }

    /** 8x band-limited reconstruction using a 64-tap Blackman-windowed sinc interpolator. */
    private fun truePeak8x(values: FloatArray): Double {
        val halfLength = 32
        var peak = 0.0
        for (base in halfLength until values.size - halfLength) {
            for (phase in 0 until 8) {
                val fraction = phase / 8.0
                var weighted = 0.0
                var weightSum = 0.0
                for (tap in -halfLength + 1..halfLength) {
                    val distance = fraction - tap
                    val normalized = distance / halfLength
                    if (abs(normalized) >= 1.0) continue
                    val window = 0.42 + 0.5 * cos(PI * normalized) + 0.08 * cos(2.0 * PI * normalized)
                    val sinc = if (abs(distance) < 1e-12) 1.0 else sin(PI * distance) / (PI * distance)
                    val weight = window * sinc
                    weighted += values[base + tap] * weight
                    weightSum += weight
                }
                peak = max(peak, abs(weighted / weightSum))
            }
        }
        return peak
    }

    private fun fmt(value: Double): String = if (value.isNaN()) "n/a" else String.format(Locale.US, "%.6f", value)
}

class BatAudioBaselineTest {
    @Test
    fun offlineBaselineHarnessRunsAndReportsFiniteCoreMetrics() {
        val report = BatAudioBaselineHarness.measureAll()
        println(report)
        assertTrue(report.contains("eq_max_abs_error_db="))
        assertTrue(report.contains("queueInput_allocation_measurement=NOT_MEASURED_ANDROID"))
        assertTrue(report.contains("samples_abs_ge_1="))
        assertEquals(0, Regex("samples_abs_ge_1=(\\d+)").findAll(report).map { it.groupValues[1].toInt() }.maxOrNull())
        val eqError = Regex("eq_max_abs_error_db=([0-9.]+)").find(report)!!.groupValues[1].toDouble()
        val thdn = Regex("sine_0dbfs_1khz_thdn_db=(-?[0-9.]+)").find(report)!!.groupValues[1].toDouble()
        val truePeaks = Regex("true_peak_8x=([0-9.]+)").findAll(report).map { it.groupValues[1].toDouble() }.toList() +
            Regex("square_phase_sweep_true_peak_8x_min=([0-9.]+); max=([0-9.]+)").find(report)!!.groupValues[2].toDouble()
        val stereoPeak = Regex("stereo_linked_true_peak_8x=([0-9.]+)").find(report)!!.groupValues[1].toDouble()
        val gainError = Regex("batboost_200_gain_error_db=(-?[0-9.]+)").find(report)!!.groupValues[1].toDouble()
        assertTrue(report.contains("bypass_pcm_float_bit_exact=true"))
        assertTrue(report.contains("bypass_pcm16_bit_exact=true"))
        assertTrue(report.contains("eos_last_sample_preserved=true"))
        assertTrue(report.contains("impulse_latency_samples=44100:52,48000:55,96000:103"))
        assertTrue("EQ response error must remain below 0.1 dB", eqError <= 0.1)
        assertTrue("THD+N must be at most -60 dB", thdn <= -60.0)
        assertTrue("True peak must retain at least 0.25 dB margin under the 0.985 ceiling", truePeaks.all { it <= 0.985 * 10.0.pow(-0.25 / 20.0) })
        assertTrue("Linked stereo true peak must retain ceiling margin", stereoPeak <= 0.985 * 10.0.pow(-0.25 / 20.0))
        assertTrue("BATBOOST linear gain error must remain within 0.01 dB", abs(gainError) <= 0.01)
        assertTrue("New BATBOOST default must not compress the signal", report.contains("compressor_default_enabled=false"))
        val compressorDelta = Regex("compressor_on_vs_transparent_rms_delta_db=(-?[0-9.]+)").find(report)!!.groupValues[1].toDouble()
        assertTrue("The diagnostic signal should expose the old compressor's gain cancellation", compressorDelta > 0.5)
        val meterValues = Regex("meter_pre_limiter_rms_db=(-?[0-9.]+); meter_output_rms_db=(-?[0-9.]+)").find(report)!!
        assertTrue("Pre-limiter RMS meter must report finite audio level", meterValues.groupValues[1].toDouble() in -40.0..0.0)
        assertTrue("Output RMS meter must report finite audio level", meterValues.groupValues[2].toDouble() in -40.0..0.0)
        assertTrue("Processed PCM16 must preserve the frame count and stay in range", report.contains("pcm16_processed_output_safe=true"))
    }
}
