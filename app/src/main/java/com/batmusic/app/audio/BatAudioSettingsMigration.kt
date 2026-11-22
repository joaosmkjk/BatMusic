package com.batmusic.app.audio

import android.content.Context

/** One-time migration for BATBOOST X 3.0 defaults. */
internal object BatAudioSettingsMigration {
    private const val SCHEMA_VERSION = 3

    fun migrate(context: Context) {
        val preferences = context.getSharedPreferences("batmusic", Context.MODE_PRIVATE)
        val version = preferences.getInt("batboost_schema_version", 0)
        if (version < SCHEMA_VERSION) {
            // The former default compressor could cancel most or all of BATGAIN.
            // Start the new engine in transparent gain mode; users can re-enable compression.
            preferences.edit()
                .putBoolean("compressor_enabled", false)
                .putInt("batboost_schema_version", SCHEMA_VERSION)
                .apply()
        }
    }
}
