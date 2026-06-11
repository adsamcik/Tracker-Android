package com.adsamcik.tracker.tracker.feed

import com.adsamcik.tracker.shared.base.mapper.toModel
import com.adsamcik.tracker.shared.model.Location
import com.adsamcik.tracker.stats.api.TrackerLiveLocationFeed
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [TrackerLiveLocationFeed] implementation that adapts the live state
 * exposed by [TrackerServiceController] into the read-only feed contract.
 *
 * Singleton so every consumer shares the same upstream StateFlow without
 * adding fan-out machinery; the underlying [TrackerServiceController.collectionDataFlow]
 * is already a StateFlow and broadcasts to all collectors.
 *
 * The implementation lives in `:tracker` (rather than `:stats-api`) because
 * the controller is a tracker-internal type. The `stats-api` interface is
 * the only thing other modules (`:game`, dashboard widgets, etc.) need to see.
 *
 * Locations are filtered for null in two places:
 *  1. The CollectionData wrapper may be null between sessions.
 *  2. CollectionData.location is itself nullable until the first fix arrives.
 *
 * distinctUntilChanged() guards against accidental duplicate emissions when a
 * downstream CollectionData update arrives that did not change the location
 * (for example, a refreshed activity sample with the same GPS fix attached).
 */
@Singleton
class DefaultTrackerLiveLocationFeed @Inject constructor(
	private val controller: TrackerServiceController,
) : TrackerLiveLocationFeed {

	override val isActiveFlow: StateFlow<Boolean>
		get() = controller.isServiceRunningFlow

	override val isActive: Boolean
		get() = controller.isServiceRunning

	override fun locations(): Flow<Location> = controller.collectionDataFlow
		.filterNotNull()
		.map { it.location }
		.filterNotNull()
		.map { it.toModel() }
		.distinctUntilChanged()
}
