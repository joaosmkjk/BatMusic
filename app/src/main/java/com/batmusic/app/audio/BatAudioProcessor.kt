package com.batmusic.app.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.log10

/** Immutable, atomically swapped controls. queueInput reads one snapshot per PCM block. */
data class BatAudioSettings(
    val enabled: Boolean = false,
    val batBoost: Int = 100,
    val equalizerEnabled: Boolean = false,
    val bandGainsDb: List<Float> = List(10) { 0f },
    val bassBoost: Int = 0,
    val preGainDb: Float = 0f,
    val compressorEnabled: Boolean = false,
    val compressorThresholdDb: Float = -12f,
    val compressorRatio: Float = 3f,
    val compressorAttackMs: Float = 6f,
    val compressorReleaseMs: Float = 120f,
    val compressorMakeupDb: Float = 0f,
    /** Independent release time for the output true-peak limiter. */
    val limiterReleaseMs: Float = 120f
)

/** PCM processor installed in ExoPlayer's DefaultAudioSink; it never changes system volume. */
@UnstableApi
class BatAudioProcessor : BaseAudioProcessor() {
    @Volatile var settings = BatAudioSettings()
        set(value) {
            field = value
            truePeakLimiter?.setReleaseMs(value.limiterReleaseMs)
        }
    @Volatile var pcmProcessingSupported = false
        private set
    @Volatile var outputPeak = 0f
        private set
    @Volatile var limiterActive = false
        private set
    @Volatile var clippingBlocks: Long = 0
        private set
    @Volatile var limiterGainReductionDb: Float = 0f
        private set
    @Volatile var compressorGainReductionDb: Float = 0f
        private set
    @Volatile var preLimiterPeak: Float = 0f
        private set
    @Volatile var preLimiterRmsDb: Float = -120f
        private set
    @Volatile var outputRmsDb: Float = -120f
        private set
    @Volatile var visualizerEnabled: Boolean = false

    private var channels = 0
    private var sampleRate = 0
    private var formatSupported = false
    private var states = emptyArray<Array<BiquadState>>()
    private var sampleBuffer = FloatArray(0)
    private var rawSampleBits = IntArray(0)
    private var outputSamples = FloatArray(0)
    private var outputRawBits = IntArray(0)
    private var outputBypassed = BooleanArray(0)
    private var truePeakLimiter: LookaheadTruePeakLimiter? = null
    private var configuredEncoding = C.ENCODING_INVALID
    private var appliedSettings: BatAudioSettings? = null
    private var filters = emptyArray<Pair<Int, Coefficients>>()
    private var compressorEnvelope = 0f
    private var attackCoefficient = 0f
    private var releaseCoefficient = 0f
    private val visualizerRing = FloatArray(VISUALIZER_SIZE)
    @Volatile private var visualizerWritePosition = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        formatSupported = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        pcmProcessingSupported = formatSupported
        channels = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        configuredEncoding = inputAudioFormat.encoding
        truePeakLimiter = if (formatSupported) {
            LookaheadTruePeakLimiter(sampleRate, channels, TRUE_PEAK_CEILING, marginDb = 0.5f, lookaheadMs = 5f, releaseMs = settings.limiterReleaseMs)
        } else null
        val initialOutputSamples = (truePeakLimiter?.latencyFrames ?: 0) * channels
        outputSamples = FloatArray(initialOutputSamples)
        outputRawBits = IntArray(initialOutputSamples)
        outputBypassed = BooleanArray(initialOutputSamples / channels.coerceAtLeast(1))
        return if (formatSupported) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET
    }

    override fun isActive(): Boolean = formatSupported

    override fun onFlush() {
        states = Array(channels) { Array(11) { BiquadState() } }
        attackCoefficient = exp(-1f / (sampleRate * 0.006f))
        releaseCoefficient = exp(-1f / (sampleRate * 0.12f))
        compressorEnvelope = 0f
        compressorGainReductionDb = 0f
        limiterGainReductionDb = 0f
        preLimiterPeak = 0f
        preLimiterRmsDb = -120f
        outputRmsDb = -120f
        truePeakLimiter?.reset()
        appliedSettings = null
    }

    override fun onQueueEndOfStream() {
        val limiter = truePeakLimiter ?: return
        val pendingFrames = limiter.pendingFrames
        if (pendingFrames <= 0) {
            limiter.reset()
            return
        }
        val sampleCount = pendingFrames * channels
        ensureOutputCapacity(sampleCount)
        limiter.beginBlock()
        val drainedFrames = limiter.drain(outputSamples, outputRawBits, outputBypassed)
        val output = replaceOutputBuffer(drainedFrames * channels * bytesPerSample(configuredEncoding))
            .order(ByteOrder.nativeOrder())
        var peak = 0f
        var squareSum = 0.0
        var measuredSamples = 0
        for (frame in 0 until drainedFrames) {
            val sampleOffset = frame * channels
            writeOutputFrame(output, sampleOffset, outputBypassed[frame])
            for (channel in 0 until channels) {
                val value = outputSamples[sampleOffset + channel]
                if (value.isFinite()) {
                    peak = maxOf(peak, abs(value))
                    squareSum += value.toDouble() * value.toDouble()
                    measuredSamples++
                }
            }
        }
        outputRmsDb = if (measuredSamples > 0) (20.0 * log10(sqrt(squareSum / measuredSamples).coerceAtLeast(1e-12))).toFloat() else -120f
        outputPeak = peak.coerceAtMost(1f)
        preLimiterPeak = limiter.blockPreLimiterPeak
        preLimiterRmsDb = limiter.blockPreLimiterRmsDb
        limiterActive = limiter.blockLimited
        limiterGainReductionDb = limiter.blockGainReductionDb
        if (limiterActive) clippingBlocks += 1
        if (visualizerEnabled) captureSamples(outputSamples, drainedFrames)
        output.flip()
        // Drain closes this stream. Reset the delayed timeline so the next
        // media item cannot inherit stale lookahead frames or silence padding.
        limiter.reset()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val byteCount = input.remaining()
        val config = settings
        if (!formatSupported) {
            val encoding = inputAudioFormat.encoding
            val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
            val sampleCount = input.remaining() / bytesPerSample
            var peak = 0f
            if (encoding == C.ENCODING_PCM_FLOAT) {
                val view = input.asFloatBuffer()
                for (index in 0 until sampleCount) view.get(index).takeIf { it.isFinite() }?.let { peak = maxOf(peak, abs(it)) }
            } else if (encoding == C.ENCODING_PCM_16BIT) {
                val view = input.asShortBuffer()
                for (index in 0 until sampleCount) peak = maxOf(peak, abs(view.get(index) / 32768f))
            }
            outputPeak = peak
            limiterActive = false
            if (visualizerEnabled) captureInput(input, inputAudioFormat.encoding, sampleCount)
            val output = replaceOutputBuffer(byteCount)
            output.put(input)
            output.flip()
            return
        }

        val encoding = inputAudioFormat.encoding
        val bytesPerSample = bytesPerSample(encoding)
        val sampleCount = byteCount / bytesPerSample
        val frameCount = sampleCount / channels
        val inputStart = input.position()
        if (sampleBuffer.size < sampleCount) sampleBuffer = FloatArray(sampleCount)
        if (rawSampleBits.size < sampleCount) rawSampleBits = IntArray(sampleCount)
        ensureOutputCapacity(sampleCount)
        val samples = sampleBuffer
        if (encoding == C.ENCODING_PCM_FLOAT) {
            for (index in 0 until sampleCount) {
                val bits = input.getInt(inputStart + index * 4)
                rawSampleBits[index] = bits
                samples[index] = java.lang.Float.intBitsToFloat(bits)
            }
        } else {
            for (index in 0 until sampleCount) {
                val value = input.getShort(inputStart + index * 2)
                rawSampleBits[index] = value.toInt()
                samples[index] = value / 32768f
            }
        }
        input.position(input.limit())

        if (config.enabled) processEffects(samples, frameCount, config)
        else compressorGainReductionDb = 0f

        val limiter = truePeakLimiter ?: return
        val expectedOutputFrames = limiter.outputFramesAfterInput(frameCount)
        val output = replaceOutputBuffer(expectedOutputFrames * channels * bytesPerSample)
            .order(ByteOrder.nativeOrder())
        limiter.beginBlock()
        var writtenFrames = 0
        var outputBlockPeak = 0f
        var outputSquareSum = 0.0
        var outputSampleCount = 0
        for (frame in 0 until frameCount) {
            val sampleOffset = frame * channels
            if (limiter.processFrame(
                    samples, sampleOffset, rawSampleBits, config.enabled,
                    outputSamples, outputRawBits, writtenFrames * channels, outputBypassed, writtenFrames
                )) {
                val outputOffset = writtenFrames * channels
                writeOutputFrame(output, outputOffset, outputBypassed[writtenFrames])
                for (channel in 0 until channels) {
                    val value = outputSamples[outputOffset + channel]
                    if (value.isFinite()) {
                        outputBlockPeak = maxOf(outputBlockPeak, abs(value))
                        outputSquareSum += value.toDouble() * value.toDouble()
                        outputSampleCount++
                    }
                }
                writtenFrames++
            }
        }
        outputPeak = outputBlockPeak.coerceAtMost(1f)
        outputRmsDb = if (outputSampleCount > 0) (20.0 * log10(sqrt(outputSquareSum / outputSampleCount).coerceAtLeast(1e-12))).toFloat() else -120f
        preLimiterPeak = limiter.blockPreLimiterPeak
        preLimiterRmsDb = limiter.blockPreLimiterRmsDb
        limiterActive = limiter.blockLimited
        limiterGainReductionDb = limiter.blockGainReductionDb
        if (limiterActive) clippingBlocks += 1
        if (visualizerEnabled) captureSamples(outputSamples, writtenFrames)
        output.flip()
    }

    private fun processEffects(samples: FloatArray, frameCount: Int, config: BatAudioSettings) {
        if (config != appliedSettings) {
            filters = createFilters(config)
            attackCoefficient = exp(-1f / (sampleRate * config.compressorAttackMs.coerceIn(1f, 100f) / 1000f))
            releaseCoefficient = exp(-1f / (sampleRate * config.compressorReleaseMs.coerceIn(10f, 1_000f) / 1000f))
            appliedSettings = config
        }
        val amount = ((config.batBoost.coerceIn(100, 200) - 100) / 100f)
        val maxEq = if (config.equalizerEnabled) config.bandGainsDb.maxOfOrNull { it.coerceAtLeast(0f) } ?: 0f else 0f
        val bassDb = config.bassBoost.coerceIn(0, 100) * 0.12f
        val preGainDb = config.preGainDb.coerceIn(0f, 24f)
        val positiveStack = (maxEq + bassDb + preGainDb).coerceAtMost(48f)
        val headroomDb = (positiveStack - 7f).coerceAtLeast(0f) * (0.35f + amount * 0.25f)
        val inputGain = 10f.pow((preGainDb - headroomDb) / 20f)
        val extraMakeupDb = if (config.compressorEnabled) config.compressorMakeupDb.coerceIn(-12f, 12f) else 0f
        val makeupGain = 10f.pow((amount * 6.0206f + extraMakeupDb) / 20f)
        val threshold = 10f.pow(config.compressorThresholdDb.coerceIn(-36f, 0f) / 20f)
        val ratio = config.compressorRatio.coerceIn(1f, 12f)
        var maximumReductionDb = 0f

        for (frame in 0 until frameCount) {
            val offset = frame * channels
            var framePeak = 0f
            for (channel in 0 until channels) {
                val sampleIndex = offset + channel
                var value = samples[sampleIndex].let { if (it.isFinite()) it else 0f }
                for ((filterIndex, coefficients) in filters) {
                    value = states[channel][filterIndex].process(value, coefficients)
                }
                if (!value.isFinite()) value = 0f
                value *= inputGain
                samples[sampleIndex] = value
                framePeak = maxOf(framePeak, abs(value))
            }

            val coefficient = if (framePeak > compressorEnvelope) attackCoefficient else releaseCoefficient
            compressorEnvelope = coefficient * compressorEnvelope + (1f - coefficient) * framePeak
            val detectorDb = 8.68589f * ln(compressorEnvelope.coerceAtLeast(1e-5f))
            val thresholdDb = 8.68589f * ln(threshold)
            val overThresholdDb = detectorDb - thresholdDb
            val kneeDb = 6f
            val reductionDb = when {
                !config.compressorEnabled || overThresholdDb <= -kneeDb / 2f -> 0f
                overThresholdDb >= kneeDb / 2f -> overThresholdDb * (1f - 1f / ratio)
                else -> ((overThresholdDb + kneeDb / 2f).pow(2) / (2f * kneeDb)) * (1f - 1f / ratio)
            }
            if (reductionDb > maximumReductionDb) maximumReductionDb = reductionDb
            val compressionGain = 10f.pow(-reductionDb / 20f)
            for (channel in 0 until channels) {
                val sampleIndex = offset + channel
                samples[sampleIndex] *= compressionGain * makeupGain
            }
        }
        compressorGainReductionDb = maximumReductionDb
    }

    private fun ensureOutputCapacity(sampleCount: Int) {
        if (outputSamples.size < sampleCount) {
            outputSamples = FloatArray(sampleCount)
            outputRawBits = IntArray(sampleCount)
            outputBypassed = BooleanArray(sampleCount / channels.coerceAtLeast(1))
        }
    }

    private fun writeOutputFrame(output: ByteBuffer, sampleOffset: Int, bypassed: Boolean) {
        for (channel in 0 until channels) {
            val index = sampleOffset + channel
            if (bypassed) {
                if (configuredEncoding == C.ENCODING_PCM_FLOAT) output.putInt(outputRawBits[index])
                else output.putShort(outputRawBits[index].toShort())
            } else if (configuredEncoding == C.ENCODING_PCM_FLOAT) {
                output.putFloat(outputSamples[index])
            } else {
                output.putShort((outputSamples[index].coerceIn(-1f, 1f) * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
            }
        }
    }

    private fun bytesPerSample(encoding: Int): Int = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2

    private fun captureInput(input: ByteBuffer, encoding: Int, sampleCount: Int) {
        if (channels <= 0) return
        val frames = sampleCount / channels
        val step = 1
        var write = visualizerWritePosition
        val inputStart = input.position()
        if (encoding == C.ENCODING_PCM_FLOAT) {
            var frame = 0
            while (frame < frames) {
                var mono = 0f
                for (channel in 0 until channels) mono += input.getFloat(inputStart + (frame * channels + channel) * 4)
                visualizerRing[write] = (mono / channels).coerceIn(-1f, 1f)
                write = (write + 1) % visualizerRing.size
                frame += step
            }
        } else if (encoding == C.ENCODING_PCM_16BIT) {
            var frame = 0
            while (frame < frames) {
                var mono = 0f
                for (channel in 0 until channels) mono += input.getShort(inputStart + (frame * channels + channel) * 2) / 32768f
                visualizerRing[write] = (mono / channels).coerceIn(-1f, 1f)
                write = (write + 1) % visualizerRing.size
                frame += step
            }
        }
        visualizerWritePosition = write
    }

    private fun captureSamples(values: FloatArray, frames: Int) {
        if (channels <= 0) return
        val step = 1
        var write = visualizerWritePosition
        var frame = 0
        while (frame < frames) {
            var mono = 0f
            val offset = frame * channels
            for (channel in 0 until channels) mono += values[offset + channel]
            visualizerRing[write] = (mono / channels).coerceIn(-1f, 1f)
            write = (write + 1) % visualizerRing.size
            frame += step
        }
        visualizerWritePosition = write
    }

    fun copyVisualizerSamples(destination: FloatArray) {
        val size = minOf(destination.size, visualizerRing.size)
        val start = visualizerWritePosition
        for (i in 0 until size) destination[i] = visualizerRing[(start + i) % visualizerRing.size]
    }

    private fun createFilters(config: BatAudioSettings): Array<Pair<Int, Coefficients>> = buildList {
        val bass = config.bassBoost.coerceIn(0, 100) * 0.12f
        if (bass > 0.01f) add(0 to lowShelf(95f, bass))
        if (config.equalizerEnabled) {
            for (band in BAND_HZ.indices) {
                val gain = config.bandGainsDb.getOrElse(band) { 0f }.coerceIn(-12f, 12f)
                if (abs(gain) > 0.01f) add((band + 1) to peaking(BAND_HZ[band], gain))
            }
        }
    }.toTypedArray()

    private data class Coefficients(val b0: Float, val b1: Float, val b2: Float, val a1: Float, val a2: Float)
    private class BiquadState {
        private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f
        fun process(x: Float, c: Coefficients): Float {
            val y = c.b0 * x + c.b1 * x1 + c.b2 * x2 - c.a1 * y1 - c.a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
    }

    private fun peaking(hz: Float, db: Float): Coefficients {
        val safeHz = hz.coerceIn(1f, sampleRate * 0.45f)
        val a = 10.0.pow(db / 40.0).toFloat()
        val w = (2.0 * PI * safeHz / sampleRate).toFloat()
        val alpha = sin(w) / 2f
        val c = cos(w)
        return normalize(1f + alpha * a, -2f * c, 1f - alpha * a,
            1f + alpha / a, -2f * c, 1f - alpha / a)
    }

    private fun lowShelf(hz: Float, db: Float): Coefficients {
        val safeHz = hz.coerceIn(1f, sampleRate * 0.45f)
        val a = 10.0.pow(db / 40.0).toFloat()
        val w = (2.0 * PI * safeHz / sampleRate).toFloat()
        val c = cos(w); val s = sin(w)
        val alpha = s * 0.5f * kotlin.math.sqrt(2f)
        val root = 2f * kotlin.math.sqrt(a) * alpha
        return normalize(a * ((a + 1f) - (a - 1f) * c + root),
            2f * a * ((a - 1f) - (a + 1f) * c), a * ((a + 1f) - (a - 1f) * c - root),
            (a + 1f) + (a - 1f) * c + root, -2f * ((a - 1f) + (a + 1f) * c),
            (a + 1f) + (a - 1f) * c - root)
    }

    private fun normalize(b0: Float, b1: Float, b2: Float, a0: Float, a1: Float, a2: Float) =
        Coefficients(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

    companion object {
        private const val VISUALIZER_SIZE = 4096
        private const val TRUE_PEAK_CEILING = 0.8912509f // -1 dBFS true-peak target
        private val BAND_HZ = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f)
    }
}
