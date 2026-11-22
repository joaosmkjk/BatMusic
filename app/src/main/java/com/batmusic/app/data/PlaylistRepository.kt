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

/**
 * Small local playlist store. Writes are synchronous and atomic at the
 * SharedPreferences file level. A valid previous snapshot is retained as backup.
 *
 * IMPORTANT: a malformed primary or backup is never silently interpreted as an
 * empty library by a mutating operation. This prevents create/add/rename from
 * replacing a user's playlists with an empty array after a parse failure.
 */
class PlaylistRepository(context: Context) {
    private val prefs = context.getSharedPreferences("batmusic_playlists", Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<LocalPlaylist> = runCatching { readStrict() }.getOrDefault(emptyList())

    @Synchronized
    fun create(name: String): LocalPlaylist {
        val clean = name.trim().take(80)
        require(clean.isNotBlank()) { "Informe um nome para a playlist" }
        val current = readStrict()
        val value = LocalPlaylist(UUID.randomUUID().toString(), clean, emptyList())
        save(current + value)
        return value
    }

    @Synchronized
    fun rename(id: String, name: String) {
        val clean = name.trim().take(80)
        require(clean.isNotBlank()) { "Informe um nome para a playlist" }
        save(readStrict().map { if (it.id == id) it.copy(name = clean) else it })
    }

    @Synchronized
    fun delete(id: String) {
        save(readStrict().filterNot { it.id == id })
    }

    @Synchronized
    fun setArtwork(id: String, uri: String?) {
        save(readStrict().map { if (it.id == id) it.copy(artworkUri = uri) else it })
    }

    @Synchronized
    fun addSongs(id: String, songIds: List<Long>) {
        save(readStrict().map { playlist ->
            if (playlist.id == id) {
                playlist.copy(songIds = playlist.songIds + songIds.filter { it > 0L }.filterNot { it in playlist.songIds })
            } else playlist
        })
    }

    @Synchronized
    fun removeSong(id: String, songId: Long) {
        save(readStrict().map { if (it.id == id) it.copy(songIds = it.songIds.filterNot { song -> song == songId }) else it })
    }

    @Synchronized
    fun reorder(id: String, from: Int, to: Int) {
        save(readStrict().map { playlist ->
            if (playlist.id != id || from !in playlist.songIds.indices || to !in playlist.songIds.indices) playlist
            else playlist.copy(songIds = playlist.songIds.toMutableList().apply { add(to, removeAt(from)) })
        })
    }

    private fun readStrict(): List<LocalPlaylist> {
        val primary = prefs.getString(KEY_PLAYLISTS, null)
        if (primary == null) return emptyList()

        return try {
            parse(primary)
        } catch (primaryError: Exception) {
            val backup = prefs.getString(KEY_BACKUP, null) ?: throw IllegalStateException(
                "Dados das playlists estão corrompidos; gravação bloqueada para proteger os dados.",
                primaryError
            )
            try {
                parse(backup)
            } catch (backupError: Exception) {
                throw IllegalStateException(
                    "Arquivo principal e backup das playlists estão corrompidos; gravação bloqueada.",
                    backupError
                )
            }
        }
    }

    private fun parse(raw: String): List<LocalPlaylist> {
        val rows = JSONArray(raw)
        return buildList {
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val ids = row.optJSONArray("songs") ?: JSONArray()
                add(LocalPlaylist(
                    id = row.getString("id"),
                    name = row.optString("name").trim().take(80).ifBlank { "Playlist" },
                    songIds = (0 until ids.length()).mapNotNull { ids.optLong(it).takeIf { id -> id > 0L } },
                    artworkUri = row.optString("artwork").takeIf { it.isNotBlank() }
                ))
            }
        }
    }

    private fun save(playlists: List<LocalPlaylist>) {
        val rows = JSONArray()
        playlists.forEach { playlist ->
            rows.put(JSONObject().apply {
                put("id", playlist.id)
                put("name", playlist.name)
                put("songs", JSONArray(playlist.songIds))
                put("artwork", playlist.artworkUri ?: "")
            })
        }
        val serialized = rows.toString()
        // SharedPreferences commits the complete XML file atomically. Keep the
        // last known-good logical snapshot before replacing the primary value.
        val editor = prefs.edit()
            .putString(KEY_BACKUP, JSONArray().also { backup ->
                playlists.forEach { playlist ->
                    backup.put(JSONObject().apply {
                        put("id", playlist.id)
                        put("name", playlist.name)
                        put("songs", JSONArray(playlist.songIds))
                        put("artwork", playlist.artworkUri ?: "")
                    })
                }
            }.toString())
            .putString(KEY_PLAYLISTS, serialized)
        check(editor.commit()) { "Não foi possível salvar as playlists" }
    }

    private companion object {
        const val KEY_PLAYLISTS = "playlists"
        const val KEY_BACKUP = "playlists.bak"
    }
}
