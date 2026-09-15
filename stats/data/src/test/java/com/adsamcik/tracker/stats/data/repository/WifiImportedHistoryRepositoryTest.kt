package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.stats.api.repository.ExportPortableCapturedWifiRequest
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductCandidate
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluation
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluator
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRangePage
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRangeRequest
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableWifiAcquisitionCompleteness
import com.adsamcik.tracker.stats.api.repository.PortableWifiAvailability
import com.adsamcik.tracker.stats.api.repository.PortableWifiCaptureCoverage
import com.adsamcik.tracker.stats.api.repository.PortableWifiDeletionScopeDigest
import com.adsamcik.tracker.stats.api.repository.PortableWifiIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortableWifiIntegrity
import com.adsamcik.tracker.stats.api.repository.PortableWifiOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PortableWifiResultCompleteness
import com.adsamcik.tracker.stats.api.repository.PortableWifiRunAvailability
import com.adsamcik.tracker.stats.api.repository.PortableWifiSessionMode
import com.adsamcik.tracker.stats.api.repository.ReadLocalPortableCapturedWifi
import com.adsamcik.tracker.stats.api.repository.ReadLocalPortableCapturedWifiResult
import com.adsamcik.tracker.stats.api.repository.WifiHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.WifiHistoryPage
import com.adsamcik.tracker.stats.api.repository.WifiHistoryProductState
import com.adsamcik.tracker.stats.api.repository.WifiHistoryQuery
import com.adsamcik.tracker.stats.api.repository.WifiHistoryRangePage
import com.adsamcik.tracker.stats.api.repository.WifiHistoryRangeRequest
import com.adsamcik.tracker.stats.api.repository.WifiHistorySelection
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCause
import com.adsamcik.tracker.stats.api.repository.WifiImportedHistorySelectionKey
import com.adsamcik.tracker.stats.api.repository.WifiDeletedHistoryReader
import com.adsamcik.tracker.stats.api.repository.WifiDeletedHistoryResult
import com.adsamcik.tracker.stats.api.value.EpochMs
import io.kotest.matchers.shouldBe
import java.security.MessageDigest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WifiImportedHistoryRepositoryTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `imported-only entry is discoverable and exactly selectable without native fallback`() = runTest {
		val entry = portableEntry()
		val evaluation = ImportedWifiProductEvaluation.Readable(
			candidate = ImportedWifiProductCandidate(
				entry.identity,
				1L,
				entry.contentChecksum,
				entry.startTimeMs,
				entry.endTimeMs,
				3_000L,
				entry.runs.single().startTimeMs,
				entry.runs.single().identity,
			),
			entry = entry,
			entryDeleted = false,
			deletedRunIdentities = emptySet(),
			retainedObservationIdentities = entry.runs.flatMapTo(linkedSetOf()) { run ->
				run.observations.map { it.identity }
			},
			retentionLimited = false,
		)
		val evaluator = object : ImportedWifiProductEvaluator {
			override suspend fun selectIdentityInTransaction(
				selection: WifiImportedHistorySelectionKey,
			): ImportedWifiProductEvaluation? =
				evaluation.takeIf { selection == it.candidate.selection.key }

			override suspend fun selectRecentInTransaction(
				limit: Int,
			): List<ImportedWifiProductEvaluation> = listOf(evaluation).take(limit)

			override suspend fun selectRangeInTransaction(
				request: ImportedWifiProductRangeRequest,
			): ImportedWifiProductRangePage = ImportedWifiProductRangePage(
				evaluations = listOf(evaluation).filter {
					it.candidate.endTimeMs > request.fromInclusiveMs &&
						it.candidate.startTimeMs < request.toExclusiveMs
				}.take(request.limit),
				hasMore = false,
			)
		}
		val repository = DefaultWifiHistoryRepository(
			database,
			SourceProductLaneExecutionAuthority { false },
			evaluator,
			object : ReadLocalPortableCapturedWifi {
				override suspend fun readInTransaction(
					request: ExportPortableCapturedWifiRequest,
				): ReadLocalPortableCapturedWifiResult = error("No local collision expected")
			},
			WifiDeletedHistoryReader { WifiDeletedHistoryResult.NotDeleted },
			UnconfinedTestDispatcher(testScheduler),
		)

		val recent = (repository.recent(10) as WifiHistoryPage.Available).entries.single()
		val sourceRecent = database.withTransaction {
			repository.recentInTransaction(10)
		} as WifiSourceRecentPage.Available
		val ranged = repository.range(
			WifiHistoryRangeRequest(EpochMs(0L), EpochMs(10_000L), 10),
		) as WifiHistoryRangePage.Available
		val selected = (
			repository.imported(evaluation.candidate.selection.key) as WifiHistoryQuery.Found
			).entry
		val lookedUp = (
			repository.lookup(WifiHistorySelection.Imported(evaluation.candidate.selection)) as
				WifiHistoryQuery.Found
			).entry

		recent shouldBe selected
		lookedUp shouldBe selected
		sourceRecent.entries.single() shouldBe WifiSourceRecentEntry.Imported(
			selected,
			evaluation.candidate.newestMemberStartTimeMs,
			evaluation.candidate.newestMemberIdentity.value,
		)
		ranged.entries.single() shouldBe selected
		selected.origin shouldBe WifiHistoryOrigin.IMPORTED
		selected.state shouldBe WifiHistoryProductState.READY
		selected.importedSelection shouldBe evaluation.candidate.selection
		selected.capturesOnlyWifi shouldBe false
		repository.lookup(
			WifiHistorySelection.Imported(
				evaluation.candidate.selection.copy(contentChecksum = "f".repeat(64)),
			),
		) shouldBe WifiHistoryQuery.Failed(WifiHistoryCause.STALE_SELECTION)
		repository.session(1L) shouldBe WifiHistoryQuery.NotFound
	}

	@Test
	fun `public and transactional lookup preserve only authenticated failed selection`() = runTest {
		val entry = portableEntry()
		val candidate = ImportedWifiProductCandidate(
			entry.identity,
			1L,
			entry.contentChecksum,
			entry.startTimeMs,
			entry.endTimeMs,
			3_000L,
			entry.runs.single().startTimeMs,
			entry.runs.single().identity,
		)
		val failure = ImportedWifiProductEvaluation.Unverifiable(
			candidate,
			com.adsamcik.tracker.stats.api.repository.ImportedWifiProductFailure.VALUE_OVERFLOW,
			authenticatedSelection = candidate.selection,
		)
		val evaluator = object : ImportedWifiProductEvaluator {
			override suspend fun selectIdentityInTransaction(
				selection: WifiImportedHistorySelectionKey,
			): ImportedWifiProductEvaluation = failure

			override suspend fun selectRecentInTransaction(
				limit: Int,
			): List<ImportedWifiProductEvaluation> = listOf(failure)

			override suspend fun selectRangeInTransaction(
				request: ImportedWifiProductRangeRequest,
			): ImportedWifiProductRangePage = ImportedWifiProductRangePage(listOf(failure), false)
		}
		val repository = DefaultWifiHistoryRepository(
			database,
			SourceProductLaneExecutionAuthority { false },
			evaluator,
			object : ReadLocalPortableCapturedWifi {
				override suspend fun readInTransaction(
					request: ExportPortableCapturedWifiRequest,
				): ReadLocalPortableCapturedWifiResult = error("No local collision expected")
			},
			WifiDeletedHistoryReader { WifiDeletedHistoryResult.NotDeleted },
			UnconfinedTestDispatcher(testScheduler),
		)

		val public = (repository.imported(candidate.selection.key) as WifiHistoryQuery.Found).entry
		val transactional = database.withTransaction {
			repository.lookup(WifiHistorySelection.Imported(candidate.selection))
		} as WifiHistoryQuery.Found

		public.selection shouldBe WifiHistorySelection.Imported(candidate.selection)
		transactional.entry shouldBe public
	}

	private fun portableEntry(): PortableCapturedWifiEntryV1 {
		val observation = PortableWifiIntegrity.createObservation(
			identity = identity(PortableWifiIdentityKind.OBSERVATION, "observation"),
			semanticRevision = 1L,
			supersedesSemanticRevision = null,
			aggregateOwnerIdentity = null,
			aggregateOwnerSemanticRevision = null,
			coverageStartTimeMs = 1_100L,
			observedTimeMs = 1_200L,
			latestPossibleTimeMs = 1_201L,
			wallTimeUncertaintyMs = 1L,
			storedZoneId = "Europe/Prague",
			availability = PortableWifiAvailability.AVAILABLE,
			resultCompleteness = PortableWifiResultCompleteness.COMPLETE,
			submittedResultCount = 1,
			acceptedResultCount = 1,
			staleResultCount = 0,
			clockUnverifiableResultCount = 0,
			malformedResultCount = 0,
			observationCount = 1,
			twoPointFourGhzCount = 1,
			fiveGhzCount = 0,
			sixGhzCount = 0,
			otherBandCount = 0,
			strongestSignalDbm = -55,
			weakestSignalDbm = -55,
			meanSignalDbm = -55.0,
			sourceQualityFlags = 0L,
			sourceQualityConfidence = 1f,
		)
		val run = PortableWifiIntegrity.createRun(
			identity = identity(PortableWifiIdentityKind.PHYSICAL_RUN, "run"),
			deletionScopeDigest = PortableWifiDeletionScopeDigest(
				sha256("test-imported-history-scope:run"),
			),
			startTimeMs = 1_000L,
			endTimeMs = 2_000L,
			storedZoneIds = listOf("Europe/Prague"),
			captureCoverage = PortableWifiCaptureCoverage.WHOLE_RUN,
			availability = PortableWifiRunAvailability.RETAINED,
			acquisitionCompleteness = PortableWifiAcquisitionCompleteness.COMPLETE,
			hasUnresolvedProviderRange = false,
			retentionLoss = false,
			observations = listOf(observation),
		)
		return PortableWifiIntegrity.createEntry(
			identity = identity(PortableWifiIdentityKind.LOGICAL_ENTRY, "entry"),
			sessionMode = PortableWifiSessionMode.MANUAL,
			startTimeMs = run.startTimeMs,
			endTimeMs = run.endTimeMs,
			runs = listOf(run),
		)
	}

	private fun identity(kind: PortableWifiIdentityKind, seed: String) =
		PortableWifiOpaqueIdentity.derive(kind, seed)

	private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
		.digest(value.toByteArray())
		.joinToString("") { "%02x".format(it) }
}
