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

typealias TrackingSourceComponent =
	com.adsamcik.tracker.shared.model.tracking.TrackingSource
