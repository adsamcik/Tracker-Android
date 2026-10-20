package com.adsamcik.tracker.shared.base.database.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.ZoneId

const val LEGACY_CELL_WAL_PAYLOAD_VERSION = 5
const val CELL_WAL_PAYLOAD_VERSION = 6

/**
 * One original tower reading with available tower token, technology, signal and nullable provider
 * timestamp.
 */
data class CellWalPayloadObservation(
	val identifierToken: String,
	val radioType: String,
	val registered: Boolean,
	val signalLevelDbm: Int?,
	val providerTimestampNanos: Long?,
) {
	override fun toString(): String = "CellWalPayloadObservation"
}

/**
 * One original Cell delivery: tower readings, optional subscription grouping, refresh outcome and
 * observation zone.
 */
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

/**
 * Immutable Cell payload bytes together with their deterministic checksum.
 */
data class EncodedCellWalPayload internal constructor(
	val bytes: List<Byte>,
	val checksum: String,
) {
	/**
	 * Returns an independent byte-array copy of the encoded payload.
	 */
	fun toByteArray(): ByteArray = bytes.toByteArray()

	override fun toString(): String = "EncodedCellWalPayload"
}

/**
 * Supported-version, payload-bound and canonical-format decode failure causes.
 */
enum class CellWalPayloadDecodeFailure {
	UNSUPPORTED_PAYLOAD_VERSION,
	PAYLOAD_TOO_LARGE,
	MALFORMED_PAYLOAD,
	NON_CANONICAL_PAYLOAD,
}

/**
 * Decoded Cell payload or a specific format rejection; decoding alone grants no collection
 * permission.
 */
sealed interface CellWalPayloadDecodeResult {
	/**
	 * Supported canonical payload returned after bounded binary decoding.
	 */
	data class Decoded(val payload: CellWalPayload) : CellWalPayloadDecodeResult
	/**
	 * Specific rejected decode, carrier or per-purpose qualification outcome; it carries no accepted
	 * payload.
	 */
	data class Rejected(val reason: CellWalPayloadDecodeFailure) : CellWalPayloadDecodeResult
}

/**
 * Canonical codec for the production Cell WAL payload.
 *
 * This pure contract lives below both capture and product readers. It deliberately exposes copied
 * byte lists instead of the mutable Room payload array.
 */
object CellWalPayloadCodec {
	/**
	 * Serializes Cell payload values into the supported deterministic binary layout; nullable
	 * measurements keep their absence.
	 */
	fun encode(
		payload: CellWalPayload,
		payloadVersion: Int = CELL_WAL_PAYLOAD_VERSION,
	): EncodedCellWalPayload {
		require(payloadVersion in LEGACY_CELL_WAL_PAYLOAD_VERSION..CELL_WAL_PAYLOAD_VERSION)
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

	/**
	 * Decodes a bounded supported Cell payload and requires exact canonical bytes; malformed data is
	 * rejected through the declared result type.
	 */
	fun decodeCanonical(
		payloadVersion: Int,
		bytes: ByteArray,
	): CellWalPayloadDecodeResult {
		if (payloadVersion !in LEGACY_CELL_WAL_PAYLOAD_VERSION..CELL_WAL_PAYLOAD_VERSION) {
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
		return canonicalResult(payload, payloadVersion, bytes)
	}

	private fun canonicalResult(
		payload: CellWalPayload,
		payloadVersion: Int,
		bytes: ByteArray,
	): CellWalPayloadDecodeResult {
		val canonical = runCatching { encode(payload, payloadVersion).toByteArray() }.getOrElse {
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
	if (value != null) {
		writeInt(value)
	}
}

private fun DataInputStream.readNullableInt(): Int? = if (readBoolean()) {
	readInt()
} else {
	null
}

private fun DataOutputStream.writeNullableLong(value: Long?) {
	writeBoolean(value != null)
	if (value != null) {
		writeLong(value)
	}
}

private fun DataInputStream.readNullableLong(): Long? = if (readBoolean()) {
	readLong()
} else {
	null
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
	.digest(this)
	.toHexString()

private const val TYPE_CELL_SNAPSHOT = 8
const val MAX_CELL_WAL_PAYLOAD_BYTES = 65_536
const val MAX_CELL_WAL_CHILDREN = 256
const val CELL_REFRESH_OUTCOME_CALLBACK = 0
private val CELL_REFRESH_OUTCOME_CODES = 0..7
