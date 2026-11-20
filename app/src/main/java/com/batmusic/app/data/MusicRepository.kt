package com.batmusic.app.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val folderPath: String,
    val uri: Uri,
    val artworkUri: Uri?
)

/** Reads the user's existing indexed audio. It never copies or changes media files. */
class MusicRepository(private val context: Context) {
    suspend fun loadSongs(): CatalogLoadResult = withContext(Dispatchers.IO) {
        try {
            val versionBefore = mediaStoreVersion()
            val result = mutableListOf<Song>()
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA
            )
            val cursor = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            ) ?: return@withContext CatalogLoadResult(
                emptyList(), CatalogStatus.UNAVAILABLE,
                error = IllegalStateException("MediaStore returned a null cursor")
            )
            cursor.use {
                val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val data = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                while (cursor.moveToNext()) {
                    val mediaId = cursor.getLong(id)
                    val mediaAlbumId = cursor.getLong(albumId)
                    result += Song(
                        id = mediaId,
                        title = cursor.getString(title).orEmpty().ifBlank { "Música sem título" },
                        artist = cursor.getString(artist).orEmpty().ifBlank { "Artista desconhecido" },
                        album = cursor.getString(album).orEmpty().ifBlank { "Álbum desconhecido" },
                        duration = cursor.getLong(duration),
                        folderPath = cursor.getString(data).orEmpty().substringBeforeLast('/', ""),
                        uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId),
                        artworkUri = if (mediaAlbumId > 0) Uri.parse("content://media/external/audio/albumart/$mediaAlbumId") else null
                    )
                }
            }
            val versionAfter = mediaStoreVersion()
            val status = PlaybackQueuePolicies.catalogStatus(
                cursorReturned = true,
                cursorExhausted = true,
                queryFailed = false,
                versionBefore = versionBefore,
                versionAfter = versionAfter
            )
            CatalogLoadResult(result, status, versionAfter)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "Could not enumerate MediaStore audio", error)
            CatalogLoadResult(emptyList(), CatalogStatus.UNAVAILABLE, error = error)
        }
    }

    private fun mediaStoreVersion(): String? {
        if (android.os.Build.VERSION.SDK_INT < 29) return MediaStore.getVersion(context)
        val volumes = MediaStore.getExternalVolumeNames(context).sorted()
        if (volumes.isEmpty()) return null
        val versions = mutableListOf<String>()
        for (volume in volumes) {
            val version = MediaStore.getVersion(context, volume) ?: return null
            versions += "$volume=$version"
        }
        return versions.joinToString("|")
    }

    private companion object { const val TAG = "MusicRepository" }
}
