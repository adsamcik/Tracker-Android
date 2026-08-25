package com.adsamcik.tracker.activity.receiver

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.activity.api.ingress.ActivityIngressStartContext
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEvidence
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEvidenceBatch
import com.adsamcik.tracker.activity.api.ingress.ActivityTransitionEvidence
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationIdentity
import com.adsamcik.tracker.stats.api.DetectedActivityType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

internal class ActivityCallbackRetryStoreException(
	message: String,
	cause: Throwable? = null,
	val corrupt: Boolean,
) : IOException(message, cause)

/**
 * Source-local, no-backup retry spool for an Activity callback that could not reach Room.
 *
 * Each callback is one independently atomic file. A poison/corrupt callback therefore cannot pin
 * unrelated callbacks, and resolving one callback never rewrites the rest of the spool. The file
 * name is the SHA-256 of the complete immutable callback envelope, making retries idempotent.
 */
@Singleton
internal class ActivityCallbackRetryStore internal constructor(
	private val directory: File,
	private val syncDirectory: (File) -> Unit = {},
	private val maxPendingCallbacks: Int = MAX_PENDING_CALLBACKS,
) {
	@Inject
	constructor(
		@ApplicationContext context: Context,
	) : this(
		directory = File(context.noBackupFilesDir, DIRECTORY_NAME),
		syncDirectory = ::fsyncDirectory,
	)

	/** Persists and read-verifies the exact callback before returning its stable identity. */
	@Synchronized
	fun retain(batch: ActivityRecognitionEvidenceBatch): String {
		requireNotNull(batch.registrationIdentity) {
			"A retry-owned Activity callback requires a physical registration identity"
		}
		require(batch.eventCount in 1..MAX_EVENTS_PER_CALLBACK) {
			"Activity callback member count is outside the durable retry bound"
		}
		val encoded = encode(batch)
		if (encoded.size > MAX_CALLBACK_BYTES) {
			throw ActivityCallbackRetryStoreException(
				"Activity callback exceeds the durable retry record bound",
				corrupt = false,
			)
		}
		val id = callbackId(encoded)
		ensureDirectory()
		val target = callbackFile(id)
		if (target.exists() || backupFile(target).exists()) {
			val existing = load(id)
			if (existing != batch) {
				throw ActivityCallbackRetryStoreException(
					"Activity retry identity resolved to different callback bytes",
					corrupt = true,
				)
			}
			return id
		}
		if (pendingIds().size >= maxPendingCallbacks) {
			throw ActivityCallbackRetryStoreException(
				"Activity callback retry spool is at capacity",
				corrupt = false,
			)
		}

		val atomic = AtomicFile(target)
		val output = try {
			atomic.startWrite()
		} catch (error: IOException) {
			throw ActivityCallbackRetryStoreException(
				"Unable to start Activity callback retry write",
				error,
				corrupt = false,
			)
		}
		try {
			output.write(encoded)
			output.flush()
			atomic.finishWrite(output)
		} catch (error: Exception) {
			atomic.failWrite(output)
			throw ActivityCallbackRetryStoreException(
				"Unable to persist Activity callback retry",
				error,
				corrupt = false,
			)
		}
		try {
			syncDirectory(directory)
		} catch (error: Exception) {
			// The file may exist, but its directory entry is not proven durable. Keep the receiver
			// permit unresolved instead of claiming durable retry ownership.
			throw ActivityCallbackRetryStoreException(
				"Unable to sync Activity callback retry directory",
				error,
				corrupt = false,
			)
		}
		if (load(id) != batch) {
			throw ActivityCallbackRetryStoreException(
				"Activity callback retry did not read back exactly",
				corrupt = true,
			)
		}
		return id
	}

	@Synchronized
	fun pendingIds(): List<String> {
		if (!directory.exists()) return emptyList()
		val ids = linkedSetOf<String>()
		directory.listFiles().orEmpty().forEach { file ->
			val match = CALLBACK_FILE_PATTERN.matchEntire(file.name) ?: return@forEach
			ids += match.groupValues[1]
		}
		return ids.sorted()
	}

	@Synchronized
	fun load(id: String): ActivityRecognitionEvidenceBatch {
		require(CALLBACK_ID_PATTERN.matches(id)) { "Invalid Activity callback retry identity" }
		val base = callbackFile(id)
		if (listOf(base, backupFile(base)).any { it.exists() && it.length() > MAX_CALLBACK_BYTES }) {
			throw ActivityCallbackRetryStoreException(
				"Activity callback retry record exceeds its bound",
				corrupt = true,
			)
		}
		val atomic = AtomicFile(base)
		val encoded = try {
			atomic.openRead().use { input ->
				val bytes = input.readBytes()
				if (bytes.size > MAX_CALLBACK_BYTES) {
					throw ActivityCallbackRetryStoreException(
						"Activity callback retry record exceeds its bound",
						corrupt = true,
					)
				}
				bytes
			}
		} catch (error: FileNotFoundException) {
			throw error
		} catch (error: ActivityCallbackRetryStoreException) {
			throw error
		} catch (error: IOException) {
			throw ActivityCallbackRetryStoreException(
				"Unable to read Activity callback retry",
				error,
				corrupt = false,
			)
		}
		if (callbackId(encoded) != id) {
			throw ActivityCallbackRetryStoreException(
				"Activity callback retry content identity does not match its file",
				corrupt = true,
			)
		}
		return decode(encoded)
	}

	@Synchronized
	fun remove(id: String) {
		require(CALLBACK_ID_PATTERN.matches(id)) { "Invalid Activity callback retry identity" }
		if (!directory.exists()) return
		AtomicFile(callbackFile(id)).delete()
		try {
			syncDirectory(directory)
		} catch (error: Exception) {
			throw ActivityCallbackRetryStoreException(
				"Unable to sync resolved Activity callback retry",
				error,
				corrupt = false,
			)
		}
	}

	/** Privacy deletion terminally rejects and removes every retained callback, including poison. */
	@Synchronized
	fun purge() {
		if (!directory.exists()) return
		directory.listFiles().orEmpty().forEach { file ->
			if (!file.delete() && file.exists()) {
				throw ActivityCallbackRetryStoreException(
					"Unable to remove Activity callback retry during collected-data deletion",
					corrupt = false,
				)
			}
		}
		try {
			syncDirectory(directory)
		} catch (error: Exception) {
			throw ActivityCallbackRetryStoreException(
				"Unable to sync purged Activity callback retry directory",
				error,
				corrupt = false,
			)
		}
	}

	private fun ensureDirectory() {
		if (directory.exists()) {
			if (!directory.isDirectory) {
				throw ActivityCallbackRetryStoreException(
					"Activity callback retry path is not a directory",
					corrupt = true,
				)
			}
			return
		}
		val parent = directory.parentFile
		if (!directory.mkdirs() && !directory.isDirectory) {
			throw ActivityCallbackRetryStoreException(
				"Unable to create Activity callback retry directory",
				corrupt = false,
			)
		}
		if (parent != null) {
			try {
				syncDirectory(parent)
			} catch (error: Exception) {
				throw ActivityCallbackRetryStoreException(
					"Unable to sync Activity callback retry parent directory",
					error,
					corrupt = false,
				)
			}
		}
	}

	private fun callbackFile(id: String) = File(directory, "activity-callback-$id.bin")

	private fun backupFile(base: File) = File("${base.path}.bak")

	private fun encode(batch: ActivityRecognitionEvidenceBatch): ByteArray {
		val bytes = ByteArrayOutputStream()
		DataOutputStream(bytes).use { output ->
			output.writeInt(RECORD_MAGIC)
			output.writeInt(RECORD_VERSION)
			output.writeLong(batch.receivedElapsedRealtimeNanos)
			output.writeLong(batch.receivedWallTimeMs)
			output.writeByte(batch.startContext.ordinal)
			val identity = requireNotNull(batch.registrationIdentity)
			writeBoundedUtf(output, identity.sourceInstanceId)
			output.writeLong(identity.registrationGeneration)
			output.writeLong(identity.collectedDataEpoch)
			writeBoundedUtf(output, identity.clockDomainId)
			writeBoundedUtf(output, identity.physicalConfigurationFingerprint)
			output.writeBoolean(batch.automaticRecognitionEligible)
			val automaticTransitions = batch.automaticTransitions.sortedWith(
				compareBy<ActivityTransitionData> { it.activity.name }.thenBy { it.type.value },
			)
			require(automaticTransitions.size <= MAX_AUTOMATIC_TRANSITIONS)
			output.writeInt(automaticTransitions.size)
			automaticTransitions.forEach { transition ->
				output.writeUTF(transition.activity.name)
				output.writeInt(transition.type.value)
			}
			output.writeInt(batch.recognitions.size)
			batch.recognitions.forEach { evidence ->
				output.writeUTF(evidence.activityType.name)
				output.writeInt(evidence.confidencePercent)
				output.writeLong(evidence.providerElapsedRealtimeNanos)
			}
			output.writeInt(batch.transitions.size)
			batch.transitions.forEach { evidence ->
				output.writeUTF(evidence.activityType.name)
				output.writeInt(evidence.transitionType.value)
				output.writeLong(evidence.providerElapsedRealtimeNanos)
			}
		}
		return bytes.toByteArray()
	}

	private fun decode(encoded: ByteArray): ActivityRecognitionEvidenceBatch = try {
		DataInputStream(ByteArrayInputStream(encoded)).use { input ->
			if (input.readInt() != RECORD_MAGIC) error("Invalid Activity callback retry header")
			if (input.readInt() != RECORD_VERSION) error("Unsupported Activity callback retry version")
			val receivedElapsed = input.readLong()
			val receivedWall = input.readLong()
			val startContext = ActivityIngressStartContext.entries.getOrNull(input.readUnsignedByte())
				?: error("Invalid Activity callback start context")
			val identity = ActivityRegistrationIdentity(
				sourceInstanceId = readBoundedUtf(input),
				registrationGeneration = input.readLong(),
				collectedDataEpoch = input.readLong(),
				clockDomainId = readBoundedUtf(input),
				physicalConfigurationFingerprint = readBoundedUtf(input),
			)
			val automaticRecognitionEligible = input.readBoolean()
			val automaticTransitions = readCount(input, MAX_AUTOMATIC_TRANSITIONS) {
				ActivityTransitionData(
					activity = readActivityType(input),
					type = readTransitionType(input),
				)
			}.toSet()
			val recognitions = readCount(input, MAX_EVENTS_PER_CALLBACK) {
				ActivityRecognitionEvidence(
					activityType = readActivityType(input),
					confidencePercent = input.readInt(),
					providerElapsedRealtimeNanos = input.readLong(),
				)
			}
			val remainingEvents = MAX_EVENTS_PER_CALLBACK - recognitions.size
			val transitions = readCount(input, remainingEvents) {
				ActivityTransitionEvidence(
					activityType = readActivityType(input),
					transitionType = readTransitionType(input),
					providerElapsedRealtimeNanos = input.readLong(),
				)
			}
			if (recognitions.isEmpty() && transitions.isEmpty()) error("Empty Activity callback retry")
			if (input.read() != -1) error("Trailing Activity callback retry bytes")
			ActivityRecognitionEvidenceBatch(
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
	} catch (error: ActivityCallbackRetryStoreException) {
		throw error
	} catch (error: EOFException) {
		throw ActivityCallbackRetryStoreException(
			"Activity callback retry is truncated",
			error,
			corrupt = true,
		)
	} catch (error: Exception) {
		throw ActivityCallbackRetryStoreException(
			"Activity callback retry cannot be decoded",
			error,
			corrupt = true,
		)
	}

	private fun readActivityType(input: DataInputStream): DetectedActivityType =
		DetectedActivityType.valueOf(readBoundedUtf(input))

	private fun readTransitionType(input: DataInputStream): ActivityTransitionType {
		val code = input.readInt()
		return ActivityTransitionType.entries.firstOrNull { it.value == code }
			?: error("Invalid Activity transition type $code")
	}

	private fun <T> readCount(input: DataInputStream, maximum: Int, read: () -> T): List<T> {
		val count = input.readInt()
		if (count !in 0..maximum) error("Invalid Activity callback retry member count $count")
		return List(count) { read() }
	}

	private fun writeBoundedUtf(output: DataOutputStream, value: String) {
		require(value.isNotBlank() && value.length <= MAX_IDENTITY_TEXT_LENGTH)
		output.writeUTF(value)
	}

	private fun readBoundedUtf(input: DataInputStream): String = input.readUTF().also { value ->
		if (value.isBlank() || value.length > MAX_IDENTITY_TEXT_LENGTH) {
			error("Invalid Activity callback retry identity text")
		}
	}

	private fun callbackId(encoded: ByteArray): String = MessageDigest.getInstance("SHA-256")
		.digest(encoded)
		.joinToString("") { byte -> "%02x".format(byte) }

	private companion object {
		const val DIRECTORY_NAME = "activity-callback-retry-v1"
		const val RECORD_MAGIC = 0x41435248 // ACRH
		const val RECORD_VERSION = 1
		const val MAX_PENDING_CALLBACKS = 1_024
		const val MAX_EVENTS_PER_CALLBACK = 1_024
		const val MAX_AUTOMATIC_TRANSITIONS = 64
		const val MAX_IDENTITY_TEXT_LENGTH = 4_096
		const val MAX_CALLBACK_BYTES = 256 * 1_024
		val CALLBACK_ID_PATTERN = Regex("[0-9a-f]{64}")
		// AtomicFile `.new` is an interrupted, never-read-verified write. It did not earn callback
		// ownership and must not keep WorkManager retrying forever after a process crash.
		val CALLBACK_FILE_PATTERN = Regex("activity-callback-([0-9a-f]{64})\\.bin(?:\\.bak)?")

		fun fsyncDirectory(directory: File) {
			val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
			try {
				Os.fsync(descriptor)
			} finally {
				Os.close(descriptor)
			}
		}
	}
}
