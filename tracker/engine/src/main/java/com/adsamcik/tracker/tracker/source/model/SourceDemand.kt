package com.adsamcik.tracker.tracker.source.model

data class SourceDemand(
	val source: SourceKind,
	val maximumAgeMs: Long,
	val desiredLatencyMs: Long,
	val quality: EvidenceQuality,
	val reason: DemandReason,
	val acquisitionFloor: SourceAcquisitionFloor? = null,
	val requestedDeliveryLatencyMs: Long? = null,
	val adaptiveReductionAllowed: Boolean = false,
) {
	init {
		require(maximumAgeMs >= 0L)
		require(desiredLatencyMs >= 0L)
		require(requestedDeliveryLatencyMs == null || requestedDeliveryLatencyMs >= 0L)
		require(reason == DemandReason.POLICY || acquisitionFloor != null) {
			"Direct source demands must declare a source-specific acquisition floor"
		}
		require(acquisitionFloor == null || acquisitionFloor.source == source) {
			"Demand acquisition floor must belong to the demanded source"
		}
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
