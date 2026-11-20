package com.batmusic.app.data

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

data class PlaybackQueueEntry(val mediaIndex: Int, val mediaId: String)
data class PlaybackQueueSnapshot(val entries: List<PlaybackQueueEntry>, val currentMediaIndex: Int, val origin: String)

/** Reads the Media3 timeline as the authoritative queue, including its active shuffle order. */
@UnstableApi
class PlaybackQueue {
    fun capture(player: Player, contextType: String, contextId: String): PersistedPlaybackQueue {
        val mediaItems = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
        val traversalOrder = if (player.shuffleModeEnabled) shuffleTraversal(player, mediaItems.size) else mediaItems.indices.toList()
        val currentItem = player.currentMediaItem
        return PersistedPlaybackQueue(
            contextType = contextType,
            contextId = contextId,
            items = mediaItems.map { item ->
                val metadata = item.mediaMetadata
                QueueMediaDescriptor(
                    mediaId = item.mediaId,
                    uri = item.localConfiguration?.uri?.toString().orEmpty(),
                    title = metadata.title?.toString().orEmpty(),
                    artist = metadata.artist?.toString().orEmpty(),
                    album = metadata.albumTitle?.toString().orEmpty(),
                    artworkUri = metadata.artworkUri?.toString()
                )
            },
            currentMediaId = currentItem?.mediaId,
            currentMediaIndex = player.currentMediaItemIndex,
            positionMs = player.currentPosition.takeIf { it >= 0L } ?: 0L,
            shuffleEnabled = player.shuffleModeEnabled,
            shuffleOrderIndices = traversalOrder,
            repeatMode = player.repeatMode,
            playWhenReady = player.playWhenReady
        )
    }

    private fun shuffleTraversal(player: Player, mediaCount: Int): List<Int> {
        val timeline = player.currentTimeline
        if (timeline.windowCount != mediaCount || mediaCount == 0) return (0 until mediaCount).toList()
        var index = timeline.getFirstWindowIndex(true)
        if (index !in 0 until mediaCount) return (0 until mediaCount).toList()
        val seen = mutableSetOf<Int>()
        val result = mutableListOf<Int>()
        while (index in 0 until mediaCount && seen.add(index)) {
            result += index
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
        }
        return if (result.size == mediaCount) result else (0 until mediaCount).toList()
    }

    fun snapshot(player: Player, origin: String): PlaybackQueueSnapshot {
        if (player.mediaItemCount == 0) return PlaybackQueueSnapshot(emptyList(), C.INDEX_UNSET, origin)
        val current = player.currentMediaItemIndex
        var index = current.takeIf { it in 0 until player.mediaItemCount } ?: 0
        val seen = mutableSetOf<Int>()
        val entries = mutableListOf<PlaybackQueueEntry>()
        while (index in 0 until player.mediaItemCount && seen.add(index)) {
            entries += PlaybackQueueEntry(index, player.getMediaItemAt(index).mediaId)
            val next = player.currentTimeline.getNextWindowIndex(index, player.repeatMode, player.shuffleModeEnabled)
            if (next == C.INDEX_UNSET || next in seen) break
            index = next
        }
        return PlaybackQueueSnapshot(entries, current, origin)
    }
}
