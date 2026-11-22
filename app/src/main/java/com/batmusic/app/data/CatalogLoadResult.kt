package com.batmusic.app.data

/**
 * Trust attached to one MediaStore enumeration. CONFIRMED means that the returned cursor
 * was exhausted without error and MediaStore's available volume version fingerprint was
 * stable around the query. This confirms only the returned visible snapshot. Android exposes no
 * signal that background indexing of every device file is complete or that a missing row
 * represents a physical deletion.
 */
enum class CatalogStatus { CONFIRMED, INCOMPLETE, UNAVAILABLE }

data class CatalogLoadResult(
    val songs: List<Song>,
    val status: CatalogStatus,
    val mediaStoreVersion: String? = null,
    val error: Throwable? = null
)
