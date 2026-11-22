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
                        require(sampleRate >= 8_000 && channels in 1..8) {
                            "Unsupported decoded audio format: $sampleRate Hz, $channels channels"
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
                durationMs = durationMs,
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
 * Independent 4x cubic-interpolated inter-sample peak estimate. This is deliberately
 * a monitor, not a certified BS.1770 true-peak meter. It is used to conservatively
 * constrain normalization and must remain separately testable from the playback limiter.
 */
class OversampledTruePeakMeter(private val channels: Int) {
    init { require(channels in 1..8) }

    private val previous2 = FloatArray(channels)
    private val previous1 = FloatArray(channels)
    private val current = FloatArray(channels)
    private var frames = 0L
    private var peak = 0f

    fun process(interleaved: FloatArray, frameCount: Int = interleaved.size / channels) {
        require(frameCount >= 0 && frameCount * channels <= interleaved.size)
        for (frame in 0 until frameCount) {
            val offset = frame * channels
            for (ch in 0 until channels) {
                val next = interleaved[offset + ch].let { if (it.isFinite()) it else 0f }
                current[ch] = next
                peak = max(peak, abs(next))
                if (frames >= 2L) {
                    val y0 = previous2[ch].toDouble()
                    val y1 = previous1[ch].toDouble()
                    val y2 = current[ch].toDouble()
                    // Cubic Hermite interpolation with centered finite-difference slopes.
                    val m1 = (y2 - y0) * 0.5
                    val m2 = y2 - y1
                    for (phase in 1..3) {
                        val t = phase / 4.0
                        val t2 = t * t
                        val t3 = t2 * t
                        val value = (2*t3 - 3*t2 + 1)*y1 +
                            (t3 - 2*t2 + t)*m1 +
                            (-2*t3 + 3*t2)*y2 +
                            (t3 - t2)*m2
                        peak = max(peak, abs(value.toFloat()))
                    }
                }
                previous2[ch] = previous1[ch]
                previous1[ch] = current[ch]
            }
            frames++
        }
    }

    fun finishDbtp(): Float {
        if (frames == 0L || peak <= 1e-12f) return -120f
        return (20.0 * log10(peak.toDouble())).toFloat()
    }
}
