package com.adsamcik.tracker.tracker.time

import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/** A timestamp in Android's monotonic, sleep-aware elapsed-realtime clock domain. */
@JvmInline
internal value class ElapsedRealtimeNanos private constructor(val raw: Long) {
	/** Returns null when the values cannot belong to the same boot clock domain. */
	fun durationSince(earlier: ElapsedRealtimeNanos): Duration? = if (raw >= earlier.raw) {
		(raw - earlier.raw).nanoseconds
	} else {
		null
	}

	companion object {
		fun fromRaw(raw: Long?): ElapsedRealtimeNanos? =
			raw?.takeIf { it >= 0L }?.let(::ElapsedRealtimeNanos)
	}
}

internal fun elapsedRealtimeAge(
	nowElapsedRealtimeNanos: Long,
	observedElapsedRealtimeNanos: Long?,
): Duration? {
	val now = ElapsedRealtimeNanos.fromRaw(nowElapsedRealtimeNanos) ?: return null
	val observed = ElapsedRealtimeNanos.fromRaw(observedElapsedRealtimeNanos) ?: return null
	return now.durationSince(observed)
}
