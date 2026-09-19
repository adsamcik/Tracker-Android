package com.adsamcik.tracker.impexp.importer.file

import android.content.Context
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.impexp.importer.ImportJobRunner
import com.adsamcik.tracker.impexp.importer.ImportReceiptStore
import com.adsamcik.tracker.impexp.importer.ImportResult
import com.adsamcik.tracker.impexp.importer.worker.importSourceReadLimit
import com.adsamcik.tracker.impexp.portable.PortableStepsJsonV1Codec
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ImportEntryReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportJobReceiptEntity
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsResult
import com.adsamcik.tracker.stats.api.repository.ImportPortableSteps
import com.adsamcik.tracker.stats.api.repository.ImportPortableStepsResult
import com.adsamcik.tracker.stats.api.repository.PortableStepsCaptureCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsCompletenessV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsConflictScope
import com.adsamcik.tracker.stats.api.repository.PortableStepsDeletionScopeDigest
import com.adsamcik.tracker.stats.api.repository.PortableStepsEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsFactCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsFactV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortableStepsImportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.PortableStepsManifestV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PortableStepsProviderCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsRunV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsSessionMode
import com.adsamcik.tracker.stats.api.repository.PortableStepsTransferRetryableReason
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PortableStepsFileImportTest {
	private val context = mockk<Context>()
	private val database = mockk<AppDatabase>()

	@Test
	fun `portable extension routes only through importer managed transactions`() {
		val importer = PortableStepsFileImport { error("Not invoked") }

		importer.supportedExtensions shouldContainExactly listOf("trackersteps")
		importer.transactionMode shouldBe ImportTransactionMode.IMPORTER_MANAGED
		importSourceReadLimit("TRACKERSTEPS") shouldBe PortableStepsFileImport.MAX_FILE_BYTES
	}

	@Test
	fun `missing durable receipt fails before opening or resolving dependencies`() = runTest {
		var opens = 0
		var providers = 0
		val importer = PortableStepsFileImport(
			dependenciesProvider = {
				providers++
				error("Missing receipt must fail first")
			},
		)
		val stream = FileImportStream(
			fileName = "steps.trackersteps",
			streamProvider = {
				opens++
				ByteArrayInputStream(byteArrayOf())
			},
		)

		shouldThrow<PortableStepsImportReceiptContextException> {
			importer.import(context, database, stream)
		}
		opens shouldBe 0
		providers shouldBe 0
	}

	@Test
	fun `applied replay and durable refusals retain distinct file counts`() = runTest {
		val entries = listOf(
			entry("applied", 1_000L),
			entry("duplicate", 3_000L),
			entry("deleted", 5_000L),
			entry("retained", 7_000L),
		)
		val outcomes = ArrayDeque<ImportPortableStepsResult>().apply {
			add(ImportPortableStepsResult.Applied(1, 1))
			add(ImportPortableStepsResult.Duplicate)
			add(ImportPortableStepsResult.DeletedScope)
			add(ImportPortableStepsResult.OutsideRetention)
		}
		val seen = mutableListOf<PortableStepsEntryV1>()
		val importer = PortableStepsFileImport {
			sourceImporter { imported ->
				seen += imported
				outcomes.removeFirst()
			}
		}

		val result = importer.import(context, database, stream(encode(entries)))

		seen shouldContainExactly entries
		result shouldBe ImportResult(
			successCount = 1,
			skippedCount = 3,
			errors = listOf(
				"Portable Steps entry is protected by a deletion fence.",
				"Portable Steps entry is outside the retained data window.",
			),
		)
	}

	@Test
	fun `independent permanent failures do not hide a later healthy entry`() = runTest {
		val entries = listOf(
			entry("conflict", 1_000L),
			entry("unverifiable", 3_000L),
			entry("healthy", 5_000L),
		)
		val outcomes = ArrayDeque<ImportPortableStepsResult>().apply {
			add(ImportPortableStepsResult.Conflict(PortableStepsConflictScope.FACT))
			add(ImportPortableStepsResult.Unverifiable(
				PortableStepsImportUnverifiableReason.DAY_REPAIR_UNVERIFIABLE,
			))
			add(ImportPortableStepsResult.Applied(1, 1))
		}
		val importer = PortableStepsFileImport {
			sourceImporter { outcomes.removeFirst() }
		}

		val result = importer.import(context, database, stream(encode(entries)))

		result.successCount shouldBe 1
		result.failedCount shouldBe 2
		result.errors shouldContainExactly listOf(
			"Portable Steps entry conflicts with existing fact identity.",
			"Portable Steps entry cannot be verified: day_repair_unverifiable.",
		)
	}

	@Test
	fun `retryable source refusal becomes retryable file IO failure`() = runTest {
		val importer = PortableStepsFileImport {
			sourceImporter {
				ImportPortableStepsResult.RetryableFailure(
					PortableStepsTransferRetryableReason.DAY_REPAIR_MATERIALIZING,
				)
			}
		}

		shouldThrow<PortableStepsRetryableImportException> {
			importer.import(context, database, stream(encode(listOf(entry("retry", 1_000L)))))
		}
	}

	@Test
	fun `malformed truncated and checksum invalid documents become permanent file failures`() =
		runTest {
		var calls = 0
		val importer = PortableStepsFileImport {
			sourceImporter {
				calls++
				ImportPortableStepsResult.Applied(1, 1)
			}
		}

		val valid = encode(listOf(entry("checksum", 1_000L))).decodeToString()
		val invalidDocuments = listOf(
			"{}",
			"{\"format\":\"tracker-portable-steps\"",
			valid.replaceFirst("\"stepCount\":12", "\"stepCount\":13"),
		)

		invalidDocuments.forEach { document ->
			importer.import(context, database, stream(document.encodeToByteArray())) shouldBe
				ImportResult(
					failedCount = 1,
					errors = listOf(PortableStepsFileImport.PERMANENT_FORMAT_ERROR),
				)
		}
		calls shouldBe 0
	}

	@Test
	@Suppress("LongMethod")
	fun `job runner rejects malformed prefix and suffix before source admission`() =
		runTest {
			val store = StepsImportReceiptStore()
			val runner = ImportJobRunner(store) { 1_000L }
			var calls = 0
			val importer = PortableStepsFileImport {
				sourceImporter {
					calls++
					if (calls == 1) {
						ImportPortableStepsResult.Applied(1, 1)
					} else {
						ImportPortableStepsResult.Duplicate
					}
				}
			}
			val malformedPrefix = "{]".encodeToByteArray()
			runner.start(
				"prefix-job",
				"prefix.trackersteps",
				malformedPrefix.size.toLong(),
			) shouldBe true

			val prefixResult = runner.importSingle(
				jobId = "prefix-job",
				stream = stream(malformedPrefix, "prefix.trackersteps"),
				transactionMode = importer.transactionMode,
			) { source ->
				importer.import(context, database, source)
			}

			prefixResult shouldBe ImportResult(
				failedCount = 1,
				errors = listOf(PortableStepsFileImport.PERMANENT_FORMAT_ERROR),
			)
			calls shouldBe 0
			store.entry("prefix-job", "prefix.trackersteps")?.status shouldBe
				ImportEntryReceiptEntity.STATUS_FAILURE

			val malformedSuffix = encode(listOf(entry("prefix", 1_000L))) + 'x'.code.toByte()
			runner.start(
				"suffix-job",
				"suffix.trackersteps",
				malformedSuffix.size.toLong(),
			) shouldBe true

			val firstResult = runner.importSingle(
				jobId = "suffix-job",
				stream = stream(malformedSuffix, "suffix.trackersteps"),
				transactionMode = importer.transactionMode,
			) { source ->
				importer.import(context, database, source)
			}

			firstResult shouldBe ImportResult(
				failedCount = 1,
				errors = listOf(PortableStepsFileImport.PERMANENT_FORMAT_ERROR),
			)
			store.entry("suffix-job", "suffix.trackersteps") shouldBe
				firstResult.failureReceipt("suffix-job", "suffix.trackersteps", 1_000L)

			runner.start(
				"suffix-job",
				"suffix.trackersteps",
				malformedSuffix.size.toLong(),
			) shouldBe true
			val replayResult = runner.importSingle(
				jobId = "suffix-job",
				stream = stream(malformedSuffix, "suffix.trackersteps"),
				transactionMode = importer.transactionMode,
			) { source ->
				importer.import(context, database, source)
			}

			replayResult shouldBe ImportResult(
				failedCount = 1,
				errors = listOf(PortableStepsFileImport.PERMANENT_FORMAT_ERROR),
			)
			calls shouldBe 0
		}

	@Test
	@Suppress("LongMethod")
	fun `job runner preserves transport IO source retryable failures and cancellation`() = runTest {
		val store = StepsImportReceiptStore()
		val runner = ImportJobRunner(store) { 2_000L }
		val transportFailure = EOFException("source stream unavailable")
		val transportImporter = PortableStepsFileImport {
			sourceImporter { error("Transport failure must occur before source import") }
		}
		runner.start("io-job", "io.trackersteps", 100L) shouldBe true

		val thrownIo = shouldThrow<EOFException> {
			runner.importSingle(
				jobId = "io-job",
				stream = FileImportStream(
					FailingInputStream(
						"{\"format\":\"tracker-portable-steps\"".encodeToByteArray(),
						transportFailure,
					),
					"io.trackersteps",
				),
				transactionMode = transportImporter.transactionMode,
			) { source ->
				transportImporter.import(context, database, source)
			}
		}

		thrownIo shouldBe transportFailure
		store.entry("io-job", "io.trackersteps")?.status shouldBe
			ImportEntryReceiptEntity.STATUS_FAILURE

		val retryableImporter = PortableStepsFileImport {
			sourceImporter {
				ImportPortableStepsResult.RetryableFailure(
					PortableStepsTransferRetryableReason.DAY_REPAIR_MATERIALIZING,
				)
			}
		}
		val valid = encode(listOf(entry("retry", 1_000L)))
		runner.start("retry-job", "retry.trackersteps", valid.size.toLong()) shouldBe true

		shouldThrow<PortableStepsRetryableImportException> {
			runner.importSingle(
				jobId = "retry-job",
				stream = stream(valid, "retry.trackersteps"),
				transactionMode = retryableImporter.transactionMode,
			) { source ->
				retryableImporter.import(context, database, source)
			}
		}
		store.entry("retry-job", "retry.trackersteps")?.status shouldBe
			ImportEntryReceiptEntity.STATUS_FAILURE

		val cancellingImporter = PortableStepsFileImport {
			sourceImporter { throw CancellationException("cancel") }
		}
		runner.start("cancel-job", "cancel.trackersteps", valid.size.toLong()) shouldBe true

		shouldThrow<CancellationException> {
			runner.importSingle(
				jobId = "cancel-job",
				stream = stream(valid, "cancel.trackersteps"),
				transactionMode = cancellingImporter.transactionMode,
			) { source ->
				cancellingImporter.import(context, database, source)
			}
		}
		store.entry("cancel-job", "cancel.trackersteps") shouldBe null
	}

	@Test
	fun `cancellation from authoritative import is never converted`() = runTest {
		val importer = PortableStepsFileImport {
			sourceImporter { throw CancellationException("cancel") }
		}

		shouldThrow<CancellationException> {
			importer.import(context, database, stream(encode(listOf(entry("cancel", 1_000L)))))
		}
	}

	private fun stream(bytes: ByteArray) = FileImportStream(
		ByteArrayInputStream(bytes),
		"steps.trackersteps",
	).withImportReceipt("direct-test-job", 1_000L)

	private fun stream(bytes: ByteArray, fileName: String) = FileImportStream(
		ByteArrayInputStream(bytes),
		fileName,
	)

	private fun sourceImporter(
		block: suspend (PortableStepsEntryV1) -> ImportPortableStepsResult,
	): ImportPortableSteps = object : ImportPortableSteps {
		override suspend fun importEntry(entry: PortableStepsEntryV1): ImportPortableStepsResult = block(entry)
	}

	private suspend fun encode(entries: List<PortableStepsEntryV1>): ByteArray {
		val output = ByteArrayOutputStream()
		PortableStepsJsonV1Codec().encode(output) { sink ->
			entries.forEach { entry -> sink.emit(entry) }
			ExportPortableStepsResult.Exported(entries.size)
		}
		return output.toByteArray()
	}

	private fun entry(seed: String, startTimeMs: Long): PortableStepsEntryV1 {
		val runId = "$seed-run"
		val run = PortableStepsRunV1(
			identity = identity(PortableStepsIdentityKind.PHYSICAL_RUN, runId),
			deletionScopeDigest = PortableStepsDeletionScopeDigest.derive(seed, runId),
			startTimeMs = startTimeMs,
			endTimeMs = startTimeMs + 1_000L,
			storedZoneId = "Europe/Prague",
			manifests = listOf(
				PortableStepsManifestV1(
					revision = 1L,
					effectiveWallTimeMs = startTimeMs,
					originSourcePolicyRevision = 7L,
					captureConsentEpoch = 3L,
				),
			),
			completeness = PortableStepsCompletenessV1(
				captureCoverage = PortableStepsCaptureCoverage.WHOLE_RUN,
				providerCoverage = PortableStepsProviderCoverage.COMPLETE,
				appDrainComplete = true,
				stopComplete = true,
				hasUnresolvedProviderRange = false,
			),
			facts = listOf(
				PortableStepsFactV1.create(
					identity = identity(PortableStepsIdentityKind.FACT, "$seed-fact"),
					manifestRevision = 1L,
					intervalStartTimeMs = startTimeMs,
					intervalEndTimeMs = startTimeMs + 1_000L,
					wallTimeUncertaintyMs = 25L,
					coverage = PortableStepsFactCoverage.COVERED,
					stepCount = 12L,
				),
			),
		)
		return PortableStepsEntryV1.create(
			identity = identity(PortableStepsIdentityKind.LOGICAL_ENTRY, seed),
			sessionMode = PortableStepsSessionMode.MANUAL,
			startTimeMs = run.startTimeMs,
			endTimeMs = run.endTimeMs,
			runs = listOf(run),
		)
	}

	private fun identity(kind: PortableStepsIdentityKind, seed: String) =
		PortableStepsOpaqueIdentity.derive(kind, seed)

	private fun ImportResult.failureReceipt(
		jobId: String,
		entryName: String,
		updatedAt: Long,
	) = ImportEntryReceiptEntity(
		jobId = jobId,
		entryKey = entryName,
		entryName = entryName,
		status = ImportEntryReceiptEntity.STATUS_FAILURE,
		successCount = successCount,
		skippedCount = skippedCount,
		failedCount = failedCount,
		errorMessage = errors.joinToString("\n"),
		updatedAt = updatedAt,
	)

	private class FailingInputStream(
		private val prefix: ByteArray,
		private val failure: IOException,
	) : InputStream() {
		private var offset = 0

		override fun read(): Int {
			if (offset >= prefix.size) throw failure
			return prefix[offset++].toInt() and 0xff
		}

		override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
			if (this.offset >= prefix.size) throw failure
			val count = minOf(length, prefix.size - this.offset)
			prefix.copyInto(buffer, offset, this.offset, this.offset + count)
			this.offset += count
			return count
		}
	}

	private class StepsImportReceiptStore : ImportReceiptStore {
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

		fun entry(jobId: String, entryKey: String): ImportEntryReceiptEntity? =
			entries[jobId to entryKey]
	}
}
