package com.batmusic.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Small adapter around the pure queue model. Writes use apply() so the audio thread never waits on disk. */
class PlaybackQueueStore(context: Context) {
    private val preferences: SharedPreferences = context.getSharedPreferences("batmusic_playback", Context.MODE_PRIVATE)

    @Synchronized
    fun read(): PersistedPlaybackQueue? {
        val encoded = preferences.getString(KEY_QUEUE, null) ?: return null
        return try {
            val queue = decode(JSONObject(encoded))
            val checkpointIndex = preferences.getInt(KEY_POSITION_INDEX, -1)
            val checkpointId = preferences.getString(KEY_POSITION_ID, null)
            if (checkpointIndex == queue.currentMediaIndex && checkpointId == queue.currentMediaId) {
                queue.copy(positionMs = preferences.getLong(KEY_POSITION_MS, queue.positionMs).coerceAtLeast(0L))
            } else queue
        } catch (error: JSONException) {
            Log.e(TAG, "Saved playback queue is malformed; ignoring the saved snapshot", error)
            null
        } catch (error: RuntimeException) {
            Log.e(TAG, "Could not read saved playback queue", error)
            null
        }
    }

    @Synchronized
    fun write(queue: PersistedPlaybackQueue): Boolean {
        try {
            preferences.edit()
                .putString(KEY_QUEUE, encode(queue).toString())
                .remove(KEY_POSITION_ID).remove(KEY_POSITION_INDEX).remove(KEY_POSITION_MS)
                .apply()
            return true
        } catch (error: RuntimeException) {
            Log.e(TAG, "Could not persist playback queue", error)
            return false
        }
    }

    @Synchronized
    fun writePosition(mediaId: String?, mediaIndex: Int, positionMs: Long) {
        try {
            preferences.edit()
                .putString(KEY_POSITION_ID, mediaId)
                .putInt(KEY_POSITION_INDEX, mediaIndex)
                .putLong(KEY_POSITION_MS, positionMs.coerceAtLeast(0L))
                .apply()
        } catch (error: RuntimeException) {
            Log.e(TAG, "Could not persist playback position checkpoint", error)
        }
    }

    @Synchronized
    fun updateContext(type: String, id: String) {
        val previous = read() ?: PersistedPlaybackQueue()
        write(previous.copy(contextType = type, contextId = id))
    }

    private fun encode(queue: PersistedPlaybackQueue) = JSONObject().apply {
        put("version", 1)
        put("contextType", queue.contextType)
        put("contextId", queue.contextId)
        put("items", JSONArray().apply {
            queue.items.forEach { item ->
                put(JSONObject().apply {
                    put("mediaId", item.mediaId)
                    put("uri", item.uri)
                    put("title", item.title)
                    put("artist", item.artist)
                    put("album", item.album)
                    put("artworkUri", item.artworkUri ?: JSONObject.NULL)
                })
            }
        })
        put("currentMediaId", queue.currentMediaId ?: JSONObject.NULL)
        put("currentMediaIndex", queue.currentMediaIndex)
        put("positionMs", queue.positionMs.coerceAtLeast(0L))
        put("shuffleEnabled", queue.shuffleEnabled)
        put("shuffleOrder", JSONArray(queue.shuffleOrderIndices))
        put("repeatMode", queue.repeatMode)
        put("playWhenReady", queue.playWhenReady)
    }

    private fun decode(root: JSONObject): PersistedPlaybackQueue {
        val itemsJson = root.optJSONArray("items") ?: JSONArray()
        val items = buildList {
            for (index in 0 until itemsJson.length()) {
                val item = itemsJson.optJSONObject(index) ?: continue
                val id = item.optString("mediaId")
                val uri = item.optString("uri")
                // Keep entries without a URI so their timeline indices and shuffle order
                // remain aligned. The restorer filters them against currently playable items.
                if (id.isBlank()) continue
                add(QueueMediaDescriptor(
                    mediaId = id,
                    uri = uri,
                    title = item.optString("title"),
                    artist = item.optString("artist"),
                    album = item.optString("album"),
                    artworkUri = item.optString("artworkUri").takeIf { it.isNotBlank() && it != "null" }
                ))
            }
        }
        val orderJson = root.optJSONArray("shuffleOrder") ?: JSONArray()
        return PersistedPlaybackQueue(
            contextType = root.optString("contextType", "LIBRARY"),
            contextId = root.optString("contextId", "all"),
            items = items,
            currentMediaId = root.optString("currentMediaId").takeIf { it.isNotBlank() && it != "null" },
            currentMediaIndex = root.optInt("currentMediaIndex", -1),
            positionMs = root.optLong("positionMs", 0L).coerceAtLeast(0L),
            shuffleEnabled = root.optBoolean("shuffleEnabled", false),
            shuffleOrderIndices = (0 until orderJson.length()).mapNotNull { index -> orderJson.optInt(index, -1).takeIf { it >= 0 } },
            repeatMode = root.optInt("repeatMode", 0),
            playWhenReady = root.optBoolean("playWhenReady", false)
        )
    }

    private companion object {
        const val KEY_QUEUE = "queue_state_v1"
        const val KEY_POSITION_ID = "queue_position_media_id"
        const val KEY_POSITION_INDEX = "queue_position_media_index"
        const val KEY_POSITION_MS = "queue_position_ms"
        const val TAG = "PlaybackQueueStore"
    }
}
