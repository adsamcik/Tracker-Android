package com.adsamcik.tracker.impexp.importer.file

import android.content.Context
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.impexp.portable.PortableActivityJsonException
import com.adsamcik.tracker.impexp.portable.PortableActivityJsonV1Codec
import com.adsamcik.tracker.impexp.portable.activityEntry
import com.adsamcik.tracker.impexp.portable.activityEnvelope
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ImportPortableCapturedActivity
import com.adsamcik.tracker.shared.base.database.ImportPortableCapturedActivityRequest
import com.adsamcik.tracker.shared.base.database.ImportPortableCapturedActivityResult
import com.adsamcik.tracker.shared.base.database.PortableActivityImportBlockedReason
import com.adsamcik.tracker.shared.base.database.PortableActivityTransferRetryableReason
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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

		shouldThrow<PortableActivityJsonException> {
			importer.import(
				mockk(relaxed = true),
				database(epoch = 3L),
				stream(truncated, "activity.trackeractivity", "receipt"),
			)
		}
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
}
