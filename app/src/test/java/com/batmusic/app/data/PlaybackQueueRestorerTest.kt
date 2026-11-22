package com.batmusic.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueRestorerTest {
    @Test fun restoresNormalQueueInExactTimelineOrder() {
        val plan = PlaybackQueueRestorer.plan(
            queue(items = listOf("a", "b", "c"), current = "b", position = 4200),
            available("a", "b", "c"), emptyMap()
        )!!

        assertEquals(listOf("a", "b", "c"), plan.state.items.map { it.mediaId })
        assertEquals(1, plan.currentIndex)
        assertEquals(4200L, plan.state.positionMs)
        assertFalse(plan.state.shuffleEnabled)
        assertEquals(listOf(0, 1, 2), plan.shuffleOrderIndices.toList())
    }

    @Test fun restoresSavedShuffleTraversalOrder() {
        val plan = PlaybackQueueRestorer.plan(
            queue(items = listOf("a", "b", "c"), current = "b", shuffle = true, shuffleOrder = listOf(2, 0, 1)),
            available("a", "b", "c"), emptyMap()
        )!!

        assertTrue(plan.state.shuffleEnabled)
        assertEquals(listOf(2, 0, 1), plan.shuffleOrderIndices.toList())
        assertEquals("b", plan.state.currentMediaId)
    }

    @Test fun preservesCurrentTrackAndPositionWhenPlaylistIsUnchanged() {
        val plan = PlaybackQueueRestorer.plan(
            queue(type = PlaybackQueueRestorer.CONTEXT_PLAYLIST, id = "p", items = listOf("a", "b"), current = "b", position = 9000),
            available("a", "b"), mapOf("p" to listOf("a", "b"))
        )!!

        assertEquals(1, plan.currentIndex)
        assertEquals(9000L, plan.state.positionMs)
        assertEquals(PlaybackQueueRestorer.CONTEXT_PLAYLIST, plan.state.contextType)
    }

    @Test fun alteredPlaylistUsesCurrentMembershipAndKeepsCurrentTrackWhenPossible() {
        val plan = PlaybackQueueRestorer.plan(
            queue(type = PlaybackQueueRestorer.CONTEXT_PLAYLIST, id = "p", items = listOf("a", "b", "c"), current = "b", position = 5000, shuffle = true, shuffleOrder = listOf(2, 1, 0)),
            available("a", "b", "c", "d"), mapOf("p" to listOf("b", "c", "d"))
        )!!

        assertEquals(listOf("b", "c", "d"), plan.state.items.map { it.mediaId })
        assertEquals("b", plan.state.currentMediaId)
        assertEquals(5000L, plan.state.positionMs)
        assertEquals(listOf(1, 0, 2), plan.shuffleOrderIndices.toList())
    }

    @Test fun removedPlaylistDowngradesSavedQueueInsteadOfClaimingPlaylistContext() {
        val plan = PlaybackQueueRestorer.plan(
            queue(type = PlaybackQueueRestorer.CONTEXT_PLAYLIST, id = "deleted", items = listOf("a", "b"), current = "b"),
            available("a", "b"), emptyMap()
        )!!

        assertEquals(PlaybackQueueRestorer.CONTEXT_CUSTOM, plan.state.contextType)
        assertEquals(listOf("a", "b"), plan.state.items.map { it.mediaId })
    }

    @Test fun incompatibleTimelineCannotKeepPlaylistContext() {
        val context = PlaybackQueueRestorer.validatedContext(
            PlaybackQueueRestorer.CONTEXT_PLAYLIST, "p", listOf("a", "outside"), mapOf("p" to listOf("a", "b"))
        )

        assertEquals(PlaybackQueueRestorer.CONTEXT_CUSTOM, context.first)
    }

    @Test fun emptyQueueDoesNotCreateAFirstTrackRestore() {
        assertNull(PlaybackQueueRestorer.plan(queue(items = emptyList()), available("a"), emptyMap()))
    }

    @Test fun mediaStoreAbsenceDoesNotEraseSavedIdsOrShufflePermutation() {
        val plan = PlaybackQueueRestorer.plan(
            queue(type = PlaybackQueueRestorer.CONTEXT_CUSTOM, items = listOf("a", "missing", "c"), current = "missing", position = 3000, shuffle = true, shuffleOrder = listOf(2, 1, 0)),
            available("a", "c"), emptyMap()
        )!!

        assertEquals(listOf("a", "missing", "c"), plan.state.items.map { it.mediaId })
        assertEquals("missing", plan.state.currentMediaId)
        assertEquals(3000L, plan.state.positionMs)
        assertEquals(listOf(2, 1, 0), plan.shuffleOrderIndices.toList())
    }

    @Test fun repeatedTracksRetainTheirQueueEntriesAndCurrentOccurrence() {
        val saved = queue(
            type = PlaybackQueueRestorer.CONTEXT_CUSTOM,
            items = listOf("a", "b", "a"), current = "a", shuffle = true, shuffleOrder = listOf(2, 1, 0)
        ).copy(currentMediaIndex = 2)
        val plan = PlaybackQueueRestorer.plan(saved, available("a", "b"), emptyMap())!!

        assertEquals(listOf("a", "b", "a"), plan.state.items.map { it.mediaId })
        assertEquals(2, plan.currentIndex)
        assertEquals(listOf(2, 1, 0), plan.shuffleOrderIndices.toList())
    }

    private fun queue(
        type: String = "LIBRARY", id: String = "all", items: List<String>, current: String? = items.firstOrNull(),
        position: Long = 0L, shuffle: Boolean = false, shuffleOrder: List<Int> = items.indices.toList()
    ) = PersistedPlaybackQueue(
        contextType = type, contextId = id, items = items.map(::descriptor), currentMediaId = current,
        currentMediaIndex = current?.let(items::indexOf) ?: -1,
        positionMs = position, shuffleEnabled = shuffle, shuffleOrderIndices = shuffleOrder
    )

    private fun available(vararg ids: String) = ids.map(::descriptor)

    private fun descriptor(id: String) = QueueMediaDescriptor(id, "content://media/$id", title = id)
}
