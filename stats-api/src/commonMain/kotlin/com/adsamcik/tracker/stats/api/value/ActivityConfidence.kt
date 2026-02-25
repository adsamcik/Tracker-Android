package com.adsamcik.tracker.stats.api.value

import kotlin.jvm.JvmInline

/** Activity recognition confidence [0, 100]. */
@JvmInline
value class ActivityConfidence(val raw: Int) {
	init {
		require(raw in 0..100) { "Confidence must be 0-100: $raw" }
	}

	companion object {
		val ZERO = ActivityConfidence(0)
		val MAX = ActivityConfidence(100)
		fun coerced(raw: Int) = ActivityConfidence(raw.coerceIn(0, 100))
	}
}
