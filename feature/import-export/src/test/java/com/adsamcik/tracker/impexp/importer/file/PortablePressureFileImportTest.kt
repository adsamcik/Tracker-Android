package com.adsamcik.tracker.impexp.importer.file

import android.content.Context
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.impexp.importer.ImportJobRunner
import com.adsamcik.tracker.impexp.importer.ImportReceiptStore
import com.adsamcik.tracker.impexp.importer.ImportResult
import com.adsamcik.tracker.impexp.portable.encodePressureEntries
import com.adsamcik.tracker.impexp.portable.pressureEntry
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ImportEntryReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportJobReceiptEntity
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.stats.api.repository.ImportPortablePressure
import com.adsamcik.tracker.stats.api.repository.ImportPortablePressureRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortablePressureResult
import com.adsamcik.tracker.stats.api.repository.PortablePressureImportBlockedReason
import com.adsamcik.tracker.stats.api.repository.PortablePressureImportReceipt
import com.adsamcik.tracker.stats.api.repository.PortablePressureImportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.PortablePressureTransferRetryableReason
import com.adsamcik.tracker.stats.api.repository.PressurePortableFormatV1
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PortablePressureFileImportTest {
	private val context = mockk<Context>()
	private val database = mockk<AppDatabase>()

	@Test
	fun `portable extension selects importer managed transactions and the codec file cap`() {
		val importer = PortablePressureFileImport(
			dependenciesProvider = { error("Not invoked") },
		)

		importer.supportedExtensions shouldContainExactly listOf("trackerpressure")
		importer.transactionMode shouldBe ImportTransactionMode.IMPORTER_MANAGED
		PortablePressureFileImport.MAX_FILE_BYTES shouldBe 64L * 1024L * 1024L
		importer.supportedExtensions.single() shouldBe PressurePortableFormatV1.FILE_EXTENSION
	}

	@Test
	fun `duplicate and correction imports use stable content receipts and current epoch`() = runTest {
		val original = pressureEntry("logical", meanHectopascals = 1_000.25)
		val correction = pressureEntry("logical", meanHectopascals = 1_000.5)
		val lifecycle = FakeLifecycleStore(7L)
		val requests = mutableListOf<ImportPortablePressureRequest>()
		var revision = 0L
		var currentChecksum: String? = null
		val importer = adapter(lifecycle) { request ->
			requests += request
			if (request.entry.contentChecksum.value == currentChecksum) {
				ImportPortablePressureResult.Duplicate(revision)
			} else {
				revision++
				currentChecksum = request.entry.contentChecksum.value
				ImportPortablePressureResult.Applied(
					importRevision = revision,
					physicalRunCount = request.entry.runs.size,
					windowCount = request.entry.runs.sumOf { it.windows.size },
				)
			}
		}

		importer.import(
			context,
			database,
			stream(
				encodePressureEntries(listOf(original)),
				jobId = "original-content-job",
				receivedAtMs = 123L,
			),
		) shouldBe ImportResult(successCount = 1)
		importer.import(
			context,
			database,
			stream(
				encodePressureEntries(listOf(original)),
				jobId = "original-content-job",
				receivedAtMs = 123L,
			),
		) shouldBe ImportResult(skippedCount = 1)
		importer.import(
			context,
			database,
			stream(
				encodePressureEntries(listOf(correction)),
				jobId = "corrected-content-job",
				receivedAtMs = 456L,
			),
		) shouldBe ImportResult(successCount = 1)

		requests.map { it.expectedCollectedDataEpoch } shouldContainExactly listOf(7L, 7L, 7L)
		requests[0].receipt shouldBe requests[1].receipt
		requests[2].receipt.entryKey shouldBe requests[0].receipt.entryKey
		requests[0].receipt.jobId shouldBe "original-content-job"
		requests[0].receipt.receivedAtMs shouldBe 123L
		requests[2].receipt.jobId shouldBe "corrected-content-job"
		requests[2].receipt.receivedAtMs shouldBe 456L
		requests.map { it.receipt.sourceName }.distinct() shouldContainExactly
			listOf("pressure.trackerpressure")
	}

	@Test
	fun `durable epoch is read again immediately before every source transaction`() = runTest {
		val entries = listOf(
			pressureEntry("first", 1_000L),
			pressureEntry("second", 5_000L),
		)
		val lifecycle = FakeLifecycleStore(4L)
		val epochs = mutableListOf<Long>()
		val receipts = mutableListOf<PortablePressureImportReceipt>()
		val importer = adapter(lifecycle) { request ->
			epochs += request.expectedCollectedDataEpoch
			receipts += request.receipt
			if (epochs.size == 1) lifecycle.epoch = 5L
			ImportPortablePressureResult.Applied(1L, 1, 1)
		}

		importer.import(context, database, stream(encodePressureEntries(entries))) shouldBe
			ImportResult(successCount = 2)
		epochs shouldContainExactly listOf(4L, 5L)
		receipts.map { it.jobId }.distinct() shouldContainExactly listOf(DEFAULT_JOB_ID)
		receipts.map { it.receivedAtMs }.distinct() shouldContainExactly
			listOf(DEFAULT_RECEIVED_AT_MS)
		receipts[0].entryKey shouldNotBe receipts[1].entryKey
	}

	@Test
	fun `subordinate entry receipt remains bounded stable from the maximum file entry key`() = runTest {
		val lifecycle = FakeLifecycleStore(2L)
		val receipts = mutableListOf<PortablePressureImportReceipt>()
		val importer = adapter(lifecycle) { request ->
			receipts += request.receipt
			ImportPortablePressureResult.Applied(1L, 1, 1)
		}
		val bytes = encodePressureEntries(listOf(pressureEntry()))
		val maximumEntryKey =
			"x".repeat(PressurePortableFormatV1.MAX_IMPORT_RECEIPT_FIELD_LENGTH)

		repeat(2) {
			importer.import(
				context,
				database,
				stream(
					bytes = bytes,
					entryKey = maximumEntryKey,
					jobId = "real-content-job",
					receivedAtMs = 789L,
				),
			)
		}

		receipts[0] shouldBe receipts[1]
		receipts.forEach { receipt ->
			receipt.jobId shouldBe "real-content-job"
			receipt.entryKey.length shouldBe 71
			receipt.sourceName shouldBe "pressure.trackerpressure"
			receipt.receivedAtMs shouldBe 789L
		}
	}

	@Test
	fun `missing durable receipt context fails before stream or source mutation`() = runTest {
		var dependencyCalls = 0
		var opens = 0
		val bytes = encodePressureEntries(listOf(pressureEntry()))
		val importer = PortablePressureFileImport(
			dependenciesProvider = {
				dependencyCalls++
				error("Missing receipt must fail before dependency resolution")
			},
		)
		val unbound = FileImportStream(
			fileName = "pressure.trackerpressure",
			streamProvider = {
				opens++
				ByteArrayInputStream(bytes)
			},
		)

		shouldThrow<PortablePressureImportReceiptContextException> {
			importer.import(context, database, unbound)
		}.let { failure -> failure is IOException shouldBe true }
		dependencyCalls shouldBe 0
		opens shouldBe 0
	}

	@Test
	fun `blocked unverifiable and deletion outcomes remain distinct file results`() = runTest {
		val outcomes = ArrayDeque<ImportPortablePressureResult>().apply {
			add(ImportPortablePressureResult.Blocked(
				PortablePressureImportBlockedReason.DELETED_ENTRY,
			))
			add(ImportPortablePressureResult.Blocked(
				PortablePressureImportBlockedReason.RECEIPT_CONFLICT,
			))
			add(ImportPortablePressureResult.Unverifiable(
				PortablePressureImportUnverifiableReason.ENTRY_INVALID,
			))
			add(ImportPortablePressureResult.Applied(1L, 1, 1))
		}
		val importer = adapter(FakeLifecycleStore(1L)) { outcomes.removeFirst() }

		val result = importer.import(
			context,
			database,
			stream(
				encodePressureEntries(
					listOf(
						pressureEntry("deleted", 1_000L),
						pressureEntry("conflict", 5_000L),
						pressureEntry("invalid", 9_000L),
						pressureEntry("healthy", 13_000L),
					),
				),
			),
		)

		result.successCount shouldBe 1
		result.skippedCount shouldBe 1
		result.failedCount shouldBe 2
		result.errors shouldContainExactly listOf(
			"Portable Pressure entry is protected by retained deletion authority.",
			"Portable Pressure entry was blocked: receipt_conflict.",
			"Portable Pressure entry cannot be verified: entry_invalid.",
		)
	}

	@Test
	fun `retry preserves the real file receipt and cancellation is not converted`() = runTest {
		val bytes = encodePressureEntries(listOf(pressureEntry()))
		val receipts = mutableListOf<PortablePressureImportReceipt>()
		var attempts = 0
		val retryable = adapter(FakeLifecycleStore(1L)) { request ->
			receipts += request.receipt
			attempts++
			if (attempts == 1) {
				ImportPortablePressureResult.RetryableFailure(
					PortablePressureTransferRetryableReason.STORAGE_UNAVAILABLE,
				)
			} else {
				ImportPortablePressureResult.Applied(1L, 1, 1)
			}
		}

		shouldThrow<PortablePressureRetryableImportException> {
			retryable.import(
				context,
				database,
				stream(bytes, jobId = "retry-job", receivedAtMs = 321L),
			)
		}.let { failure -> failure is IOException shouldBe true }
		retryable.import(
			context,
			database,
			stream(bytes, jobId = "retry-job", receivedAtMs = 321L),
		) shouldBe ImportResult(successCount = 1)
		receipts[0] shouldBe receipts[1]

		val cancelled = adapter(FakeLifecycleStore(1L)) {
			throw CancellationException("cancel")
		}
		shouldThrow<CancellationException> {
			cancelled.import(context, database, stream(bytes))
		}
	}

	@Test
	fun `malformed suffix becomes one permanent failure while retaining the applied prefix`() = runTest {
		val entries = listOf(
			pressureEntry("first", 1_000L),
			pressureEntry("second", 5_000L),
		)
		val original = encodePressureEntries(entries).decodeToString()
		val invalid = original.replaceFirst(
			"\"identity\":\"${entries[1].identity.value}\"",
			"\"unexpected\":true,\"identity\":\"${entries[1].identity.value}\"",
		)
		val imported = mutableListOf<ImportPortablePressureRequest>()
		val underlying = CloseTrackingInputStream(invalid.encodeToByteArray())
		val importer = adapter(FakeLifecycleStore(1L)) { request ->
			imported += request
			ImportPortablePressureResult.Applied(1L, 1, 1)
		}

		val result = importer.import(
			context,
			database,
			FileImportStream(underlying, "pressure.trackerpressure")
				.withImportReceipt(DEFAULT_JOB_ID, DEFAULT_RECEIVED_AT_MS),
		)
		result shouldBe ImportResult(
			successCount = 1,
			failedCount = 1,
			errors = listOf(PortablePressureFileImport.PERMANENT_FORMAT_ERROR),
		)
		imported.map { it.entry } shouldContainExactly listOf(entries.first())
		underlying.closed shouldBe false
	}

	@Test
	fun `real job runner retains prefix counts and stable source receipt on malformed retry`() = runTest {
		val entries = listOf(
			pressureEntry("first", 1_000L),
			pressureEntry("second", 5_000L),
		)
		val original = encodePressureEntries(entries).decodeToString()
		val invalid = original.replaceFirst(
			"\"identity\":\"${entries[1].identity.value}\"",
			"\"unexpected\":true,\"identity\":\"${entries[1].identity.value}\"",
		).encodeToByteArray()
		val store = PressureImportReceiptStore()
		val runner = ImportJobRunner(store, nowMs = { 777L })
		runner.start("real-content-job", "pressure.trackerpressure", invalid.size.toLong())
		val receipts = mutableListOf<PortablePressureImportReceipt>()
		val applied = hashSetOf<String>()
		val importer = adapter(FakeLifecycleStore(9L)) { request ->
			receipts += request.receipt
			if (applied.add(request.entry.identity.value)) {
				ImportPortablePressureResult.Applied(1L, 1, 1)
			} else {
				ImportPortablePressureResult.Duplicate(1L)
			}
		}

		suspend fun importThroughRunner(): ImportResult = runner.importSingle(
			jobId = "real-content-job",
			stream = FileImportStream(
				fileName = "pressure.trackerpressure",
				receiptKey = "archive-entry-pressure",
				streamProvider = { ByteArrayInputStream(invalid) },
			),
			transactionMode = ImportTransactionMode.IMPORTER_MANAGED,
		) { boundStream ->
			importer.import(context, database, boundStream)
		}

		importThroughRunner() shouldBe ImportResult(
			successCount = 1,
			failedCount = 1,
			errors = listOf(PortablePressureFileImport.PERMANENT_FORMAT_ERROR),
		)
		importThroughRunner() shouldBe ImportResult(
			skippedCount = 1,
			failedCount = 1,
			errors = listOf(PortablePressureFileImport.PERMANENT_FORMAT_ERROR),
		)
		receipts.size shouldBe 2
		receipts[0] shouldBe receipts[1]
		receipts.first().jobId shouldBe "real-content-job"
		receipts.first().sourceName shouldBe "pressure.trackerpressure"
		receipts.first().receivedAtMs shouldBe 777L
		receipts.first().entryKey.length shouldBe 71
		store.entryStatus("real-content-job", "archive-entry-pressure") shouldBe
			ImportEntryReceiptEntity.STATUS_FAILURE
	}

	@Test
	fun `raw transport IO remains retryable and never becomes a permanent format result`() = runTest {
		var sourceCalls = 0
		val importer = adapter(FakeLifecycleStore(1L)) {
			sourceCalls++
			ImportPortablePressureResult.Applied(1L, 1, 1)
		}
		val stream = FileImportStream(
			fileName = "pressure.trackerpressure",
			streamProvider = { FailingPressureInputStream(IOException("transport failed")) },
		).withImportReceipt(DEFAULT_JOB_ID, DEFAULT_RECEIVED_AT_MS)

		shouldThrow<IOException> {
			importer.import(context, database, stream)
		}.message shouldBe "transport failed"
		sourceCalls shouldBe 0

		val valid = encodePressureEntries(listOf(pressureEntry()))
		val transportEof = EOFException("transport EOF")
		val propagatedEof = shouldThrow<EOFException> {
			importer.import(
				context,
				database,
				FileImportStream(
					fileName = "pressure.trackerpressure",
					streamProvider = {
						PrefixThenFailingPressureInputStream(valid, transportEof)
					},
				).withImportReceipt(DEFAULT_JOB_ID, DEFAULT_RECEIVED_AT_MS),
			)
		}
		(propagatedEof === transportEof) shouldBe true
		sourceCalls shouldBe 1

		val truncated = valid.decodeToString()
			.substringBefore("\"zoneId\"")
			.encodeToByteArray()
		importer.import(
			context,
			database,
			stream(truncated, entryKey = "truncated"),
		) shouldBe ImportResult(
			failedCount = 1,
			errors = listOf(PortablePressureFileImport.PERMANENT_FORMAT_ERROR),
		)
		sourceCalls shouldBe 1
	}

	private fun adapter(
		lifecycle: CollectedDataLifecycleStore,
		block: suspend (ImportPortablePressureRequest) -> ImportPortablePressureResult,
	): PortablePressureFileImport = PortablePressureFileImport(
		dependenciesProvider = {
			PortablePressureImportDependencies(
				importer = object : ImportPortablePressure {
					override suspend fun importEntry(
						request: ImportPortablePressureRequest,
					): ImportPortablePressureResult = block(request)
				},
				lifecycleStore = lifecycle,
			)
		},
	)

	private fun stream(
		bytes: ByteArray,
		entryKey: String = "direct-source-v1",
		jobId: String = DEFAULT_JOB_ID,
		receivedAtMs: Long = DEFAULT_RECEIVED_AT_MS,
	): FileImportStream = FileImportStream(
		fileName = "pressure.trackerpressure",
		receiptKey = entryKey,
		streamProvider = { ByteArrayInputStream(bytes) },
	).withImportReceipt(jobId, receivedAtMs)

	private companion object {
		const val DEFAULT_JOB_ID = "content-addressed-job"
		const val DEFAULT_RECEIVED_AT_MS = 100L
	}
}

private class FakeLifecycleStore(initialEpoch: Long) : CollectedDataLifecycleStore {
	private val state = MutableStateFlow(CollectedDataLifecycleSnapshot(initialEpoch, null))
	var epoch: Long
		get() = state.value.epoch
		set(value) {
			state.value = CollectedDataLifecycleSnapshot(value, state.value.retainedFromMs)
		}

	override val snapshots: Flow<CollectedDataLifecycleSnapshot> = state

	override suspend fun snapshot(): CollectedDataLifecycleSnapshot = state.value

	override suspend fun beginFullDeletion(deletedAtMs: Long): CollectedDataLifecycleSnapshot =
		CollectedDataLifecycleSnapshot(epoch + 1L, deletedAtMs).also { state.value = it }

	override suspend fun advanceRetainedFrom(
		retainedFromMs: Long,
	): CollectedDataLifecycleSnapshot =
		CollectedDataLifecycleSnapshot(epoch, retainedFromMs).also { state.value = it }
}

private class CloseTrackingInputStream(
	bytes: ByteArray,
) : InputStream() {
	private val delegate = ByteArrayInputStream(bytes)
	var closed = false

	override fun read(): Int = delegate.read()
	override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
		delegate.read(buffer, offset, length)

	override fun close() {
		closed = true
		delegate.close()
	}
}

private class FailingPressureInputStream(
	private val failure: IOException,
) : InputStream() {
	override fun read(): Int = throw failure
	override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw failure
}

private class PrefixThenFailingPressureInputStream(
	bytes: ByteArray,
	private val failure: IOException,
) : InputStream() {
	private val delegate = ByteArrayInputStream(bytes)

	override fun read(): Int = delegate.read().takeUnless { it < 0 } ?: throw failure

	override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
		delegate.read(buffer, offset, length).takeUnless { it < 0 } ?: throw failure
}

private class PressureImportReceiptStore : ImportReceiptStore {
	private val jobs = mutableMapOf<String, ImportJobReceiptEntity>()
	private val entries = mutableMapOf<Pair<String, String>, ImportEntryReceiptEntity>()

	override suspend fun <T> transaction(block: suspend () -> T): T = block()

	override suspend fun getJob(jobId: String): ImportJobReceiptEntity? = jobs[jobId]

	override suspend fun insertJob(job: ImportJobReceiptEntity) {
		jobs.putIfAbsent(job.jobId, job)
	}

	override suspend fun markJobInProgress(
		jobId: String,
		sourceName: String,
		sourceSizeBytes: Long,
		updatedAt: Long,
	) {
		jobs[jobId] = jobs.getValue(jobId).copy(
			sourceName = sourceName,
			sourceSizeBytes = sourceSizeBytes,
			status = ImportJobReceiptEntity.STATUS_IN_PROGRESS,
			completedAt = null,
			updatedAt = updatedAt,
		)
	}

	override suspend fun markJobComplete(jobId: String, completedAt: Long) {
		jobs[jobId] = jobs.getValue(jobId).copy(
			status = ImportJobReceiptEntity.STATUS_COMPLETE,
			completedAt = completedAt,
			updatedAt = completedAt,
		)
	}

	override suspend fun getEntry(
		jobId: String,
		entryKey: String,
	): ImportEntryReceiptEntity? = entries[jobId to entryKey]

	override suspend fun putEntry(entry: ImportEntryReceiptEntity) {
		entries[entry.jobId to entry.entryKey] = entry
	}

	fun entryStatus(jobId: String, entryKey: String): String? =
		entries[jobId to entryKey]?.status
}
