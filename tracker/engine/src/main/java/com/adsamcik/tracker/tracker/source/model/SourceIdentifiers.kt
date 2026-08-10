package com.adsamcik.tracker.tracker.source.model

@JvmInline
value class SourceEventId(val value: String) {
	init {
		require(value.isNotBlank()) { "Source event ID must not be blank" }
	}
}

@JvmInline
value class SourceInstanceId(val value: String) {
	init {
		require(value.isNotBlank()) { "Source instance ID must not be blank" }
	}
}

@JvmInline
value class LogicalTrackingId(val value: String) {
	init {
		require(value.isNotBlank()) { "Logical tracking ID must not be blank" }
	}
}

@JvmInline
value class ServiceRunId(val value: String) {
	init {
		require(value.isNotBlank()) { "Service run ID must not be blank" }
	}
}

enum class SourceKind(val stableCode: Int) {
	LOCATION(1),
	ACTIVITY(2),
	STEPS(3),
	PRESSURE(4),
	WIFI(5),
	CELL(6),
}

