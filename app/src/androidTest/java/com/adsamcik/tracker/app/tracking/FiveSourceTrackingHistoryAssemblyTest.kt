package com.adsamcik.tracker.app.tracking

import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adsamcik.tracker.app.Application
import com.adsamcik.tracker.app.di.FiveSourceTrackingHistoryEntryPoint
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ImportPortableCapturedActivityRequest
import com.adsamcik.tracker.shared.base.database.ImportPortableCapturedActivityResult
import com.adsamcik.tracker.shared.base.database.ImportPortableCapturedCellRequest
import com.adsamcik.tracker.shared.base.database.ImportPortableCapturedCellResult
import com.adsamcik.tracker.shared.base.database.PortableActivityImportReceipt
import com.adsamcik.tracker.shared.base.database.PortableCellImportReceipt
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryQuery
import com.adsamcik.tracker.stats.api.repository.ActivityHistorySelection
import com.adsamcik.tracker.stats.api.repository.CellHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.HistoryCapture
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.ImportPortableCapturedWifiRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortableCapturedWifiResult
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRecentRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortablePressureRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortablePressureResult
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiImportReceipt
import com.adsamcik.tracker.stats.api.repository.PortablePressureImportReceipt
import com.adsamcik.tracker.stats.api.repository.PressureHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.SessionHistoryQuery
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageEntry
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageQuery
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageUnavailableReason
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryActionTarget
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryReadSnapshot
import com.adsamcik.tracker.stats.api.repository.WifiHistoryOrigin
import com.adsamcik.tracker.stats.data.repository.TrackingHistoryIntegrationCheckpoint
import com.adsamcik.tracker.stats.data.repository.TrackingHistoryIntegrationObserver
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
@Suppress("LargeClass")
class FiveSourceTrackingHistoryAssemblyTest {
	private lateinit var collectedDataLifecycleStore: CollectedDataLifecycleStore
	private lateinit var database: AppDatabase
	private lateinit var entryPoint: FiveSourceTrackingHistoryEntryPoint
	private lateinit var historyIntegrationObserver: TrackingHistoryIntegrationObserver
	private lateinit var startupGate: TrackingStartupGate
	private var collectedDataEpoch: Long = 0L
	private var fixtureTimeOffsetMs: Long = 0L

	@Before
	fun setUp() = runBlocking {
		val application = ApplicationProvider.getApplicationContext<Application>()
		entryPoint = EntryPointAccessors.fromApplication(
			application,
			FiveSourceTrackingHistoryEntryPoint::class.java,
		)
		startupGate = entryPoint.startupGate()
		val startup = application.awaitStartupReconciliation()
		check(startup is TrackingStartupResult.Ready && startupGate.isReady) {
			"Five-source history instrumentation requires completed Ready startup reconciliation"
		}
		collectedDataLifecycleStore = entryPoint.collectedDataLifecycleStore()
		database = entryPoint.database()
		historyIntegrationObserver = entryPoint.historyIntegrationObserver()
		resetDatabase()
	}

	@After
	fun tearDown() = runBlocking {
		resetDatabase()
	}

	@Test
	fun productionGraphComposesCurrentFiveSourcesWithAuthenticatedRecencyAndFinalLimit() = runTest {
		seedExactNativeStepsMembership(database, fixtureTime(1_000L))
		val wifiAlphaV1 = wifiAssemblyEntry(
			"five-source-wifi-alpha",
			fixtureTime(100L),
			fixtureTime(2_000L),
		)
		val wifiAlphaV2 = wifiAssemblyEntry(
			"five-source-wifi-alpha",
			fixtureTime(100L),
			fixtureTime(2_000L),
			semanticRevision = 2L,
		)
		val wifiZulu = wifiAssemblyEntry(
			"five-source-wifi-zulu",
			fixtureTime(100L),
			fixtureTime(2_000L),
		)
		val wifiOld = wifiAssemblyEntry(
			"five-source-wifi-old",
			fixtureTime(50L),
			fixtureTime(500L),
		)
		val cell = cellAssemblyEntry(
			"five-source-cell",
			fixtureTime(100L),
			fixtureTime(2_000L),
		)
		val activity = activityAssemblyEntry(
			'a',
			fixtureTime(100L),
			fixtureTime(2_000L),
		)
		val pressure = pressureAssemblyEntry(
			"five-source-pressure",
			fixtureTime(100L),
			fixtureTime(2_000L),
		)

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
				wifiAssemblyEntry(
					"five-source-wifi-alpha",
					fixtureTime(200L),
					fixtureTime(2_100L),
				).entry,
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
						fixtureTime(2_500L),
					),
					expectedCollectedDataEpoch = collectedDataEpoch,
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
						fixtureTime(2_500L),
					),
					expectedCollectedDataEpoch = collectedDataEpoch,
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
						fixtureTime(2_500L),
					),
					expectedCollectedDataEpoch = collectedDataEpoch,
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
		val selectedActivity =
			content.entries.filterIsInstance<SourceAwareHistoryPageEntry.ActivityOnly>().single()
		assertEquals(ActivityHistoryOrigin.IMPORTED, selectedActivity.history.origin)
		val activityAction = selectedActivity.actionTarget as TrackingHistoryActionTarget.Activity
		val activitySelection =
			(activityAction.selection as ActivityHistorySelection.Imported).selected
		assertEquals(selectedActivity.history.key, activitySelection.key)
		assertEquals(activity.identity.value, activitySelection.identity.value)
		assertEquals(1L, activitySelection.importRevision)
		assertEquals(activity.contentChecksum.value, activitySelection.contentChecksum.value)
		assertEquals(
			evidenceBeforeRead.collectedDataEpoch,
			activitySelection.readSnapshot.collectedDataEpoch,
		)
		assertEquals(
			evidenceBeforeRead.revision,
			activitySelection.readSnapshot.sourceEvidenceRevision,
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
	fun readableCellOriginConflictFailsClosedWithoutPageOrSelector() = runTest {
		val logicalId = "five-source-cell-readable-conflict"
		val cell = cellAssemblyEntry(
			logicalId,
			fixtureTime(100L),
			fixtureTime(2_000L),
		)
		assertTrue(importCell(cell, "readable-conflict") is
			ImportPortableCapturedCellResult.Applied)
		seedCellOriginClaim(database, logicalId, fixtureTime(1L))

		val query = entryPoint.history().observeRecentSourceAwarePage(
			candidateSegmentIds = emptyList(),
			limit = 6,
		).first()
		assertEquals(
			SourceAwareHistoryPageQuery.Unavailable(
				SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
				HistorySource.CELL,
			),
			query,
		)
	}

	@Test
	fun cumulativeCellReadBudgetFailsWithoutPublishingASelector() = runTest {
		repeat(129) { index ->
			val entry = cellAssemblyEntry(
				logicalId = "five-source-cell-budget-$index",
				olderStartMs = fixtureTime(10_000L + index * 1_000L),
				newestStartMs = fixtureTime(10_100L + index * 1_000L),
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
	fun duplicatePressureRecencyAuthorityFailsBeforePressureSelectorOrDetailReads() = runTest {
		val first = pressureRecencyCollisionEntry(
			logicalId = "five-source-pressure-collision-a",
			olderStartMs = fixtureTime(100L),
			newestStartMs = fixtureTime(2_000L),
		)
		val second = pressureRecencyCollisionEntry(
			logicalId = "five-source-pressure-collision-b",
			olderStartMs = fixtureTime(200L),
			newestStartMs = fixtureTime(2_000L),
		)
		assertTrue(importPressure(first, "collision-a") is ImportPortablePressureResult.Applied)
		assertTrue(importPressure(second, "collision-b") is ImportPortablePressureResult.Applied)
		rewritePressureNewestRunIdentityForCollision(
			database = database,
			entry = second,
			collidingRun = first.runs.maxWith(
				compareBy({ it.startTimeMs }, { it.identity.value }),
			),
		)
		val preflight = database.importedPressureDao()
			.recentHistoryRecencyAuthorityPreflight()
		assertEquals(0L, preflight.invalidLiveRecencyCount)
		assertEquals(1L, preflight.duplicateRecencyTupleCount)
		val observation = historyIntegrationObserver.startObservation()
		try {
			assertEquals(
				SourceAwareHistoryPageQuery.Unavailable(
					SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
					HistorySource.PRESSURE,
				),
				entryPoint.history().observeRecentSourceAwarePage(
					candidateSegmentIds = emptyList(),
					limit = 1,
				).first(),
			)
			val snapshot = observation.snapshots.value
			assertEquals(
				1L,
				snapshot.count(
					TrackingHistoryIntegrationCheckpoint.SOURCE_AWARE_READ_STARTED,
				),
			)
			assertEquals(
				1L,
				snapshot.count(
					TrackingHistoryIntegrationCheckpoint.SOURCE_AWARE_READ_FINISHED,
				),
			)
			assertEquals(
				1L,
				snapshot.count(
					TrackingHistoryIntegrationCheckpoint.PRESSURE_RECENCY_PREFLIGHT,
				),
			)
			listOf(
				TrackingHistoryIntegrationCheckpoint.PRESSURE_IMPORTED_CANDIDATE_PAGE,
				TrackingHistoryIntegrationCheckpoint.PRESSURE_IMPORTED_DETAIL,
				TrackingHistoryIntegrationCheckpoint.PRESSURE_LOCAL_DUPLICATE_SELECTOR,
				TrackingHistoryIntegrationCheckpoint.PRESSURE_LIVE_SELECTOR,
			).forEach { checkpoint ->
				assertEquals(0L, snapshot.count(checkpoint))
			}
		} finally {
			observation.close()
		}
	}

	@Test
	fun corruptImportedStepsMakesTheRealSharedPageUnavailableWithoutPhysicalFallback() = runTest {
		val imported = importedStepsAssemblyEntry(fixtureTime(3_000L))
		val segmentId = 920_001L
		seedImportedSteps(database, imported, segmentId, collectedDataEpoch)
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
	fun lifecycleDesiredActionInvalidationRereadsOneActiveRepositoryCollection() = runTest {
		val observation = historyIntegrationObserver.startObservation()
		val emissions = Channel<SourceAwareHistoryPageQuery>(Channel.UNLIMITED)
		val collection = launch(start = CoroutineStart.UNDISPATCHED) {
			entryPoint.history().observeRecentSourceAwarePage(
				candidateSegmentIds = emptyList(),
				limit = 1,
			).collect { emissions.send(it) }
		}
		try {
			val beforePage = emissions.receive() as SourceAwareHistoryPageQuery.Content
			assertTrue(collection.isActive)
			val beforeCheckpoint = observation.snapshots.first { snapshot ->
				snapshot.count(
					TrackingHistoryIntegrationCheckpoint.SOURCE_AWARE_READ_FINISHED,
				) >= 1L
			}
			val finishedBefore = beforeCheckpoint.count(
				TrackingHistoryIntegrationCheckpoint.SOURCE_AWARE_READ_FINISHED,
			)
			val lifecycleReadsBefore = beforeCheckpoint.count(
				TrackingHistoryIntegrationCheckpoint
					.SOURCE_AWARE_LIFECYCLE_INVALIDATION_READ,
			)
			val evidenceBefore = requireNotNull(database.sourceEvidenceStateDao().get())

			database.sourceSessionDao().insertLifecycleActions(
				listOf(lifecycleInvalidationAction()),
			)

			val afterCheckpoint = observation.snapshots.first { snapshot ->
				snapshot.count(
					TrackingHistoryIntegrationCheckpoint
						.SOURCE_AWARE_LIFECYCLE_INVALIDATION_READ,
				) > lifecycleReadsBefore &&
					snapshot.count(
						TrackingHistoryIntegrationCheckpoint.SOURCE_AWARE_READ_FINISHED,
					) > finishedBefore
			}
			assertEquals(
				finishedBefore + 1L,
				afterCheckpoint.count(
					TrackingHistoryIntegrationCheckpoint.SOURCE_AWARE_READ_FINISHED,
				),
			)
			assertEquals(
				lifecycleReadsBefore + 1L,
				afterCheckpoint.count(
					TrackingHistoryIntegrationCheckpoint
						.SOURCE_AWARE_LIFECYCLE_INVALIDATION_READ,
				),
			)
			assertEquals(
				evidenceBefore,
				requireNotNull(database.sourceEvidenceStateDao().get()),
			)
			assertEquals(
				TrackingHistoryReadSnapshot(
					evidenceBefore.collectedDataEpoch,
					evidenceBefore.revision,
				),
				beforePage.readSnapshot,
			)
			assertTrue(collection.isActive)
		} finally {
			collection.cancelAndJoin()
			emissions.close()
			observation.close()
		}
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
				receivedAtMs = fixtureTime(2_500L),
			),
			expectedCollectedDataEpoch = collectedDataEpoch,
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
				receivedAtMs = fixtureTime(1_000_000L),
			),
			expectedCollectedDataEpoch = collectedDataEpoch,
		),
	)

	private suspend fun importPressure(
		entry: com.adsamcik.tracker.stats.api.repository.PortablePressureEntryV1,
		suffix: String,
	): ImportPortablePressureResult = entryPoint.importPressure().importEntry(
		ImportPortablePressureRequest(
			entry = entry,
			receipt = PortablePressureImportReceipt(
				jobId = "five-source-$suffix-job",
				entryKey = "five-source-$suffix-entry",
				sourceName = "five-source.trackerpressure",
				receivedAtMs = fixtureTime(2_500L),
			),
			expectedCollectedDataEpoch = collectedDataEpoch,
		),
	)

	private suspend fun resetDatabase() {
		val generation = startupGate.currentGeneration
		collectedDataEpoch = requireNotNull(
			startupGate.withReadyGenerationOperation(generation) {
				val lifecycle = collectedDataLifecycleStore.snapshot()
				check(lifecycle.epoch >= 0L) {
					"Collected-data lifecycle epoch must be non-negative"
				}
				withContext(Dispatchers.IO) {
					requireDisposableRuntime()
					val previousEvidence = requireNotNull(
						database.sourceEvidenceStateDao().get(),
					)
					check(previousEvidence.collectedDataEpoch == lifecycle.epoch)
					check(previousEvidence.retainedFromMs == lifecycle.retainedFromMs)
					val deletedSourceEventHighWater = maxOf(
						previousEvidence.deletedSourceEventHighWaterOrdinal,
						sourceEventWalHighWater(),
					)
					database.clearAllTables()
					database.sourceEvidenceStateDao().ensure(
						SourceEvidenceState(
							revision = Math.addExact(previousEvidence.revision, 1L),
							collectedDataEpoch = lifecycle.epoch,
							retainedFromMs = lifecycle.retainedFromMs,
							deletedSourceEventHighWaterOrdinal = deletedSourceEventHighWater,
							updatedAtMs = System.currentTimeMillis(),
						),
					)
					val evidence = requireNotNull(database.sourceEvidenceStateDao().get())
					check(evidence.collectedDataEpoch == lifecycle.epoch)
					check(evidence.retainedFromMs == lifecycle.retainedFromMs)
					check(
						evidence.deletedSourceEventHighWaterOrdinal ==
							deletedSourceEventHighWater,
					)
				}
				fixtureTimeOffsetMs = Math.addExact(
					lifecycle.retainedFromMs ?: 0L,
					FIXTURE_TIME_MARGIN_MS,
				)
				lifecycle.epoch
			},
		) {
			"Five-source history database reset lost startup readiness"
		}
	}

	private suspend fun requireDisposableRuntime() {
		check(database.sourceSessionDao().activeSession() == null) {
			"Five-source history instrumentation cannot clear an active logical session"
		}
		listOf(
			"SELECT COUNT(*) FROM source_service_run " +
				"WHERE state NOT IN ('FINALIZED', 'CLOSED', 'FAILED') OR completed_at_ms IS NULL",
			"SELECT COUNT(*) FROM source_demand " +
				"WHERE status IN ('ACTIVE', 'RETIRING', 'BLOCKED')",
			"SELECT COUNT(*) FROM provider_registration_generation " +
				"WHERE status IN ('RESERVED', 'ACTIVE', 'RETIRING')",
			"SELECT COUNT(*) FROM tracker_run " +
				"WHERE end_time_ms IS NULL AND legacy_runtime_fenced = 0",
		).forEach { query ->
			check(queryLong(query) == 0L) {
				"Five-source history instrumentation requires an idle tracking runtime"
			}
		}
	}

	private fun queryLong(query: String): Long =
		database.openHelper.writableDatabase.query(query).use { cursor ->
			check(cursor.moveToFirst())
			cursor.getLong(0)
		}

	private fun sourceEventWalHighWater(): Long = queryLong(
		"SELECT MAX(" +
			"COALESCE((SELECT seq FROM sqlite_sequence WHERE name = 'source_event_wal'), 0), " +
			"COALESCE((SELECT MAX(admission_ordinal) FROM source_event_wal), 0))",
	)

	private fun fixtureTime(relativeTimeMs: Long): Long =
		Math.addExact(fixtureTimeOffsetMs, relativeTimeMs)

	private fun lifecycleInvalidationAction() = LifecycleDesiredActionEntity(
		actionId = "five-source-invalidation-action",
		logicalTrackingId = "five-source-invalidation-logical",
		serviceRunId = "five-source-invalidation-run",
		manifestRevision = 1L,
		actionRevision = 1L,
		actionFamily = "SOURCE_RUNTIME",
		sourceKind = SourceDestinationOwnerEntity.SOURCE_WIFI,
		desiredState = "STOPPED",
		desiredPlanRevision = 1L,
		sourcePolicyRevision = 1L,
		consentEpoch = null,
		startOrigin = "FIVE_SOURCE_HISTORY_TEST",
		bootId = "five-source-invalidation-boot",
		leaseGeneration = 1L,
		requestedAtMs = fixtureTime(1L),
		requestedElapsedRealtimeNanos = 1L,
		status = "STOP_ACCEPTED",
		attemptCount = 1,
		acknowledgedAtMs = fixtureTime(2L),
		acknowledgedElapsedRealtimeNanos = 2L,
		failureCode = null,
		retryTrigger = null,
		sourceInstanceId = null,
		registrationGeneration = null,
	)

	private companion object {
		const val FIXTURE_TIME_MARGIN_MS = 1_000_000L
	}
}
