package com.adsamcik.tracker.shared.preferences

import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import kotlinx.coroutines.flow.first

/**
 * Utility object that provides methods for common preference operations.
 */
object PreferencesAssist {
	/**
	 * Checks if there is anything to track.
	 *
	 * @param trackingParamsRepository repository for tracking parameters
	 * @return true if at least one of location, cell and wifi tracking is enabled
	 */
	suspend fun hasAnythingToTrack(trackingParamsRepository: TrackingParamsRepository): Boolean {
		val params = trackingParamsRepository.data.first()
		return params.locationEnabled ||
				params.cellEnabled ||
				params.wifiLocationCountEnabled ||
				params.wifiNetworkEnabled
	}
}
