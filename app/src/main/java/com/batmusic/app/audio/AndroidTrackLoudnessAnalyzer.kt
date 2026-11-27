package com.batmusic.app.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Decodes a media URI off the UI/playback thread and streams decoded PCM into
 * bounded-memory meters. Cancellation always releases codec and extractor.
 *
 * The caller supplies a fingerprint derived from URI + size + modified time.
 * The cache is only populated after the decoder reaches EOS successfully.
 */
class AndroidTrackLoudnessAnalyzer(
    context: Context,
    private val cache: TrackLoudnessCache = TrackLoudnessCache(context)
) {
    private val appContext = context.applicationContext

    suspend fun analyze(
        uri: Uri,
        fingerprint: String,
        durationMs: Long,
        onProgress: ((Long, Long) -> Unit)? = null
    ): TrackLoudnessRecord = withContext(Dispatchers.IO) {
        require(fingerprint.isNotBlank())
        val mediaKey = uri.toString()
        cache.get(mediaKey, fingerprint)?.let { return@withContext it }

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var meter: LoudnessMeter? = null
        var truePeak: OversampledTruePeakMeter? = null
        var sampleRate = 0
        var channels = 0
        var sawOutputFormat = false
        var inputEos = false
        var outputEos = false
        var decodedFrames = 0L

        try {
            extractor.setDataSource(appContext, uri, null)
            var selectedFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    extractor.selectTrack(index)
                    selectedFormat = format
                    break
                }
            }
            val sourceFormat = selectedFormat ?: error("No audio track found")
            val mime = sourceFormat.getString(MediaFormat.KEY_MIME) ?: error("Audio MIME type missing")
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(sourceFormat, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var lastProgressMs = -1L
            while (!outputEos) {
                coroutineContext.ensureActive()

                if (!inputEos) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                            ?: error("Decoder input buffer unavailable")
                        inputBuffer.clear()
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            val pts = extractor.sampleTime.coerceAtLeast(0L)
                            codec.queueInputBuffer(inputIndex, 0, size, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        // MediaCodec exposes channel count here, but not a reliable
                        // speaker-position map across all codecs/devices. Do not guess
                        // LFE or surround weights for loudness normalization.
                        require(sampleRate >= 8_000 && channels in 1..2) {
                            "Loudness normalization requires mono/stereo PCM; channel layout is ambiguous"
                        }
                        meter = LoudnessMeter(sampleRate, channels)
                        truePeak = OversampledTruePeakMeter(channels)
                        sawOutputFormat = true
                    }
                    else -> if (outputIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && info.size > 0) {
                            check(sawOutputFormat) { "Decoder produced PCM before output format" }
                            outputBuffer.position(info.offset)
                            outputBuffer.limit(info.offset + info.size)
                            val pcm = outputBuffer.slice().order(java.nio.ByteOrder.nativeOrder())
                            val encoding = if (BuildCompat.hasPcmEncoding(codec.outputFormat)) {
                                codec.outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            } else android.media.AudioFormat.ENCODING_PCM_16BIT
                            val bytesPerSample = when (encoding) {
                                android.media.AudioFormat.ENCODING_PCM_FLOAT -> 4
                                android.media.AudioFormat.ENCODING_PCM_16BIT -> 2
                                else -> error("Unsupported decoded PCM encoding: $encoding")
                            }
                            val sampleCount = pcm.remaining() / bytesPerSample
                            val usableSamples = sampleCount - sampleCount % channels
                            val samples = FloatArray(usableSamples)
                            for (i in 0 until usableSamples) {
                                samples[i] = when (encoding) {
                                    android.media.AudioFormat.ENCODING_PCM_FLOAT -> pcm.getFloat().let { if (it.isFinite()) it else 0f }
                                    else -> pcm.getShort() / 32768f
                                }
                            }
                            val frames = usableSamples / channels
                            meter!!.process(samples, frames)
                            truePeak!!.process(samples, frames)
                            decodedFrames += frames
                            val progress = (info.presentationTimeUs / 1_000L).coerceAtLeast(0L)
                            if (progress != lastProgressMs) {
                                onProgress?.invoke(progress, durationMs)
                                lastProgressMs = progress
                            }
                        }
                        outputEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }

            check(sawOutputFormat && decodedFrames > 0L) { "No decoded audio frames" }
            val record = TrackLoudnessRecord(
                mediaKey = mediaKey,
                fingerprint = fingerprint,
                durationMs = decodedFrames * 1_000L / sampleRate,
                sampleRate = sampleRate,
                channels = channels,
                integratedLufs = meter!!.integratedLufs,
                truePeakDbtp = truePeak!!.finishDbtp()
            )
            cache.put(record)
            record
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private object BuildCompat {
        fun hasPcmEncoding(format: MediaFormat): Boolean =
            android.os.Build.VERSION.SDK_INT >= 24 && format.containsKey(MediaFormat.KEY_PCM_ENCODING)
    }

    private companion object { const val TIMEOUT_US = 10_000L }
}

/**
 * 4x oversampled inter-sample peak monitor using the same 16-tap FIR phases
 * as the playback limiter. This is a conservative engineering estimate, not
 * a claim of formal ITU-R BS.1770 compliance (which specifies a longer filter).
 */
class OversampledTruePeakMeter(
    private val channels: Int,
    private val sampleRate: Int = 48_000
) {
    init {
        require(channels in 1..8)
        require(sampleRate >= 8_000)
    }

    // ITU-R BS.1770-5 Annex 2 gives a 48-tap, four-phase interpolating FIR.
    // Each polyphase branch has 12 coefficients and a six-frame centered delay.
    private val ring = FloatArray(FILTER_TAPS * channels)
    private val firstFrame = FloatArray(channels)
    private val lastFrame = FloatArray(channels)
    private var hasInput = false
    private var frames = 0L
    private var peak = 0f
    private var finished = false

    fun process(interleaved: FloatArray, frameCount: Int = interleaved.size / channels) {
        check(!finished) { "Peak analysis is already finished" }
        require(frameCount >= 0 && frameCount * channels <= interleaved.size)
        for (frame in 0 until frameCount) pushFrame(interleaved, frame * channels, true)
    }

    fun finishDbtp(): Float {
        if (!finished) {
            // Extend the last sample through the FIR look-ahead. Zero-padding
            // would invent a discontinuity at the file boundary and over-read DC.
            repeat(FUTURE_FRAMES) { pushFrame(null, 0, false) }
            finished = true
        }
        return if (frames == 0L || peak <= 1e-12f) -120f
        else (20.0 * log10(peak.toDouble())).toFloat()
    }

    private fun pushFrame(input: FloatArray?, offset: Int, real: Boolean) {
        val frameIndex = frames
        if (real && !hasInput) {
            for (channel in 0 until channels) {
                val first = input!![offset + channel].let { if (it.isFinite()) it else 0f }
                firstFrame[channel] = first
                lastFrame[channel] = first
            }
            // Replicate the first frame into FIR history to avoid a synthetic
            // zero-to-signal step before the first real PCM frame.
            for (history in 0 until FILTER_TAPS) {
                val historyOffset = history * channels
                for (channel in 0 until channels) ring[historyOffset + channel] = firstFrame[channel]
            }
            hasInput = true
        }
        val slot = (frameIndex % FILTER_TAPS).toInt()
        val ringOffset = slot * channels
        for (channel in 0 until channels) {
            val sample = if (real) input!![offset + channel].let { if (it.isFinite()) it else 0f } else lastFrame[channel]
            ring[ringOffset + channel] = sample
            if (real) lastFrame[channel] = sample
            if (real) peak = max(peak, abs(sample))
        }
        frames++
        if (frameIndex < FUTURE_FRAMES) return

        // The centered FIR is evaluated once its six future samples are present.
        val targetFrame = frameIndex - FUTURE_FRAMES
        for (channel in 0 until channels) {
            for (phase in 0 until OVERSAMPLE_FACTOR) {
                var value = 0.0
                val coefficients = PHASE_COEFFICIENTS[phase]
                for (tap in 0 until POLYPHASE_TAPS) {
                    val sourceIndex = targetFrame + FIRST_TAP + tap
                    val sample = if (sourceIndex < 0L) firstFrame[channel]
                        else ring[(sourceIndex % FILTER_TAPS).toInt() * channels + channel]
                    value += sample.toDouble() * coefficients[tap]
                }
                peak = max(peak, abs(value.toFloat()))
            }
        }
    }

    companion object {
        private const val OVERSAMPLE_FACTOR = 4
        private const val POLYPHASE_TAPS = 12
        private const val FILTER_TAPS = 48
        private const val FIRST_TAP = -5
        private const val FUTURE_FRAMES = 6

        // Coefficients from ITU-R BS.1770-5 Annex 2 (48-tap interpolating FIR).
        // Rows are the four phases as printed in the recommendation. Each phase
        // is DC-normalized to avoid a small phase-dependent gain offset caused
        // by the finite-precision coefficient table.
        private val PHASE_COEFFICIENTS: Array<DoubleArray> = run {
            val rows = arrayOf(
                doubleArrayOf( 0.0017089843750, -0.0291748046875, -0.0189208984375, -0.0083007812500),
                doubleArrayOf( 0.0109863281250,  0.0292968750000,  0.0330810546875,  0.0148925781250),
                doubleArrayOf(-0.0196533203125, -0.0517578125000, -0.0582275390625, -0.0266113281250),
                doubleArrayOf( 0.0332031250000,  0.0891113281250,  0.1015625000000,  0.0476074218750),
                doubleArrayOf(-0.0594482421875, -0.1665039062500, -0.2003173828125, -0.1022949218750),
                doubleArrayOf( 0.1373291015625,  0.4650878906250,  0.7797851562500,  0.9721679687500),
                doubleArrayOf( 0.9721679687500,  0.7797851562500,  0.4650878906250,  0.1373291015625),
                doubleArrayOf(-0.1022949218750, -0.2003173828125, -0.1665039062500, -0.0594482421875),
                doubleArrayOf( 0.0476074218750,  0.1015625000000,  0.0891113281250,  0.0332031250000),
                doubleArrayOf(-0.0266113281250, -0.0582275390625, -0.0517578125000, -0.0196533203125),
                doubleArrayOf( 0.0148925781250,  0.0330810546875,  0.0292968750000,  0.0109863281250),
                doubleArrayOf(-0.0083007812500, -0.0189208984375, -0.0291748046875,  0.0017089843750)
            )
            Array(OVERSAMPLE_FACTOR) { phase ->
                DoubleArray(POLYPHASE_TAPS) { tap -> rows[tap][phase] }.also { coefficients ->
                    val sum = coefficients.sum()
                    if (sum != 0.0) for (tap in coefficients.indices) coefficients[tap] /= sum
                }
            }
        }
    }
}
