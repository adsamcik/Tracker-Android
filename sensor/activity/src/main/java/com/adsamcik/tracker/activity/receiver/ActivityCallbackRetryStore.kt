package com.adsamcik.tracker.activity.receiver

import android.content.Context
import android.os.SystemClock
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
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
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

/** Stable, non-localized codes retained in gap receipts and emitted to diagnostics. */
internal enum class ActivityCallbackGapCode(
	val wireCode: Int,
	val telemetryCode: String,
	val corruptRetryRecord: Boolean = false,
	val terminalRetryRecord: Boolean = false,
) {
	HANDOFF_BUDGET_EXHAUSTED(1, "ACTIVITY_CALLBACK_HANDOFF_BUDGET_EXHAUSTED"),
	RETRY_RECORD_TOO_LARGE(2, "ACTIVITY_CALLBACK_RETRY_RECORD_TOO_LARGE"),
	RETRY_COUNT_BUDGET_EXHAUSTED(3, "ACTIVITY_CALLBACK_RETRY_COUNT_BUDGET_EXHAUSTED"),
	RETRY_BYTE_BUDGET_EXHAUSTED(4, "ACTIVITY_CALLBACK_RETRY_BYTE_BUDGET_EXHAUSTED"),
	RETRY_STORAGE_UNAVAILABLE(5, "ACTIVITY_CALLBACK_RETRY_STORAGE_UNAVAILABLE"),
	RETRY_RECORD_CORRUPT(
		6,
		"ACTIVITY_CALLBACK_RETRY_RECORD_CORRUPT",
		corruptRetryRecord = true,
		terminalRetryRecord = true,
	),
	RETRY_RECORD_UNSUPPORTED_VERSION(
		7,
		"ACTIVITY_CALLBACK_RETRY_RECORD_UNSUPPORTED_VERSION",
		corruptRetryRecord = true,
		terminalRetryRecord = true,
	),
	RETRY_RECORD_EXPIRED(
		8,
		"ACTIVITY_CALLBACK_RETRY_RECORD_EXPIRED",
		terminalRetryRecord = true,
	),
	RETRY_CLOCK_DOMAIN_CHANGED(
		9,
		"ACTIVITY_CALLBACK_RETRY_CLOCK_DOMAIN_CHANGED",
		terminalRetryRecord = true,
	),
}

internal class ActivityCallbackRetryStoreException(
	message: String,
	cause: Throwable? = null,
	val code: ActivityCallbackGapCode = ActivityCallbackGapCode.RETRY_STORAGE_UNAVAILABLE,
) : IOException(message, cause) {
	val corrupt: Boolean get() = code.corruptRetryRecord
	val terminalRetryRecord: Boolean get() = code.terminalRetryRecord
}

internal data class ActivityCallbackGapReceipt(
	val callbackId: String,
	val code: ActivityCallbackGapCode,
	val receivedWallTimeMs: Long?,
	val registrationGeneration: Long?,
	val collectedDataEpoch: Long?,
	val recordedAtMs: Long,
)

/**
 * Source-local, no-backup retry spool for an Activity callback that could not reach Room.
 *
 * Each callback is one independently atomic file. A poison callback cannot pin unrelated callbacks,
 * and resolving one callback never rewrites the rest of the spool. The small count, byte, and age
 * budgets bound failure-mode storage. Gap receipts are also bounded and are purged with collected
 * data; they make terminal loss explicit without becoming another observation queue.
 */
@Singleton
internal class ActivityCallbackRetryStore internal constructor(
	private val directory: File,
	private val currentClockDomainId: () -> String,
	private val nowElapsedRealtimeNanos: () -> Long,
	private val syncDirectory: (File) -> Unit = {},
	private val nowWallTimeMs: () -> Long = System::currentTimeMillis,
	private val maxPendingCallbacks: Int = MAX_PENDING_CALLBACKS,
	private val maxPendingBytes: Long = MAX_PENDING_BYTES,
	private val maxCallbackBytes: Int = MAX_CALLBACK_BYTES,
	private val maxRetryAgeMs: Long = MAX_RETRY_AGE_MS,
	private val maxGapReceipts: Int = MAX_GAP_RECEIPTS,
	private val maxGapAgeMs: Long = MAX_GAP_AGE_MS,
) {
	init {
		require(maxPendingCallbacks > 0 && maxPendingBytes > 0L && maxCallbackBytes > 0)
		require(maxRetryAgeMs in 1L..(Long.MAX_VALUE / NANOS_PER_MILLISECOND))
		require(maxGapReceipts > 0 && maxGapAgeMs > 0L)
	}

	@Inject
	constructor(
		@ApplicationContext context: Context,
		bootClockDomainProvider: BootClockDomainProvider,
	) : this(
		directory = File(context.noBackupFilesDir, DIRECTORY_NAME),
		syncDirectory = ::fsyncDirectory,
		currentClockDomainId = bootClockDomainProvider::current,
		nowElapsedRealtimeNanos = SystemClock::elapsedRealtimeNanos,
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
		val encoded = encodeV2(batch)
		if (encoded.size > maxCallbackBytes) {
			throw ActivityCallbackRetryStoreException(
				"Activity callback exceeds the durable retry record bound",
				code = ActivityCallbackGapCode.RETRY_RECORD_TOO_LARGE,
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
					code = ActivityCallbackGapCode.RETRY_RECORD_CORRUPT,
				)
			}
			return id
		}
		val pendingFiles = pendingCallbackFiles()
		if (pendingFiles.map { it.id }.distinct().size >= maxPendingCallbacks) {
			throw ActivityCallbackRetryStoreException(
				"Activity callback retry spool reached its callback-count budget",
				code = ActivityCallbackGapCode.RETRY_COUNT_BUDGET_EXHAUSTED,
			)
		}
		if (pendingFiles.sumOf { it.file.length() } + encoded.size > maxPendingBytes) {
			throw ActivityCallbackRetryStoreException(
				"Activity callback retry spool reached its total-byte budget",
				code = ActivityCallbackGapCode.RETRY_BYTE_BUDGET_EXHAUSTED,
			)
		}

		writeAtomic(target, encoded, "Unable to persist Activity callback retry")
		if (load(id) != batch) {
			throw ActivityCallbackRetryStoreException(
				"Activity callback retry did not read back exactly",
				code = ActivityCallbackGapCode.RETRY_RECORD_CORRUPT,
			)
		}
		return id
	}

	@Synchronized
	fun pendingIds(): List<String> = pendingCallbackFiles()
		.mapTo(linkedSetOf()) { it.id }
		.sorted()

	@Synchronized
	fun load(id: String): ActivityRecognitionEvidenceBatch {
		require(CALLBACK_ID_PATTERN.matches(id)) { "Invalid Activity callback retry identity" }
		val base = callbackFile(id)
		if (listOf(base, backupFile(base)).any { it.exists() && it.length() > maxCallbackBytes }) {
			throw ActivityCallbackRetryStoreException(
				"Activity callback retry record exceeds its bound",
				code = ActivityCallbackGapCode.RETRY_RECORD_CORRUPT,
			)
		}
		val encoded = readAtomic(
			base = base,
			maximumBytes = maxCallbackBytes,
			readFailureCode = ActivityCallbackGapCode.RETRY_STORAGE_UNAVAILABLE,
		)
		if (callbackId(encoded) != id) {
			throw ActivityCallbackRetryStoreException(
				"Activity callback retry content identity does not match its file",
				code = ActivityCallbackGapCode.RETRY_RECORD_CORRUPT,
			)
		}
		val batch = decodeRetryRecord(encoded)
		val currentClockDomain = try {
			currentClockDomainId()
		} catch (error: Exception) {
			throw ActivityCallbackRetryStoreException(
				"Unable to resolve the Activity callback retry clock domain",
				error,
			)
		}
		if (currentClockDomain != batch.registrationIdentity?.clockDomainId) {
			throw ActivityCallbackRetryStoreException(
				"Activity callback retry belongs to an earlier boot clock domain",
				code = ActivityCallbackGapCode.RETRY_CLOCK_DOMAIN_CHANGED,
			)
		}
		val nowElapsedNanos = try {
			nowElapsedRealtimeNanos()
		} catch (error: Exception) {
			throw ActivityCallbackRetryStoreException(
				"Unable to resolve Activity callback retry elapsed time",
				error,
			)
		}
		if (nowElapsedNanos < batch.receivedElapsedRealtimeNanos ||
			nowElapsedNanos - batch.receivedElapsedRealtimeNanos >
			maxRetryAgeMs * NANOS_PER_MILLISECOND
		) {
			throw ActivityCallbackRetryStoreException(
				"Activity callback retry exceeded its operational inbox freshness budget",
				code = ActivityCallbackGapCode.RETRY_RECORD_EXPIRED,
			)
		}
		return batch
	}

	@Synchronized
	fun remove(id: String) {
		require(CALLBACK_ID_PATTERN.matches(id)) { "Invalid Activity callback retry identity" }
		if (!directory.exists()) return
		AtomicFile(callbackFile(id)).delete()
		syncAfterMutation("Unable to sync resolved Activity callback retry")
	}

	/**
	 * Writes a small auditable loss receipt. Failure is reported to the caller and never grants
	 * observation durability or callback authority.
	 */
	@Synchronized
	fun recordGap(
		callbackId: String,
		code: ActivityCallbackGapCode,
		batch: ActivityRecognitionEvidenceBatch? = null,
	): Boolean {
		require(CALLBACK_ID_PATTERN.matches(callbackId))
		return try {
			ensureDirectory()
			pruneGapReceipts()
			val receipt = ActivityCallbackGapReceipt(
				callbackId = callbackId,
				code = code,
				receivedWallTimeMs = batch?.receivedWallTimeMs,
				registrationGeneration = batch?.registrationIdentity?.registrationGeneration,
				collectedDataEpoch = batch?.registrationIdentity?.collectedDataEpoch,
				recordedAtMs = nowWallTimeMs().coerceAtLeast(0L),
			)
			val target = gapFile(receipt)
			if (target.exists() || backupFile(target).exists()) return true
			val existing = gapFiles().sortedBy { it.file.lastModified() }
			if (existing.size >= maxGapReceipts) {
				existing.take(existing.size - maxGapReceipts + 1).forEach { gap ->
					AtomicFile(gap.file).delete()
				}
				syncAfterMutation("Unable to sync bounded Activity callback gap receipts")
			}
			val bytes = encodeGapReceipt(receipt)
			writeAtomic(target, bytes, "Unable to persist Activity callback gap receipt")
			decodeGapReceipt(readAtomic(target, MAX_GAP_RECEIPT_BYTES)) == receipt
		} catch (_: Exception) {
			false
		}
	}

	@Synchronized
	fun recordGap(
		batch: ActivityRecognitionEvidenceBatch,
		code: ActivityCallbackGapCode,
	): Boolean = recordGap(
		callbackId = callbackId(encodeV2(batch)),
		code = code,
		batch = batch,
	)

	@Synchronized
	fun gapReceipts(): List<ActivityCallbackGapReceipt> {
		if (!directory.isDirectory) return emptyList()
		return gapFiles().mapNotNull { gap ->
			runCatching {
				decodeGapReceipt(readAtomic(gap.file, MAX_GAP_RECEIPT_BYTES))
			}.getOrNull()
		}.sortedBy(ActivityCallbackGapReceipt::recordedAtMs)
	}

	/** Privacy deletion removes retained callbacks, poison files, and gap receipts together. */
	@Synchronized
	fun purge() {
		if (!directory.exists()) return
		directory.listFiles().orEmpty().forEach { file ->
			if (!file.delete() && file.exists()) {
				throw ActivityCallbackRetryStoreException(
					"Unable to remove Activity callback retry data during collected-data deletion",
				)
			}
		}
		syncAfterMutation("Unable to sync purged Activity callback retry directory")
	}

	private fun pendingCallbackFiles(): List<CallbackFile> {
		if (!directory.isDirectory) return emptyList()
		return directory.listFiles().orEmpty().mapNotNull { file ->
			val match = CALLBACK_FILE_PATTERN.matchEntire(file.name) ?: return@mapNotNull null
			CallbackFile(match.groupValues[1], file)
		}
	}

	private fun gapFiles(): List<GapFile> {
		if (!directory.isDirectory) return emptyList()
		return directory.listFiles().orEmpty().mapNotNull { file ->
			if (!GAP_FILE_PATTERN.matches(file.name)) return@mapNotNull null
			GapFile(file)
		}
	}

	private fun pruneGapReceipts() {
		val nowMs = nowWallTimeMs()
		val expired = gapFiles().filter { gap ->
			val modifiedAtMs = gap.file.lastModified()
			nowMs >= modifiedAtMs && nowMs - modifiedAtMs > maxGapAgeMs
		}
		if (expired.isEmpty()) return
		expired.forEach { gap -> AtomicFile(gap.file).delete() }
		syncAfterMutation("Unable to sync expired Activity callback gap receipts")
	}

	private fun ensureDirectory() {
		if (directory.exists()) {
			if (!directory.isDirectory) {
				throw ActivityCallbackRetryStoreException(
					"Activity callback retry path is not a directory",
					code = ActivityCallbackGapCode.RETRY_RECORD_CORRUPT,
				)
			}
			return
		}
		val parent = directory.parentFile
		if (!directory.mkdirs() && !directory.isDirectory) {
			throw ActivityCallbackRetryStoreException(
				"Unable to create Activity callback retry directory",
			)
		}
		if (parent != null) {
			try {
				syncDirectory(parent)
			} catch (error: Exception) {
				throw ActivityCallbackRetryStoreException(
					"Unable to sync Activity callback retry parent directory",
					error,
				)
			}
		}
	}

	private fun writeAtomic(target: File, bytes: ByteArray, failureMessage: String) {
		val atomic = AtomicFile(target)
		val output = try {
			atomic.startWrite()
		} catch (error: IOException) {
			throw ActivityCallbackRetryStoreException(failureMessage, error)
		}
		try {
			output.write(bytes)
			output.flush()
			atomic.finishWrite(output)
		} catch (error: Exception) {
			atomic.failWrite(output)
			throw ActivityCallbackRetryStoreException(failureMessage, error)
		}
		syncAfterMutation("$failureMessage: directory sync failed")
	}

	private fun readAtomic(
		base: File,
		maximumBytes: Int,
		readFailureCode: ActivityCallbackGapCode = ActivityCallbackGapCode.RETRY_RECORD_CORRUPT,
	): ByteArray {
		if (listOf(base, backupFile(base)).any { it.exists() && it.length() > maximumBytes }) {
			throw ActivityCallbackRetryStoreException(
				"Activity callback record exceeds its read bound",
				code = ActivityCallbackGapCode.RETRY_RECORD_CORRUPT,
			)
		}
		return try {
			AtomicFile(base).openRead().use { input ->
				input.readBytes().also { bytes ->
					if (bytes.size > maximumBytes) {
						throw ActivityCallbackRetryStoreException(
							"Activity callback record exceeds its read bound",
							code = ActivityCallbackGapCode.RETRY_RECORD_CORRUPT,
						)
					}
				}
			}
		} catch (error: FileNotFoundException) {
			throw error
		} catch (error: ActivityCallbackRetryStoreException) {
			throw error
		} catch (error: IOException) {
			throw ActivityCallbackRetryStoreException(
				"Unable to read Activity callback record",
				error,
				code = readFailureCode,
			)
		}
	}

	private fun syncAfterMutation(message: String) {
		try {
			syncDirectory(directory)
		} catch (error: Exception) {
			throw ActivityCallbackRetryStoreException(message, error)
		}
	}

	private fun callbackFile(id: String) = File(directory, "activity-callback-$id.bin")

	private fun gapFile(receipt: ActivityCallbackGapReceipt) = File(
		directory,
		"activity-gap-${receipt.callbackId}-${receipt.code.wireCode}.bin",
	)

	private fun backupFile(base: File) = File("${base.path}.bak")

	private fun encodeV2(batch: ActivityRecognitionEvidenceBatch): ByteArray {
		val bytes = ByteArrayOutputStream()
		DataOutputStream(bytes).use { output ->
			output.writeInt(RECORD_MAGIC)
			output.writeInt(RECORD_VERSION_V2)
			output.writeLong(batch.receivedElapsedRealtimeNanos)
			output.writeLong(batch.receivedWallTimeMs)
			output.writeInt(startContextWireCode(batch.startContext))
			val identity = requireNotNull(batch.registrationIdentity)
			writeBoundedUtf(output, identity.sourceInstanceId)
			output.writeLong(identity.registrationGeneration)
			output.writeLong(identity.collectedDataEpoch)
			writeBoundedUtf(output, identity.clockDomainId)
			writeBoundedUtf(output, identity.physicalConfigurationFingerprint)
			output.writeBoolean(batch.automaticRecognitionEligible)
			val automaticTransitions = batch.automaticTransitions.sortedWith(
				compareBy<ActivityTransitionData> { activityTypeWireCode(it.activity) }
					.thenBy { it.type.value },
			)
			require(automaticTransitions.size <= MAX_AUTOMATIC_TRANSITIONS)
			output.writeInt(automaticTransitions.size)
			automaticTransitions.forEach { transition ->
				output.writeInt(activityTypeWireCode(transition.activity))
				output.writeInt(transition.type.value)
			}
			output.writeInt(batch.recognitions.size)
			batch.recognitions.forEach { evidence ->
				output.writeInt(activityTypeWireCode(evidence.activityType))
				output.writeInt(evidence.confidencePercent)
				output.writeLong(evidence.providerElapsedRealtimeNanos)
			}
			output.writeInt(batch.transitions.size)
			batch.transitions.forEach { evidence ->
				output.writeInt(activityTypeWireCode(evidence.activityType))
				output.writeInt(evidence.transitionType.value)
				output.writeLong(evidence.providerElapsedRealtimeNanos)
			}
		}
		return bytes.toByteArray()
	}

	private fun decodeRetryRecord(encoded: ByteArray): ActivityRecognitionEvidenceBatch = try {
		DataInputStream(ByteArrayInputStream(encoded)).use { input ->
			if (input.readInt() != RECORD_MAGIC) {
				throw ActivityCallbackRetryStoreException(
					"Invalid Activity callback retry header",
					code = ActivityCallbackGapCode.RETRY_RECORD_CORRUPT,
				)
			}
			val version = input.readInt()
			val batch = when (version) {
				RECORD_VERSION_V2 -> decodeV2Payload(input)
				else -> throw ActivityCallbackRetryStoreException(
					"Unsupported Activity callback retry version $version",
					code = ActivityCallbackGapCode.RETRY_RECORD_UNSUPPORTED_VERSION,
				)
			}
			if (input.read() != -1) error("Trailing Activity callback retry bytes")
			batch
		}
	} catch (error: ActivityCallbackRetryStoreException) {
		throw error
	} catch (error: EOFException) {
		throw ActivityCallbackRetryStoreException(
			"Activity callback retry is truncated",
			error,
			code = ActivityCallbackGapCode.RETRY_RECORD_CORRUPT,
		)
	} catch (error: Exception) {
		throw ActivityCallbackRetryStoreException(
			"Activity callback retry cannot be decoded",
			error,
			code = ActivityCallbackGapCode.RETRY_RECORD_CORRUPT,
		)
	}

	private fun decodeV2Payload(input: DataInputStream): ActivityRecognitionEvidenceBatch =
		decodePayload(
			input = input,
			readStartContext = { readStartContextV2(input) },
			readActivityType = { readActivityTypeV2(input) },
		)

	private fun decodePayload(
		input: DataInputStream,
		readStartContext: () -> ActivityIngressStartContext,
		readActivityType: () -> DetectedActivityType,
	): ActivityRecognitionEvidenceBatch {
		val receivedElapsed = input.readLong()
		val receivedWall = input.readLong()
		val startContext = readStartContext()
		val identity = ActivityRegistrationIdentity(
			sourceInstanceId = readBoundedUtf(input),
			registrationGeneration = input.readLong(),
			collectedDataEpoch = input.readLong(),
			clockDomainId = readBoundedUtf(input),
			physicalConfigurationFingerprint = readBoundedUtf(input),
		)
		val automaticRecognitionEligible = input.readBoolean()
		val automaticTransitions = readCount(input, MAX_AUTOMATIC_TRANSITIONS) {
			ActivityTransitionData(readActivityType(), readTransitionType(input))
		}.toSet()
		val recognitions = readCount(input, MAX_EVENTS_PER_CALLBACK) {
			ActivityRecognitionEvidence(
				readActivityType(),
				input.readInt(),
				input.readLong(),
			)
		}
		val remainingEvents = MAX_EVENTS_PER_CALLBACK - recognitions.size
		val transitions = readCount(input, remainingEvents) {
			ActivityTransitionEvidence(
				readActivityType(),
				readTransitionType(input),
				input.readLong(),
			)
		}
		if (recognitions.isEmpty() && transitions.isEmpty()) error("Empty Activity callback retry")
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

	private fun encodeGapReceipt(receipt: ActivityCallbackGapReceipt): ByteArray =
		ByteArrayOutputStream().let { bytes ->
			DataOutputStream(bytes).use { output ->
				output.writeInt(GAP_MAGIC)
				output.writeInt(GAP_VERSION)
				writeBoundedUtf(output, receipt.callbackId)
				output.writeInt(receipt.code.wireCode)
				output.writeLong(receipt.receivedWallTimeMs ?: NULL_LONG)
				output.writeLong(receipt.registrationGeneration ?: NULL_LONG)
				output.writeLong(receipt.collectedDataEpoch ?: NULL_LONG)
				output.writeLong(receipt.recordedAtMs)
			}
			bytes.toByteArray()
		}

	private fun decodeGapReceipt(encoded: ByteArray): ActivityCallbackGapReceipt =
		DataInputStream(ByteArrayInputStream(encoded)).use { input ->
			require(input.readInt() == GAP_MAGIC)
			require(input.readInt() == GAP_VERSION)
			val callbackId = readBoundedUtf(input)
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
			)
			require(input.read() == -1)
			receipt
		}

	private fun readStartContextV2(input: DataInputStream): ActivityIngressStartContext =
		when (val code = input.readInt()) {
			START_CONTEXT_LIVE_CALLBACK -> ActivityIngressStartContext.LIVE_PROVIDER_CALLBACK
			START_CONTEXT_DURABLE_REPLAY -> ActivityIngressStartContext.DURABLE_REPLAY
			else -> error("Invalid Activity callback start-context code $code")
		}

	private fun startContextWireCode(context: ActivityIngressStartContext): Int = when (context) {
		ActivityIngressStartContext.LIVE_PROVIDER_CALLBACK -> START_CONTEXT_LIVE_CALLBACK
		ActivityIngressStartContext.DURABLE_REPLAY -> START_CONTEXT_DURABLE_REPLAY
	}

	private fun readActivityTypeV2(input: DataInputStream): DetectedActivityType =
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

	private fun activityTypeWireCode(type: DetectedActivityType): Int = when (type) {
		DetectedActivityType.STILL -> ACTIVITY_STILL
		DetectedActivityType.WALKING -> ACTIVITY_WALKING
		DetectedActivityType.RUNNING -> ACTIVITY_RUNNING
		DetectedActivityType.ON_BICYCLE -> ACTIVITY_ON_BICYCLE
		DetectedActivityType.IN_VEHICLE -> ACTIVITY_IN_VEHICLE
		DetectedActivityType.ON_FOOT -> ACTIVITY_ON_FOOT
		DetectedActivityType.TILTING -> ACTIVITY_TILTING
		DetectedActivityType.UNKNOWN -> ACTIVITY_UNKNOWN
	}

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

	private data class CallbackFile(val id: String, val file: File)
	private data class GapFile(val file: File)

	private companion object {
		const val DIRECTORY_NAME = "activity-callback-retry-v1"
		const val RECORD_MAGIC = 0x41435248 // ACRH
		const val RECORD_VERSION_V2 = 2
		const val GAP_MAGIC = 0x41434750 // ACGP
		const val GAP_VERSION = 1
		const val START_CONTEXT_LIVE_CALLBACK = 1
		const val START_CONTEXT_DURABLE_REPLAY = 2
		const val ACTIVITY_STILL = 1
		const val ACTIVITY_WALKING = 2
		const val ACTIVITY_RUNNING = 3
		const val ACTIVITY_ON_BICYCLE = 4
		const val ACTIVITY_IN_VEHICLE = 5
		const val ACTIVITY_ON_FOOT = 6
		const val ACTIVITY_TILTING = 7
		const val ACTIVITY_UNKNOWN = 8
		const val MAX_PENDING_CALLBACKS = 64
		const val MAX_EVENTS_PER_CALLBACK = 1_024
		const val MAX_AUTOMATIC_TRANSITIONS = 64
		const val MAX_IDENTITY_TEXT_LENGTH = 512
		const val MAX_CALLBACK_BYTES = 32 * 1_024
		const val MAX_PENDING_BYTES = 512L * 1_024L
		const val MAX_RETRY_AGE_MS = 6L * 60L * 60L * 1_000L
		const val MAX_GAP_RECEIPTS = 64
		const val MAX_GAP_AGE_MS = 7L * 24L * 60L * 60L * 1_000L
		const val MAX_GAP_RECEIPT_BYTES = 1_024
		const val NULL_LONG = -1L
		const val NANOS_PER_MILLISECOND = 1_000_000L
		val CALLBACK_ID_PATTERN = Regex("[0-9a-f]{64}")
		// AtomicFile `.new` is an interrupted, never-read-verified write. It did not earn callback
		// ownership and must not keep WorkManager retrying forever after a process crash.
		val CALLBACK_FILE_PATTERN = Regex("activity-callback-([0-9a-f]{64})\\.bin(?:\\.bak)?")
		val GAP_FILE_PATTERN = Regex("activity-gap-[0-9a-f]{64}-[0-9]+\\.bin(?:\\.bak)?")

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
