package com.adsamcik.tracker.tracker.source.model

/** Frozen source-owned classification for qualified Pressure payload version 4 windows. */
enum class PressureWindowQualification {
	COMPLETE,
	PARTIAL,
	;

	/** Deterministic classifier shared by admission and dormant fact projection. */
	companion object {
		/** Classifies only the frozen qualified-v4 Pressure payload shape. */
		fun classify(payload: PressureWindowPayload): PressureWindowQualification {
			if (payload.closureKind != PressureWindowClosureKind.TARGET_ELAPSED) {
				return PARTIAL
			}
			val expectedCount = requireNotNull(payload.expectedSampleCount)
			require(expectedCount > 0)
			val samplePeriodNanos =
				requireNotNull(payload.effectiveSamplePeriodMicros).toLong() * NANOS_PER_MICROSECOND
			require(samplePeriodNanos > 0L)
			// The rollover trigger belongs to the next half-open source window.
			val requiredObservedSpanNanos =
				saturatedMultiply(expectedCount.toLong() - 1L, samplePeriodNanos)
			val observedSpanNanos = payload.windowEndElapsedRealtimeNanos -
				payload.windowStartElapsedRealtimeNanos
			require(observedSpanNanos >= 0L)
			// Two complete provider periods prove that at least one cadence slot is absent.
			val maximumGapNanos = requireNotNull(payload.maximumInterSampleGapNanos)
			require(maximumGapNanos >= 0L)
			val hasNoMissingCadenceSlot = samplePeriodNanos > Long.MAX_VALUE / 2L ||
				maximumGapNanos < 2L * samplePeriodNanos
			return if (payload.sampleCount >= expectedCount &&
				observedSpanNanos >= requiredObservedSpanNanos && hasNoMissingCadenceSlot
			) {
				COMPLETE
			} else {
				PARTIAL
			}
		}

		private const val NANOS_PER_MICROSECOND = 1_000L

		private fun saturatedMultiply(first: Long, second: Long): Long =
			if (first == 0L || second <= Long.MAX_VALUE / first) {
				first * second
			} else {
				Long.MAX_VALUE
			}
	}
}
