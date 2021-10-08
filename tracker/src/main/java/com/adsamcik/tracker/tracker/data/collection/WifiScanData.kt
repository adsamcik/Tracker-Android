package com.adsamcik.tracker.tracker.data.collection

import android.net.wifi.ScanResult

/**
 * Wi-Fi scan data
 */
data class WifiScanData(
		/**
		 * Time in milliseconds
		 */
		val timeMillis: Long,
		/**
		 * Time in nanoseconds since boot
		 */
		val relativeTimeNanos: Long,
		/**
		 * Scan result data
		 */
		val data: Array<ScanResult>
) {
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (javaClass != other?.javaClass) return false

		other as WifiScanData

		if (timeMillis != other.timeMillis) return false
		if (relativeTimeNanos != other.relativeTimeNanos) return false
		if (!data.contentEquals(other.data)) return false

		return true
	}

	override fun hashCode(): Int {
		var result = timeMillis.hashCode()
		result = 31 * result + relativeTimeNanos.hashCode()
		result = 31 * result + data.contentHashCode()
		return result
	}
}

