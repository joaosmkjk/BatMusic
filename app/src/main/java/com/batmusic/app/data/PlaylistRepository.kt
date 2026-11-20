package com.batmusic.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class LocalPlaylist(
    val id: String,
    val name: String,
    val songIds: List<Long>,
    val artworkUri: String? = null
)

/** Small local playlist store. Song IDs refer to MediaStore rows; device files are never copied. */
class PlaylistRepository(context: Context) {
    private val prefs = context.getSharedPreferences("batmusic_playlists", Context.MODE_PRIVATE)

    @Synchronized fun all(): List<LocalPlaylist> = runCatching {
        val rows = JSONArray(prefs.getString("playlists", "[]"))
        buildList {
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val ids = row.optJSONArray("songs") ?: JSONArray()
                add(LocalPlaylist(
                    id = row.getString("id"),
                    name = row.optString("name").trim().take(80).ifBlank { "Playlist" },
                    songIds = (0 until ids.length()).mapNotNull { ids.optLong(it).takeIf { id -> id > 0 } },
                    artworkUri = row.optString("artwork").takeIf { it.isNotBlank() }
                ))
            }
        }
    }.getOrDefault(emptyList())

    @Synchronized fun create(name: String): LocalPlaylist {
        val clean = name.trim().take(80)
        require(clean.isNotBlank()) { "Informe um nome para a playlist" }
        val value = LocalPlaylist(UUID.randomUUID().toString(), clean, emptyList())
        save(all() + value)
        return value
    }

    @Synchronized fun rename(id: String, name: String) {
        val clean = name.trim().take(80)
        require(clean.isNotBlank()) { "Informe um nome para a playlist" }
        save(all().map { if (it.id == id) it.copy(name = clean) else it })
    }

    @Synchronized fun delete(id: String) { save(all().filterNot { it.id == id }) }

    @Synchronized fun setArtwork(id: String, uri: String?) {
        save(all().map { if (it.id == id) it.copy(artworkUri = uri) else it })
    }

    @Synchronized fun addSongs(id: String, songIds: List<Long>) {
        save(all().map { playlist ->
            if (playlist.id == id) playlist.copy(songIds = playlist.songIds + songIds.filterNot { it in playlist.songIds }) else playlist
        })
    }

    @Synchronized fun removeSong(id: String, songId: Long) {
        save(all().map { if (it.id == id) it.copy(songIds = it.songIds.filterNot { song -> song == songId }) else it })
    }

    @Synchronized fun reorder(id: String, from: Int, to: Int) {
        save(all().map { playlist ->
            if (playlist.id != id || from !in playlist.songIds.indices || to !in playlist.songIds.indices) playlist
            else playlist.copy(songIds = playlist.songIds.toMutableList().apply { add(to, removeAt(from)) })
        })
    }

    private fun save(playlists: List<LocalPlaylist>) {
        val rows = JSONArray()
        playlists.forEach { playlist ->
            rows.put(JSONObject().apply {
                put("id", playlist.id); put("name", playlist.name)
                put("songs", JSONArray(playlist.songIds)); put("artwork", playlist.artworkUri ?: "")
            })
        }
        prefs.edit().putString("playlists", rows.toString()).apply()
    }
}
