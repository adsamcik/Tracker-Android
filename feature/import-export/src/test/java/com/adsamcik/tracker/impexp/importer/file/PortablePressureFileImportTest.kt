package com.adsamcik.tracker.impexp.importer.file

import android.content.Context
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.impexp.importer.ImportResult
import com.adsamcik.tracker.impexp.portable.PortablePressureJsonException
import com.adsamcik.tracker.impexp.portable.encodePressureEntries
import com.adsamcik.tracker.impexp.portable.pressureEntry
import com.adsamcik.tracker.shared.base.database.AppDatabase
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

		importer.import(context, database, stream(encodePressureEntries(listOf(original)))) shouldBe
			ImportResult(successCount = 1)
		importer.import(context, database, stream(encodePressureEntries(listOf(original)))) shouldBe
			ImportResult(skippedCount = 1)
		importer.import(context, database, stream(encodePressureEntries(listOf(correction)))) shouldBe
			ImportResult(successCount = 1)

		requests.map { it.expectedCollectedDataEpoch } shouldContainExactly listOf(7L, 7L, 7L)
		requests[0].receipt shouldBe requests[1].receipt
		requests[2].receipt.entryKey shouldBe requests[0].receipt.entryKey
		requests[2].receipt.jobId shouldNotBe requests[0].receipt.jobId
		requests.forEach { request ->
			request.receipt.sourceName shouldBe "pressure.trackerpressure"
			request.receipt.receivedAtMs shouldBe 0L
		}
	}

	@Test
	fun `durable epoch is read again immediately before every source transaction`() = runTest {
		val entries = listOf(
			pressureEntry("first", 1_000L),
			pressureEntry("second", 5_000L),
		)
		val lifecycle = FakeLifecycleStore(4L)
		val epochs = mutableListOf<Long>()
		val importer = adapter(lifecycle) { request ->
			epochs += request.expectedCollectedDataEpoch
			if (epochs.size == 1) lifecycle.epoch = 5L
			ImportPortablePressureResult.Applied(1L, 1, 1)
		}

		importer.import(context, database, stream(encodePressureEntries(entries))) shouldBe
			ImportResult(successCount = 2)
		epochs shouldContainExactly listOf(4L, 5L)
	}

	@Test
	fun `receipt fields remain bounded stable for oversized file metadata`() = runTest {
		val lifecycle = FakeLifecycleStore(2L)
		val receipts = mutableListOf<PortablePressureImportReceipt>()
		val importer = adapter(lifecycle) { request ->
			receipts += request.receipt
			ImportPortablePressureResult.Applied(1L, 1, 1)
		}
		val bytes = encodePressureEntries(listOf(pressureEntry()))
		val oversized = "x".repeat(PressurePortableFormatV1.MAX_IMPORT_RECEIPT_FIELD_LENGTH + 1)

		repeat(2) {
			importer.import(
				context,
				database,
				FileImportStream(
					fileName = oversized,
					receiptKey = oversized,
					streamProvider = { ByteArrayInputStream(bytes) },
				),
			)
		}

		receipts[0] shouldBe receipts[1]
		receipts.forEach { receipt ->
			receipt.jobId.length shouldBe 71
			receipt.entryKey.length shouldBe 71
			receipt.sourceName.length shouldBe 71
		}
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
	fun `retryable source refusal is an IOException and cancellation is not converted`() = runTest {
		val bytes = encodePressureEntries(listOf(pressureEntry()))
		val retryable = adapter(FakeLifecycleStore(1L)) {
			ImportPortablePressureResult.RetryableFailure(
				PortablePressureTransferRetryableReason.STORAGE_UNAVAILABLE,
			)
		}

		shouldThrow<PortablePressureRetryableImportException> {
			retryable.import(context, database, stream(bytes))
		}.let { failure -> failure is IOException shouldBe true }

		val cancelled = adapter(FakeLifecycleStore(1L)) {
			throw CancellationException("cancel")
		}
		shouldThrow<CancellationException> {
			cancelled.import(context, database, stream(bytes))
		}
	}

	@Test
	fun `malformed suffix leaves only a replay safe authenticated prefix and stream open`() = runTest {
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

		shouldThrow<PortablePressureJsonException> {
			importer.import(
				context,
				database,
				FileImportStream(underlying, "pressure.trackerpressure"),
			)
		}
		imported.map { it.entry } shouldContainExactly listOf(entries.first())
		underlying.closed shouldBe false
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

	private fun stream(bytes: ByteArray): FileImportStream = FileImportStream(
		ByteArrayInputStream(bytes),
		"pressure.trackerpressure",
	)
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
