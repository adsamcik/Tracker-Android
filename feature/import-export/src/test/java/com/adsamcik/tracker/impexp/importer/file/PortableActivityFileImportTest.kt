package com.adsamcik.tracker.impexp.importer.file

import android.content.Context
import android.database.sqlite.SQLiteException
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.impexp.importer.ImportJobRunner
import com.adsamcik.tracker.impexp.importer.ImportReceiptStore
import com.adsamcik.tracker.impexp.portable.PortableActivityJsonV1Codec
import com.adsamcik.tracker.impexp.portable.activityEntry
import com.adsamcik.tracker.impexp.portable.activityEnvelope
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ImportPortableCapturedActivity
import com.adsamcik.tracker.shared.base.database.ImportPortableCapturedActivityRequest
import com.adsamcik.tracker.shared.base.database.ImportPortableCapturedActivityResult
import com.adsamcik.tracker.shared.base.database.PortableActivityImportBlockedReason
import com.adsamcik.tracker.shared.base.database.PortableActivityTransferRetryableReason
import com.adsamcik.tracker.shared.base.database.data.ImportEntryReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportJobReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PortableActivityFileImportTest {
	@Test
	fun `complete file decodes before mutation and uses current durable epoch`() = runTest {
		val requests = mutableListOf<ImportPortableCapturedActivityRequest>()
		val importer = PortableActivityFileImport(
			importerProvider = {
				fakeImporter { request ->
					requests += request
					ImportPortableCapturedActivityResult.Applied(1L, 1, 1, 1)
				}
			},
		)
		val envelope = activityEnvelope(
			activityEntry("first", 1_000L),
			activityEntry("second", 5_000L),
		)

		val result = importer.import(
			context = mockk<Context>(relaxed = true),
			database = database(epoch = 7L),
			stream = stream(
				encode(envelope),
				"activity.trackeractivity",
				"receipt-a",
				jobId = "content-job",
				receivedAtMs = 9_000L,
			),
		)

		result.successCount shouldBe 2
		requests shouldHaveSize 2
		requests.all { it.expectedCollectedDataEpoch == 7L } shouldBe true
		requests.map { it.receipt.entryKey }.distinct().size shouldBe 2
		requests.all { it.receipt.entryKey.length == 64 } shouldBe true
		requests.map { it.receipt.jobId }.toSet() shouldBe setOf("content-job")
		requests.map { it.receipt.sourceName }.toSet() shouldBe
			setOf("activity.trackeractivity")
		requests.map { it.receipt.receivedAtMs }.toSet() shouldBe setOf(9_000L)
		importer.supportedExtensions shouldBe listOf(PortableActivityFileImport.EXTENSION)
		importer.transactionMode shouldBe ImportTransactionMode.IMPORTER_MANAGED
	}

	@Test
	fun `malformed or truncated document cannot invoke source mutation`() = runTest {
		var calls = 0
		val importer = PortableActivityFileImport(
			importerProvider = {
				fakeImporter {
					calls++
					ImportPortableCapturedActivityResult.Duplicate(1L)
				}
			},
		)
		val truncated = encode(activityEnvelope(activityEntry())).dropLast(1).toByteArray()

		importer.import(
			mockk(relaxed = true),
			database(epoch = 3L),
			stream(truncated, "activity.trackeractivity", "receipt"),
		).failedCount shouldBe 1
		calls shouldBe 0
	}

	@Test
	fun `file adapter preserves raw EOF identity but keeps lexical violation permanent`() = runTest {
		var calls = 0
		val importer = PortableActivityFileImport {
			fakeImporter {
				calls++
				ImportPortableCapturedActivityResult.Duplicate(1L)
			}
		}
		val bytes = encode(activityEnvelope(activityEntry()))
		val original = EOFException("transport interrupted")
		val rawFailure = shouldThrow<EOFException> {
			importer.import(
				mockk(relaxed = true),
				database(epoch = 1L),
				FileImportStream(
					fileName = "activity.trackeractivity",
					receiptKey = "raw-eof",
					streamProvider = {
						FailingSourceInputStream(bytes.dropLast(1).toByteArray(), original)
					},
				).withImportReceipt("raw-eof-job", 100L),
			)
		}
		(rawFailure === original) shouldBe true

		val lexical = importer.import(
			mockk(relaxed = true),
			database(epoch = 1L),
			stream(
				"{\"format\":\"${"x".repeat(769)}\"}".encodeToByteArray(),
				"activity.trackeractivity",
				"lexical",
			),
		)
		lexical.failedCount shouldBe 1
		calls shouldBe 0
	}

	@Test
	fun `same exact receipt identity with a different source name is a typed collision`() = runTest {
		var retained: ImportPortableCapturedActivityRequest? = null
		val source = fakeImporter { request ->
			val first = retained
			if (first == null) {
				retained = request
				ImportPortableCapturedActivityResult.Applied(1L, 1, 1, 1)
			} else if (first.receipt.jobId == request.receipt.jobId &&
				first.receipt.entryKey == request.receipt.entryKey &&
				first.receipt != request.receipt
			) {
				ImportPortableCapturedActivityResult.Blocked(
					PortableActivityImportBlockedReason.RECEIPT_CONFLICT,
				)
			} else {
				ImportPortableCapturedActivityResult.Duplicate(1L)
			}
		}
		val importer = PortableActivityFileImport { source }
		val bytes = encode(activityEnvelope(activityEntry()))
		val database = database(epoch = 2L)

		importer.import(
			mockk(relaxed = true),
			database,
			stream(
				bytes,
				"first.trackeractivity",
				"same-receipt",
				jobId = "same-job",
				receivedAtMs = 10_000L,
			),
		).successCount shouldBe 1
		importer.import(
			mockk(relaxed = true),
			database,
			stream(
				bytes,
				"renamed.trackeractivity",
				"same-receipt",
				jobId = "same-job",
				receivedAtMs = 10_000L,
			),
		).failedCount shouldBe 1
	}

	@Test
	fun `missing durable receipt fails before decode or source import`() = runTest {
		var providerCalls = 0
		val importer = PortableActivityFileImport {
			providerCalls++
			fakeImporter { ImportPortableCapturedActivityResult.Duplicate(1L) }
		}

		importer.import(
			mockk(relaxed = true),
			database(epoch = 1L),
			unboundStream("{not-json".encodeToByteArray(), "activity.trackeractivity", "entry"),
		).failedCount shouldBe 1
		providerCalls shouldBe 0
	}

	@Test
	fun `current epoch absence fails closed and cancellation or retry remains exceptional`() = runTest {
		var calls = 0
		val bytes = encode(activityEnvelope(activityEntry()))
		val missingEpoch = PortableActivityFileImport({
			fakeImporter {
				calls++
				ImportPortableCapturedActivityResult.Duplicate(1L)
			}
		})
		missingEpoch.import(
			mockk(relaxed = true),
			database(epoch = null),
			stream(bytes, "activity.trackeractivity", "receipt"),
		).failedCount shouldBe 1
		calls shouldBe 0

		val cancelled = PortableActivityFileImport({
			fakeImporter { throw CancellationException("cancelled") }
		})
		shouldThrow<CancellationException> {
			cancelled.import(
				mockk(relaxed = true),
				database(epoch = 1L),
				stream(bytes, "activity.trackeractivity", "cancel"),
			)
		}

		val retry = PortableActivityFileImport({
			fakeImporter {
				ImportPortableCapturedActivityResult.RetryableFailure(
					PortableActivityTransferRetryableReason.STORAGE_UNAVAILABLE,
				)
			}
		})
		shouldThrow<PortableActivityRetryableImportException> {
			retry.import(
				mockk(relaxed = true),
				database(epoch = 1L),
				stream(bytes, "activity.trackeractivity", "retry"),
			)
		}

		val unavailableDatabase = mockk<AppDatabase>()
		val unavailableState =
			mockk<com.adsamcik.tracker.shared.base.database.dao.SourceEvidenceStateDao>()
		every { unavailableDatabase.sourceEvidenceStateDao() } returns unavailableState
		coEvery { unavailableState.get() } throws SQLiteException("storage unavailable")
		shouldThrow<PortableActivityRetryableImportException> {
			missingEpoch.import(
				mockk(relaxed = true),
				unavailableDatabase,
				stream(bytes, "activity.trackeractivity", "sqlite"),
			)
		}
	}

	@Test
	fun `actual import runner records permanent format failure but rethrows transport IO`() = runTest {
		val store = ActivityImportReceiptStore()
		val runner = ImportJobRunner(store, nowMs = { 100L })
		val importer = PortableActivityFileImport {
			fakeImporter { ImportPortableCapturedActivityResult.Duplicate(1L) }
		}
		runner.start("format-job", "activity.trackeractivity", 10L)
		val truncatedBytes = encode(activityEnvelope(activityEntry())).dropLast(1).toByteArray()

		val truncated = runner.importSingle(
			jobId = "format-job",
			stream = unboundStream(
				truncatedBytes,
				"activity.trackeractivity",
				"direct-entry",
			),
			transactionMode = ImportTransactionMode.IMPORTER_MANAGED,
		) { stream ->
			importer.import(mockk(relaxed = true), database(epoch = 1L), stream)
		}

		truncated.failedCount shouldBe 1
		store.entry("format-job", "direct-entry")?.status shouldBe
			ImportEntryReceiptEntity.STATUS_FAILURE

		runner.start("transport-job", "activity.trackeractivity", 10L)
		val original = EOFException("transport interrupted")
		val transport = FileImportStream(
			fileName = "activity.trackeractivity",
			receiptKey = "direct-entry",
			streamProvider = {
				FailingSourceInputStream(
					"{\"format\":\"tracker-portable-captured-activity\"".encodeToByteArray(),
					original,
				)
			},
		)
		val transportFailure = shouldThrow<EOFException> {
			runner.importSingle(
				jobId = "transport-job",
				stream = transport,
				transactionMode = ImportTransactionMode.IMPORTER_MANAGED,
			) { stream ->
				importer.import(mockk(relaxed = true), database(epoch = 1L), stream)
			}
		}
		(transportFailure === original) shouldBe true
		store.entry("transport-job", "direct-entry")?.status shouldBe
			ImportEntryReceiptEntity.STATUS_FAILURE

		runner.start("lexical-job", "activity.trackeractivity", 10L)
		val lexical = runner.importSingle(
			jobId = "lexical-job",
			stream = unboundStream(
				"{\"format\":\"${"x".repeat(769)}\"}".encodeToByteArray(),
				"activity.trackeractivity",
				"direct-entry",
			),
			transactionMode = ImportTransactionMode.IMPORTER_MANAGED,
		) { stream ->
			importer.import(mockk(relaxed = true), database(epoch = 1L), stream)
		}
		lexical.failedCount shouldBe 1
		store.entry("lexical-job", "direct-entry")?.status shouldBe
			ImportEntryReceiptEntity.STATUS_FAILURE
	}

	private fun database(epoch: Long?): AppDatabase {
		val database = mockk<AppDatabase>()
		val dao = mockk<com.adsamcik.tracker.shared.base.database.dao.SourceEvidenceStateDao>()
		every { database.sourceEvidenceStateDao() } returns dao
		coEvery { dao.get() } returns epoch?.let { SourceEvidenceState(collectedDataEpoch = it) }
		return database
	}

	private fun fakeImporter(
		block: suspend (ImportPortableCapturedActivityRequest) -> ImportPortableCapturedActivityResult,
	): ImportPortableCapturedActivity = object : ImportPortableCapturedActivity {
		override suspend fun importEntry(
			request: ImportPortableCapturedActivityRequest,
		): ImportPortableCapturedActivityResult = block(request)
	}

	private fun stream(
		bytes: ByteArray,
		name: String,
		receipt: String,
		jobId: String = "job",
		receivedAtMs: Long = 100L,
	) = unboundStream(bytes, name, receipt).withImportReceipt(jobId, receivedAtMs)

	private fun unboundStream(
		bytes: ByteArray,
		name: String,
		receipt: String,
	) = FileImportStream(
			fileName = name,
			receiptKey = receipt,
			streamProvider = { ByteArrayInputStream(bytes) },
		)

		private class FailingSourceInputStream(
			private val prefix: ByteArray,
			private val failure: IOException,
		) : InputStream() {
			private var offset = 0

			override fun read(): Int {
				if (offset >= prefix.size) throw failure
				return prefix[offset++].toInt() and 0xff
			}

			override fun read(buffer: ByteArray, targetOffset: Int, length: Int): Int {
				if (offset >= prefix.size) throw failure
				val count = minOf(length, prefix.size - offset)
				prefix.copyInto(buffer, targetOffset, offset, offset + count)
				offset += count
				return count
			}
		}

		private suspend fun encode(
		envelope: com.adsamcik.tracker.shared.base.database.PortableActivityEnvelopeV1,
	): ByteArray {
		val output = ByteArrayOutputStream()
		PortableActivityJsonV1Codec().encode(output) { sink ->
			sink.emit(envelope)
			com.adsamcik.tracker.shared.base.database.ExportPortableCapturedActivityResult.Exported(
				envelope.entries.size,
			)
		}
		return output.toByteArray()
	}

	private class ActivityImportReceiptStore : ImportReceiptStore {
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
			val previous = requireNotNull(jobs[jobId])
			jobs[jobId] = previous.copy(
				sourceName = sourceName,
				sourceSizeBytes = sourceSizeBytes,
				status = ImportJobReceiptEntity.STATUS_IN_PROGRESS,
				updatedAt = updatedAt,
			)
		}

		override suspend fun markJobComplete(jobId: String, completedAt: Long) {
			val previous = requireNotNull(jobs[jobId])
			jobs[jobId] = previous.copy(
				status = ImportJobReceiptEntity.STATUS_COMPLETE,
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

		fun entry(jobId: String, entryKey: String): ImportEntryReceiptEntity? =
			entries[jobId to entryKey]
	}
}
