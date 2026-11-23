package com.adsamcik.tracker.activity.receiver

import android.content.Context
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEvidenceBatch
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
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
	val originalMetadata: ActivityCallbackRetryMetadata? = null,
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
	val originalMetadata: ActivityCallbackRetryMetadata? = null,
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
	private val deleteOrphanFile: (File) -> Boolean = File::delete,
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
		require(batch.eventCount in 1..ActivityCallbackRetryCodec.MAX_EVENTS_PER_CALLBACK) {
			"Activity callback member count is outside the durable retry bound"
		}
		val durableBatch = batch.copy(liveStartCommandGeneration = null)
		val payload = ActivityCallbackRetryCodec.encodeV2(durableBatch)
		val id = ActivityCallbackRetryCodec.callbackId(payload)
		val encoded = ActivityCallbackRetryCodec.encodeRetryRecord(
			id, payload, ActivityCallbackRetryMetadata.from(durableBatch),
		)
		if (encoded.size > maxCallbackBytes) {
			throw ActivityCallbackRetryStoreException(
				"Activity callback exceeds the durable retry record bound",
				code = ActivityCallbackGapCode.RETRY_RECORD_TOO_LARGE,
			)
		}
		ensureDirectory()
		val pendingFiles = pendingCallbackFiles()
		val target = callbackFile(id)
		if (target.exists() || backupFile(target).exists()) {
			val existing = load(id)
			if (existing != durableBatch) {
				throw ActivityCallbackRetryStoreException(
					"Activity retry identity resolved to different callback bytes",
					code = ActivityCallbackGapCode.RETRY_RECORD_CORRUPT,
				)
			}
			return id
		}
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
		if (load(id) != durableBatch) {
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
		val encoded = readAtomic(
			base = base,
			maximumBytes = maxCallbackBytes,
			readFailureCode = ActivityCallbackGapCode.RETRY_STORAGE_UNAVAILABLE,
		)
		val batch = ActivityCallbackRetryCodec.decodeRetryRecord(id, encoded)
		val metadata = ActivityCallbackRetryMetadata.from(batch)
		val currentClockDomain = try {
			currentClockDomainId()
		} catch (error: Exception) {
			throw ActivityCallbackRetryStoreException(
				"Unable to resolve the Activity callback retry clock domain",
				error,
			)
		}
		if (currentClockDomain != metadata.registrationIdentity.clockDomainId) {
			throw ActivityCallbackRetryStoreException(
				"Activity callback retry belongs to an earlier boot clock domain",
				code = ActivityCallbackGapCode.RETRY_CLOCK_DOMAIN_CHANGED,
				originalMetadata = metadata,
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
				originalMetadata = metadata,
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
		originalMetadata: ActivityCallbackRetryMetadata? = batch?.let { ActivityCallbackRetryMetadata.from(it) },
	): Boolean {
		require(CALLBACK_ID_PATTERN.matches(callbackId))
		return try {
			ensureDirectory()
			pruneGapReceipts()
			val receipt = ActivityCallbackGapReceipt(
				callbackId = callbackId,
				code = code,
				receivedWallTimeMs = originalMetadata?.receivedWallTimeMs,
				registrationGeneration = originalMetadata?.registrationIdentity?.registrationGeneration,
				collectedDataEpoch = originalMetadata?.registrationIdentity?.collectedDataEpoch,
				recordedAtMs = nowWallTimeMs().coerceAtLeast(0L),
				originalMetadata = originalMetadata,
			)
			val target = gapFile(receipt)
			if (verifiedGapExists(target, receipt)) {
				return true
			}
			reserveGapCapacity(target)
			// A corrupt or older unscoped receipt is atomically repaired before discarding its retry.
			val bytes = ActivityCallbackGapReceiptCodec.encode(receipt)
			writeAtomic(target, bytes, "Unable to persist Activity callback gap receipt")
			ActivityCallbackGapReceiptCodec.decode(readAtomic(target, MAX_GAP_RECEIPT_BYTES)) == receipt
		} catch (_: Exception) {
			false
		}
	}

	private fun verifiedGapExists(target: File, receipt: ActivityCallbackGapReceipt): Boolean {
		if (!target.exists() && !backupFile(target).exists()) {
			return false
		}
		val existing = runCatching {
			ActivityCallbackGapReceiptCodec.decode(readAtomic(target, MAX_GAP_RECEIPT_BYTES))
		}.getOrNull() ?: return false
		if (existing != receipt.copy(recordedAtMs = existing.recordedAtMs)) {
			return false
		}
		syncAfterMutation("Unable to verify existing Activity callback gap durability")
		return true
	}

	private fun reserveGapCapacity(target: File) {
		if (target.exists() || backupFile(target).exists()) {
			return
		}
		val existing = gapFiles().sortedBy { it.file.lastModified() }
		val excess = existing.size - maxGapReceipts + 1
		if (excess > 0) {
			existing.take(excess).forEach { gap -> AtomicFile(gap.file).delete() }
			syncAfterMutation("Unable to sync bounded Activity callback gap receipts")
		}
	}

	@Synchronized
	fun recordGap(
		batch: ActivityRecognitionEvidenceBatch,
		code: ActivityCallbackGapCode,
	): Boolean = recordGap(
		callbackId = ActivityCallbackRetryCodec.callbackId(ActivityCallbackRetryCodec.encodeV2(batch)),
		code = code,
		batch = batch,
	)

	@Synchronized
	fun gapReceipts(): List<ActivityCallbackGapReceipt> {
		if (!directory.isDirectory) return emptyList()
		pruneGapReceipts()
		return gapFiles().mapNotNull { gap ->
			runCatching {
				ActivityCallbackGapReceiptCodec.decode(readAtomic(gap.file, MAX_GAP_RECEIPT_BYTES))
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
		val files = listCallbackDirectory()
		val orphanFiles = files.filter { CALLBACK_ORPHAN_FILE_PATTERN.matches(it.name) }
		if (orphanFiles.isNotEmpty()) {
			orphanFiles.forEach { orphan ->
				try {
					deleteOrphanFile(orphan)
				} catch (error: Exception) {
					throw ActivityCallbackRetryStoreException(
						"Unable to remove interrupted Activity callback retry write",
						error,
					)
				}
				if (orphan.exists()) {
					throw ActivityCallbackRetryStoreException(
						"Unable to remove interrupted Activity callback retry write",
					)
				}
			}
			syncAfterMutation("Unable to sync interrupted Activity callback retry cleanup")
		}
		return listCallbackDirectory().mapNotNull { file ->
			val match = CALLBACK_FILE_PATTERN.matchEntire(file.name) ?: return@mapNotNull null
			CallbackFile(match.groupValues[1], file)
		}
	}

	private fun listCallbackDirectory(): Array<File> = directory.listFiles()
		?: throw ActivityCallbackRetryStoreException(
			"Unable to inventory Activity callback retry storage",
		)

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

	private data class CallbackFile(val id: String, val file: File)
	private data class GapFile(val file: File)

	private companion object {
		const val DIRECTORY_NAME = "activity-callback-retry-v1"
		const val MAX_PENDING_CALLBACKS = 64
		const val MAX_CALLBACK_BYTES = 32 * 1_024
		const val MAX_PENDING_BYTES = 512L * 1_024L
		const val MAX_RETRY_AGE_MS = 6L * 60L * 60L * 1_000L
		const val MAX_GAP_RECEIPTS = 64
		const val MAX_GAP_AGE_MS = 7L * 24L * 60L * 60L * 1_000L
		const val MAX_GAP_RECEIPT_BYTES = 16 * 1_024
		const val NANOS_PER_MILLISECOND = 1_000_000L
		val CALLBACK_ID_PATTERN = Regex("[0-9a-f]{64}")
		val CALLBACK_FILE_PATTERN = Regex("activity-callback-([0-9a-f]{64})\\.bin(?:\\.bak)?")
		// AtomicFile `.new` is an interrupted, never-read-verified write. It did not earn callback
		// ownership and must be removed before another callback can consume bounded spool capacity.
		val CALLBACK_ORPHAN_FILE_PATTERN =
			Regex("activity-callback-[0-9a-f]{64}\\.bin\\.new")
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
