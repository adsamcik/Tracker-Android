package com.adsamcik.tracker.activity.receiver

import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEvidenceBatch
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationIdentity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Original callback scope, readable even when the independently stored payload is damaged. */
internal data class ActivityCallbackRetryMetadata(
	val registrationIdentity: ActivityRegistrationIdentity,
	val receivedElapsedRealtimeNanos: Long,
	val receivedWallTimeMs: Long,
	val observedElapsedRealtimeNanos: List<Long>,
) {
	companion object {
		fun from(batch: ActivityRecognitionEvidenceBatch): ActivityCallbackRetryMetadata {
			require(batch.receivedElapsedRealtimeNanos >= 0L && batch.receivedWallTimeMs >= 0L)
			val observed = (batch.recognitions.map { it.providerElapsedRealtimeNanos } +
				batch.transitions.map { it.providerElapsedRealtimeNanos })
				.filter { it in 0L..batch.receivedElapsedRealtimeNanos }.distinct().sorted()
			return ActivityCallbackRetryMetadata(requireNotNull(batch.registrationIdentity),
				batch.receivedElapsedRealtimeNanos, batch.receivedWallTimeMs, observed)
		}
	}
}

/** A bounded checksum protects attribution from accidental header corruption, not user edits. */
internal object ActivityCallbackRetryMetadataCodec {
	private const val MAX_TEXT_LENGTH = 512
	private const val MAX_HEADER_BYTES = 16 * 1_024
	private const val MAX_OBSERVATIONS = 1_024
	private const val CHECKSUM_BYTES = 32

	fun write(output: DataOutputStream, callbackId: String, metadata: ActivityCallbackRetryMetadata) {
		val bytes = ByteArrayOutputStream().also { buffer ->
			DataOutputStream(buffer).use { header ->
				header.writeUTF(callbackId)
				val identity = metadata.registrationIdentity
				header.writeUTF(identity.sourceInstanceId)
				header.writeLong(identity.registrationGeneration)
				header.writeLong(identity.collectedDataEpoch)
				header.writeUTF(identity.clockDomainId)
				header.writeUTF(identity.physicalConfigurationFingerprint)
				header.writeLong(metadata.receivedElapsedRealtimeNanos)
				header.writeLong(metadata.receivedWallTimeMs)
				require(metadata.observedElapsedRealtimeNanos.size <= MAX_OBSERVATIONS)
				header.writeInt(metadata.observedElapsedRealtimeNanos.size)
				metadata.observedElapsedRealtimeNanos.forEach(header::writeLong)
			}
		}.toByteArray()
		require(bytes.size <= MAX_HEADER_BYTES)
		output.writeInt(bytes.size)
		output.write(bytes)
		output.write(MessageDigest.getInstance("SHA-256").digest(bytes))
	}

	fun read(input: DataInputStream, callbackId: String): ActivityCallbackRetryMetadata {
		val size = input.readInt()
		require(size in 1..MAX_HEADER_BYTES)
		val bytes = ByteArray(size).also(input::readFully)
		val checksum = ByteArray(CHECKSUM_BYTES).also(input::readFully)
		require(MessageDigest.isEqual(checksum, MessageDigest.getInstance("SHA-256").digest(bytes)))
		return DataInputStream(ByteArrayInputStream(bytes)).use { header ->
			require(header.readUTF() == callbackId)
			val identity = ActivityRegistrationIdentity(
				readText(header), header.readLong(), header.readLong(), readText(header), readText(header))
			val receivedElapsed = header.readLong()
			val receivedWall = header.readLong()
			require(receivedElapsed >= 0L && receivedWall >= 0L)
			val count = header.readInt()
			require(count in 0..MAX_OBSERVATIONS)
			val observed = List(count) { header.readLong().also { require(it in 0L..receivedElapsed) } }
			require(observed == observed.distinct().sorted())
			require(header.read() == -1)
			ActivityCallbackRetryMetadata(identity, receivedElapsed, receivedWall, observed)
		}
	}

	private fun readText(input: DataInputStream): String = input.readUTF().also {
		require(it.isNotBlank() && it.length <= MAX_TEXT_LENGTH)
	}
}
