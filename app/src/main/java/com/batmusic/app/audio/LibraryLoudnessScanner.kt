package com.batmusic.app.audio

import android.content.Context
import android.provider.MediaStore
import com.batmusic.app.data.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class LibraryAnalysisProgress(
    val completed: Int, val total: Int, val currentTitle: String,
    val currentPositionMs: Long, val currentDurationMs: Long
)

data class LibraryAnalysisResult(
    val analyzed: Int, val cached: Int, val failed: Int,
    val records: List<TrackLoudnessRecord>
)

/** Sequential, cancellable scanner; decoded PCM is streamed and never retained per track. */
class LibraryLoudnessScanner(context: Context) {
    private val appContext = context.applicationContext
    private val analyzer = AndroidTrackLoudnessAnalyzer(appContext)
    private val cache = TrackLoudnessCache(appContext)

    suspend fun scan(
        songs: List<Song>,
        onProgress: ((LibraryAnalysisProgress) -> Unit)? = null
    ): LibraryAnalysisResult = withContext(Dispatchers.IO) {
        var analyzed = 0
        var cachedCount = 0
        var failed = 0
        val records = ArrayList<TrackLoudnessRecord>(songs.size)

        for ((index, song) in songs.withIndex()) {
            coroutineContext.ensureActive()
            val fingerprint = fingerprint(song)
            val cached = cache.get(song.uri.toString(), fingerprint)
            if (cached != null) {
                cachedCount++
                records += cached
                onProgress?.invoke(LibraryAnalysisProgress(index + 1, songs.size,
                    song.title, song.duration, song.duration))
                continue
            }
            try {
                val record = analyzer.analyze(song.uri, fingerprint, song.duration) { position, duration ->
                    onProgress?.invoke(LibraryAnalysisProgress(index, songs.size,
                        song.title, position, duration))
                }
                analyzed++
                records += record
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed++
            }
            onProgress?.invoke(LibraryAnalysisProgress(index + 1, songs.size,
                song.title, song.duration, song.duration))
        }
        LibraryAnalysisResult(analyzed, cachedCount, failed, records)
    }

    private fun fingerprint(song: Song): String {
        var size = -1L
        var modified = -1L
        runCatching {
            appContext.contentResolver.query(
                song.uri,
                arrayOf(MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    val modifiedIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                    if (sizeIndex >= 0) size = cursor.getLong(sizeIndex)
                    if (modifiedIndex >= 0) modified = cursor.getLong(modifiedIndex)
                }
            }
        }
        return "v${TrackLoudnessRecord.CURRENT_VERSION};uri=${song.uri};size=$size;modified=$modified"
    }
}
