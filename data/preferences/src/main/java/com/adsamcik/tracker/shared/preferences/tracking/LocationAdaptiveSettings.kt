package com.adsamcik.tracker.shared.preferences.tracking

/** Separate from historical SourceQos codes and from permission/provider availability. */
enum class LocationBackgroundMode(val stableCode: Int) {
	PASSIVE_ONLY(1),
	ADAPTIVE(2);

	/** Stable code decoding for persisted Location preferences. */
	companion object {
		/** Decodes a known persisted code, rejecting unsupported values. */
		fun fromStableCode(code: Int): LocationBackgroundMode =
			requireNotNull(entries.singleOrNull { it.stableCode == code }) {
				"Unknown Location background mode"
			}
	}
}

/** Validation and default values for the additive Location balance profile. */
object LocationAdaptiveSettings {
	const val PROFILE_VERSION = 1
	const val DEFAULT_BALANCE_LEVEL = 3
	val BALANCE_LEVELS = 1..5

	/** Accepts absent legacy profiles or a supported version paired with a level from 1 to 5. */
	fun requireValidProfile(version: Int?, level: Int?) {
		require((version == null) == (level == null)) { "Location profile and balance must be paired" }
		if (version != null) {
			require(version == PROFILE_VERSION) { "Unsupported Location profile version" }
			require(level != null && level in BALANCE_LEVELS) { "Location balance must be between 1 and 5" }
		}
	}
}
