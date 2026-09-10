package com.adsamcik.tracker.impexp.importer.file

import android.content.Context
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.impexp.importer.ImportResult
import com.adsamcik.tracker.impexp.importer.worker.importSourceReadLimit
import com.adsamcik.tracker.impexp.portable.PortableStepsJsonException
import com.adsamcik.tracker.impexp.portable.PortableStepsJsonV1Codec
import com.adsamcik.tracker.shared.base.database.AppDatabase
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
	fun `malformed document never reaches authoritative import`() = runTest {
		var calls = 0
		val importer = PortableStepsFileImport {
			sourceImporter {
				calls++
				ImportPortableStepsResult.Applied(1, 1)
			}
		}

		shouldThrow<PortableStepsJsonException> {
			importer.import(context, database, stream("{}".encodeToByteArray()))
		}
		calls shouldBe 0
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
}
