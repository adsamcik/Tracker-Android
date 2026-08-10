package com.adsamcik.tracker.tracker.source.model

data class SourceQuality(
	val confidence: Float? = null,
	val flags: Set<SourceQualityFlag> = emptySet(),
) {
	init {
		require(confidence == null || confidence in 0f..1f) {
			"Source confidence must be between zero and one"
		}
	}
}

enum class SourceQualityFlag(val bit: Long) {
	CACHED(1L shl 0),
	BATCHED(1L shl 1),
	APPROXIMATE(1L shl 2),
	THROTTLED(1L shl 3),
	PERMISSION_DEGRADED(1L shl 4),
	PROVIDER_DEGRADED(1L shl 5),
	INCOMPLETE_WINDOW(1L shl 6),
	CLOCK_UNCERTAIN(1L shl 7),
	DUPLICATE_SUSPECTED(1L shl 8),
}

fun SourceQuality.toStableFlags(): Long = flags.fold(0L) { result, flag -> result or flag.bit }

fun sourceQualityFromStableFlags(
	flags: Long,
	confidence: Float? = null,
): SourceQuality = SourceQuality(
	confidence = confidence,
	flags = SourceQualityFlag.entries.filterTo(mutableSetOf()) { flag -> flags and flag.bit != 0L },
)

