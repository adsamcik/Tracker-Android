package com.adsamcik.tracker.tracker.pipeline

import com.adsamcik.tracker.shared.base.data.MutableCollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle

/**
 * Accumulated context passed through pipeline stages.
 * Mutable by design — stages enrich it progressively.
 */
internal data class CycleContext(
	val cycle: TrackingCycle,
	val collectionData: MutableCollectionData,
	var session: TrackerSession? = null,
	var signal: TrackingSignal? = null,
	var shouldContinue: Boolean = true,
)
