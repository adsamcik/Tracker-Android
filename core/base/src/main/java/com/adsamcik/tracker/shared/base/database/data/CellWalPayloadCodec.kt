package com.adsamcik.tracker.shared.base.database.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.ZoneId

const val CELL_WAL_PAYLOAD_VERSION = 5

data class CellWalPayloadObservation(
	val identifierToken: String,
	val radioType: String,
	val registered: Boolean,
	val signalLevelDbm: Int?,
	val providerTimestampNanos: Long?,
)

data class CellWalPayload(
	val subscriptionId: Int?,
	val observations: List<CellWalPayloadObservation>,
	val refreshOutcomeCode: Int,
	val observationZoneId: String,
) {
	init {
		require(refreshOutcomeCode in CELL_REFRESH_OUTCOME_CODES)
		require(observationZoneId.isNotBlank())
		require(runCatching { ZoneId.of(observationZoneId) }.isSuccess)
	}
}

data class EncodedCellWalPayload internal constructor(
	val bytes: List<Byte>,
	val checksum: String,
) {
	fun toByteArray(): ByteArray = bytes.toByteArray()
}

enum class CellWalPayloadDecodeFailure {
	UNSUPPORTED_PAYLOAD_VERSION,
	PAYLOAD_TOO_LARGE,
	MALFORMED_PAYLOAD,
	NON_CANONICAL_PAYLOAD,
}

sealed interface CellWalPayloadDecodeResult {
	data class Decoded(val payload: CellWalPayload) : CellWalPayloadDecodeResult
	data class Rejected(val reason: CellWalPayloadDecodeFailure) : CellWalPayloadDecodeResult
}

/**
 * Canonical codec for the production Cell WAL payload.
 *
 * This pure contract lives below both capture and product readers. It deliberately exposes copied
 * byte lists instead of the mutable Room payload array.
 */
object CellWalPayloadCodec {
	fun encode(payload: CellWalPayload): EncodedCellWalPayload {
		require(payload.observations.size <= MAX_CELL_WAL_CHILDREN)
		val bytes = ByteArrayOutputStream().use { buffer ->
			DataOutputStream(buffer).use { output ->
				output.writeInt(TYPE_CELL_SNAPSHOT)
				output.writeNullableInt(payload.subscriptionId)
				output.writeInt(payload.observations.size)
				payload.observations.forEach { observation ->
					output.writeUTF(observation.identifierToken)
					output.writeUTF(observation.radioType)
					output.writeBoolean(observation.registered)
					output.writeNullableInt(observation.signalLevelDbm)
					output.writeNullableLong(observation.providerTimestampNanos)
				}
				output.writeInt(payload.refreshOutcomeCode)
				output.writeUTF(payload.observationZoneId)
			}
			buffer.toByteArray()
		}
		require(bytes.size <= MAX_CELL_WAL_PAYLOAD_BYTES)
		return EncodedCellWalPayload(bytes.toList(), bytes.sha256())
	}

	fun decodeCanonical(
		payloadVersion: Int,
		bytes: ByteArray,
	): CellWalPayloadDecodeResult {
		if (payloadVersion != CELL_WAL_PAYLOAD_VERSION) {
			return CellWalPayloadDecodeResult.Rejected(
				CellWalPayloadDecodeFailure.UNSUPPORTED_PAYLOAD_VERSION,
			)
		}
		if (bytes.size > MAX_CELL_WAL_PAYLOAD_BYTES) {
			return CellWalPayloadDecodeResult.Rejected(CellWalPayloadDecodeFailure.PAYLOAD_TOO_LARGE)
		}
		val payload = runCatching {
			DataInputStream(ByteArrayInputStream(bytes)).use { input ->
				require(input.readInt() == TYPE_CELL_SNAPSHOT)
				val subscriptionId = input.readNullableInt()
				val count = input.readInt()
				require(count in 0..MAX_CELL_WAL_CHILDREN)
				val observations = List(count) {
					CellWalPayloadObservation(
						identifierToken = input.readUTF(),
						radioType = input.readUTF(),
						registered = input.readBoolean(),
						signalLevelDbm = input.readNullableInt(),
						providerTimestampNanos = input.readNullableLong(),
					)
				}
				val refreshOutcomeCode = input.readInt()
				require(refreshOutcomeCode in CELL_REFRESH_OUTCOME_CODES)
				val zoneId = input.readUTF()
				require(input.available() == 0)
				CellWalPayload(
					subscriptionId = subscriptionId,
					observations = observations,
					refreshOutcomeCode = refreshOutcomeCode,
					observationZoneId = zoneId,
				)
			}
		}.getOrElse {
			return CellWalPayloadDecodeResult.Rejected(
				CellWalPayloadDecodeFailure.MALFORMED_PAYLOAD,
			)
		}
		val canonical = runCatching { encode(payload).toByteArray() }.getOrElse {
			return CellWalPayloadDecodeResult.Rejected(
				CellWalPayloadDecodeFailure.MALFORMED_PAYLOAD,
			)
		}
		if (!canonical.contentEquals(bytes)) {
			return CellWalPayloadDecodeResult.Rejected(
				CellWalPayloadDecodeFailure.NON_CANONICAL_PAYLOAD,
			)
		}
		return CellWalPayloadDecodeResult.Decoded(payload)
	}
}

private fun DataOutputStream.writeNullableInt(value: Int?) {
	writeBoolean(value != null)
	if (value != null) writeInt(value)
}

private fun DataInputStream.readNullableInt(): Int? = if (readBoolean()) readInt() else null

private fun DataOutputStream.writeNullableLong(value: Long?) {
	writeBoolean(value != null)
	if (value != null) writeLong(value)
}

private fun DataInputStream.readNullableLong(): Long? = if (readBoolean()) readLong() else null

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
	.digest(this)
	.joinToString(separator = "") { byte -> "%02x".format(byte) }

private const val TYPE_CELL_SNAPSHOT = 8
private const val MAX_CELL_WAL_PAYLOAD_BYTES = 65_536
internal const val MAX_CELL_WAL_CHILDREN = 256
const val CELL_REFRESH_OUTCOME_CALLBACK = 0
private val CELL_REFRESH_OUTCOME_CODES = 0..7
