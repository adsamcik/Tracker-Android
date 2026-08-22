package com.adsamcik.tracker.shared.preferences.tracking

enum class SourceCollectionFrequency(val stableCode: Int) {
	OFF(0),
	BATTERY_SAVER(1),
	BALANCED(2),
	RESPONSIVE(3);

	companion object {
		fun fromStableCode(value: Int): SourceCollectionFrequency = entries
			.singleOrNull { it.stableCode == value }
			?: throw IllegalArgumentException("Unknown source collection frequency code $value")
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

enum class TrackingSourceComponent(val stableCode: Int) {
	LOCATION(1),
	ACTIVITY(2),
	STEPS(3),
	PRESSURE(4),
	WIFI(5),
	CELL(6);

	companion object {
		fun fromStableCode(value: Int): TrackingSourceComponent = entries.single { it.stableCode == value }
	}
}
