package com.adsamcik.tracker.shared.preferences.tracking

enum class SourceCollectionFrequency(val stableCode: Int) {
	OFF(0),
	BATTERY_SAVER(1),
	BALANCED(2),
	RESPONSIVE(3);

	companion object {
		fun fromStableCode(value: Int): SourceCollectionFrequency = entries
			.firstOrNull { it.stableCode == value }
			?: BALANCED
	}
}

data class SourceCollectionSettings(
	val location: SourceCollectionFrequency = SourceCollectionFrequency.BALANCED,
	val activity: SourceCollectionFrequency = SourceCollectionFrequency.BALANCED,
	val steps: SourceCollectionFrequency = SourceCollectionFrequency.BALANCED,
	val pressure: SourceCollectionFrequency = SourceCollectionFrequency.BALANCED,
	val wifi: SourceCollectionFrequency = SourceCollectionFrequency.OFF,
	val cell: SourceCollectionFrequency = SourceCollectionFrequency.OFF,
)

enum class TrackingSourceComponent { LOCATION, ACTIVITY, STEPS, PRESSURE, WIFI, CELL }
