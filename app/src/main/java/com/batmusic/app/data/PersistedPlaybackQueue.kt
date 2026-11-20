package com.batmusic.app.data

/** Serializable queue metadata kept independently of ExoPlayer's process lifetime. */
data class QueueMediaDescriptor(
    val mediaId: String,
    val uri: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artworkUri: String? = null
)

data class PersistedPlaybackQueue(
    val contextType: String = "LIBRARY",
    val contextId: String = "all",
    val items: List<QueueMediaDescriptor> = emptyList(),
    val currentMediaId: String? = null,
    val currentMediaIndex: Int = -1,
    val positionMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    /** ExoPlayer timeline indices in shuffle traversal order. Supports repeated media IDs. */
    val shuffleOrderIndices: List<Int> = emptyList(),
    val repeatMode: Int = 0,
    val playWhenReady: Boolean = false
)

data class QueueRestorePlan(
    val state: PersistedPlaybackQueue,
    val currentIndex: Int,
    val shuffleOrderIndices: IntArray
)

/** Pure policy used by the app and unit tests before applying a saved state to Media3. */
object PlaybackQueueRestorer {
    const val CONTEXT_PLAYLIST = "PLAYLIST"
    const val CONTEXT_CUSTOM = "CUSTOM"
    const val CONTEXT_EXTERNAL = "EXTERNAL"

    fun plan(
        saved: PersistedPlaybackQueue?,
        availableItems: List<QueueMediaDescriptor>,
        playlists: Map<String, List<String>>,
        catalogStatus: CatalogStatus = CatalogStatus.CONFIRMED
    ): QueueRestorePlan? {
        if (saved == null || saved.items.isEmpty()) return null

        // An unavailable or unstable enumeration is not evidence that saved IDs were deleted.
        if (catalogStatus != CatalogStatus.CONFIRMED) return preserveSavedQueue(saved)

        val availableById = availableItems.associateBy { it.mediaId }
        val playlistIds = if (saved.contextType == CONTEXT_PLAYLIST) playlists[saved.contextId] else null
        val contextType = when {
            saved.contextType == CONTEXT_PLAYLIST && playlistIds != null -> CONTEXT_PLAYLIST
            saved.contextType == CONTEXT_PLAYLIST -> CONTEXT_CUSTOM // Keep the saved queue, but never label a deleted playlist as active.
            saved.contextType == CONTEXT_EXTERNAL -> CONTEXT_CUSTOM
            else -> saved.contextType
        }
        val targetItems = mutableListOf<QueueMediaDescriptor>()
        val sourceIndices = mutableListOf<Int>()
        if (contextType == CONTEXT_PLAYLIST) {
            playlistIds.orEmpty().distinct().forEach { id ->
                val sourceIndex = saved.items.indexOfFirst { it.mediaId == id }
                val descriptor = availableById[id]
                    ?: saved.items.getOrNull(sourceIndex)
                    ?: return@forEach
                targetItems += descriptor
                sourceIndices += sourceIndex
            }
        } else {
            saved.items.forEachIndexed { index, item ->
                val currentDescriptor = availableById[item.mediaId] ?: item
                targetItems += currentDescriptor
                sourceIndices += index
            }
        }
        if (targetItems.isEmpty()) return null

        val finalIds = targetItems.map { it.mediaId }
        val oldIds = saved.items.map { it.mediaId }
        val requestedCurrent = saved.currentMediaId
        val oldCurrentIndex = saved.currentMediaIndex.takeIf { it in oldIds.indices && oldIds[it] == requestedCurrent }
            ?: requestedCurrent?.let(oldIds::indexOf)?.takeIf { it >= 0 }
            ?: 0
        val retainedIndex = sourceIndices.indexOf(oldCurrentIndex).takeIf { it >= 0 }
        val currentIndex = retainedIndex
            ?: requestedCurrent?.let(finalIds::indexOf)?.takeIf { it >= 0 }
            ?: nearestSurvivingIndex(oldCurrentIndex, sourceIndices, finalIds.size)
            ?: 0
        val currentId = finalIds[currentIndex]
        val position = if (requestedCurrent == currentId) saved.positionMs.coerceAtLeast(0L) else 0L

        val oldToNew = IntArray(saved.items.size) { -1 }
        sourceIndices.forEachIndexed { newIndex, oldIndex -> if (oldIndex in oldToNew.indices) oldToNew[oldIndex] = newIndex }
        val shuffleIndices = if (saved.shuffleEnabled) {
            val retainedOrder = saved.shuffleOrderIndices.mapNotNull { oldIndex ->
                oldToNew.getOrNull(oldIndex)?.takeIf { it >= 0 }
            }.distinct()
            retainedOrder + targetItems.indices.filterNot { it in retainedOrder }
        } else targetItems.indices.toList()
        if (shuffleIndices.size != targetItems.size || shuffleIndices.toSet().size != targetItems.size) return null

        return QueueRestorePlan(
            state = saved.copy(
                contextType = contextType,
                items = targetItems,
                currentMediaId = finalIds[currentIndex],
                currentMediaIndex = currentIndex,
                positionMs = position,
                shuffleOrderIndices = shuffleIndices
            ),
            currentIndex = currentIndex,
            shuffleOrderIndices = shuffleIndices.toIntArray()
        )
    }

    /** External or stale timelines cannot retain the PLAYLIST label if they contain foreign IDs. */
    fun validatedContext(
        contextType: String,
        contextId: String,
        timelineIds: List<String>,
        playlists: Map<String, List<String>>
    ): Pair<String, String> {
        if (contextType != CONTEXT_PLAYLIST) {
            return if (contextType == CONTEXT_EXTERNAL) CONTEXT_CUSTOM to "Fila recuperada" else contextType to contextId
        }
        val members = playlists[contextId]
        return if (members != null && timelineIds == members) {
            CONTEXT_PLAYLIST to contextId
        } else {
            CONTEXT_CUSTOM to "Fila recuperada"
        }
    }

    private fun nearestSurvivingIndex(oldIndex: Int, sourceIndices: List<Int>, targetSize: Int): Int? {
        sourceIndices.indexOfFirst { it >= oldIndex }.takeIf { it >= 0 }?.let { return it }
        sourceIndices.indexOfLast { it < oldIndex }.takeIf { it >= 0 }?.let { return it }
        return 0.takeIf { targetSize > 0 }
    }

    private fun preserveSavedQueue(saved: PersistedPlaybackQueue): QueueRestorePlan {
        val size = saved.items.size
        val requested = saved.currentMediaIndex.takeIf { it in 0 until size }
            ?: saved.currentMediaId?.let { id -> saved.items.indexOfFirst { it.mediaId == id }.takeIf { it >= 0 } }
            ?: 0
        val order = if (saved.shuffleEnabled) {
            val valid = saved.shuffleOrderIndices.filter { it in 0 until size }.distinct()
            valid + (0 until size).filterNot { it in valid }
        } else (0 until size).toList()
        return QueueRestorePlan(
            saved.copy(currentMediaIndex = requested, currentMediaId = saved.items[requested].mediaId,
                positionMs = saved.positionMs.coerceAtLeast(0L), shuffleOrderIndices = order),
            requested,
            order.toIntArray()
        )
    }
}
