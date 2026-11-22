package com.batmusic.app.audio

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Persisted analysis for one exact media revision. The caller must invalidate the
 * record when the URI, size, modified time, decoder format, or analysis version changes.
 */
data class TrackLoudnessRecord(
    val mediaKey: String,
    val fingerprint: String,
    val durationMs: Long,
    val sampleRate: Int,
    val channels: Int,
    val integratedLufs: Float,
    val truePeakDbtp: Float,
    val analysisVersion: Int = CURRENT_VERSION
) {
    init {
        require(mediaKey.isNotBlank())
        require(fingerprint.isNotBlank())
        require(durationMs >= 0L)
        require(sampleRate >= 8_000)
        require(channels in 1..8)
        require(integratedLufs.isFinite())
        require(truePeakDbtp.isFinite())
        require(analysisVersion > 0)
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

/**
 * Small SharedPreferences cache. It stores measurements only; it never stores or
 * applies playback gain. Fingerprints should include the media URI, file size,
 * modified timestamp and decoder/analysis version.
 */
class TrackLoudnessCache(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("batmusic_loudness_cache_v1", Context.MODE_PRIVATE)

    fun get(mediaKey: String, fingerprint: String): TrackLoudnessRecord? {
        if (mediaKey.isBlank() || fingerprint.isBlank()) return null
        val key = cacheKey(mediaKey)
        val raw = preferences.getString(key, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val record = TrackLoudnessRecord(
                mediaKey = json.getString("mediaKey"),
                fingerprint = json.getString("fingerprint"),
                durationMs = json.getLong("durationMs"),
                sampleRate = json.getInt("sampleRate"),
                channels = json.getInt("channels"),
                integratedLufs = json.getDouble("integratedLufs").toFloat(),
                truePeakDbtp = json.getDouble("truePeakDbtp").toFloat(),
                analysisVersion = json.getInt("analysisVersion")
            )
            record.takeIf {
                it.mediaKey == mediaKey &&
                    it.fingerprint == fingerprint &&
                    it.analysisVersion == TrackLoudnessRecord.CURRENT_VERSION
            }
        }.getOrNull()
    }

    fun put(record: TrackLoudnessRecord) {
        if (record.analysisVersion != TrackLoudnessRecord.CURRENT_VERSION) return
        val json = JSONObject().apply {
            put("mediaKey", record.mediaKey)
            put("fingerprint", record.fingerprint)
            put("durationMs", record.durationMs)
            put("sampleRate", record.sampleRate)
            put("channels", record.channels)
            put("integratedLufs", record.integratedLufs.toDouble())
            put("truePeakDbtp", record.truePeakDbtp.toDouble())
            put("analysisVersion", record.analysisVersion)
        }
        preferences.edit().putString(cacheKey(record.mediaKey), json.toString()).apply()
    }

    fun invalidate(mediaKey: String) {
        if (mediaKey.isNotBlank()) preferences.edit().remove(cacheKey(mediaKey)).apply()
    }

    private fun cacheKey(mediaKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(mediaKey.toByteArray(Charsets.UTF_8))
        return "track_" + digest.joinToString("") { "%02x".format(it) }
    }
}

/**
 * A conservative gain proposal. Both integrated loudness and a separately measured
 * true peak are mandatory. This class does not alter audio or device volume.
 */
data class LoudnessGainPlan(
    val requestedGainDb: Float,
    val appliedGainDb: Float,
    val predictedIntegratedLufs: Float,
    val predictedTruePeakDbtp: Float
)

object LoudnessNormalizer {
    const val DEFAULT_TARGET_LUFS = -18f
    const val TRUE_PEAK_CEILING_DBTP = -1.5f
    const val MAX_POSITIVE_GAIN_DB = 3f
    const val MAX_CUT_DB = -12f

    fun plan(
        integratedLufs: Float,
        truePeakDbtp: Float,
        targetLufs: Float = DEFAULT_TARGET_LUFS
    ): LoudnessGainPlan? {
        if (!integratedLufs.isFinite() || !truePeakDbtp.isFinite() || !targetLufs.isFinite()) return null
        if (integratedLufs <= LoudnessMeter.ABSOLUTE_GATE_LUFS.toFloat()) return null
        val requested = targetLufs - integratedLufs
        val peakLimited = TRUE_PEAK_CEILING_DBTP - truePeakDbtp
        val applied = requested.coerceIn(MAX_CUT_DB, MAX_POSITIVE_GAIN_DB)
            .coerceAtMost(peakLimited)
        return LoudnessGainPlan(
            requestedGainDb = requested,
            appliedGainDb = applied,
            predictedIntegratedLufs = integratedLufs + applied,
            predictedTruePeakDbtp = truePeakDbtp + applied
        )
    }
}

/**
 * Streaming offline-analysis accumulator. A decoder feeds decoded interleaved PCM
 * blocks; this keeps the measurement bounded in memory regardless of track length.
 * True peak must be supplied by a separately validated oversampled measurement.
 */
class OfflineLoudnessAccumulator(
    private val mediaKey: String,
    private val fingerprint: String,
    private val durationMs: Long,
    sampleRate: Int,
    channels: Int
) {
    private val meter = LoudnessMeter(sampleRate, channels)
    private val sampleRateValue = sampleRate
    private val channelCount = channels
    private var finished = false

    fun feed(interleavedPcm: FloatArray, frameCount: Int = interleavedPcm.size / channelCount) {
        check(!finished) { "Analysis is already finished" }
        meter.process(interleavedPcm, frameCount)
    }

    fun finish(measuredTruePeakDbtp: Float): TrackLoudnessRecord {
        check(!finished) { "Analysis is already finished" }
        require(measuredTruePeakDbtp.isFinite())
        finished = true
        return TrackLoudnessRecord(
            mediaKey = mediaKey,
            fingerprint = fingerprint,
            durationMs = durationMs,
            sampleRate = sampleRateValue,
            channels = channelCount,
            integratedLufs = meter.integratedLufs,
            truePeakDbtp = measuredTruePeakDbtp
        )
    }
}
