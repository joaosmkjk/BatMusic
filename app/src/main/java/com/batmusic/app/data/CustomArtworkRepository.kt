package com.batmusic.app.data

import android.content.Context

/** Stores user-selected artwork references without modifying the original audio files. */
class CustomArtworkRepository(context: Context) {
    private val prefs = context.getSharedPreferences("batmusic_custom_artwork", Context.MODE_PRIVATE)

    fun songArtwork(songId: Long): String? = prefs.getString("song_$songId", null)

    fun setSongArtwork(songId: Long, uri: String) {
        require(songId > 0L && uri.isNotBlank())
        prefs.edit().putString("song_$songId", uri).apply()
    }

    fun folderArtworks(): Map<String, String> = prefs.all
        .filterKeys { it.startsWith("folder:") }
        .mapNotNull { (key, value) -> (value as? String)?.let { key.removePrefix("folder:") to it } }
        .toMap()

    fun setFolderArtwork(folderPath: String, uri: String) {
        require(uri.isNotBlank())
        prefs.edit().putString("folder:$folderPath", uri).apply()
    }
}
