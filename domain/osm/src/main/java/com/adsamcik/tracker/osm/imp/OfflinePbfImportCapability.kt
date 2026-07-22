package com.adsamcik.tracker.osm.imp

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central release capability for user-selected offline `.osm.pbf` imports.
 *
 * The production injection path is deliberately fixed to [UNAVAILABLE]. This
 * is not a preference, remote-config value, or UI feature flag: both the
 * controller and the Worker consult this same capability before doing any
 * import work. Characterization tests may opt in explicitly, but that test
 * wiring is not reachable from a production app.
 */
@Singleton
class OfflinePbfImportCapability private constructor(
	val availability: Availability,
) {
	@Inject
	constructor() : this(Availability.UNAVAILABLE)

	val isAvailable: Boolean
		get() = availability == Availability.CHARACTERIZATION_ONLY

	/** The only states intentionally supported by the import capability. */
	enum class Availability {
		/** Safe default for every shipped artifact until safe intake is complete. */
		UNAVAILABLE,

		/** Explicit test-only wiring for parser characterization. */
		CHARACTERIZATION_ONLY,
	}

	internal companion object {
		/**
		 * Makes old-parser characterization possible in module tests only. It
		 * must never be added to a production dependency graph.
		 */
		fun forCharacterizationTests(): OfflinePbfImportCapability =
			OfflinePbfImportCapability(Availability.CHARACTERIZATION_ONLY)
	}
}
