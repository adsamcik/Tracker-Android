package com.adsamcik.tracker.geocoder.places

import android.content.Context
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads the bundled `places.geo` asset into a [PlacesDataset] once, lazily, off the
 * main thread. The asset (~5 MB) is parsed in place; failures are swallowed and
 * surfaced as `null` so the geocoder degrades gracefully (callers fall back to raw
 * coordinates) rather than crashing.
 */
@Singleton
class PlacesAssetReader @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dispatchers: DispatchersProvider,
) {

    private val mutex = Mutex()

    @Volatile
    private var loaded: PlacesDataset? = null

    @Volatile
    private var failed = false

    /** Returns the parsed dataset, loading it on first use. `null` if the asset is missing/corrupt. */
    suspend fun dataset(): PlacesDataset? {
        loaded?.let { return it }
        if (failed) return null
        return mutex.withLock {
            loaded?.let { return it }
            if (failed) return null
            val parsed = withContext(dispatchers.io) {
                runCatching {
                    context.assets.open(ASSET_NAME).use { it.readBytes() }
                }.mapCatching { PlacesDataset(it) }.getOrNull()
            }
            if (parsed == null) failed = true else loaded = parsed
            parsed
        }
    }

    private companion object {
        const val ASSET_NAME = "places.geo"
    }
}
