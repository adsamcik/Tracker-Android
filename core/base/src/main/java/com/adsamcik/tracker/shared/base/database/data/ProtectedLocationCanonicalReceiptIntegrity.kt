package com.adsamcik.tracker.shared.base.database.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

class ProtectedLocationCanonicalReceiptPayload(
	val sourceEventId: String,
	val sourceAdmissionOrdinal: Long,
	val walIntegrityIdentity: String,
	val acquisitionMode: String,
	val requestPriority: String,
	val requiredAccuracyMeters: Float,
	val policyTier: String,
	val policyName: String,
	val curationVersion: Int,
	val altitudeModelVersion: Int,
	val altitudeEstimatorVersion: Int,
	val altitudeCalibrationVersion: Int,
	val observation: ByteArray,
	val decision: ByteArray,
	val sample: ByteArray?,
	val curationState: ByteArray,
)

object ProtectedLocationCanonicalReceiptCodec {
	const val VERSION = 4
	private const val MAGIC = 0x504c4352
	private const val MAX_RECEIPT_PART_BYTES = 256 * 1024

	fun encode(value: ProtectedLocationCanonicalReceiptPayload): ByteArray =
		ByteArrayOutputStream().use { bytes ->
			DataOutputStream(bytes).use { output ->
				output.writeInt(MAGIC)
				output.writeInt(VERSION)
				output.writeUTF(value.sourceEventId)
				output.writeLong(value.sourceAdmissionOrdinal)
				output.writeUTF(value.walIntegrityIdentity)
				output.writeUTF(value.acquisitionMode)
				output.writeUTF(value.requestPriority)
				output.writeInt(java.lang.Float.floatToRawIntBits(value.requiredAccuracyMeters))
				output.writeUTF(value.policyTier)
				output.writeUTF(value.policyName)
				output.writeInt(value.curationVersion)
				output.writeInt(value.altitudeModelVersion)
				output.writeInt(value.altitudeEstimatorVersion)
				output.writeInt(value.altitudeCalibrationVersion)
				output.writeByteArray(value.observation)
				output.writeByteArray(value.decision)
				output.writeNullableByteArray(value.sample)
				output.writeByteArray(value.curationState)
			}
			bytes.toByteArray().withSha256Trailer()
		}

	fun decode(payload: ByteArray): ProtectedLocationCanonicalReceiptPayload =
		DataInputStream(ByteArrayInputStream(payload.verifiedSha256Body())).use { input ->
			require(input.readInt() == MAGIC)
			require(input.readInt() == VERSION)
			val decoded = ProtectedLocationCanonicalReceiptPayload(
				sourceEventId = input.readUTF(),
				sourceAdmissionOrdinal = input.readLong(),
				walIntegrityIdentity = input.readUTF(),
				acquisitionMode = input.readUTF(),
				requestPriority = input.readUTF(),
				requiredAccuracyMeters = java.lang.Float.intBitsToFloat(input.readInt()),
				policyTier = input.readUTF(),
				policyName = input.readUTF(),
				curationVersion = input.readInt(),
				altitudeModelVersion = input.readInt(),
				altitudeEstimatorVersion = input.readInt(),
				altitudeCalibrationVersion = input.readInt(),
				observation = input.readByteArray(),
				decision = input.readByteArray(),
				sample = input.readNullableByteArray(),
				curationState = input.readByteArray(),
			)
			require(input.available() == 0)
			decoded
		}

	private fun DataOutputStream.writeByteArray(value: ByteArray) {
		writeInt(value.size)
		write(value)
	}

	private fun DataOutputStream.writeNullableByteArray(value: ByteArray?) {
		writeBoolean(value != null)
		if (value != null) writeByteArray(value)
	}

	private fun DataInputStream.readByteArray(): ByteArray {
		val size = readInt()
		require(size in 0..MAX_RECEIPT_PART_BYTES)
		return ByteArray(size).also(::readFully)
	}

	private fun DataInputStream.readNullableByteArray(): ByteArray? =
		if (readBoolean()) readByteArray() else null
}

fun protectedLocationCanonicalProductIdentity(sourceEventId: String): String =
	"protected-location-canonical:$sourceEventId"

fun protectedLocationCanonicalReceiptStateKey(sourceEventId: String): String =
	"canonical-receipt:$sourceEventId"

fun protectedLocationPreparedCurationStateKey(sourceEventId: String): String =
	"prepared-curation:$sourceEventId"

fun protectedLocationRunCurationStateKey(
	logicalTrackingId: String,
	serviceRunId: String,
): String = "curation-state:$logicalTrackingId:$serviceRunId"

fun SourceProjectionJoinStateEntity.authenticatedProtectedLocationCanonicalCurationState(
	sourceEventId: String,
	sourceAdmissionOrdinal: Long,
	walIntegrityIdentity: String,
	observation: LocationObservation,
	decision: LocationObservationDecision,
	sample: LocationSample?,
): ByteArray? = runCatching {
	if (payloadVersion != ProtectedLocationCanonicalReceiptCodec.VERSION ||
		minimumRequiredOrdinal !in setOf(sourceAdmissionOrdinal, Long.MAX_VALUE)
	) return@runCatching null
	val decoded = ProtectedLocationCanonicalReceiptCodec.decode(payload)
	decoded.curationState.takeIf {
		decoded.sourceEventId == sourceEventId &&
			decoded.sourceAdmissionOrdinal == sourceAdmissionOrdinal &&
			decoded.walIntegrityIdentity == walIntegrityIdentity &&
			decoded.observation.contentEquals(
				observation.protectedLocationCanonicalReceiptBytes(),
			) &&
			decoded.decision.contentEquals(
				decision.protectedLocationCanonicalReceiptBytes(),
			) &&
			decoded.sample.contentEqualsNullable(
				sample?.protectedLocationCanonicalReceiptBytes(),
			)
	}
}.getOrNull()

fun LocationObservation.protectedLocationCanonicalReceiptBytes(): ByteArray =
	canonicalLocationReceiptBytes {
		writeLong(id)
		writeLong(fixTimeMs)
		writeLong(fixElapsedRealtimeNanos)
		writeLong(receivedAtMs)
		writeLong(receivedElapsedRealtimeNanos)
		writeNullableLong(deliveryAgeMs)
		writeNullableInt(latE7)
		writeNullableInt(lonE7)
		writeNullableFloat(rawAltitudeM)
		writeNullableFloat(hAccM)
		writeNullableFloat(vAccM)
		writeNullableFloat(speedMps)
		writeNullableFloat(speedAccuracyMps)
		writeUTF(provider)
		writeUTF(acquisitionMode)
		writeUTF(requestPriority)
		writeUTF(permissionPrecision)
		writeInt(batchIndex)
		writeInt(batchSize)
		writeBoolean(isMock)
		writeUTF(ingressDisposition)
		writeInt(estimatorVersion)
		writeInt(calibrationVersion)
		writeLong(createdAt)
		writeNullableString(sourceSignalId)
		writeNullableString(sourceEventId)
		writeNullableString(callbackId)
		writeNullableString(clockDomainId)
		writeLong(sourceRevision)
		writeNullableFloat(bearingDeg)
		writeNullableFloat(bearingAccuracyDeg)
		writeNullableString(bootClockDomainId)
	}

fun LocationObservationDecision.protectedLocationCanonicalReceiptBytes(): ByteArray =
	canonicalLocationReceiptBytes {
		writeLong(id)
		writeUTF(observationSourceEventId)
		writeUTF(decision)
		writeNullableString(reason)
		writeInt(decisionVersion)
		writeNullableString(writerProjectionId)
		writeNullableInt(writerProjectionVersion)
		writeNullableLong(writerBindingGeneration)
		writeNullableLong(writerOwnerGeneration)
		writeNullableString(acceptedSampleSourceSignalId)
		writeUTF(sourceSignalId)
		writeNullableString(clockDomainId)
		writeLong(decidedAtMs)
		writeLong(sourceRevision)
	}

fun LocationSample.protectedLocationCanonicalReceiptBytes(): ByteArray =
	canonicalLocationReceiptBytes {
		writeLong(id)
		writeLong(timeMs)
		writeLong(elapsedRealtimeNanos)
		writeNullableInt(latE7)
		writeNullableInt(lonE7)
		writeNullableFloat(altitudeM)
		writeNullableFloat(rawGpsAltitudeM)
		writeNullableFloat(hAccM)
		writeNullableFloat(vAccM)
		writeNullableFloat(speedMps)
		writeNullableFloat(speedAccuracyMps)
		writeUTF(provider)
		writeUTF(quality.name)
		writeNullableString(motionState?.name)
		writeNullableString(policy)
		writeNullableLong(bucketId)
		writeLong(createdAt)
		writeLong(receivedElapsedRealtimeNanos)
		writeNullableLong(deliveryAgeMs)
		writeUTF(acquisitionMode)
		writeUTF(requestPriority)
		writeUTF(permissionPrecision)
		writeInt(batchIndex)
		writeInt(batchSize)
		writeBoolean(isMock)
		writeInt(estimatorVersion)
		writeInt(calibrationVersion)
		writeNullableString(sourceSignalId)
		writeNullableString(sourceEventId)
		writeNullableString(clockDomainId)
		writeLong(sourceRevision)
		writeUTF(altitudeDatum.name)
		writeUTF(altitudeSource.name)
		writeUTF(altitudeConversionStatus.name)
		writeUTF(rawGpsAltitudeDatum.name)
		writeInt(altitudeModelVersion)
		writeNullableFloat(rawPlatformSpeedMps)
		writeNullableFloat(rawPlatformSpeedAccuracyMps)
		writeNullableFloat(bearingDeg)
		writeNullableFloat(bearingAccuracyDeg)
		writeNullableString(bootClockDomainId)
	}

private inline fun canonicalLocationReceiptBytes(
	write: DataOutputStream.() -> Unit,
): ByteArray = ByteArrayOutputStream().use { bytes ->
	DataOutputStream(bytes).use { output -> output.write() }
	bytes.toByteArray()
}

private fun DataOutputStream.writeNullableString(value: String?) {
	writeBoolean(value != null)
	if (value != null) writeUTF(value)
}

private fun DataOutputStream.writeNullableLong(value: Long?) {
	writeBoolean(value != null)
	if (value != null) writeLong(value)
}

private fun DataOutputStream.writeNullableInt(value: Int?) {
	writeBoolean(value != null)
	if (value != null) writeInt(value)
}

private fun DataOutputStream.writeNullableFloat(value: Float?) {
	writeBoolean(value != null)
	if (value != null) writeInt(java.lang.Float.floatToRawIntBits(value))
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean = when {
	this == null -> other == null
	other == null -> false
	else -> contentEquals(other)
}

private fun ByteArray.withSha256Trailer(): ByteArray =
	this + MessageDigest.getInstance("SHA-256").digest(this)

private fun ByteArray.verifiedSha256Body(): ByteArray {
	require(size >= SHA_256_BYTES)
	val body = copyOfRange(0, size - SHA_256_BYTES)
	val expected = copyOfRange(size - SHA_256_BYTES, size)
	require(MessageDigest.getInstance("SHA-256").digest(body).contentEquals(expected))
	return body
}

private const val SHA_256_BYTES = 32
