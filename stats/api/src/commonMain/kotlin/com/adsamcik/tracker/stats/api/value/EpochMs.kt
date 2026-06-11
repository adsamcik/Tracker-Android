package com.adsamcik.tracker.stats.api.value

import kotlin.jvm.JvmInline

/** Epoch milliseconds timestamp. */
@JvmInline
value class EpochMs(val raw: Long) : Comparable<EpochMs> {
	init {
		require(raw >= 0L) { "Timestamp cannot be negative: $raw" }
	}

	override fun compareTo(other: EpochMs) = raw.compareTo(other.raw)
	operator fun minus(other: EpochMs) = raw - other.raw

	companion object {
		val ZERO = EpochMs(0L)
	}
}
