package com.batmusic.app.audio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SavedAudioPreset(
    val name: String,
    val audioEnabled: Boolean,
    val batBoost: Int,
    val equalizerEnabled: Boolean,
    val gainsTenthDb: List<Int>,
    val bassEnabled: Boolean,
    val bassStrength: Int,
    val preGainDb: Float,
    val compressorEnabled: Boolean,
    val thresholdDb: Float,
    val ratio: Float,
    val attackMs: Float,
    val releaseMs: Float,
    val makeupDb: Float,
    val limiterReleaseMs: Float = 120f
)

class AudioPresetRepository(context: Context) {
    private val prefs = context.getSharedPreferences("batmusic_audio_presets", Context.MODE_PRIVATE)

    @Synchronized fun all(): List<SavedAudioPreset> = runCatching {
        val rows = JSONArray(prefs.getString("presets", "[]"))
        buildList {
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                val gains = row.optJSONArray("gains") ?: JSONArray()
                if (gains.length() != 10) continue
                add(SavedAudioPreset(
                    name = row.optString("name").trim().take(32).ifBlank { "Preset" },
                    audioEnabled = row.optBoolean("audioEnabled", true),
                    batBoost = row.optInt("batBoost", 100).coerceIn(100, 200),
                    equalizerEnabled = row.optBoolean("eqEnabled", false),
                    gainsTenthDb = (0 until 10).map { gains.optInt(it).coerceIn(-120, 120) },
                    bassEnabled = row.optBoolean("bassEnabled", false),
                    bassStrength = row.optInt("bassStrength", 0).coerceIn(0, 1000),
                    preGainDb = row.optDouble("preGainDb", 0.0).toFloat().coerceIn(0f, 24f),
                    compressorEnabled = row.optBoolean("compressorEnabled", false),
                    thresholdDb = row.optDouble("thresholdDb", -12.0).toFloat().coerceIn(-36f, 0f),
                    ratio = row.optDouble("ratio", 3.0).toFloat().coerceIn(1f, 12f),
                    attackMs = row.optDouble("attackMs", 6.0).toFloat().coerceIn(1f, 100f),
                    releaseMs = row.optDouble("releaseMs", 120.0).toFloat().coerceIn(10f, 1000f),
                    makeupDb = row.optDouble("makeupDb", 0.0).toFloat().coerceIn(-12f, 12f),
                    limiterReleaseMs = row.optDouble("limiterReleaseMs", 120.0).toFloat().coerceIn(20f, 1_000f)
                ))
            }
        }
    }.getOrDefault(emptyList())

    @Synchronized fun find(name: String) = all().firstOrNull { it.name == name }

    @Synchronized fun save(preset: SavedAudioPreset) {
        val name = preset.name.trim().take(32)
        require(name.isNotBlank()) { "O preset precisa de um nome" }
        require(BUILT_IN_PRESETS.none { it.equals(name, ignoreCase = true) }) { "Esse nome pertence a um preset integrado" }
        require(preset.gainsTenthDb.size == 10) { "O equalizador exige dez bandas" }
        val rows = JSONArray()
        (all().filterNot { it.name.equals(name, ignoreCase = true) } + preset.copy(name = name)).forEach { item ->
            rows.put(JSONObject().apply {
                put("name", item.name); put("audioEnabled", item.audioEnabled); put("batBoost", item.batBoost); put("eqEnabled", item.equalizerEnabled)
                put("gains", JSONArray(item.gainsTenthDb)); put("bassEnabled", item.bassEnabled); put("bassStrength", item.bassStrength)
                put("preGainDb", item.preGainDb.toDouble()); put("compressorEnabled", item.compressorEnabled)
                put("thresholdDb", item.thresholdDb.toDouble()); put("ratio", item.ratio.toDouble())
                put("attackMs", item.attackMs.toDouble()); put("releaseMs", item.releaseMs.toDouble()); put("makeupDb", item.makeupDb.toDouble()); put("limiterReleaseMs", item.limiterReleaseMs.toDouble())
            })
        }
        prefs.edit().putString("presets", rows.toString()).apply()
    }

    @Synchronized fun delete(name: String) {
        val rows = JSONArray()
        all().filterNot { it.name == name }.forEach { item ->
            rows.put(JSONObject().apply {
                put("name", item.name); put("audioEnabled", item.audioEnabled); put("batBoost", item.batBoost); put("eqEnabled", item.equalizerEnabled)
                put("gains", JSONArray(item.gainsTenthDb)); put("bassEnabled", item.bassEnabled); put("bassStrength", item.bassStrength)
                put("preGainDb", item.preGainDb.toDouble()); put("compressorEnabled", item.compressorEnabled)
                put("thresholdDb", item.thresholdDb.toDouble()); put("ratio", item.ratio.toDouble())
                put("attackMs", item.attackMs.toDouble()); put("releaseMs", item.releaseMs.toDouble()); put("makeupDb", item.makeupDb.toDouble()); put("limiterReleaseMs", item.limiterReleaseMs.toDouble())
            })
        }
        prefs.edit().putString("presets", rows.toString()).apply()
    }

    companion object {
        private val BUILT_IN_PRESETS = setOf("Original / Flat", "Rock", "Metal", "Classical", "Electronic", "Vocal", "Custom", "Alternative / Indie", "Bass Focus", "BATLOUD")
    }
}
