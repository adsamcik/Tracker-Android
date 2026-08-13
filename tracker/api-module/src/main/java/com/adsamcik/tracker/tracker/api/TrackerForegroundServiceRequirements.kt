package com.adsamcik.tracker.tracker.api

/**
 * Source categories that a tracker start command must cover when it promotes to the foreground.
 *
 * Signal sources are retained even when location or health is also active so a permission
 * transition can accurately fall back to the narrow signal-only special-use mode.
 */
data class TrackerForegroundServiceRequirements(
	val requiresLocation: Boolean,
	val requiresHealth: Boolean,
	val hasSignalSources: Boolean,
) {
	init {
		require(requiresLocation || requiresHealth || hasSignalSources) {
			"At least one foreground tracking source category is required"
		}
	}
}

/** Resolves the current, permission-aware source categories before service dispatch. */
interface TrackerForegroundServiceRequirementsProvider {
	fun current(
		isUserInitiated: Boolean,
		isAmbient: Boolean,
	): TrackerForegroundServiceRequirements?
}
