package com.adsamcik.tracker.tracker.component.producer

internal data class CellFingerprintReading(
	val cellId: Long,
	val areaCode: Int,
	val mcc: String,
	val mnc: String,
	val networkType: Int,
	val signalStrengthAsu: Int,
)

internal object CellSnapshotGate {
	fun fingerprint(readings: List<CellFingerprintReading>): String = readings
		.sortedWith(compareBy(CellFingerprintReading::mcc, CellFingerprintReading::mnc, CellFingerprintReading::areaCode, CellFingerprintReading::cellId))
		.joinToString(separator = ";") { reading ->
			val strengthBucket = Math.floorDiv(reading.signalStrengthAsu, SIGNAL_BUCKET_ASU)
			reading.mcc + "|" + reading.mnc + "|" + reading.areaCode + "|" + reading.cellId + "|" +
				reading.networkType + "|" + strengthBucket
		}

	fun shouldRecordSnapshot(
		candidateFingerprint: String,
		previousFingerprint: String?,
		nowElapsedRealtimeMillis: Long,
		lastRecordedElapsedRealtimeMillis: Long,
		heartbeatMillis: Long,
	): Boolean {
		if (candidateFingerprint.isEmpty()) return false
		if (candidateFingerprint != previousFingerprint) return true
		if (lastRecordedElapsedRealtimeMillis < 0L) return true
		return nowElapsedRealtimeMillis - lastRecordedElapsedRealtimeMillis >= heartbeatMillis
	}

	private const val SIGNAL_BUCKET_ASU = 5
}
