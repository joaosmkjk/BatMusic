package com.batmusic.app.audio

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Allocation-free streaming true-peak limiter core. It uses a 4x, 16-tap
 * windowed-sinc detector and a 1 ms peak window. Coefficients are constants;
 * sample-rate dependent state is prepared by the constructor, outside queueInput.
 */
internal class LookaheadTruePeakLimiter(
    private val sampleRate: Int,
    private val channels: Int,
    ceiling: Float,
    marginDb: Float = 0.5f,
    lookaheadMs: Float = 1f,
    releaseMs: Float = 120f
) {
    private val lookaheadFrames = ceil(sampleRate * lookaheadMs / 1_000f).toInt().coerceAtLeast(1)
    private val delayFrames = INTERPOLATOR_FUTURE_FRAMES + lookaheadFrames - 1
    private val ringFrameCount = delayFrames + INTERPOLATOR_TAPS + 2
    private val audioRing = FloatArray(ringFrameCount * channels)
    private val rawRing = IntArray(ringFrameCount * channels)
    private val activeRing = BooleanArray(ringFrameCount)
    private val peakDequeIndices = LongArray(lookaheadFrames + 2)
    private val peakDequeValues = FloatArray(lookaheadFrames + 2)
    private val zeroFrame = FloatArray(channels)
    private val zeroRawFrame = IntArray(channels)
    @Volatile private var releaseCoefficient = exp(-1f / (sampleRate * releaseMs.coerceAtLeast(1f) / 1_000f))
    private val targetCeiling = ceiling * 10f.pow(-marginDb / 20f) // Internal safety reserve below the configured ceiling.

    private var timelineFrames = 0L
    private var realFrames = 0L
    private var outputFrames = 0L
    private var dequeHead = 0L
    private var dequeTail = 0L
    private var limiterGain = 1f

    var lastOutputBypassed: Boolean = false
        private set
    var blockLimited: Boolean = false
        private set
    var blockMinimumGain: Float = 1f
        private set
    var blockPreLimiterPeak: Float = 0f
        private set
    private var blockPreLimiterSquareSum = 0.0
    private var blockPreLimiterSampleCount = 0
    val blockPreLimiterRmsDb: Float
        get() = if (blockPreLimiterSampleCount > 0) {
            (20.0 * log10(sqrt(blockPreLimiterSquareSum / blockPreLimiterSampleCount).coerceAtLeast(1e-12))).toFloat()
        } else -120f
    val blockGainReductionDb: Float get() = if (blockMinimumGain >= 1f) 0f else -20f * log10(blockMinimumGain)
    val latencyFrames: Int get() = delayFrames
    val pendingFrames: Int get() = (realFrames - outputFrames).toInt()

    fun reset() {
        timelineFrames = 0L
        realFrames = 0L
        outputFrames = 0L
        dequeHead = 0L
        dequeTail = 0L
        limiterGain = 1f
        blockLimited = false
        blockMinimumGain = 1f
        blockPreLimiterPeak = 0f
        blockPreLimiterSquareSum = 0.0
        blockPreLimiterSampleCount = 0
        lastOutputBypassed = false
    }

    fun beginBlock() {
        blockLimited = false
        blockMinimumGain = 1f
        blockPreLimiterPeak = 0f
        blockPreLimiterSquareSum = 0.0
        blockPreLimiterSampleCount = 0
    }

    /** Called by the settings publisher, not from queueInput. */
    fun setReleaseMs(releaseMs: Float) {
        val safeReleaseMs = releaseMs.takeIf { it.isFinite() }?.coerceIn(10f, 1_000f) ?: 120f
        releaseCoefficient = exp(-1f / (sampleRate * safeReleaseMs / 1_000f))
    }

    fun outputFramesAfterInput(inputFrameCount: Int): Int =
        ((realFrames + inputFrameCount - delayFrames).coerceAtLeast(0L) - outputFrames).toInt()

    /** Pushes one candidate output frame and, when available, writes its delayed frame. */
    fun processFrame(
        inputSamples: FloatArray,
        inputOffset: Int,
        rawInput: IntArray,
        active: Boolean,
        outputSamples: FloatArray,
        outputRaw: IntArray,
        outputOffset: Int,
        outputBypassed: BooleanArray,
        outputFrameOffset: Int
    ): Boolean = pushFrame(inputSamples, inputOffset, rawInput, active, true, outputSamples, outputRaw, outputOffset, outputBypassed, outputFrameOffset)

    /** Emits the buffered tail using zero padding for the interpolation/lookahead context. */
    fun drain(outputSamples: FloatArray, outputRaw: IntArray, outputBypassed: BooleanArray): Int {
        var writtenFrames = 0
        var paddingFrames = 0
        while (pendingFrames > 0 && paddingFrames < delayFrames) {
            if (pushFrame(zeroFrame, 0, zeroRawFrame, false, false, outputSamples, outputRaw,
                    writtenFrames * channels, outputBypassed, writtenFrames)) {
                writtenFrames++
            }
            paddingFrames++
        }
        check(pendingFrames == 0) { "True-peak limiter failed to drain its delayed frames" }
        return writtenFrames
    }

    private fun pushFrame(
        inputSamples: FloatArray,
        inputOffset: Int,
        rawInput: IntArray,
        active: Boolean,
        isRealInput: Boolean,
        outputSamples: FloatArray,
        outputRaw: IntArray,
        outputOffset: Int,
        outputBypassed: BooleanArray,
        outputFrameOffset: Int
    ): Boolean {
        val frameIndex = timelineFrames
        val ringIndex = slot(frameIndex)
        val ringOffset = ringIndex * channels
        for (channel in 0 until channels) {
            audioRing[ringOffset + channel] = inputSamples[inputOffset + channel]
            rawRing[ringOffset + channel] = rawInput[inputOffset + channel]
        }
        activeRing[ringIndex] = active && isRealInput
        timelineFrames++
        if (isRealInput) realFrames++

        val segmentIndex = frameIndex - INTERPOLATOR_FUTURE_FRAMES
        if (segmentIndex >= 0L) {
            val segmentSlot = slot(segmentIndex)
            val peak = if (activeRing[segmentSlot]) estimateSegmentTruePeak(segmentIndex) else 0f
            pushPeak(segmentIndex, peak)
        }

        val outputIndex = frameIndex - delayFrames
        if (outputIndex < 0L || outputIndex >= realFrames) return false

        while (dequeHead < dequeTail && peakDequeIndices[dequeSlot(dequeHead)] < outputIndex) dequeHead++
        val windowPeak = if (dequeHead < dequeTail) peakDequeValues[dequeSlot(dequeHead)] else 0f
        val peakFrame = if (dequeHead < dequeTail) peakDequeIndices[dequeSlot(dequeHead)] else outputIndex
        val targetGain = if (windowPeak > targetCeiling) targetCeiling / windowPeak else 1f
        val outputSlot = slot(outputIndex)
        val outputRingOffset = outputSlot * channels
        lastOutputBypassed = !activeRing[outputSlot]
        outputBypassed[outputFrameOffset] = lastOutputBypassed

        // Measure the delayed frame before limiter gain so this RMS/peak window
        // describes exactly the same audio frames as the post-limiter output meter.
        for (channel in 0 until channels) {
            val value = audioRing[outputRingOffset + channel]
            if (value.isFinite()) {
                blockPreLimiterPeak = maxOf(blockPreLimiterPeak, abs(value))
                blockPreLimiterSquareSum += value.toDouble() * value.toDouble()
                blockPreLimiterSampleCount++
            }
        }

        if (lastOutputBypassed) {
            limiterGain = 1f
            for (channel in 0 until channels) {
                outputSamples[outputOffset + channel] = audioRing[outputRingOffset + channel]
                outputRaw[outputOffset + channel] = rawRing[outputRingOffset + channel]
            }
        } else {
            limiterGain = if (targetGain < limiterGain) {
                val framesToPeak = (peakFrame - outputIndex + 1L).coerceAtLeast(1L).toFloat()
                if (outputFrames == 0L) targetGain
                else maxOf(targetGain, limiterGain - (limiterGain - targetGain) / framesToPeak)
            } else {
                minOf(1f, limiterGain + (1f - releaseCoefficient) * (1f - limiterGain))
            }
            if (limiterGain < 0.999999f) {
                blockLimited = true
                if (limiterGain < blockMinimumGain) blockMinimumGain = limiterGain
            }
            for (channel in 0 until channels) {
                outputSamples[outputOffset + channel] = audioRing[outputRingOffset + channel] * limiterGain
                outputRaw[outputOffset + channel] = rawRing[outputRingOffset + channel]
            }
        }
        outputFrames++
        return true
    }

    private fun estimateSegmentTruePeak(segmentIndex: Long): Float {
        var peak = 0f
        val coefficientOffset = 0
        for (channel in 0 until channels) {
            for (phase in 0 until OVERSAMPLE_FACTOR) {
                var interpolated = 0f
                val phaseOffset = coefficientOffset + phase * INTERPOLATOR_TAPS
                for (tap in 0 until INTERPOLATOR_TAPS) {
                    val sourceIndex = segmentIndex + INTERPOLATOR_FIRST_TAP + tap
                    interpolated += sampleAt(sourceIndex, channel) * INTERPOLATION_COEFFICIENTS[phaseOffset + tap]
                }
                peak = maxOf(peak, abs(interpolated))
            }
        }
        return peak
    }

    private fun sampleAt(frameIndex: Long, channel: Int): Float {
        if (frameIndex < 0L || frameIndex >= timelineFrames) return 0f
        return audioRing[slot(frameIndex) * channels + channel]
    }

    private fun pushPeak(frameIndex: Long, peak: Float) {
        // Keep the earliest frame for equal maxima so an attack ramp can reach
        // the target before a repeated peak (for example, a fs/4 sequence).
        while (dequeTail > dequeHead && peakDequeValues[dequeSlot(dequeTail - 1L)] < peak) dequeTail--
        val tailSlot = dequeSlot(dequeTail)
        peakDequeIndices[tailSlot] = frameIndex
        peakDequeValues[tailSlot] = peak
        dequeTail++
    }

    private fun slot(frameIndex: Long): Int = (frameIndex % ringFrameCount).toInt()
    private fun dequeSlot(index: Long): Int = (index % peakDequeIndices.size).toInt()

    companion object {
        private const val OVERSAMPLE_FACTOR = 4
        private const val INTERPOLATOR_TAPS = 16
        private const val INTERPOLATOR_FIRST_TAP = -7
        private const val INTERPOLATOR_FUTURE_FRAMES = 8

        // Four 1/4-sample phases of a normalized 16-tap Blackman-windowed sinc.
        // Phase zero is an exact sample phase; the other coefficients are precomputed.
        private val INTERPOLATION_COEFFICIENTS = floatArrayOf(
            0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
            -0.0002498180f, 0.0017681750f, -0.0059955350f, 0.0154912161f,
            -0.0345300007f, 0.0721794778f, -0.1630195067f, 0.8967047937f,
            0.2895787107f, -0.1057302544f, 0.0500258218f, -0.0234394137f,
            0.0098715498f, -0.0034129936f, 0.0007830557f, -0.0000252786f,
            -0.0001492781f, 0.0017078792f, -0.0064579991f, 0.0175787207f,
            -0.0403416098f, 0.0849361269f, -0.1838220161f, 0.6265481763f,
            0.6265481763f, -0.1838220161f, 0.0849361269f, -0.0403416098f,
            0.0175787207f, -0.0064579991f, 0.0017078792f, -0.0001492781f,
            -0.0000252786f, 0.0007830557f, -0.0034129936f, 0.0098715498f,
            -0.0234394137f, 0.0500258218f, -0.1057302544f, 0.2895787107f,
            0.8967047937f, -0.1630195067f, 0.0721794778f, -0.0345300007f,
            0.0154912161f, -0.0059955350f, 0.0017681750f, -0.0002498180f
        )
    }
}
