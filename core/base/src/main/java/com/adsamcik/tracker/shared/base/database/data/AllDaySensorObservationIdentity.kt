package com.adsamcik.tracker.shared.base.database.data

/** Stable observation identities in the existing native tables; no session is implied. */
object AllDaySensorObservationIdentity {
	const val ACTIVITY_PREFIX = "all-day:activity:"
	const val PRESSURE_PREFIX = "all-day:pressure:"
	const val IMPORTED_CAPABILITY = "PORTABLE_IMPORT"
	const val UNKNOWN_ALTITUDE_CAPABILITY = "ALTITUDE_UNKNOWN"
	const val ACTIVITY_PROJECTION_ID = "all-day:sensor-observations:2"
	const val PRESSURE_PROJECTION_ID = "all-day:sensor-observations:4"
	const val PROJECTION_VERSION = 1

	/** Stable identity of an all-day Activity observation. */
	fun activity(eventId: String): String = identity(ACTIVITY_PREFIX, eventId)
	/** Stable identity of an all-day Pressure observation. */
	fun pressure(eventId: String): String = identity(PRESSURE_PREFIX, eventId)
	/** Returns the original event only for a native all-day Activity identity. */
	fun activityEventId(identity: String?): String? = eventId(identity, ACTIVITY_PREFIX)
	/** Returns the original event only for a native all-day Pressure identity. */
	fun pressureEventId(identity: String?): String? = eventId(identity, PRESSURE_PREFIX)
	/** Existing all-day writer name for the selected sensor source. */
	fun projectionId(sourceKind: Int): String = when (sourceKind) {
		SourceDestinationOwnerEntity.SOURCE_ACTIVITY -> ACTIVITY_PROJECTION_ID
		SourceDestinationOwnerEntity.SOURCE_PRESSURE -> PRESSURE_PROJECTION_ID
		else -> error("Not an all-day Activity or Pressure source: $sourceKind")
	}

	private fun identity(prefix: String, eventId: String): String {
		require(eventId.isNotBlank())
		return prefix + eventId
	}

	private fun eventId(identity: String?, prefix: String): String? =
		identity?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.takeIf(String::isNotBlank)
}
