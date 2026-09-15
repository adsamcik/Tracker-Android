package com.adsamcik.tracker.impexp.importer.file

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.impexp.importer.ImportJobRunner
import com.adsamcik.tracker.impexp.importer.ImportReceiptStore
import com.adsamcik.tracker.impexp.importer.ImportResult
import com.adsamcik.tracker.impexp.importer.archive.ArchiveEntryMetadata
import com.adsamcik.tracker.impexp.importer.archive.ArchiveExtractor
import com.adsamcik.tracker.impexp.portable.ambientArchive
import com.adsamcik.tracker.impexp.portable.completeAmbientDay
import com.adsamcik.tracker.impexp.portable.encodeAmbientStepsArchive
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ImportEntryReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportJobReceiptEntity
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientSteps
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientStepsRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientStepsResult
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsImportBlockedReason
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsImportReceipt
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsImportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsTransferRetryableReason
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.time.LocalDate
import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PortableAmbientStepsFileImportTest {
	private val context = mockk<Context>()
	private val database = mockk<AppDatabase>()

	@Test
	fun `complete archive uses exact metadata durable receipt and current epoch`() = runTest {
		val archive = ambientArchive(
			completeAmbientDay(LocalDate.of(2026, 1, 1), 0L),
			completeAmbientDay(LocalDate.of(2026, 1, 2), 12L),
		)
		val bytes = encodeAmbientStepsArchive(archive)
		val requests = mutableListOf<ImportPortableAmbientStepsRequest>()
		val importer = adapter(FakeAmbientLifecycleStore(7L)) { request ->
			requests += request
			ImportPortableAmbientStepsResult.Applied(
				request.archive.identity,
				appendedDayRevisionCount = 2,
				dayCount = 2,
				factCount = 2,
				gapCount = 0,
			)
		}

		importer.import(
			context,
			database,
			stream(bytes, "archive-entry", "content-job", 900L),
		) shouldBe ImportResult(successCount = 2)

		val request = requests.single()
		request.archive shouldBe archive
		request.expectedCollectedDataEpoch shouldBe 7L
		request.metadata.encodedByteCount shouldBe bytes.size.toLong()
		request.metadata.archiveContentChecksum shouldBe archive.contentChecksum
		request.metadata.dayCount shouldBe 2
		request.metadata.factCount shouldBe 2
		request.metadata.gapCount shouldBe 0
		request.receipt.jobId shouldBe "content-job"
		request.receipt.sourceName shouldBe "ambient.trackerambientsteps"
		request.receipt.receivedAtMs shouldBe 900L
		request.receipt.archiveKey.length shouldBe 64
		importer.supportedExtensions shouldContainExactly listOf("trackerambientsteps")
		importer.transactionMode shouldBe ImportTransactionMode.IMPORTER_MANAGED
	}

	@Test
	fun `duplicate correction deletion fence and unverifiable outcomes remain distinct`() = runTest {
		val archive = ambientArchive(
			completeAmbientDay(LocalDate.of(2026, 1, 1), 2L),
		)
		val outcomes = ArrayDeque<ImportPortableAmbientStepsResult>().apply {
			add(ImportPortableAmbientStepsResult.Duplicate(archive.identity, 1))
			add(ImportPortableAmbientStepsResult.Applied(archive.identity, 1, 1, 1, 0))
			add(ImportPortableAmbientStepsResult.Blocked(
				PortableAmbientStepsImportBlockedReason.DELETED_DAY,
			))
			add(ImportPortableAmbientStepsResult.Unverifiable(
				PortableAmbientStepsImportUnverifiableReason.ARCHIVE_INVALID,
			))
		}
		val importer = adapter(FakeAmbientLifecycleStore(1L)) { outcomes.removeFirst() }
		val bytes = encodeAmbientStepsArchive(archive)

		importer.import(context, database, stream(bytes, "one")) shouldBe
			ImportResult(skippedCount = 1)
		importer.import(context, database, stream(bytes, "two")) shouldBe
			ImportResult(successCount = 1)
		importer.import(context, database, stream(bytes, "three")) shouldBe ImportResult(
			skippedCount = 1,
			errors = listOf(
				"Portable Ambient Steps archive is protected by deleted_day.",
			),
		)
		importer.import(context, database, stream(bytes, "four")) shouldBe ImportResult(
			failedCount = 1,
			errors = listOf(
				"Portable Ambient Steps archive cannot be verified: archive_invalid.",
			),
		)
	}

	@Test
	fun `malformed file is terminal before source mutation while raw IO retry and cancellation escape`() =
		runTest {
			var calls = 0
			val importer = adapter(FakeAmbientLifecycleStore(1L)) {
				calls++
				ImportPortableAmbientStepsResult.Duplicate(it.archive.identity, it.archive.days.size)
			}
			importer.import(
				context,
				database,
				stream("{not-json".encodeToByteArray(), "malformed"),
			) shouldBe ImportResult(
				failedCount = 1,
				errors = listOf(PortableAmbientStepsFileImport.PERMANENT_FORMAT_ERROR),
			)
			calls shouldBe 0

			shouldThrow<IOException> {
				importer.import(
					context,
					database,
					FileImportStream(
						fileName = "ambient.trackerambientsteps",
						streamProvider = { FailingAmbientImportStream() },
					).withImportReceipt("job", 1L),
				)
			}.message shouldBe "transport failed"
			calls shouldBe 0

			val archive = ambientArchive(
				completeAmbientDay(LocalDate.of(2026, 1, 1), 1L),
			)
			val valid = encodeAmbientStepsArchive(archive)
			val transportEof = EOFException("transport EOF")
			val propagatedEof = shouldThrow<EOFException> {
				importer.import(
					context,
					database,
					FileImportStream(
						fileName = "ambient.trackerambientsteps",
						streamProvider = {
							PrefixThenFailingAmbientImportStream(valid, transportEof)
						},
					).withImportReceipt("eof-job", 2L),
				)
			}
			(propagatedEof === transportEof) shouldBe true
			calls shouldBe 0
			importer.import(
				context,
				database,
				stream(valid.copyOf(valid.size - 1), "truncated"),
			) shouldBe ImportResult(
				failedCount = 1,
				errors = listOf(PortableAmbientStepsFileImport.PERMANENT_FORMAT_ERROR),
			)
			calls shouldBe 0

			val cancelled = adapter(FakeAmbientLifecycleStore(1L)) {
				throw CancellationException("cancelled")
			}
			shouldThrow<CancellationException> {
				cancelled.import(
					context,
					database,
					stream(encodeAmbientStepsArchive(archive), "cancel"),
				)
			}
		}

	@Test
	fun `out of domain structural date is permanent before source mutation`() = runTest {
		val archive = ambientArchive(
			completeAmbientDay(LocalDate.of(2026, 1, 1), 1L),
		)
		val day = archive.days.single()
		val invalid = encodeAmbientStepsArchive(archive).decodeToString()
			.replaceFirst(
				"\"structuralEpochDay\":${day.structuralEpochDay}",
				"\"structuralEpochDay\":${Long.MAX_VALUE}",
			)
			.encodeToByteArray()
		var calls = 0
		val importer = adapter(FakeAmbientLifecycleStore(1L)) {
			calls++
			ImportPortableAmbientStepsResult.Duplicate(it.archive.identity, it.archive.days.size)
		}

		importer.import(
			context,
			database,
			stream(invalid, "invalid-date"),
		) shouldBe ImportResult(
			failedCount = 1,
			errors = listOf(PortableAmbientStepsFileImport.PERMANENT_FORMAT_ERROR),
		)
		calls shouldBe 0
	}

	@Test
	fun `missing receipt fails before decode dependencies or stream open`() = runTest {
		var providers = 0
		var opens = 0
		val importer = PortableAmbientStepsFileImport(
			dependenciesProvider = {
				providers++
				error("Receipt must fail first")
			},
		)
		val stream = FileImportStream(
			fileName = "ambient.trackerambientsteps",
			streamProvider = {
				opens++
				ByteArrayInputStream(byteArrayOf())
			},
		)

		shouldThrow<PortableAmbientStepsImportReceiptContextException> {
			importer.import(context, database, stream)
		}
		providers shouldBe 0
		opens shouldBe 0
	}

	@Test
	fun `successful direct import leaves the caller owned stream open`() = runTest {
		val archive = ambientArchive(
			completeAmbientDay(LocalDate.of(2026, 1, 1), 1L),
		)
		val underlying = CloseTrackingAmbientImportStream(encodeAmbientStepsArchive(archive))
		val importer = adapter(FakeAmbientLifecycleStore(1L)) { request ->
			ImportPortableAmbientStepsResult.Applied(request.archive.identity, 1, 1, 1, 0)
		}

		importer.import(
			context,
			database,
			FileImportStream(underlying, "ambient.trackerambientsteps")
				.withImportReceipt("job", 1L),
		) shouldBe ImportResult(successCount = 1)
		underlying.closed shouldBe false
	}

	@Test
	fun `actual archive runner retains successful file prefix and retries only failed entry`() = runTest {
		val archive = ambientArchive(
			completeAmbientDay(LocalDate.of(2026, 1, 1), 1L),
			completeAmbientDay(LocalDate.of(2026, 1, 2), 2L),
		)
		val valid = encodeAmbientStepsArchive(archive)
		val invalid = valid.copyOf(valid.size - 1)
		val store = AmbientImportReceiptStore()
		val runner = ImportJobRunner(store, nowMs = { 700L })
		runner.start("archive-job", "backup.zip", (valid.size + invalid.size).toLong())
		val requests = mutableListOf<ImportPortableAmbientStepsRequest>()
		val importer = adapter(FakeAmbientLifecycleStore(5L)) { request ->
			requests += request
			ImportPortableAmbientStepsResult.Applied(
				request.archive.identity,
				2,
				2,
				2,
				0,
			)
		}
		val extractor = FixedAmbientArchiveExtractor(
			listOf(
				AmbientArchiveEntry("first.trackerambientsteps", "entry-1", valid),
				AmbientArchiveEntry("second.trackerambientsteps", "entry-2", invalid),
			),
		)
		val file = mockk<DocumentFile> {
			io.mockk.every { this@mockk.name } returns "backup.zip"
		}

		suspend fun runArchive(): ImportResult = runner.importArchive(
			jobId = "archive-job",
			context = context,
			file = file,
			extractor = extractor,
			transactionModeForEntry = { ImportTransactionMode.IMPORTER_MANAGED },
		) { bound ->
			importer.import(context, database, bound)
		}

		runArchive() shouldBe ImportResult(
			successCount = 2,
			failedCount = 1,
			errors = listOf(PortableAmbientStepsFileImport.PERMANENT_FORMAT_ERROR),
		)
		runArchive() shouldBe ImportResult(
			successCount = 2,
			failedCount = 1,
			errors = listOf(PortableAmbientStepsFileImport.PERMANENT_FORMAT_ERROR),
		)
		requests.size shouldBe 1
		requests.single().receipt.jobId shouldBe "archive-job"
		requests.single().receipt.sourceName shouldBe "first.trackerambientsteps"
		requests.single().receipt.receivedAtMs shouldBe 700L
		store.entryStatus("archive-job", "entry-1") shouldBe
			ImportEntryReceiptEntity.STATUS_SUCCESS
		store.entryStatus("archive-job", "entry-2") shouldBe
			ImportEntryReceiptEntity.STATUS_FAILURE
	}

	@Test
	fun `actual runner preserves source receipt across retryable import replay`() = runTest {
		val archive = ambientArchive(
			completeAmbientDay(LocalDate.of(2026, 1, 1), 1L),
		)
		val bytes = encodeAmbientStepsArchive(archive)
		val store = AmbientImportReceiptStore()
		val runner = ImportJobRunner(store, nowMs = { 300L })
		runner.start("retry-job", "ambient.trackerambientsteps", bytes.size.toLong())
		val receipts = mutableListOf<PortableAmbientStepsImportReceipt>()
		var attempt = 0
		val importer = adapter(FakeAmbientLifecycleStore(2L)) { request ->
			receipts += request.receipt
			attempt++
			if (attempt == 1) {
				ImportPortableAmbientStepsResult.RetryableFailure(
					PortableAmbientStepsTransferRetryableReason.STORAGE_UNAVAILABLE,
				)
			} else {
				ImportPortableAmbientStepsResult.Applied(request.archive.identity, 1, 1, 1, 0)
			}
		}

		suspend fun runImport(): ImportResult = runner.importSingle(
			jobId = "retry-job",
			stream = FileImportStream(
				fileName = "ambient.trackerambientsteps",
				receiptKey = "direct-entry",
				streamProvider = { ByteArrayInputStream(bytes) },
			),
			transactionMode = ImportTransactionMode.IMPORTER_MANAGED,
		) { bound -> importer.import(context, database, bound) }

		shouldThrow<PortableAmbientStepsRetryableImportException> { runImport() }
		runImport() shouldBe ImportResult(successCount = 1)
		receipts[0] shouldBe receipts[1]
	}

	private fun adapter(
		lifecycle: CollectedDataLifecycleStore,
		block: suspend (ImportPortableAmbientStepsRequest) -> ImportPortableAmbientStepsResult,
	): PortableAmbientStepsFileImport = PortableAmbientStepsFileImport(
		dependenciesProvider = {
			PortableAmbientStepsImportDependencies(
				importer = object : ImportPortableAmbientSteps {
					override suspend fun importArchive(
						request: ImportPortableAmbientStepsRequest,
					): ImportPortableAmbientStepsResult = block(request)
				},
				lifecycleStore = lifecycle,
			)
		},
	)

	private fun stream(
		bytes: ByteArray,
		entryKey: String,
		jobId: String = "content-job",
		receivedAtMs: Long = 100L,
	): FileImportStream = FileImportStream(
		fileName = "ambient.trackerambientsteps",
		receiptKey = entryKey,
		streamProvider = { ByteArrayInputStream(bytes) },
	).withImportReceipt(jobId, receivedAtMs)
}

private data class AmbientArchiveEntry(
	val fileName: String,
	val receiptKey: String,
	val bytes: ByteArray,
)

private class FixedAmbientArchiveExtractor(
	private val entries: List<AmbientArchiveEntry>,
) : ArchiveExtractor {
	override val supportedExtensions: Collection<String> = listOf("zip")

	override suspend fun extract(
		context: Context,
		file: DocumentFile,
		shouldExtract: suspend (ArchiveEntryMetadata) -> Boolean,
		consume: suspend (FileImportStream) -> Unit,
	): Boolean {
		entries.forEach { entry ->
			val metadata = ArchiveEntryMetadata(entry.receiptKey, entry.fileName)
			if (shouldExtract(metadata)) {
				FileImportStream(
					fileName = entry.fileName,
					receiptKey = entry.receiptKey,
					streamProvider = { ByteArrayInputStream(entry.bytes) },
				).use { consume(it) }
			}
		}
		return true
	}
}

private class FakeAmbientLifecycleStore(initialEpoch: Long) : CollectedDataLifecycleStore {
	private val state = MutableStateFlow(CollectedDataLifecycleSnapshot(initialEpoch, null))
	override val snapshots: Flow<CollectedDataLifecycleSnapshot> = state
	override suspend fun snapshot(): CollectedDataLifecycleSnapshot = state.value
	override suspend fun beginFullDeletion(deletedAtMs: Long): CollectedDataLifecycleSnapshot =
		error("Not used")
	override suspend fun advanceRetainedFrom(retainedFromMs: Long): CollectedDataLifecycleSnapshot =
		error("Not used")
}

private class FailingAmbientImportStream : InputStream() {
	override fun read(): Int = throw IOException("transport failed")
	override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
		throw IOException("transport failed")
}

private class PrefixThenFailingAmbientImportStream(
	bytes: ByteArray,
	private val failure: IOException,
) : InputStream() {
	private val delegate = ByteArrayInputStream(bytes)

	override fun read(): Int = delegate.read().takeUnless { it < 0 } ?: throw failure

	override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
		delegate.read(buffer, offset, length).takeUnless { it < 0 } ?: throw failure
}

private class CloseTrackingAmbientImportStream(
	bytes: ByteArray,
) : ByteArrayInputStream(bytes) {
	var closed = false

	override fun close() {
		closed = true
		super.close()
	}
}

private class AmbientImportReceiptStore : ImportReceiptStore {
	private val jobs = linkedMapOf<String, ImportJobReceiptEntity>()
	private val entries = linkedMapOf<Pair<String, String>, ImportEntryReceiptEntity>()

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
		jobs[jobId] = requireNotNull(jobs[jobId]).copy(
			sourceName = sourceName,
			sourceSizeBytes = sourceSizeBytes,
			status = ImportJobReceiptEntity.STATUS_IN_PROGRESS,
			completedAt = null,
			updatedAt = updatedAt,
		)
	}
	override suspend fun markJobComplete(jobId: String, completedAt: Long) {
		jobs[jobId] = requireNotNull(jobs[jobId]).copy(
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
