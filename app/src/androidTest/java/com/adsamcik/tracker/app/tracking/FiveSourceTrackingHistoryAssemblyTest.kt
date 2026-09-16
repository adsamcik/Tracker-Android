package com.adsamcik.tracker.app.tracking

import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adsamcik.tracker.app.Application
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ImportPortableCapturedActivity
import com.adsamcik.tracker.shared.base.database.ImportPortableCapturedActivityRequest
import com.adsamcik.tracker.shared.base.database.ImportPortableCapturedActivityResult
import com.adsamcik.tracker.shared.base.database.ImportPortableCapturedCellRequest
import com.adsamcik.tracker.shared.base.database.ImportPortableCapturedCellResult
import com.adsamcik.tracker.shared.base.database.PortableActivityImportReceipt
import com.adsamcik.tracker.shared.base.database.PortableCellImportReceipt
import com.adsamcik.tracker.shared.base.database.RoomImportPortableCapturedCell
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryQuery
import com.adsamcik.tracker.stats.api.repository.CellHistoryCause
import com.adsamcik.tracker.stats.api.repository.CellHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.CellHistoryProductState
import com.adsamcik.tracker.stats.api.repository.HistoryCapture
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.ImportPortableCapturedWifi
import com.adsamcik.tracker.stats.api.repository.ImportPortableCapturedWifiRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortableCapturedWifiResult
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRecentPageEvaluator
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRecentRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortablePressure
import com.adsamcik.tracker.stats.api.repository.ImportPortablePressureRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortablePressureResult
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiImportReceipt
import com.adsamcik.tracker.stats.api.repository.PortablePressureImportReceipt
import com.adsamcik.tracker.stats.api.repository.PressureHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.SessionHistoryQuery
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageEntry
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageQuery
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageUnavailableReason
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryReadSnapshot
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryRepository
import com.adsamcik.tracker.stats.api.repository.WifiHistoryOrigin
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * App-level source assembly proof using the production Hilt graph.
 *
 * This class proves real source readers assemble without replacing the Wi-Fi adapter/evaluator
 * edge with a test proxy, and keeps source failures closed at the app composition boundary.
 */
@RunWith(AndroidJUnit4::class)
class FiveSourceTrackingHistoryAssemblyTest {
	private lateinit var database: AppDatabase
	private lateinit var entryPoint: FiveSourceHistoryEntryPoint

	@Before
	fun setUp() {
		val application = ApplicationProvider.getApplicationContext<Application>()
		entryPoint = EntryPointAccessors.fromApplication(
			application,
			FiveSourceHistoryEntryPoint::class.java,
		)
		database = entryPoint.database()
		resetDatabase()
	}

	@After
	fun tearDown() = resetDatabase()

	@Test
	fun productionGraphComposesCurrentFiveSourcesWithAuthenticatedRecencyAndFinalLimit() = runTest {
		seedExactNativeStepsMembership(database)
		val wifiAlphaV1 = wifiAssemblyEntry("five-source-wifi-alpha", 100L, 2_000L)
		val wifiAlphaV2 = wifiAssemblyEntry(
			"five-source-wifi-alpha",
			100L,
			2_000L,
			semanticRevision = 2L,
		)
		val wifiZulu = wifiAssemblyEntry("five-source-wifi-zulu", 100L, 2_000L)
		val wifiOld = wifiAssemblyEntry("five-source-wifi-old", 50L, 500L)
		val cell = cellAssemblyEntry("five-source-cell", 100L, 2_000L)
		val activity = activityAssemblyEntry('a', 100L, 2_000L)
		val pressure = pressureAssemblyEntry("five-source-pressure", 100L, 2_000L)

		assertTrue(importWifi(wifiAlphaV1.entry, "wifi-alpha-v1") is
			ImportPortableCapturedWifiResult.Applied)
		val corrected = importWifi(wifiAlphaV2.entry, "wifi-alpha-v2")
		assertEquals(
			ImportPortableCapturedWifiResult.Applied(2L, 2, 2),
			corrected,
		)
		assertEquals(
			ImportPortableCapturedWifiResult.Duplicate(2L),
			importWifi(wifiAlphaV2.entry, "wifi-alpha-duplicate"),
		)
		assertTrue(
			importWifi(
				wifiAssemblyEntry("five-source-wifi-alpha", 200L, 2_100L).entry,
				"wifi-alpha-conflict",
			) is ImportPortableCapturedWifiResult.Blocked,
		)
		assertTrue(importWifi(wifiZulu.entry, "wifi-zulu") is
			ImportPortableCapturedWifiResult.Applied)
		assertTrue(importWifi(wifiOld.entry, "wifi-old") is
			ImportPortableCapturedWifiResult.Applied)
		assertTrue(
			entryPoint.importCell().importEntry(
				ImportPortableCapturedCellRequest(
					entry = cell,
					receipt = PortableCellImportReceipt(
						"five-source-cell-job",
						"five-source-cell-entry",
						"five-source.trackercell",
						2_500L,
					),
					expectedCollectedDataEpoch = 0L,
				),
			) is ImportPortableCapturedCellResult.Applied,
		)
		assertTrue(
			entryPoint.importActivity().importEntry(
				ImportPortableCapturedActivityRequest(
					entry = activity,
					receipt = PortableActivityImportReceipt(
						"five-source-activity-job",
						"five-source-activity-entry",
						"five-source.trackeractivity",
						2_500L,
					),
					expectedCollectedDataEpoch = 0L,
				),
			) is ImportPortableCapturedActivityResult.Applied,
		)
		assertTrue(
			entryPoint.importPressure().importEntry(
				ImportPortablePressureRequest(
					entry = pressure,
					receipt = PortablePressureImportReceipt(
						"five-source-pressure-job",
						"five-source-pressure-entry",
						"five-source.trackerpressure",
						2_500L,
					),
					expectedCollectedDataEpoch = 0L,
				),
			) is ImportPortablePressureResult.Applied,
		)

		val evaluatorPage = database.withTransaction {
			entryPoint.wifiEvaluator().openRecentScanInTransaction()
				.selectPageInTransaction(ImportedWifiProductRecentRequest(limit = 10))
		}
		assertEquals(3, evaluatorPage.evaluations.size)
		evaluatorPage.evaluations.single {
			it.candidate.identity == wifiAlphaV2.entry.identity
		}.candidate.let { alpha ->
			assertEquals(2L, alpha.importRevision)
			assertEquals(wifiAlphaV2.entry.contentChecksum, alpha.contentChecksum)
			assertEquals(wifiAlphaV2.newestRunIdentity, alpha.newestMemberIdentity)
		}

		val evidenceBeforeRead = requireNotNull(database.sourceEvidenceStateDao().get())
		val query = entryPoint.history().observeRecentSourceAwarePage(
			candidateSegmentIds = listOf(FIVE_SOURCE_NATIVE_STEPS_SEGMENT_ID),
			limit = 6,
		).first()
		val content = when (query) {
			is SourceAwareHistoryPageQuery.Content -> query
			is SourceAwareHistoryPageQuery.Unavailable ->
				error("Production five-source page unavailable: ${query.reason}")
		}
		assertEquals(6, content.entries.size)
		assertEquals(
			listOf(
				HistorySource.WIFI,
				HistorySource.WIFI,
				HistorySource.CELL,
				HistorySource.ACTIVITY,
				HistorySource.PRESSURE,
				HistorySource.STEPS,
			),
			content.entries.map(SourceAwareHistoryPageEntry::source),
		)
		val expectedWifiOrder = listOf(wifiAlphaV2, wifiZulu)
			.sortedByDescending { it.newestRunIdentity.value }
			.map { it.entry.identity.value }
		val selectedWifi = content.entries.filterIsInstance<SourceAwareHistoryPageEntry.WifiOnly>()
		assertEquals(
			expectedWifiOrder,
			selectedWifi.map { requireNotNull(it.history.importedSelection).key.value },
		)
		val wifiRevisionByIdentity = mapOf(
			wifiAlphaV2.entry.identity.value to 2L,
			wifiZulu.entry.identity.value to 1L,
		)
		assertEquals(
			expectedWifiOrder.map(wifiRevisionByIdentity::getValue),
			selectedWifi.map { requireNotNull(it.history.importedSelection).importRevision },
		)
		assertFalse(
			selectedWifi.any {
				it.history.importedSelection?.key?.value == wifiOld.entry.identity.value
			},
		)
		assertEquals(
			1,
			selectedWifi.count {
				it.history.importedSelection?.key?.value == wifiAlphaV2.entry.identity.value
			},
		)
		assertTrue(selectedWifi.all { it.history.origin == WifiHistoryOrigin.IMPORTED })
		assertTrue(
			content.entries.filterIsInstance<SourceAwareHistoryPageEntry.CellOnly>()
				.single().history.origin is CellHistoryOrigin.Imported,
		)
		assertEquals(
			ActivityHistoryOrigin.IMPORTED,
			content.entries.filterIsInstance<SourceAwareHistoryPageEntry.ActivityOnly>()
				.single().history.origin,
		)
		assertTrue(
			content.entries.filterIsInstance<SourceAwareHistoryPageEntry.PressureOnly>()
				.single().history.origin is PressureHistoryOrigin.Imported,
		)
		assertTrue(content.entries.last() is SourceAwareHistoryPageEntry.StepsOnly)
		val evidenceAfterRead = requireNotNull(database.sourceEvidenceStateDao().get())
		assertEquals(evidenceBeforeRead, evidenceAfterRead)
		assertEquals(
			TrackingHistoryReadSnapshot(
				evidenceBeforeRead.collectedDataEpoch,
				evidenceBeforeRead.revision,
			),
			content.readSnapshot,
		)

		val selectedNative = when (val selected = entryPoint.history()
			.observeSession(FIVE_SOURCE_NATIVE_STEPS_SEGMENT_ID)
			.first()
		) {
			is SessionHistoryQuery.Found -> selected
			SessionHistoryQuery.NotFound -> error("Native Steps fixture disappeared")
			is SessionHistoryQuery.Unavailable ->
				error("Native Steps fixture unavailable: ${selected.reason}")
		}
		val exactCapture = selectedNative.history.capture as HistoryCapture.Exact
		assertEquals(1, exactCapture.revisions.size)
		assertEquals(
			setOf(HistorySource.STEPS),
			exactCapture.revisions.single().capturedSources,
		)
		val products = requireNotNull(selectedNative.history.sourceProducts)
		assertTrue(products.activity is ActivityHistoryQuery.Found)
		assertEquals(
			ActivityHistoryOrigin.LOCAL,
			(products.activity as ActivityHistoryQuery.Found).entry.origin,
		)
		assertFalse(HistorySource.ACTIVITY in selectedNative.history.qualifiedSources)
		assertFalse(HistorySource.LOCATION in selectedNative.history.qualifiedSources)
	}

	@Test
	fun readableCellOriginConflictRemainsNonqualifyingFailedCarrier() = runTest {
		val logicalId = "five-source-cell-readable-conflict"
		val cell = cellAssemblyEntry(logicalId, 100L, 2_000L)
		assertTrue(importCell(cell, "readable-conflict") is
			ImportPortableCapturedCellResult.Applied)
		seedCellOriginClaim(database, logicalId)

		val query = entryPoint.history().observeRecentSourceAwarePage(
			candidateSegmentIds = emptyList(),
			limit = 6,
		).first()
		val content = query as SourceAwareHistoryPageQuery.Content
		val failed = content.entries.filterIsInstance<SourceAwareHistoryPageEntry.CellOnly>()
			.single().history
		assertEquals(CellHistoryProductState.UNVERIFIABLE, failed.state)
		assertEquals(setOf(CellHistoryCause.ORIGIN_IDENTITY_CONFLICT), failed.causes)
		assertTrue(failed.observations.isEmpty())
		val importedOrigin = failed.origin as CellHistoryOrigin.Imported
		assertEquals(cell.identity.value, importedOrigin.selection.identity.value)
	}

	@Test
	fun cumulativeCellReadBudgetFailsWithoutPublishingASelector() = runTest {
		repeat(129) { index ->
			val entry = cellAssemblyEntry(
				logicalId = "five-source-cell-budget-$index",
				olderStartMs = 10_000L + index * 1_000L,
				newestStartMs = 10_100L + index * 1_000L,
			)
			assertTrue(importCell(entry, "budget-$index") is
				ImportPortableCapturedCellResult.Applied)
		}

		assertEquals(
			SourceAwareHistoryPageQuery.Unavailable(
				SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED,
				HistorySource.CELL,
			),
			entryPoint.history().observeRecentSourceAwarePage(
				candidateSegmentIds = emptyList(),
				limit = 1,
			).first(),
		)
	}

	@Test
	fun corruptImportedStepsMakesTheRealSharedPageUnavailableWithoutPhysicalFallback() = runTest {
		val imported = importedStepsAssemblyEntry()
		val segmentId = 920_001L
		seedImportedSteps(database, imported, segmentId)
		database.withTransaction {
			openHelper.writableDatabase.execSQL(
				"UPDATE imported_steps_run SET retained_checksum = ? WHERE entry_identity = ?",
				arrayOf("0".repeat(64), imported.identity.value),
			)
		}

		assertEquals(
			SourceAwareHistoryPageQuery.Unavailable(
				SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
				HistorySource.STEPS,
			),
			entryPoint.history().observeRecentSourceAwarePage(
				candidateSegmentIds = listOf(segmentId),
				limit = 6,
			).first(),
		)
	}

	@Test
	fun lifecycleDesiredActionInvalidatesWithoutChangingSourceEvidenceAuthority() = runTest {
		val beforePage = entryPoint.history().observeRecentSourceAwarePage(
			candidateSegmentIds = emptyList(),
			limit = 1,
		).first() as SourceAwareHistoryPageQuery.Content
		val evidenceBefore = requireNotNull(database.sourceEvidenceStateDao().get())
		val invalidation = async(start = CoroutineStart.UNDISPATCHED) {
			database.invalidationTracker.createFlow(
				"lifecycle_desired_action",
				emitInitialState = false,
			).first()
		}

		database.sourceSessionDao().insertLifecycleActions(
			listOf(lifecycleInvalidationAction()),
		)

		assertTrue("lifecycle_desired_action" in invalidation.await())
		val evidenceAfter = requireNotNull(database.sourceEvidenceStateDao().get())
		val afterPage = entryPoint.history().observeRecentSourceAwarePage(
			candidateSegmentIds = emptyList(),
			limit = 1,
		).first() as SourceAwareHistoryPageQuery.Content
		assertEquals(evidenceBefore, evidenceAfter)
		assertEquals(beforePage.readSnapshot, afterPage.readSnapshot)
	}

	private suspend fun importWifi(
		entry: com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiEntryV1,
		suffix: String,
	): ImportPortableCapturedWifiResult = entryPoint.importWifi().importEntry(
		ImportPortableCapturedWifiRequest(
			entry = entry,
			receipt = PortableCapturedWifiImportReceipt(
				jobId = "five-source-$suffix-job",
				entryKey = "five-source-$suffix-entry",
				sourceName = "five-source.trackerwifi",
				receivedAtMs = 2_500L,
			),
			expectedCollectedDataEpoch = 0L,
		),
	)

	private suspend fun importCell(
		entry: com.adsamcik.tracker.shared.base.database.PortableCapturedCellEntryV1,
		suffix: String,
	): ImportPortableCapturedCellResult = entryPoint.importCell().importEntry(
		ImportPortableCapturedCellRequest(
			entry = entry,
			receipt = PortableCellImportReceipt(
				jobId = "five-source-$suffix-job",
				entryKey = "five-source-$suffix-entry",
				sourceName = "five-source.trackercell",
				receivedAtMs = 1_000_000L,
			),
			expectedCollectedDataEpoch = 0L,
		),
	)

	private fun resetDatabase() = runBlocking {
		withContext(Dispatchers.IO) {
			database.clearAllTables()
			database.sourceEvidenceStateDao().ensure(SourceEvidenceState())
		}
	}

	private fun lifecycleInvalidationAction() = LifecycleDesiredActionEntity(
		actionId = "five-source-invalidation-action",
		logicalTrackingId = "five-source-invalidation-logical",
		serviceRunId = "five-source-invalidation-run",
		manifestRevision = 1L,
		actionRevision = 1L,
		actionFamily = "SOURCE_RUNTIME",
		sourceKind = SourceDestinationOwnerEntity.SOURCE_WIFI,
		desiredState = "STARTED",
		desiredPlanRevision = 1L,
		sourcePolicyRevision = 1L,
		consentEpoch = 1L,
		startOrigin = "MANUAL_FOREGROUND_START",
		bootId = "five-source-invalidation-boot",
		leaseGeneration = 1L,
		requestedAtMs = 1L,
		requestedElapsedRealtimeNanos = 1L,
		status = "START_ACCEPTED",
		attemptCount = 1,
		acknowledgedAtMs = 2L,
		acknowledgedElapsedRealtimeNanos = 2L,
		failureCode = null,
		retryTrigger = null,
		sourceInstanceId = "five-source-invalidation-wifi",
		registrationGeneration = 1L,
	)

	@EntryPoint
	@InstallIn(SingletonComponent::class)
	internal interface FiveSourceHistoryEntryPoint {
		fun database(): AppDatabase
		fun history(): TrackingHistoryRepository
		fun wifiEvaluator(): ImportedWifiProductRecentPageEvaluator
		fun importWifi(): ImportPortableCapturedWifi
		fun importCell(): RoomImportPortableCapturedCell
		fun importActivity(): ImportPortableCapturedActivity
		fun importPressure(): ImportPortablePressure
	}
}
