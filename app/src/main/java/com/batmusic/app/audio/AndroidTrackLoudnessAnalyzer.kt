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
class OversampledTruePeakMeter(private val channels: Int) {
    init { require(channels in 1..8) }

    private val ring = FloatArray(FILTER_TAPS * channels)
    private var frames = 0L
    private var peak = 0f
    private var finished = false

    fun process(interleaved: FloatArray, frameCount: Int = interleaved.size / channels) {
        check(!finished) { "Peak analysis is already finished" }
        require(frameCount >= 0 && frameCount * channels <= interleaved.size)
        for (frame in 0 until frameCount) {
            pushFrame(interleaved, frame * channels, true)
        }
    }

    fun finishDbtp(): Float {
        if (!finished) {
            // Zero-pad the FIR's future context so the final real samples are measured.
            repeat(FUTURE_FRAMES) { pushFrame(null, 0, false) }
            finished = true
        }
        return if (frames == 0L || peak <= 1e-12f) -120f
        else (20.0 * log10(peak.toDouble())).toFloat()
    }

    private fun pushFrame(input: FloatArray?, offset: Int, real: Boolean) {
        val frameIndex = frames
        val slot = (frameIndex % FILTER_TAPS).toInt()
        val ringOffset = slot * channels
        for (channel in 0 until channels) {
            val sample = if (real) input!![offset + channel].let { if (it.isFinite()) it else 0f } else 0f
            ring[ringOffset + channel] = sample
            if (real) peak = max(peak, abs(sample))
        }
        frames++
        if (frameIndex < FUTURE_FRAMES) return

        val segmentIndex = frameIndex - FUTURE_FRAMES
        for (channel in 0 until channels) {
            for (phase in 0 until OVERSAMPLE_FACTOR) {
                var value = 0f
                val phaseOffset = phase * FILTER_TAPS
                for (tap in 0 until FILTER_TAPS) {
                    val sourceIndex = segmentIndex + FIRST_TAP + tap
                    val sample = if (sourceIndex < 0L) 0f
                        else ring[(sourceIndex % FILTER_TAPS).toInt() * channels + channel]
                    value += sample * COEFFICIENTS[phaseOffset + tap]
                }
                peak = max(peak, abs(value))
            }
        }
    }

    companion object {
        private const val OVERSAMPLE_FACTOR = 4
        private const val FILTER_TAPS = 16
        private const val FIRST_TAP = -7
        private const val FUTURE_FRAMES = 8

        // Four 1/4-sample phases from the playback limiter's normalized FIR.
        private val COEFFICIENTS = floatArrayOf(
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
