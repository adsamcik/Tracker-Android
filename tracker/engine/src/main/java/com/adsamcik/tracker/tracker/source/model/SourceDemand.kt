package com.adsamcik.tracker.tracker.source.model

data class SourceDemand(
	val source: SourceKind,
	val maximumAgeMs: Long,
	val desiredLatencyMs: Long,
	val quality: EvidenceQuality,
	val reason: DemandReason,
) {
	init {
		require(maximumAgeMs >= 0L)
		require(desiredLatencyMs >= 0L)
	}
}

enum class EvidenceQuality { ANY, EFFICIENT, BALANCED, HIGH }

enum class DemandReason {
	SESSION,
	AUTOMATIC_START,
	POLICY,
	LIVE_UI,
	PROJECTION,
	RECOVERY,
}

