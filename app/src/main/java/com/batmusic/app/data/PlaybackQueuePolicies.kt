package com.batmusic.app.data

enum class QueueMutationOrigin { INTERNAL_TRANSACTION, INTERNAL_UNSCOPED, EXTERNAL_CONTROLLER }

object PlaybackQueuePolicies {
    fun catalogStatus(
        cursorReturned: Boolean,
        cursorExhausted: Boolean,
        queryFailed: Boolean,
        versionBefore: String?,
        versionAfter: String?
    ): CatalogStatus = when {
        queryFailed || !cursorReturned -> CatalogStatus.UNAVAILABLE
        !cursorExhausted -> CatalogStatus.INCOMPLETE
        versionBefore == null || versionBefore != versionAfter -> CatalogStatus.INCOMPLETE
        else -> CatalogStatus.CONFIRMED
    }

    fun contextForMutation(origin: QueueMutationOrigin, requestedType: String, requestedId: String): Pair<String, String> =
        if (origin != QueueMutationOrigin.INTERNAL_TRANSACTION) {
            PlaybackQueueRestorer.CONTEXT_CUSTOM to "Fila personalizada"
        } else requestedType to requestedId

    /** The old snapshot remains authoritative until the player operation succeeds. */
    fun snapshotAfterApply(
        previous: PersistedPlaybackQueue?,
        candidate: PersistedPlaybackQueue,
        applied: Boolean
    ): PersistedPlaybackQueue? = if (applied) candidate else previous

    fun shouldPersistPosition(
        isPlaying: Boolean,
        queueHasItems: Boolean,
        transactionPending: Boolean,
        restorationPending: Boolean,
        externalMutationPending: Boolean
    ): Boolean = isPlaying && queueHasItems && !transactionPending && !restorationPending && !externalMutationPending
}
