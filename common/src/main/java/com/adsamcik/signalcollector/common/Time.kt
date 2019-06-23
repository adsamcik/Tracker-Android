package com.adsamcik.signalcollector.common

/**
 * Centralized access to time.
 * This ensures that time is taken from a single source and is therefore comparable.
 */
object Time {
	val nowMillis: Long get() = System.currentTimeMillis()
}