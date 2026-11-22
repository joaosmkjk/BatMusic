package com.batmusic.app.audio

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

data class TrackLoudnessRecord(
    val mediaKey: String, val fingerprint: String, val durationMs: Long,
    val sampleRate: Int, val channels: Int, val integratedLufs: Float,
    val truePeakDbtp: Float, val analysisVersion: Int = CURRENT_VERSION
) {
    init {
        require(mediaKey.isNotBlank() && fingerprint.isNotBlank())
        require(durationMs >= 0L && sampleRate >= 8_000 && channels in 1..8)
        require(integratedLufs.isFinite() && truePeakDbtp.isFinite() && analysisVersion > 0)
    }
    companion object { const val CURRENT_VERSION = 2 }
}

class TrackLoudnessCache(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("batmusic_loudness_cache_v2", Context.MODE_PRIVATE)

    fun get(mediaKey: String, fingerprint: String): TrackLoudnessRecord? {
        if (mediaKey.isBlank() || fingerprint.isBlank()) return null
        val raw = preferences.getString(cacheKey(mediaKey), null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            TrackLoudnessRecord(
                json.getString("mediaKey"), json.getString("fingerprint"),
                json.getLong("durationMs"), json.getInt("sampleRate"), json.getInt("channels"),
                json.getDouble("integratedLufs").toFloat(), json.getDouble("truePeakDbtp").toFloat(),
                json.getInt("analysisVersion")
            ).takeIf {
                it.mediaKey == mediaKey && it.fingerprint == fingerprint &&
                    it.analysisVersion == TrackLoudnessRecord.CURRENT_VERSION
            }
        }.getOrNull()
    }

    fun put(record: TrackLoudnessRecord) {
        if (record.analysisVersion != TrackLoudnessRecord.CURRENT_VERSION) return
        val json = JSONObject().apply {
            put("mediaKey", record.mediaKey); put("fingerprint", record.fingerprint)
            put("durationMs", record.durationMs); put("sampleRate", record.sampleRate)
            put("channels", record.channels); put("integratedLufs", record.integratedLufs.toDouble())
            put("truePeakDbtp", record.truePeakDbtp.toDouble()); put("analysisVersion", record.analysisVersion)
        }
        preferences.edit().putString(cacheKey(record.mediaKey), json.toString()).apply()
    }

    fun invalidate(mediaKey: String) {
        if (mediaKey.isNotBlank()) preferences.edit().remove(cacheKey(mediaKey)).apply()
    }

    private fun cacheKey(mediaKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(mediaKey.toByteArray(Charsets.UTF_8))
        return "track_" + digest.joinToString("") { "%02x".format(it) }
    }
}

data class LoudnessGainPlan(
    val requestedGainDb: Float, val appliedGainDb: Float,
    val predictedIntegratedLufs: Float, val predictedTruePeakDbtp: Float
)

object LoudnessNormalizer {
    const val DEFAULT_TARGET_LUFS = -16f
    const val TRUE_PEAK_CEILING_DBTP = -1.5f
    const val MAX_POSITIVE_GAIN_DB = 3f
    const val MAX_CUT_DB = -12f

    fun plan(integratedLufs: Float, truePeakDbtp: Float,
             targetLufs: Float = DEFAULT_TARGET_LUFS): LoudnessGainPlan? {
        if (!integratedLufs.isFinite() || !truePeakDbtp.isFinite() || !targetLufs.isFinite()) return null
        if (integratedLufs <= LoudnessMeter.ABSOLUTE_GATE_LUFS.toFloat()) return null
        val requested = targetLufs - integratedLufs
        val peakAllowance = TRUE_PEAK_CEILING_DBTP - truePeakDbtp
        if (peakAllowance < MAX_CUT_DB) return null
        val applied = requested.coerceIn(MAX_CUT_DB, MAX_POSITIVE_GAIN_DB).coerceAtMost(peakAllowance)
        return LoudnessGainPlan(requested, applied, integratedLufs + applied, truePeakDbtp + applied)
    }
}

/** Loudness-matched A/B proposal; use the same source section and keep this gain temporary. */
object LoudnessABMatcher {
    const val MAX_MATCH_GAIN_DB = 6f
    fun matchGainDb(referenceLufs: Float, comparisonLufs: Float, comparisonTruePeakDbtp: Float): Float? {
        if (!referenceLufs.isFinite() || !comparisonLufs.isFinite() ||
            !comparisonTruePeakDbtp.isFinite() || referenceLufs <= -70f || comparisonLufs <= -70f) return null
        val requested = (referenceLufs - comparisonLufs).coerceIn(-MAX_MATCH_GAIN_DB, MAX_MATCH_GAIN_DB)
        val peakAllowance = LoudnessNormalizer.TRUE_PEAK_CEILING_DBTP - comparisonTruePeakDbtp
        if (peakAllowance < -MAX_MATCH_GAIN_DB) return null
        return requested.coerceAtMost(peakAllowance)
    }
}

class OfflineLoudnessAccumulator(
    private val mediaKey: String, private val fingerprint: String, private val durationMs: Long,
    sampleRate: Int, channels: Int
) {
    private val meter = LoudnessMeter(sampleRate, channels)
    private val sampleRateValue = sampleRate
    private val channelCount = channels
    private var finished = false

    fun feed(interleavedPcm: FloatArray, frameCount: Int = interleavedPcm.size / channelCount) {
        check(!finished) { "Analysis is already finished" }
        meter.process(interleavedPcm, frameCount)
    }

    fun finish(measuredPeakDbtp: Float): TrackLoudnessRecord {
        check(!finished) { "Analysis is already finished" }
        require(measuredPeakDbtp.isFinite())
        finished = true
        return TrackLoudnessRecord(mediaKey, fingerprint, durationMs, sampleRateValue,
            channelCount, meter.integratedLufs, measuredPeakDbtp)
    }
}
