package com.adsamcik.tracker.activity.receiver

import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.activity.api.ingress.ActivityIngressStartContext
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEvidence
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEvidenceBatch
import com.adsamcik.tracker.activity.api.ingress.ActivityTransitionEvidence
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationIdentity
import com.adsamcik.tracker.stats.api.DetectedActivityType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Bounded callback serialization; the store owns files, budgets and freshness. */
internal object ActivityCallbackRetryCodec {
	const val MAX_EVENTS_PER_CALLBACK = 1_024
	private const val MAX_AUTOMATIC_TRANSITIONS = 64
	private const val RECORD_MAGIC = 0x41435248 // ACRH
	private const val RECORD_VERSION_V2 = 2
	private const val RECORD_VERSION_V3 = 3

	fun encodeRetryRecord(id: String, payload: ByteArray,
		metadata: ActivityCallbackRetryMetadata): ByteArray = ByteArrayOutputStream().let { bytes ->
		DataOutputStream(bytes).use { output ->
			output.writeInt(RECORD_MAGIC)
			output.writeInt(RECORD_VERSION_V3)
			ActivityCallbackRetryMetadataCodec.write(output, id, metadata)
			output.write(payload)
		}
		bytes.toByteArray()
	}

	fun encodeV2(batch: ActivityRecognitionEvidenceBatch): ByteArray {
		val bytes = ByteArrayOutputStream()
		DataOutputStream(bytes).use { output ->
			output.writeInt(RECORD_MAGIC)
			output.writeInt(RECORD_VERSION_V2)
			output.writeLong(batch.receivedElapsedRealtimeNanos)
			output.writeLong(batch.receivedWallTimeMs)
			output.writeInt(ActivityCallbackRetryWire.startContextWireCode(batch.startContext))
			val identity = requireNotNull(batch.registrationIdentity)
			ActivityCallbackRetryWire.writeBoundedUtf(output, identity.sourceInstanceId)
			output.writeLong(identity.registrationGeneration)
			output.writeLong(identity.collectedDataEpoch)
			ActivityCallbackRetryWire.writeBoundedUtf(output, identity.clockDomainId)
			ActivityCallbackRetryWire.writeBoundedUtf(output, identity.physicalConfigurationFingerprint)
			output.writeBoolean(batch.automaticRecognitionEligible)
			val automaticTransitions = batch.automaticTransitions.sortedWith(
				compareBy<ActivityTransitionData> { ActivityCallbackRetryWire.activityTypeWireCode(it.activity) }
					.thenBy { it.type.value },
			)
			require(automaticTransitions.size <= MAX_AUTOMATIC_TRANSITIONS)
			output.writeInt(automaticTransitions.size)
			automaticTransitions.forEach { transition ->
				output.writeInt(ActivityCallbackRetryWire.activityTypeWireCode(transition.activity))
				output.writeInt(transition.type.value)
			}
			output.writeInt(batch.recognitions.size)
			batch.recognitions.forEach { evidence ->
				output.writeInt(ActivityCallbackRetryWire.activityTypeWireCode(evidence.activityType))
				output.writeInt(evidence.confidencePercent)
				output.writeLong(evidence.providerElapsedRealtimeNanos)
			}
			output.writeInt(batch.transitions.size)
			batch.transitions.forEach { evidence ->
				output.writeInt(ActivityCallbackRetryWire.activityTypeWireCode(evidence.activityType))
				output.writeInt(evidence.transitionType.value)
				output.writeLong(evidence.providerElapsedRealtimeNanos)
			}
		}
		return bytes.toByteArray()
	}

	fun decodeRetryRecord(id: String, encoded: ByteArray): ActivityRecognitionEvidenceBatch {
		var metadata: ActivityCallbackRetryMetadata? = null
		return try {
			val payload = DataInputStream(ByteArrayInputStream(encoded)).use { input ->
				val magic = input.readInt()
				val version = input.readInt()
				if (magic == RECORD_MAGIC && version == RECORD_VERSION_V3) {
					metadata = ActivityCallbackRetryMetadataCodec.read(input, id)
					input.readBytes()
				} else {
					encoded // Released V2 records retain their original content identity.
				}
			}
			if (callbackId(payload) != id) {
				throw ActivityCallbackRetryStoreException(
					"Activity callback retry content identity does not match its file",
					code = ActivityCallbackGapCode.RETRY_RECORD_CORRUPT,
				)
			}
			decodeV2Record(payload).also { decoded ->
				val decodedMetadata = ActivityCallbackRetryMetadata.from(decoded)
				check(metadata == null || metadata == decodedMetadata) { "Activity callback scope does not match payload" }
				metadata = decodedMetadata
			}
		} catch (error: IOException) {
			decodingFailure(error, metadata)
		} catch (error: IllegalArgumentException) {
			decodingFailure(error, metadata)
		} catch (error: IllegalStateException) {
			decodingFailure(error, metadata)
		}
	}

	private fun decodingFailure(error: Exception, metadata: ActivityCallbackRetryMetadata?): Nothing =
		throw ActivityCallbackRetryStoreException(
			"Unable to decode Activity callback retry", error,
			(error as? ActivityCallbackRetryStoreException)?.code ?: ActivityCallbackGapCode.RETRY_RECORD_CORRUPT,
			metadata,
		)

	private fun decodeV2Record(encoded: ByteArray): ActivityRecognitionEvidenceBatch =
		DataInputStream(ByteArrayInputStream(encoded)).use { input ->
			if (input.readInt() != RECORD_MAGIC) {
				throw ActivityCallbackRetryStoreException(
					"Invalid Activity callback retry header",
					code = ActivityCallbackGapCode.RETRY_RECORD_CORRUPT,
				)
			}
			val version = input.readInt()
			val batch = when (version) {
				RECORD_VERSION_V2 -> decodePayload(input)
				else -> throw ActivityCallbackRetryStoreException(
					"Unsupported Activity callback retry version $version",
					code = ActivityCallbackGapCode.RETRY_RECORD_UNSUPPORTED_VERSION,
				)
			}
			if (input.read() != -1) {
				error("Trailing Activity callback retry bytes")
			}
			batch
		}

	private fun decodePayload(
		input: DataInputStream,
	): ActivityRecognitionEvidenceBatch {
		val receivedElapsed = input.readLong()
		val receivedWall = input.readLong()
		val startContext = ActivityCallbackRetryWire.readStartContextV2(input)
		val identity = ActivityRegistrationIdentity(
			sourceInstanceId = ActivityCallbackRetryWire.readBoundedUtf(input),
			registrationGeneration = input.readLong(),
			collectedDataEpoch = input.readLong(),
			clockDomainId = ActivityCallbackRetryWire.readBoundedUtf(input),
			physicalConfigurationFingerprint = ActivityCallbackRetryWire.readBoundedUtf(input),
		)
		val automaticRecognitionEligible = input.readBoolean()
		val automaticTransitions = ActivityCallbackRetryWire.readCount(input, MAX_AUTOMATIC_TRANSITIONS) {
			ActivityTransitionData(ActivityCallbackRetryWire.readActivityTypeV2(input),
				ActivityCallbackRetryWire.readTransitionType(input))
		}.toSet()
		val recognitions = ActivityCallbackRetryWire.readCount(input, MAX_EVENTS_PER_CALLBACK) {
			ActivityRecognitionEvidence(
				ActivityCallbackRetryWire.readActivityTypeV2(input),
				input.readInt(),
				input.readLong(),
			)
		}
		val remainingEvents = MAX_EVENTS_PER_CALLBACK - recognitions.size
		val transitions = ActivityCallbackRetryWire.readCount(input, remainingEvents) {
			ActivityTransitionEvidence(
				ActivityCallbackRetryWire.readActivityTypeV2(input),
				ActivityCallbackRetryWire.readTransitionType(input),
				input.readLong(),
			)
		}
		if (recognitions.isEmpty() && transitions.isEmpty()) {
			error("Empty Activity callback retry")
		}
		return ActivityRecognitionEvidenceBatch(
			receivedElapsedRealtimeNanos = receivedElapsed,
			receivedWallTimeMs = receivedWall,
			registrationIdentity = identity,
			startContext = startContext,
			automaticRecognitionEligible = automaticRecognitionEligible,
			automaticTransitions = automaticTransitions,
			recognitions = recognitions,
			transitions = transitions,
		)
	}

	fun callbackId(encoded: ByteArray): String = MessageDigest.getInstance("SHA-256")
		.digest(encoded)
		.joinToString("") { byte -> "%02x".format(byte) }

}

/** V1 receipts remain readable; new receipts retain independently checked original scope. */
internal object ActivityCallbackGapReceiptCodec {
	private const val GAP_MAGIC = 0x41434750 // ACGP
	private const val GAP_VERSION = 2
	private const val NULL_LONG = -1L
	private val CALLBACK_ID_PATTERN = Regex("[0-9a-f]{64}")

	fun encode(receipt: ActivityCallbackGapReceipt): ByteArray =
		ByteArrayOutputStream().let { bytes ->
			DataOutputStream(bytes).use { output ->
				output.writeInt(GAP_MAGIC)
				output.writeInt(GAP_VERSION)
				ActivityCallbackRetryWire.writeBoundedUtf(output, receipt.callbackId)
				output.writeInt(receipt.code.wireCode)
				output.writeLong(receipt.receivedWallTimeMs ?: NULL_LONG)
				output.writeLong(receipt.registrationGeneration ?: NULL_LONG)
				output.writeLong(receipt.collectedDataEpoch ?: NULL_LONG)
				output.writeLong(receipt.recordedAtMs)
				output.writeBoolean(receipt.originalMetadata != null)
				receipt.originalMetadata?.let { ActivityCallbackRetryMetadataCodec.write(output, receipt.callbackId, it) }
			}
			bytes.toByteArray()
		}

	fun decode(encoded: ByteArray): ActivityCallbackGapReceipt =
		DataInputStream(ByteArrayInputStream(encoded)).use { input ->
			require(input.readInt() == GAP_MAGIC)
			val version = input.readInt()
			require(version in 1..GAP_VERSION)
			val callbackId = ActivityCallbackRetryWire.readBoundedUtf(input)
			require(CALLBACK_ID_PATTERN.matches(callbackId))
			val codeValue = input.readInt()
			val code = ActivityCallbackGapCode.entries.singleOrNull { it.wireCode == codeValue }
				?: error("Unknown Activity callback gap code $codeValue")
			val receipt = ActivityCallbackGapReceipt(
				callbackId,
				code,
				input.readLong().takeUnless { it == NULL_LONG },
				input.readLong().takeUnless { it == NULL_LONG },
				input.readLong().takeUnless { it == NULL_LONG },
				input.readLong(),
				if (version >= 2 && input.readBoolean()) {
					ActivityCallbackRetryMetadataCodec.read(input, callbackId)
				} else {
					null
				},
			)
			require(input.read() == -1)
			receipt
		}

}

/** Stable wire codes and bounded scalar reads shared by the two local record formats. */
private object ActivityCallbackRetryWire {
	private const val MAX_IDENTITY_TEXT_LENGTH = 512
	private const val START_CONTEXT_LIVE_CALLBACK = 1
	private const val START_CONTEXT_DURABLE_REPLAY = 2
	private const val ACTIVITY_STILL = 1
	private const val ACTIVITY_WALKING = 2
	private const val ACTIVITY_RUNNING = 3
	private const val ACTIVITY_ON_BICYCLE = 4
	private const val ACTIVITY_IN_VEHICLE = 5
	private const val ACTIVITY_ON_FOOT = 6
	private const val ACTIVITY_TILTING = 7
	private const val ACTIVITY_UNKNOWN = 8

	fun readStartContextV2(input: DataInputStream): ActivityIngressStartContext =
		when (val code = input.readInt()) {
			START_CONTEXT_LIVE_CALLBACK -> ActivityIngressStartContext.LIVE_PROVIDER_CALLBACK
			START_CONTEXT_DURABLE_REPLAY -> ActivityIngressStartContext.DURABLE_REPLAY
			else -> error("Invalid Activity callback start-context code $code")
		}

	fun startContextWireCode(context: ActivityIngressStartContext): Int = when (context) {
		ActivityIngressStartContext.LIVE_PROVIDER_CALLBACK -> START_CONTEXT_LIVE_CALLBACK
		ActivityIngressStartContext.DURABLE_REPLAY -> START_CONTEXT_DURABLE_REPLAY
	}

	fun readActivityTypeV2(input: DataInputStream): DetectedActivityType =
		when (val code = input.readInt()) {
			ACTIVITY_STILL -> DetectedActivityType.STILL
			ACTIVITY_WALKING -> DetectedActivityType.WALKING
			ACTIVITY_RUNNING -> DetectedActivityType.RUNNING
			ACTIVITY_ON_BICYCLE -> DetectedActivityType.ON_BICYCLE
			ACTIVITY_IN_VEHICLE -> DetectedActivityType.IN_VEHICLE
			ACTIVITY_ON_FOOT -> DetectedActivityType.ON_FOOT
			ACTIVITY_TILTING -> DetectedActivityType.TILTING
			ACTIVITY_UNKNOWN -> DetectedActivityType.UNKNOWN
			else -> error("Invalid Activity type code $code")
		}

	fun activityTypeWireCode(type: DetectedActivityType): Int = when (type) {
		DetectedActivityType.STILL -> ACTIVITY_STILL
		DetectedActivityType.WALKING -> ACTIVITY_WALKING
		DetectedActivityType.RUNNING -> ACTIVITY_RUNNING
		DetectedActivityType.ON_BICYCLE -> ACTIVITY_ON_BICYCLE
		DetectedActivityType.IN_VEHICLE -> ACTIVITY_IN_VEHICLE
		DetectedActivityType.ON_FOOT -> ACTIVITY_ON_FOOT
		DetectedActivityType.TILTING -> ACTIVITY_TILTING
		DetectedActivityType.UNKNOWN -> ACTIVITY_UNKNOWN
	}

	fun readTransitionType(input: DataInputStream): ActivityTransitionType {
		val code = input.readInt()
		return ActivityTransitionType.entries.firstOrNull { it.value == code }
			?: error("Invalid Activity transition type $code")
	}

	fun <T> readCount(input: DataInputStream, maximum: Int, read: () -> T): List<T> {
		val count = input.readInt()
		if (count !in 0..maximum) {
			error("Invalid Activity callback retry member count $count")
		}
		return List(count) { read() }
	}

	fun writeBoundedUtf(output: DataOutputStream, value: String) {
		require(value.isNotBlank() && value.length <= MAX_IDENTITY_TEXT_LENGTH)
		output.writeUTF(value)
	}

	fun readBoundedUtf(input: DataInputStream): String = input.readUTF().also { value ->
		if (value.isBlank() || value.length > MAX_IDENTITY_TEXT_LENGTH) {
			error("Invalid Activity callback retry identity text")
		}
	}

}
