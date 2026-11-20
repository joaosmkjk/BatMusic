package com.batmusic.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueIntegrityPoliciesTest {
    private val snapshot = PersistedPlaybackQueue(
        contextType = PlaybackQueueRestorer.CONTEXT_CUSTOM,
        contextId = "Fila recuperada",
        items = listOf(descriptor("a"), descriptor("b")),
        currentMediaId = "b",
        currentMediaIndex = 1,
        positionMs = 8100,
        shuffleEnabled = true,
        shuffleOrderIndices = listOf(1, 0)
    )

    @Test fun partialMediaStoreQueryCannotReconcileQueue() {
        val status = PlaybackQueuePolicies.catalogStatus(true, false, false, "v1", "v1")
        assertEquals(CatalogStatus.INCOMPLETE, status)
        val plan = PlaybackQueueRestorer.plan(snapshot, listOf(descriptor("a")), emptyMap(), status)!!
        assertEquals(snapshot.items, plan.state.items)
    }

    @Test fun unavailableMediaStoreDoesNotClearSnapshot() {
        val status = PlaybackQueuePolicies.catalogStatus(false, false, true, "v1", null)
        assertEquals(CatalogStatus.UNAVAILABLE, status)
        assertEquals(snapshot.items, PlaybackQueueRestorer.plan(snapshot, emptyList(), emptyMap(), status)!!.state.items)
    }

    @Test fun confirmedVisibleCatalogWithoutTrackKeepsItsSavedDescriptor() {
        val status = PlaybackQueuePolicies.catalogStatus(true, true, false, "v2", "v2")
        assertEquals(CatalogStatus.CONFIRMED, status)
        val plan = PlaybackQueueRestorer.plan(snapshot, listOf(descriptor("a")), emptyMap(), status)!!
        assertEquals(listOf("a", "b"), plan.state.items.map { it.mediaId })
    }

    @Test fun externalQueueWithPlaylistMemberIdsCannotClaimPlaylistContext() {
        val playlist = listOf("a", "b", "c")
        assertEquals(
            PlaybackQueueRestorer.CONTEXT_CUSTOM,
            PlaybackQueueRestorer.validatedContext(PlaybackQueueRestorer.CONTEXT_PLAYLIST, "p", listOf("c", "b", "a"), mapOf("p" to playlist)).first
        )
        assertEquals(
            PlaybackQueueRestorer.CONTEXT_CUSTOM,
            PlaybackQueuePolicies.contextForMutation(QueueMutationOrigin.EXTERNAL_CONTROLLER, "PLAYLIST", "p").first
        )
    }

    @Test fun interruptedContextChangeKeepsPreviousSnapshot() {
        val candidate = snapshot.copy(contextType = PlaybackQueueRestorer.CONTEXT_PLAYLIST, contextId = "new")
        assertEquals(snapshot, PlaybackQueuePolicies.snapshotAfterApply(snapshot, candidate, applied = false))
    }

    @Test fun failedTimelineApplicationKeepsPreviousSnapshot() {
        val candidate = snapshot.copy(items = listOf(descriptor("new")))
        assertEquals(snapshot, PlaybackQueuePolicies.snapshotAfterApply(snapshot, candidate, applied = false))
        assertNotNull(PlaybackQueuePolicies.snapshotAfterApply(snapshot, candidate, applied = true))
    }

    @Test fun restoreWithNoCatalogItemsRetainsSavedQueueForLaterRetry() {
        val plan = PlaybackQueueRestorer.plan(snapshot, emptyList(), emptyMap(), CatalogStatus.UNAVAILABLE)
        assertEquals(snapshot.items, plan!!.state.items)
        assertEquals(1, plan.currentIndex)
    }

    @Test fun laterConfirmedCatalogRefreshUpdatesKnownItemsWithoutDroppingMissingOnes() {
        val updated = descriptor("a").copy(title = "Atualizada")
        val plan = PlaybackQueueRestorer.plan(snapshot, listOf(updated), emptyMap(), CatalogStatus.CONFIRMED)!!
        assertEquals("Atualizada", plan.state.items[0].title)
        assertEquals("b", plan.state.items[1].mediaId)
    }

    @Test fun positionCheckpointIsSuppressedDuringQueueTransactionOrRestore() {
        assertFalse(PlaybackQueuePolicies.shouldPersistPosition(true, true, true, false, false))
        assertFalse(PlaybackQueuePolicies.shouldPersistPosition(true, true, false, true, false))
        assertFalse(PlaybackQueuePolicies.shouldPersistPosition(true, true, false, false, true))
        assertTrue(PlaybackQueuePolicies.shouldPersistPosition(true, true, false, false, false))
    }

    @Test fun shuffleOrderRemainsAValidExactPermutationAfterPlaylistReconciliation() {
        val queued = snapshot.copy(
            contextType = PlaybackQueueRestorer.CONTEXT_PLAYLIST,
            contextId = "p",
            items = listOf(descriptor("a"), descriptor("b"), descriptor("c")),
            currentMediaId = "b",
            currentMediaIndex = 1,
            shuffleOrderIndices = listOf(2, 1, 0)
        )
        val plan = PlaybackQueueRestorer.plan(
            queued,
            listOf(descriptor("a"), descriptor("b"), descriptor("c")),
            mapOf("p" to listOf("b", "c")),
            CatalogStatus.CONFIRMED
        )!!
        assertEquals(listOf("b", "c"), plan.state.items.map { it.mediaId })
        assertEquals(listOf(1, 0), plan.shuffleOrderIndices.toList())
        assertEquals(plan.state.items.indices.toSet(), plan.shuffleOrderIndices.toSet())
    }

    private fun descriptor(id: String) = QueueMediaDescriptor(id, "content://media/$id", title = id)
}
