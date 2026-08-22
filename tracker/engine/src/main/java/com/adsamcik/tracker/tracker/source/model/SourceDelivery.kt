package com.adsamcik.tracker.tracker.source.model

import java.security.MessageDigest

/**
 * Stable, opaque identity supplied by a source adapter for one provider delivery.
 *
 * It is deliberately computed without process-local receipt time, source sequence, source
 * instance, broker registration generation, or authorization state. Those values describe how a
 * delivery reached this process, not which source-native delivery it is.
 */
@JvmInline
value class SourceDeliveryIdentity(val value: String) {
	init {
		require(SHA_256.matches(value)) { "Delivery identity must be a lowercase SHA-256 digest" }
	}

	companion object {
		private val SHA_256 = Regex("[0-9a-f]{64}")
	}
}

/**
 * Builds the persisted opaque identity from source-native canonical bytes only.
 *
 * SHA-256 is an identity mechanism, not anonymization. Callers must supply bytes that are already
 * safe to retain for the authorized purpose; radio adapters must not hash raw, guessable network or
 * subscription identifiers here as a substitute for purpose/epoch-keyed minimization.
 */
fun sourceDeliveryIdentity(canonicalSourceBytes: ByteArray): SourceDeliveryIdentity =
	SourceDeliveryIdentity(
		MessageDigest.getInstance("SHA-256")
			.digest(canonicalSourceBytes)
			.joinToString(separator = "") { byte -> "%02x".format(byte) },
	)

data class SourceDeliveryUnit(
	val unitIndex: Int,
	val evidence: SourceEvidenceCandidate<*>,
	val observedIntervalStartElapsedRealtimeNanos: Long = evidence.observedElapsedRealtimeNanos,
) {
	init {
		require(unitIndex >= 0) { "Delivery unit index must not be negative" }
		require(observedIntervalStartElapsedRealtimeNanos >= 0L) {
			"Observed interval start must not be negative"
		}
		require(observedIntervalStartElapsedRealtimeNanos <= evidence.observedElapsedRealtimeNanos) {
			"Observed interval cannot end before it starts"
		}
	}
}

/** One source-native callback/batch, split into the WAL units that must commit together. */
data class SourceDeliveryCandidate(
	val identity: SourceDeliveryIdentity,
	val units: List<SourceDeliveryUnit>,
) {
	init {
		require(units.isNotEmpty()) { "A source delivery must contain at least one unit" }
		require(units.map(SourceDeliveryUnit::unitIndex) == units.indices.toList()) {
			"Delivery unit indexes must be contiguous and start at zero"
		}
		val first = units.first().evidence
		require(units.all { unit -> unit.evidence.source == first.source }) {
			"A delivery cannot mix sources"
		}
		require(units.all { unit ->
			unit.evidence.capturedCollectedDataEpoch == first.capturedCollectedDataEpoch
		}) { "A delivery cannot mix collected-data epochs" }
		require(units.all { unit -> unit.evidence.clockDomainId == first.clockDomainId }) {
			"A delivery cannot mix clock domains"
		}
		require(units.all { unit -> unit.evidence.sourceInstanceId == first.sourceInstanceId }) {
			"A delivery cannot mix source instances"
		}
		require(units.all { unit ->
			unit.evidence.registrationGeneration == first.registrationGeneration
		}) { "A delivery cannot mix registration generations" }
		require(units.all { unit ->
			unit.evidence.physicalConfigurationFingerprint == first.physicalConfigurationFingerprint
		}) { "A delivery cannot mix physical configurations" }
	}

	val source: SourceKind get() = units.first().evidence.source
	val capturedCollectedDataEpoch: Long get() = units.first().evidence.capturedCollectedDataEpoch
	val clockDomainId: String get() = units.first().evidence.clockDomainId
}
