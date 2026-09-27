@file:Suppress("LongMethod", "TooManyFunctions")

package com.adsamcik.tracker.shared.base.database.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

const val PRESSURE_WAL_PAYLOAD_VERSION = 4
const val MAX_PRESSURE_WAL_PAYLOAD_BYTES = 256

enum class PressureWalClosureKind {
	TARGET_ELAPSED,
	SOURCE_BOUNDARY,
}

enum class PressureWalSensorAccuracy {
	UNKNOWN,
	UNRELIABLE,
	LOW,
	MEDIUM,
	HIGH,
}

@Suppress("LongParameterList")
data class PressureWalPayload(
	val sampleCount: Int,
	val meanHectopascals: Double,
	val sumSquaredDeviations: Double,
	val minimumHectopascals: Float,
	val maximumHectopascals: Float,
	val windowStartElapsedRealtimeNanos: Long,
	val windowEndElapsedRealtimeNanos: Long,
	val firstProviderSequence: Long,
	val lastProviderSequence: Long,
	val firstHectopascals: Float,
	val lastHectopascals: Float,
	val slopeHectopascalsPerSecond: Double?,
	val rSquared: Double?,
	val sensorAccuracy: PressureWalSensorAccuracy,
	val effectiveSamplePeriodMicros: Int,
	val effectiveMaximumReportLatencyMicros: Int,
	val targetWindowDurationNanos: Long,
	val expectedSampleCount: Int,
	val maximumInterSampleGapNanos: Long,
	val closureKind: PressureWalClosureKind,
) {
	val qualification: String
		get() {
			val samplePeriodNanos = effectiveSamplePeriodMicros.toLong() * NANOS_PER_MICROSECOND
			val requiredSpan = saturatedMultiply(expectedSampleCount.toLong() - 1L, samplePeriodNanos)
			val observedSpan = windowEndElapsedRealtimeNanos - windowStartElapsedRealtimeNanos
			val noMissingCadence = samplePeriodNanos > Long.MAX_VALUE / 2L ||
				maximumInterSampleGapNanos < samplePeriodNanos * 2L
			return if (
				closureKind == PressureWalClosureKind.TARGET_ELAPSED &&
				sampleCount >= expectedSampleCount &&
				observedSpan >= requiredSpan &&
				noMissingCadence
			) {
				PressureFactRevisionEntity.QUALIFICATION_COMPLETE
			} else {
				PressureFactRevisionEntity.QUALIFICATION_PARTIAL
			}
		}
}

object PressureWalPayloadCodec {
	fun encode(payload: PressureWalPayload): ByteArray {
		requireValid(payload)
		return ByteArrayOutputStream().use { buffer ->
			DataOutputStream(buffer).use { output ->
				output.writeInt(TYPE_PRESSURE_WINDOW)
				output.writeInt(payload.sampleCount)
				output.writeDouble(payload.meanHectopascals)
				output.writeDouble(payload.sumSquaredDeviations)
				output.writeFloat(payload.minimumHectopascals)
				output.writeFloat(payload.maximumHectopascals)
				output.writeLong(payload.windowStartElapsedRealtimeNanos)
				output.writeLong(payload.windowEndElapsedRealtimeNanos)
				output.writeLong(payload.firstProviderSequence)
				output.writeLong(payload.lastProviderSequence)
				output.writeFloat(payload.firstHectopascals)
				output.writeFloat(payload.lastHectopascals)
				output.writeNullableDouble(payload.slopeHectopascalsPerSecond)
				output.writeNullableDouble(payload.rSquared)
				output.writeInt(payload.sensorAccuracy.wireCode)
				output.writeInt(payload.effectiveSamplePeriodMicros)
				output.writeInt(payload.effectiveMaximumReportLatencyMicros)
				output.writeLong(payload.targetWindowDurationNanos)
				output.writeInt(payload.expectedSampleCount)
				output.writeLong(payload.maximumInterSampleGapNanos)
				output.writeInt(payload.closureKind.wireCode)
			}
			buffer.toByteArray()
		}
	}

	fun decodeCanonical(payloadVersion: Int, bytes: ByteArray): PressureWalPayload? =
		runCatching {
			require(payloadVersion == PRESSURE_WAL_PAYLOAD_VERSION)
			require(bytes.isNotEmpty() && bytes.size <= MAX_PRESSURE_WAL_PAYLOAD_BYTES)
			val decoded = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
				require(input.readInt() == TYPE_PRESSURE_WINDOW)
				PressureWalPayload(
					sampleCount = input.readInt(),
					meanHectopascals = input.readDouble(),
					sumSquaredDeviations = input.readDouble(),
					minimumHectopascals = input.readFloat(),
					maximumHectopascals = input.readFloat(),
					windowStartElapsedRealtimeNanos = input.readLong(),
					windowEndElapsedRealtimeNanos = input.readLong(),
					firstProviderSequence = input.readLong(),
					lastProviderSequence = input.readLong(),
					firstHectopascals = input.readFloat(),
					lastHectopascals = input.readFloat(),
					slopeHectopascalsPerSecond = input.readNullableDouble(),
					rSquared = input.readNullableDouble(),
					sensorAccuracy = input.readInt().let { wireCode ->
						PressureWalSensorAccuracy.entries.single { it.wireCode == wireCode }
					},
					effectiveSamplePeriodMicros = input.readInt(),
					effectiveMaximumReportLatencyMicros = input.readInt(),
					targetWindowDurationNanos = input.readLong(),
					expectedSampleCount = input.readInt(),
					maximumInterSampleGapNanos = input.readLong(),
					closureKind = input.readInt().let { wireCode ->
						PressureWalClosureKind.entries.single { it.wireCode == wireCode }
					},
				).also {
					require(input.available() == 0)
					requireValid(it)
				}
			}
			require(encode(decoded).contentEquals(bytes))
			decoded
		}.getOrNull()

	private fun requireValid(payload: PressureWalPayload) {
		require(payload.sampleCount > 0)
		require(payload.meanHectopascals.isFinite() && payload.meanHectopascals > 0.0)
		require(
			payload.sumSquaredDeviations.isFinite() &&
				payload.sumSquaredDeviations >= 0.0,
		)
		require(
			payload.minimumHectopascals.isFinite() &&
				payload.minimumHectopascals > 0f,
		)
		require(
			payload.maximumHectopascals.isFinite() &&
				payload.maximumHectopascals >= payload.minimumHectopascals,
		)
		require(
			payload.meanHectopascals in
				payload.minimumHectopascals.toDouble()..payload.maximumHectopascals.toDouble(),
		)
		require(
			payload.firstHectopascals.isFinite() &&
				payload.firstHectopascals in
				payload.minimumHectopascals..payload.maximumHectopascals,
		)
		require(
			payload.lastHectopascals.isFinite() &&
				payload.lastHectopascals in
				payload.minimumHectopascals..payload.maximumHectopascals,
		)
		require(payload.windowStartElapsedRealtimeNanos >= 0L)
		require(payload.windowEndElapsedRealtimeNanos >= payload.windowStartElapsedRealtimeNanos)
		require(payload.firstProviderSequence > 0L)
		require(payload.lastProviderSequence >= payload.firstProviderSequence)
		require(
			payload.lastProviderSequence - payload.firstProviderSequence ==
				payload.sampleCount.toLong() - 1L,
		)
		require(payload.effectiveSamplePeriodMicros > 0)
		require(payload.effectiveMaximumReportLatencyMicros >= 0)
		require(payload.targetWindowDurationNanos > 0L)
		require(
			payload.expectedSampleCount ==
				expectedSampleCount(
					payload.targetWindowDurationNanos,
					payload.effectiveSamplePeriodMicros,
				),
		)
		require(payload.maximumInterSampleGapNanos >= 0L)
		val observedSpan =
			payload.windowEndElapsedRealtimeNanos - payload.windowStartElapsedRealtimeNanos
		if (payload.sampleCount == 1) {
			require(payload.firstHectopascals.toRawBits() == payload.lastHectopascals.toRawBits())
			require(
				payload.firstHectopascals.toRawBits() ==
					payload.minimumHectopascals.toRawBits(),
			)
			require(
				payload.lastHectopascals.toRawBits() ==
					payload.maximumHectopascals.toRawBits(),
			)
			require(
				payload.meanHectopascals.toRawBits() ==
					payload.firstHectopascals.toDouble().toRawBits(),
			)
			require(payload.sumSquaredDeviations.toRawBits() == 0.0.toRawBits())
			require(observedSpan == 0L && payload.maximumInterSampleGapNanos == 0L)
			require(payload.slopeHectopascalsPerSecond == null && payload.rSquared == null)
		} else {
			require(
				observedSpan > 0L &&
					payload.maximumInterSampleGapNanos in 1L..observedSpan,
			)
			requireNotNull(payload.slopeHectopascalsPerSecond).also { require(it.isFinite()) }
			if (payload.sumSquaredDeviations > 0.0) {
				requireNotNull(payload.rSquared).also {
					require(it.isFinite() && it in 0.0..1.0)
				}
			} else {
				require(payload.rSquared == null)
			}
		}
	}
}

enum class PressureWalCarrierFailure {
	WRONG_SOURCE,
	UNSUPPORTED_PAYLOAD_VERSION,
	INTEGRITY_MISMATCH,
	INCOMPLETE_CAPTURE_AUTHORITY,
	MALFORMED_PROVIDER_ENVELOPE,
	PAYLOAD_INVALID,
	DELIVERY_IDENTITY_MISMATCH,
	QUALIFICATION_MISMATCH,
	FACT_LINKAGE_MISMATCH,
	FACT_SEMANTICS_MISMATCH,
	FACT_INTEGRITY_MISMATCH,
}

data class AuthenticatedPressureWalCarrier internal constructor(
	val payload: PressureWalPayload,
	val qualification: String,
)

sealed interface PressureWalCarrierAuthentication {
	data class Authenticated(
		val carrier: AuthenticatedPressureWalCarrier,
	) : PressureWalCarrierAuthentication

	data class Rejected(val reason: PressureWalCarrierFailure) : PressureWalCarrierAuthentication
}

object PressureWalCarrierAuthenticator {
	@Suppress("ComplexCondition", "ReturnCount")
	fun authenticate(wal: SourceEventWalEntity): PressureWalCarrierAuthentication {
		if (wal.sourceKind != SourceDestinationOwnerEntity.SOURCE_PRESSURE) {
			return rejected(PressureWalCarrierFailure.WRONG_SOURCE)
		}
		if (wal.payloadVersion != PRESSURE_WAL_PAYLOAD_VERSION) {
			return rejected(PressureWalCarrierFailure.UNSUPPORTED_PAYLOAD_VERSION)
		}
		if (!wal.hasExactQualifiedIntegrity(MAX_PRESSURE_WAL_PAYLOAD_BYTES)) {
			return rejected(PressureWalCarrierFailure.INTEGRITY_MISMATCH)
		}
		if (!wal.hasCompleteSessionCaptureBinding()) {
			return rejected(PressureWalCarrierFailure.INCOMPLETE_CAPTURE_AUTHORITY)
		}
		if (
			wal.providerDedupKey != null ||
			wal.activityAutomationEpoch != null ||
			wal.deliveryIdentity?.let(SOURCE_WAL_SHA_256::matches) != true ||
			wal.deliveryUnitIndex != 0 ||
			wal.deliveryUnitCount != 1 ||
			wal.sourceSequence < 0L ||
			wal.observedIntervalStartNanos?.let { it >= 0L } != true ||
			wal.observedElapsedNanos < requireNotNull(wal.observedIntervalStartNanos) ||
			wal.receivedElapsedNanos < wal.observedElapsedNanos ||
			wal.receivedWallTimeMs?.let { it < 0L } == true ||
			wal.wallTimeMs?.let { it >= 0L } != true ||
			wal.wallTimeUncertaintyMs?.let { it >= 0L } != true ||
			wal.acquiredAtMs != wal.wallTimeMs ||
			wal.createdAtMs < 0L ||
			!wal.hasCanonicalQuality()
		) {
			return rejected(PressureWalCarrierFailure.MALFORMED_PROVIDER_ENVELOPE)
		}
		val payload = PressureWalPayloadCodec.decodeCanonical(wal.payloadVersion, wal.payload)
			?: return rejected(PressureWalCarrierFailure.PAYLOAD_INVALID)
		if (
			payload.windowStartElapsedRealtimeNanos != wal.observedIntervalStartNanos ||
			payload.windowEndElapsedRealtimeNanos != wal.observedElapsedNanos
		) {
			return rejected(PressureWalCarrierFailure.MALFORMED_PROVIDER_ENVELOPE)
		}
		if (pressureDeliveryIdentity(wal.clockDomainId, payload) != wal.deliveryIdentity) {
			return rejected(PressureWalCarrierFailure.DELIVERY_IDENTITY_MISMATCH)
		}
		val incomplete =
			wal.qualityFlags and PRESSURE_INCOMPLETE_WINDOW_QUALITY_FLAG != 0L
		if (incomplete !=
			(payload.qualification == PressureFactRevisionEntity.QUALIFICATION_PARTIAL)
		) {
			return rejected(PressureWalCarrierFailure.QUALIFICATION_MISMATCH)
		}
		return PressureWalCarrierAuthentication.Authenticated(
			AuthenticatedPressureWalCarrier(payload, payload.qualification),
		)
	}

	fun authenticate(
		fact: PressureFactRevisionEntity,
		wal: SourceEventWalEntity,
		binding: SessionManifestSourceEntity,
	): PressureWalCarrierAuthentication {
		val authentication = authenticate(wal)
		if (authentication !is PressureWalCarrierAuthentication.Authenticated) {
			return authentication
		}
		if (
			fact.sourceEventId != wal.eventId ||
			fact.sourceAdmissionOrdinal != wal.admissionOrdinal ||
			fact.payloadVersion != wal.payloadVersion ||
			fact.logicalTrackingId != wal.logicalTrackingId ||
			fact.serviceRunId != wal.serviceRunId ||
			fact.sourcePolicyRevision != wal.sourcePolicyRevision ||
			fact.captureConsentEpoch != wal.captureConsentEpoch ||
			fact.manifestRevision != wal.sessionManifestRevision ||
			fact.collectedDataEpoch != wal.capturedCollectedDataEpoch ||
			fact.clockDomainId != wal.clockDomainId
		) {
			return rejected(PressureWalCarrierFailure.FACT_LINKAGE_MISMATCH)
		}
		if (!fact.matches(authentication.carrier, wal)) {
			return rejected(PressureWalCarrierFailure.FACT_SEMANTICS_MISMATCH)
		}
		if (!PressureFactRevisionIntegrity.hasValidEffectChecksum(fact, binding)) {
			return rejected(PressureWalCarrierFailure.FACT_INTEGRITY_MISMATCH)
		}
		return authentication
	}

	fun authenticates(
		fact: PressureFactRevisionEntity,
		wal: SourceEventWalEntity,
		binding: SessionManifestSourceEntity,
	): Boolean = authenticate(fact, wal, binding) is PressureWalCarrierAuthentication.Authenticated
}

@Suppress("ComplexCondition")
private fun PressureFactRevisionEntity.matches(
	carrier: AuthenticatedPressureWalCarrier,
	wal: SourceEventWalEntity,
): Boolean {
	val payload = carrier.payload
	val endTimeMs = wal.wallTimeMs ?: return false
	val uncertaintyMs = wal.wallTimeUncertaintyMs ?: return false
	val durationMs =
		(payload.windowEndElapsedRealtimeNanos - payload.windowStartElapsedRealtimeNanos) /
			NANOS_PER_MILLISECOND
	if (durationMs > endTimeMs) return false
	val startTimeMs = endTimeMs - durationMs
	val expectedLogicalFactId =
		"${SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID}:${wal.eventId}"
	return (semanticRevision > 1L || logicalFactId == expectedLogicalFactId) &&
		mutationId == "$logicalFactId:$semanticRevision" &&
		intervalStartTimeMs == startTimeMs &&
		intervalEndTimeMs == endTimeMs &&
		windowStartElapsedRealtimeNanos == payload.windowStartElapsedRealtimeNanos &&
		windowEndElapsedRealtimeNanos == payload.windowEndElapsedRealtimeNanos &&
		wallTimeUncertaintyMs == uncertaintyMs &&
		sampleCount == payload.sampleCount &&
		meanHectopascals.toRawBits() == payload.meanHectopascals.toRawBits() &&
		sumSquaredDeviations.toRawBits() == payload.sumSquaredDeviations.toRawBits() &&
		minimumHectopascals.toRawBits() == payload.minimumHectopascals.toRawBits() &&
		maximumHectopascals.toRawBits() == payload.maximumHectopascals.toRawBits() &&
		firstProviderSequence == payload.firstProviderSequence &&
		lastProviderSequence == payload.lastProviderSequence &&
		firstHectopascals.toRawBits() == payload.firstHectopascals.toRawBits() &&
		lastHectopascals.toRawBits() == payload.lastHectopascals.toRawBits() &&
		sameDouble(slopeHectopascalsPerSecond, payload.slopeHectopascalsPerSecond) &&
		sameDouble(rSquared, payload.rSquared) &&
		sensorAccuracy == payload.sensorAccuracy.name &&
		effectiveSamplePeriodMicros == payload.effectiveSamplePeriodMicros &&
		effectiveMaximumReportLatencyMicros ==
		payload.effectiveMaximumReportLatencyMicros &&
		targetWindowDurationNanos == payload.targetWindowDurationNanos &&
		expectedSampleCount == payload.expectedSampleCount &&
		maximumInterSampleGapNanos == payload.maximumInterSampleGapNanos &&
		closureKind == payload.closureKind.name &&
		qualification == carrier.qualification &&
		sourceQualityFlags == wal.qualityFlags &&
		sameFloat(sourceQualityConfidence, wal.qualityConfidence) &&
		purpose == PressureFactRevisionEntity.PURPOSE_SESSION_CAPTURE &&
		appliedAtMs == endTimeMs
}

private fun pressureDeliveryIdentity(
	clockDomainId: String,
	payload: PressureWalPayload,
): String {
	val canonical = buildString {
		append("pressure-window-v1|")
		append(clockDomainId.length)
		append(':')
		append(clockDomainId)
		append("|start:")
		append(payload.windowStartElapsedRealtimeNanos)
		append("|end:")
		append(payload.windowEndElapsedRealtimeNanos)
		append("|count:")
		append(payload.sampleCount)
		append("|mean-bits:")
		append(payload.meanHectopascals.toRawBits())
		append("|m2-bits:")
		append(payload.sumSquaredDeviations.toRawBits())
		append("|min-bits:")
		append(payload.minimumHectopascals.toRawBits())
		append("|max-bits:")
		append(payload.maximumHectopascals.toRawBits())
	}
	return MessageDigest.getInstance("SHA-256")
		.digest(canonical.toByteArray(Charsets.UTF_8))
		.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun DataOutputStream.writeNullableDouble(value: Double?) {
	writeBoolean(value != null)
	if (value != null) writeDouble(value)
}

private fun DataInputStream.readNullableDouble(): Double? =
	if (readBoolean()) readDouble() else null

private val PressureWalSensorAccuracy.wireCode: Int
	get() = ordinal + 1

private val PressureWalClosureKind.wireCode: Int
	get() = ordinal + 1

private fun expectedSampleCount(targetNanos: Long, samplePeriodMicros: Int): Int {
	val samplePeriodNanos = samplePeriodMicros.toLong() * NANOS_PER_MICROSECOND
	val wholePeriods = targetNanos / samplePeriodNanos
	val roundedUp = wholePeriods + if (targetNanos % samplePeriodNanos == 0L) 0L else 1L
	return roundedUp.coerceAtLeast(1L).also { require(it <= Int.MAX_VALUE) }.toInt()
}

private fun saturatedMultiply(first: Long, second: Long): Long =
	if (first == 0L || second <= Long.MAX_VALUE / first) first * second else Long.MAX_VALUE

private fun rejected(reason: PressureWalCarrierFailure) =
	PressureWalCarrierAuthentication.Rejected(reason)

private const val TYPE_PRESSURE_WINDOW = 5
private const val PRESSURE_INCOMPLETE_WINDOW_QUALITY_FLAG = 1L shl 6
private const val NANOS_PER_MICROSECOND = 1_000L
private const val NANOS_PER_MILLISECOND = 1_000_000L
