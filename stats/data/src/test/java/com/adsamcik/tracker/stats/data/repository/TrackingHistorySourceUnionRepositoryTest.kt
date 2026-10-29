package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.base.database.PortableActivityDigest
import com.adsamcik.tracker.shared.base.database.PortableActivityOpaqueIdentity
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.LocationPointCorrection
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.repository.ActivityActiveTime
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryConfidence
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryFragment
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryMechanism
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryWallTimeContinuity
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCause
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntry
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryObservation
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryProductState
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryQuery
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryType
import com.adsamcik.tracker.stats.api.repository.CellHistoryCause
import com.adsamcik.tracker.stats.api.repository.CellHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntry
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.CellHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.CellHistoryProductState
import com.adsamcik.tracker.stats.api.repository.CellHistoryQuery
import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.HistoryCapture
import com.adsamcik.tracker.stats.api.repository.HistoryCaptureRevision
import com.adsamcik.tracker.stats.api.repository.HistoryEvidence
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.LiveSessionHistoryDetail
import com.adsamcik.tracker.stats.api.repository.ImportedCellHistoryDigest
import com.adsamcik.tracker.stats.api.repository.ImportedCellHistoryIdentity
import com.adsamcik.tracker.stats.api.repository.ImportedCellHistorySelection
import com.adsamcik.tracker.stats.api.repository.ImportedPressureHistoryIdentity
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluation
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluator
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRangePage
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRangeRequest
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRecentPage
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRecentPageEvaluator
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRecentRequest
import com.adsamcik.tracker.stats.api.repository.ReadLocalPortableCapturedWifi
import com.adsamcik.tracker.stats.api.repository.ReadLocalPortableCapturedWifiResult
import com.adsamcik.tracker.stats.api.repository.ExportPortableCapturedWifiRequest
import com.adsamcik.tracker.stats.api.repository.PressureHistory
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCause
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.PressureHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.PressureHistoryWindow
import com.adsamcik.tracker.stats.api.repository.PressureOnlyHistoryEntry
import com.adsamcik.tracker.stats.api.repository.PortablePressureDigest
import com.adsamcik.tracker.stats.api.repository.PressureSessionHistory
import com.adsamcik.tracker.stats.api.repository.PressureSessionHistoryQuery
import com.adsamcik.tracker.stats.api.repository.PressureSensorAccuracy
import com.adsamcik.tracker.stats.api.repository.PressureWindowClosure
import com.adsamcik.tracker.stats.api.repository.PressureWindowQualification
import com.adsamcik.tracker.stats.api.repository.SessionHistoryQuery
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageEntry
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageQuery
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageUnavailableReason
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryReadSnapshot
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCause
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.WifiHistoryEntry
import com.adsamcik.tracker.stats.api.repository.WifiHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.WifiHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.WifiHistoryProductState
import com.adsamcik.tracker.stats.api.repository.WifiHistoryQuery
import com.adsamcik.tracker.stats.api.repository.WifiDeletedHistoryReader
import com.adsamcik.tracker.stats.api.repository.WifiDeletedHistoryResult
import com.adsamcik.tracker.stats.api.repository.WifiImportedHistorySelection
import com.adsamcik.tracker.stats.api.repository.WifiImportedHistorySelectionKey
import com.adsamcik.tracker.stats.api.repository.WifiLocalHistorySelectionKey
import com.adsamcik.tracker.stats.api.value.EpochMs
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageProblem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// One real Room history-union fixture keeps typed composition and snapshot invalidation comparable.
@Suppress("LargeClass")
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackingHistorySourceUnionRepositoryTest {
	private lateinit var database: AppDatabase
	private lateinit var sourceReader: FakeSourceUnionReader
	private lateinit var repository: DefaultTrackingHistoryRepository

	@Before
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(
				revision = 4L,
				collectedDataEpoch = 3L,
				updatedAtMs = 10L,
			),
		)
		sourceReader = FakeSourceUnionReader(database)
		val lane = SourceProductLaneExecutionAuthority { false }
		val steps = StepsSegmentHistorySelector(database, lane)
		repository = DefaultTrackingHistoryRepository(
			database = database,
			stepsSelector = steps,
			logicalHistoryReader = LogicalTrackingHistoryReader(database, steps),
			pressureSelector = PressureHistorySelector(database, lane),
			sourceUnionReader = sourceReader,
			ioDispatcher = UnconfinedTestDispatcher(),
		)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `one Room snapshot merges every portable source origin with producer selectors`() = runTest {
		sourceReader.recent = HistoricalSourceUnionRead.Available(
			listOf(
				importedActivityRow(startMs = 400L),
				importedPressureRow(startMs = 300L),
				importedCellRow(startMs = 200L),
				importedWifiRow(startMs = 100L),
			),
		)

		val query = repository.observeRecentSourceAwarePage(emptyList(), 10).first() as
			SourceAwareHistoryPageQuery.Content

		query.readSnapshot?.collectedDataEpoch shouldBe 3L
		query.readSnapshot?.sourceEvidenceRevision shouldBe 4L
		query.entries.map { it.source } shouldContainExactly listOf(
			HistorySource.ACTIVITY,
			HistorySource.PRESSURE,
			HistorySource.CELL,
			HistorySource.WIFI,
		)
		query.entries.all {
			it.intent == com.adsamcik.tracker.stats.api.repository.SourceOnlyHistoryIntent
				.PORTABLE_SOURCE_MEMBERSHIP
		} shouldBe true
		sourceReader.candidateCalls shouldBe 1
		sourceReader.recentCalls shouldBe 1
	}

	@Test
	fun `final union preserves native tie order before deterministic imported source order`() =
		runTest {
			sourceReader.recent = HistoricalSourceUnionRead.Available(
				listOf(
					importedCellRow(startMs = 500L),
					importedWifiRow(startMs = 500L),
					nativeWifiRow(startMs = 500L, segmentId = 51L),
				),
			)

			val query = repository.observeRecentSourceAwarePage(emptyList(), 10).first() as
				SourceAwareHistoryPageQuery.Content

			query.entries.map { entry ->
				when (entry) {
					is SourceAwareHistoryPageEntry.WifiOnly ->
						"wifi-${entry.history.origin.name.lowercase()}"
					is SourceAwareHistoryPageEntry.CellOnly ->
						"cell-${if (entry.history.origin is CellHistoryOrigin.Imported) {
							"imported"
						} else {
							"local"
						}}"
					else -> error("Unexpected test source")
				}
			} shouldContainExactly listOf("wifi-local", "wifi-imported", "cell-imported")
		}

	@Test
	fun `direct source fixture composes an empty eligible snapshot without provider probes`() =
		runTest {
			val actualRepository = productionRepository(UnconfinedTestDispatcher(testScheduler))

			actualRepository.observeRecentSourceAwarePage(emptyList(), 10).first() shouldBe
				SourceAwareHistoryPageQuery.Content(
					entries = emptyList(),
					readSnapshot =
						com.adsamcik.tracker.stats.api.repository.TrackingHistoryReadSnapshot(
							3L,
							4L,
						),
				)
		}

	@Test
	fun `real recent readers keep the healthy sixty fifth physical candidate visible`() = runTest {
		for (id in 1L..65L) {
			val samples = if (id == 1L) { 2 } else if (id <= 10L) { 1 } else { 0 }
			database.sessionSegmentDao().insert(legacySegment(id, samples).copy(
				startTimeMs = id * 1_000L, endTimeMs = id * 1_000L + 500L,
				source = SegmentSource.LEGACY_MIGRATION))
		}
		val first = LocationSample(timeMs = 1_050L, elapsedRealtimeNanos = 0L, latE7 = 0, lonE7 = 0,
			altitudeM = null, rawGpsAltitudeM = null, hAccM = null, vAccM = null, speedMps = null,
			speedAccuracyMps = null, provider = "legacy", quality = SampleQuality.HIGH, motionState = null,
			policy = null, bucketId = null, createdAt = 1_050L)
		database.locationSampleDao().insert(listOf(first, first.copy(timeMs = 1_060L, lonE7 = 100_000)))

		val page = productionRepository(UnconfinedTestDispatcher(testScheduler))
			.observeRecentHistoryPage(100).first() as SourceAwareHistoryPageQuery.Content

		page.entries.contains(SourceAwareHistoryPageEntry.Physical(1L)) shouldBe true
		val retained = requireNotNull(page.physicalTrips[1L])
		retained.hasMeasuredDistance shouldBe true
		(retained.distanceM > 1_000f) shouldBe true
		page.readSnapshot shouldBe TrackingHistoryReadSnapshot(3L, 4L)
	}

	// Keep all four typed imported readers and the resulting shared page together to verify their exact wiring.
	@Suppress("LongMethod")
	@Test
	fun `shared reader wires typed eligible pages without claiming producer Room coverage`() =
		runTest {
			val lane = SourceProductLaneExecutionAuthority { false }
			val dispatcher = UnconfinedTestDispatcher(testScheduler)
			val pressure = PressureHistorySelector(database, lane)
			val wifiEligible = wifiEligible(100L)
			val cellEligible = cellEligible(200L)
			val activityEligible = activityEligible(400L)
			val pressureEligible = pressureEligible(300L)
			val actualSourceReader = DefaultTrackingHistorySourceUnionReader(
				wifi = emptyWifiRepository(lane, dispatcher),
				cell = DefaultCellHistoryRepository(database, lane, dispatcher),
				activity = DefaultActivityHistoryRepository(database, lane, dispatcher),
				pressureSelector = pressure,
				wifiImported = object : WifiImportedHistoryEligibleReader {
					override suspend fun recentImportedEligibleForSharedHistoryInTransaction(
						limit: Int,
					) = ImportedHistoryEligiblePage.Available(listOf(wifiEligible))
				},
				cellImported = object : CellImportedHistoryEligibleReader {
					override suspend fun recentImportedEligibleForSharedHistoryInTransaction(
						limit: Int,
					) = ImportedHistoryEligiblePage.Available(listOf(cellEligible))
				},
				activityImported = object : ActivityImportedHistoryEligibleReader {
					override suspend fun recentImportedEligibleForSharedHistoryInTransaction(
						limit: Int,
					) = ImportedHistoryEligiblePage.Available(listOf(activityEligible))
				},
				pressureImported = object : PressureImportedHistoryEligibleReader {
					override suspend fun recentImportedEligibleForSharedHistoryInTransaction(
						limit: Int,
					) = ImportedHistoryEligiblePage.Available(listOf(pressureEligible))
				},
			)
			val union = when (val result = actualSourceReader.recentInTransaction(10)) {
				is HistoricalSourceUnionRead.Available -> result.value
				is HistoricalSourceUnionRead.Unavailable ->
					error("Typed interface wiring unexpectedly failed: ${result.reason}")
			}
			union.filterIsInstance<HistoricalSourceUnionEntry.ImportedWifi>()
				.single().eligible.selection shouldBe wifiEligible.selection
			union.filterIsInstance<HistoricalSourceUnionEntry.ImportedCell>()
				.single().eligible.selection shouldBe cellEligible.selection
			union.filterIsInstance<HistoricalSourceUnionEntry.ImportedActivity>()
				.single().eligible.let { retained ->
					retained.selector shouldBe activityEligible.selector
					retained.importRevision shouldBe activityEligible.importRevision
					retained.contentChecksum shouldBe activityEligible.contentChecksum
				}
			union.filterIsInstance<HistoricalSourceUnionEntry.ImportedPressure>()
				.single().eligible.let { retained ->
					retained.identity shouldBe pressureEligible.identity
					retained.importRevision shouldBe pressureEligible.importRevision
					retained.contentChecksum shouldBe pressureEligible.contentChecksum
				}

			val steps = StepsSegmentHistorySelector(database, lane)
			val actualRepository = DefaultTrackingHistoryRepository(
				database = database,
				stepsSelector = steps,
				logicalHistoryReader = LogicalTrackingHistoryReader(database, steps),
				pressureSelector = pressure,
				sourceUnionReader = actualSourceReader,
				ioDispatcher = dispatcher,
			)
			val page = actualRepository.observeRecentSourceAwarePage(emptyList(), 10).first() as
				SourceAwareHistoryPageQuery.Content
			page.entries.map { it.source } shouldContainExactly listOf(
				HistorySource.ACTIVITY,
				HistorySource.PRESSURE,
				HistorySource.CELL,
				HistorySource.WIFI,
			)
		}

	@Test
	fun `cross-source contradictory native membership fails before recent discovery`() = runTest {
		database.sessionSegmentDao().insert(legacySegment(id = 41L, sampleCount = 0))
		sourceReader.candidates = HistoricalSourceUnionRead.Available(
			listOf(
				HistoricalSourceOnlyMembership(HistorySource.WIFI, "logical-wifi", listOf(41L)),
				HistoricalSourceOnlyMembership(HistorySource.CELL, "logical-cell", listOf(41L)),
			),
		)

		val query = repository.observeRecentSourceAwarePage(listOf(41L), 10).first()

		query shouldBe SourceAwareHistoryPageQuery.Unavailable(
			SourceAwareHistoryPageUnavailableReason.SOURCE_IDENTITY_COLLISION,
		)
		sourceReader.recentCalls shouldBe 0
	}

    @Test fun `source read debt preserves qualified siblings without raw physical fallback`() = runTest {
        val problem = SourceAwareHistoryPageProblem(HistorySource.WIFI,
            SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED)
        sourceReader.candidates = HistoricalSourceUnionRead.Available(emptyList(), setOf(problem))
        sourceReader.recent = HistoricalSourceUnionRead.Available(listOf(importedActivityRow(400L)), setOf(problem))
        val page = repository.observeRecentSourceAwarePage(emptyList(), 10).first() as SourceAwareHistoryPageQuery.Content
        page.entries.map { it.source } shouldBe listOf(HistorySource.ACTIVITY)
        page.problems shouldBe setOf(problem)
        page.entries.any { it is SourceAwareHistoryPageEntry.Physical } shouldBe false
    }

    @Test fun `shared recent page includes an independent source recording with no route candidate`() = runTest {
        database.sessionSegmentDao().insert(legacySegment(51L, sampleCount = 0))
        sourceReader.recent = HistoricalSourceUnionRead.Available(listOf(nativeWifiRow(500L, 51L)))
        val page = repository.observeRecentHistoryPage(3).first() as SourceAwareHistoryPageQuery.Content
        page.entries.map { it.source } shouldBe listOf(HistorySource.WIFI)
        page.physicalTrips shouldBe emptyMap()
    }

	@Test fun `recent history retains a closed mixed Activity and Pressure session without Location`() = runTest {
		installClosedMixedOwner()
		val products = mixedSensorProducts()
		sourceReader.session = products

		val page = repository.observeRecentHistoryPage(3).first() as SourceAwareHistoryPageQuery.Content
		page.entries shouldBe listOf(SourceAwareHistoryPageEntry.Physical(41L))
		page.problems shouldBe emptySet()
		val display = requireNotNull(page.physicalTrips[41L])
		display.startTimeMs shouldBe 100L
		display.endTimeMs shouldBe 200L
		display.hasMeasuredDistance shouldBe false
		database.tripDao().getRecentTrips(1) shouldBe emptyList()
		val details = repository.observeLiveSession(41L).first()
		(details.session as SessionHistoryQuery.Found).history.qualifiedSources shouldBe
			setOf(HistorySource.ACTIVITY, HistorySource.PRESSURE)
		details.activity shouldBe products.activity
		details.pressure shouldBe products.pressure
	}

	@Test fun `recent mixed session survives an unavailable source and withholds an empty measurement`() = runTest {
		installClosedMixedOwner()
		val products = mixedSensorProducts()
		val failedActivity = ActivityHistoryQuery.Found(localUnavailableActivity().copy(
			state = ActivityHistoryProductState.FAILED,
			causes = setOf(ActivityHistoryCause.FACT_INTEGRITY_FAILED)))
		sourceReader.session = products.copy(activity = failedActivity)

		val page = repository.observeRecentHistoryPage(3).first() as SourceAwareHistoryPageQuery.Content
		page.entries shouldBe listOf(SourceAwareHistoryPageEntry.Physical(41L))
		page.physicalTrips.getValue(41L).hasMeasuredDistance shouldBe false
		val details = repository.observeLiveSession(41L).first()
		(details.session as SessionHistoryQuery.Found).history.qualifiedSources shouldBe setOf(HistorySource.PRESSURE)
		details.activity shouldBe failedActivity
		details.pressure shouldBe products.pressure

		sourceReader.session = products.copy(activity = failedActivity, pressure = PressureSessionHistoryQuery.NotFound)
		val empty = repository.observeRecentHistoryPage(3).first() as SourceAwareHistoryPageQuery.Content
		empty.entries shouldBe emptyList()
		empty.physicalTrips shouldBe emptyMap()
	}

	@Test fun `recent page qualifies only the newest physical rows needed for display`() = runTest {
		installRecentPressureCandidates()
		val page = repository.observeRecentHistoryPage(5).first() as SourceAwareHistoryPageQuery.Content
		page.entries shouldBe (10L downTo 6L).map(SourceAwareHistoryPageEntry::Physical)
		sourceReader.sessionSegmentRequests shouldBe (10L downTo 6L).toList()
		page.problems shouldBe emptySet()
	}

	@Test fun `recent qualification skips unavailable newer rows until the requested page is full`() = runTest {
		installRecentPressureCandidates()
		for (id in listOf(10L, 9L)) {
			sourceReader.sessions[id] = HistoricalSessionSourceRead.Available(id, WifiHistoryQuery.NotFound,
				CellHistoryQuery.NotFound, ActivityHistoryQuery.NotFound, PressureSessionHistoryQuery.NotFound)
		}
		val page = repository.observeRecentHistoryPage(5).first() as SourceAwareHistoryPageQuery.Content
		page.entries shouldBe (8L downTo 4L).map(SourceAwareHistoryPageEntry::Physical)
		sourceReader.sessionSegmentRequests shouldBe (10L downTo 4L).toList()
		page.problems shouldBe emptySet()
	}

	@Test fun `an older physical identity conflict is still checked beyond a full recent page`() = runTest {
		installRecentPressureCandidates()
		sourceReader.recent = HistoricalSourceUnionRead.Available(listOf(nativeWifiRow(500L, 1L)))
		repository.observeRecentHistoryPage(5).first() shouldBe SourceAwareHistoryPageQuery.Unavailable(
			SourceAwareHistoryPageUnavailableReason.SOURCE_IDENTITY_COLLISION)
		sourceReader.sessionSegmentRequests shouldBe (10L downTo 7L).toList() + 1L

		sourceReader.sessionSegmentRequests.clear()
		sourceReader.sessions[1L] = HistoricalSessionSourceRead.Available(1L, WifiHistoryQuery.NotFound,
			CellHistoryQuery.NotFound, ActivityHistoryQuery.NotFound, PressureSessionHistoryQuery.NotFound)
		val page = repository.observeRecentHistoryPage(5).first() as SourceAwareHistoryPageQuery.Content
		page.entries.first().source shouldBe HistorySource.WIFI
		page.entries.drop(1) shouldBe (10L downTo 7L).map(SourceAwareHistoryPageEntry::Physical)
		sourceReader.sessionSegmentRequests shouldBe (10L downTo 7L).toList() + 1L
	}

	@Test fun `newer source and imported rows fill the page without qualifying older empty physical candidates`() = runTest {
		installRecentPressureCandidates()
		sourceReader.sessions.clear()
		val recent = listOf(nativeWifiRow(1_000L, 51L), importedActivityRow(900L),
			importedPressureRow(800L), importedCellRow(700L), importedWifiRow(600L))
		sourceReader.recent = HistoricalSourceUnionRead.Available(recent)

		val page = repository.observeRecentHistoryPage(5).first() as SourceAwareHistoryPageQuery.Content

		page.entries shouldBe recent.map { it.entry }
		page.physicalTrips shouldBe emptyMap()
		sourceReader.sessionSegmentRequests shouldBe emptyList()
		page.problems shouldBe emptySet()
	}

	@Test fun `an examined recent window reports a clipped timestamp tie without scanning older rows`() = runTest {
		for (id in 1L..101L) {
			database.sessionSegmentDao().insert(legacySegment(id, sampleCount = 1).copy(endTimeMs = 200L + id))
		}

		val page = repository.observeRecentHistoryPage(3).first() as SourceAwareHistoryPageQuery.Content

		page.entries.map { (it as SourceAwareHistoryPageEntry.Physical).segmentId } shouldBe listOf(101L, 100L, 99L)
		page.problems shouldBe setOf(SourceAwareHistoryPageProblem(null,
			SourceAwareHistoryPageUnavailableReason.CANDIDATE_SCAN_LIMIT))
	}

	@Test fun `bounded physical discovery retains independent rows and marks an unfinished scan`() = runTest {
		for (id in 1L..101L) {
			database.sessionSegmentDao().insert(legacySegment(id, sampleCount = 1).copy(
				startTimeMs = id * 1_000L, endTimeMs = id * 1_000L + 500L))
		}
		sourceReader.candidates = HistoricalSourceUnionRead.Available(listOf(
			HistoricalSourceOnlyMembership(HistorySource.WIFI, "source-only", (2L..101L).toList())))
		sourceReader.recent = HistoricalSourceUnionRead.Available(listOf(importedActivityRow(400_000L)))

		val partial = repository.observeRecentHistoryPage(3).first() as SourceAwareHistoryPageQuery.Content
		partial.entries.map { it.source } shouldBe listOf(HistorySource.ACTIVITY)
		partial.physicalTrips shouldBe emptyMap()
		partial.problems shouldBe setOf(SourceAwareHistoryPageProblem(null,
			SourceAwareHistoryPageUnavailableReason.CANDIDATE_SCAN_LIMIT))

		sourceReader.candidates = HistoricalSourceUnionRead.Available(emptyList())
		sourceReader.recent = HistoricalSourceUnionRead.Available(emptyList())
		val filled = repository.observeRecentHistoryPage(3).first() as SourceAwareHistoryPageQuery.Content
		filled.entries.map { (it as SourceAwareHistoryPageEntry.Physical).segmentId } shouldBe listOf(101L, 100L, 99L)
		filled.problems shouldBe emptySet()
	}

	@Test fun `cancelling a reached shared recent selection remains cancellation`() = runTest {
		val cancelled = CancellationException("Recent selection no longer needed")
		sourceReader.recentFailure = cancelled

		val observed = runCatching { repository.observeRecentHistoryPage(3).first() }.exceptionOrNull()

		observed shouldBe cancelled
		sourceReader.recentCalls shouldBe 1
	}

	@Test fun `shared recent page measures saved corrected points and retains independent source recordings`() = runTest {
		database.sessionSegmentDao().insert(legacySegment(41L, sampleCount = 2).copy(source = SegmentSource.LEGACY_MIGRATION))
		val first = LocationSample(timeMs = 150L, elapsedRealtimeNanos = 0L, latE7 = 0, lonE7 = 0,
			altitudeM = null, rawGpsAltitudeM = null, hAccM = null, vAccM = null, speedMps = null,
			speedAccuracyMps = null, provider = "legacy", quality = SampleQuality.HIGH, motionState = null,
			policy = null, bucketId = null, createdAt = 150L)
		val last = first.copy(timeMs = 160L, lonE7 = 100_000)
		val ids = database.locationSampleDao().insert(listOf(first, last))
		sourceReader.recent = HistoricalSourceUnionRead.Available(listOf(importedWifiRow(400L)))

		val initial = repository.observeRecentHistoryPage(3).first() as SourceAwareHistoryPageQuery.Content
		val measured = requireNotNull(initial.physicalTrips[41L])
		measured.hasMeasuredDistance shouldBe true
		(measured.distanceM > 1_000f) shouldBe true
		initial.entries.map { it.source } shouldBe listOf(HistorySource.WIFI, null)
		initial.problems shouldBe emptySet()

		database.locationPointCorrectionDao().replace(LocationPointCorrection.from(ids.last(), 3L, 1L,
			last.copy(lonE7 = 200_000)))
		val corrected = repository.observeRecentHistoryPage(3).first() as SourceAwareHistoryPageQuery.Content
		(requireNotNull(corrected.physicalTrips[41L]).distanceM > measured.distanceM) shouldBe true
		corrected.problems shouldBe emptySet()
		database.tripDao().getById(41L)?.distanceM shouldBe 1f
		database.locationSampleDao().byIds(ids).single { it.id == ids.last() }.lonE7 shouldBe 100_000
	}

    @Test fun `production source union continues after Wi-Fi failure and keeps imported Activity`() = runTest {
        val wifi = mockk<DefaultWifiHistoryRepository>()
        val cell = mockk<DefaultCellHistoryRepository>()
        val activity = mockk<DefaultActivityHistoryRepository>()
        val pressure = mockk<PressureHistorySelector>()
        val wifiImports = mockk<WifiImportedHistoryEligibleReader>()
        val cellImports = mockk<CellImportedHistoryEligibleReader>()
        val activityImports = mockk<ActivityImportedHistoryEligibleReader>()
        val pressureImports = mockk<PressureImportedHistoryEligibleReader>()
        coEvery { wifi.recentWifiOnlyInTransaction(5) } returns WifiComposedPage.Failed(WifiHistoryCause.FACT_INTEGRITY_FAILED)
        coEvery { cell.recentCellOnlyInTransaction(5) } returns CellComposedPage.Available(emptyList())
        coEvery { activity.recentActivityOnlyInTransaction(5) } returns ActivityComposedPage.Available(emptyList())
        coEvery { pressure.discoverRecentPressureOnlyIntentInTransaction(5) } returns PressureOnlyDiscoveryResult.Content(emptyList())
        coEvery { wifiImports.recentImportedEligibleForSharedHistoryInTransaction(5) } returns ImportedHistoryEligiblePage.Available(emptyList())
        coEvery { cellImports.recentImportedEligibleForSharedHistoryInTransaction(5) } returns ImportedHistoryEligiblePage.Available(emptyList())
        coEvery { activityImports.recentImportedEligibleForSharedHistoryInTransaction(5) } returns
            ImportedHistoryEligiblePage.Available(listOf(activityEligible(400L)))
        coEvery { pressureImports.recentImportedEligibleForSharedHistoryInTransaction(5) } returns ImportedHistoryEligiblePage.Available(emptyList())
        val union = DefaultTrackingHistorySourceUnionReader(wifi, cell, activity, pressure,
            wifiImports, cellImports, activityImports, pressureImports)
        val result = union.recentInTransaction(5) as HistoricalSourceUnionRead.Available<List<HistoricalSourceUnionEntry>>
        result.value.map { it.entry.source } shouldBe listOf(HistorySource.ACTIVITY)
        result.problems shouldBe setOf(SourceAwareHistoryPageProblem(HistorySource.WIFI,
            SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE))
    }

	@Test
	fun `missing source evidence state is a shared unavailable result`() = runTest {
		database.openHelper.writableDatabase.execSQL("DELETE FROM source_evidence_state")

		repository.observeRecentSourceAwarePage(emptyList(), 10).first() shouldBe
			SourceAwareHistoryPageQuery.Unavailable(
				SourceAwareHistoryPageUnavailableReason.SOURCE_EVIDENCE_STATE_UNAVAILABLE,
			)
		sourceReader.candidateCalls shouldBe 0
		sourceReader.recentCalls shouldBe 0
	}

	@Test
	fun `factless native-only intent remains visible without fake observations`() = runTest {
		sourceReader.recent = HistoricalSourceUnionRead.Available(
			listOf(nativeWifiRow(startMs = 500L, segmentId = 51L)),
		)

		val query = repository.observeRecentSourceAwarePage(emptyList(), 10).first() as
			SourceAwareHistoryPageQuery.Content
		val wifi = (query.entries.single() as SourceAwareHistoryPageEntry.WifiOnly).history

		wifi.capturesOnlyWifi shouldBe true
		wifi.state shouldBe WifiHistoryProductState.MATERIALIZING
		wifi.observations shouldBe emptyList()
	}

	@Test
	fun `retention authority revision invalidates the complete snapshot`() = runTest {
		val firstEmission = CompletableDeferred<Unit>()
		val collection = async {
			repository.observeRecentSourceAwarePage(emptyList(), 10)
				.onEach { firstEmission.complete(Unit) }
				.take(2)
				.toList()
		}
		firstEmission.await()
		database.sourceEvidenceStateDao().updateLifecycle(
			epoch = 3L,
			retainedFromMs = 100L,
			updatedAtMs = 20L,
		) shouldBe 1

		val snapshots = collection.await().map {
			(it as SourceAwareHistoryPageQuery.Content).readSnapshot
		}
		snapshots.map { it?.sourceEvidenceRevision } shouldContainExactly listOf(4L, 5L)
		snapshots.map { it?.collectedDataEpoch } shouldContainExactly listOf(3L, 3L)
	}

	@Test
	fun `full deletion authority invalidates epoch and revision together`() = runTest {
		val firstEmission = CompletableDeferred<Unit>()
		val collection = async {
			repository.observeRecentSourceAwarePage(emptyList(), 10)
				.onEach { firstEmission.complete(Unit) }
				.take(2)
				.toList()
		}
		firstEmission.await()
		database.sourceEvidenceStateDao().updateAfterFullDeletion(
			epoch = 4L,
			retainedFromMs = null,
			deletedSourceEventHighWaterOrdinal = 12L,
			updatedAtMs = 30L,
		) shouldBe 1

		val snapshots = collection.await().map {
			(it as SourceAwareHistoryPageQuery.Content).readSnapshot
		}
		snapshots.map { it?.sourceEvidenceRevision } shouldContainExactly listOf(4L, 5L)
		snapshots.map { it?.collectedDataEpoch } shouldContainExactly listOf(3L, 4L)
	}

	@Test
	fun `lifecycle action alone invalidates source composition without changing evidence revision`() =
		runTest {
			sourceReader.lifecycleTriggeredRecent = nativeWifiRow(500L, 51L)
			val firstEmission = CompletableDeferred<Unit>()
			val collection = async {
				repository.observeRecentSourceAwarePage(emptyList(), 10)
					.onEach { firstEmission.complete(Unit) }
					.take(2)
					.toList()
			}
			firstEmission.await()
			database.sourceSessionDao().insertLifecycleActions(
				listOf(
					LifecycleDesiredActionEntity(
						actionId = ACTION_ID,
						logicalTrackingId = "logical-wifi",
						serviceRunId = "run-wifi",
						manifestRevision = 1L,
						actionRevision = 1L,
						actionFamily = "SOURCE_REGISTRATION",
						sourceKind = SourceDestinationOwnerEntity.SOURCE_WIFI,
						desiredState = "START",
						desiredPlanRevision = 1L,
						sourcePolicyRevision = 1L,
						consentEpoch = 1L,
						startOrigin = "MANUAL_FOREGROUND_START",
						bootId = "boot",
						leaseGeneration = 1L,
						requestedAtMs = 20L,
						requestedElapsedRealtimeNanos = 20L,
						status = "PENDING",
						attemptCount = 0,
						acknowledgedAtMs = null,
						acknowledgedElapsedRealtimeNanos = null,
						failureCode = null,
						retryTrigger = null,
						sourceInstanceId = "wifi",
						registrationGeneration = 1L,
					),
				),
			)

			val pages = collection.await().map { it as SourceAwareHistoryPageQuery.Content }
			pages.map { it.entries.size } shouldContainExactly listOf(0, 1)
			pages.map { it.readSnapshot?.sourceEvidenceRevision } shouldContainExactly
				listOf(4L, 4L)
		}

	@Test
	fun `legacy physical compatibility never qualifies Location from sample count`() = runTest {
		database.sessionSegmentDao().insert(legacySegment(id = 41L, sampleCount = 5))
		sourceReader.session = HistoricalSessionSourceRead.Available(
			segmentId = 41L,
			wifi = WifiHistoryQuery.Found(localUnavailableWifi()),
			cell = CellHistoryQuery.Found(localUnavailableCell()),
			activity = ActivityHistoryQuery.Found(localUnavailableActivity()),
			pressure = PressureSessionHistoryQuery.Found(
				PressureSessionHistory(
					segmentId = 41L,
					capture = HistoryCapture.Unverifiable,
					qualifiedSources = emptySet(),
					pressure = unavailablePressure(),
				),
			),
		)

		val selected = repository.observeSession(41L).first() as SessionHistoryQuery.Found

		selected.history.capture shouldBe HistoryCapture.Unverifiable
		selected.history.qualifiedSources shouldBe emptySet()
		selected.history.readSnapshot?.sourceEvidenceRevision shouldBe 4L
	}

	@Test
	fun `live snapshot keeps typed source failures and missing siblings in one Room read`() = runTest {
		database.sessionSegmentDao().insert(legacySegment(id = 41L, sampleCount = 5))
		val failedWifi = WifiHistoryQuery.Failed(WifiHistoryCause.FACT_INTEGRITY_FAILED)
		val activity = ActivityHistoryQuery.Found(localUnavailableActivity())
		sourceReader.session = HistoricalSessionSourceRead.Available(
			segmentId = 41L, wifi = failedWifi, cell = CellHistoryQuery.NotFound,
			activity = activity, pressure = PressureSessionHistoryQuery.NotFound,
		)

		val snapshot = repository.observeLiveSession(41L).first()
		val session = snapshot.session as SessionHistoryQuery.Found
		snapshot.wifi shouldBe failedWifi
		snapshot.cell shouldBe CellHistoryQuery.NotFound
		snapshot.activity shouldBe activity
		snapshot.pressure shouldBe PressureSessionHistoryQuery.NotFound
		session.history.sourceProducts?.wifi shouldBe failedWifi
		session.history.qualifiedSources shouldBe emptySet()
		snapshot.readSnapshot?.collectedDataEpoch shouldBe 3L
		snapshot.readSnapshot?.sourceEvidenceRevision shouldBe 4L
		repository.observeLiveSession(41L, LiveSessionHistoryDetail.COMPACT).first() shouldBe snapshot
		sourceReader.sessionCalls shouldBe 2
	}

	@Test
	fun `compact Location without live sensors skips unused products while full read preserves them`() = runTest {
		installExactLiveOwner(setOf(SourceDestinationOwnerEntity.SOURCE_LOCATION, SourceDestinationOwnerEntity.SOURCE_CELL))
		val cell = CellHistoryQuery.Found(localUnavailableCell())
		sourceReader.session = HistoricalSessionSourceRead.Available(41L, WifiHistoryQuery.NotFound, cell,
			ActivityHistoryQuery.NotFound, PressureSessionHistoryQuery.NotFound)

		val compact = repository.observeLiveSession(41L, LiveSessionHistoryDetail.COMPACT).first()
		val compactHistory = (compact.session as SessionHistoryQuery.Found).history
		(compactHistory.capture as HistoryCapture.Exact).revisions.single().capturedSources shouldBe
			setOf(HistorySource.LOCATION, HistorySource.CELL)
		compactHistory.sourceProducts shouldBe null
		compact.readSnapshot?.collectedDataEpoch shouldBe 3L
		compact.readSnapshot?.sourceEvidenceRevision shouldBe 4L
		sourceReader.sessionCalls shouldBe 0

		val full = repository.observeLiveSession(41L).first()
		full.cell shouldBe cell
		(full.session as SessionHistoryQuery.Found).history.steps shouldBe compactHistory.steps
		sourceReader.sessionCalls shouldBe 1
	}

	@Test
	fun `compact Location capture retains sensor values and isolates sibling failures`() = runTest {
		installExactLiveOwner(setOf(SourceDestinationOwnerEntity.SOURCE_LOCATION,
			SourceDestinationOwnerEntity.SOURCE_ACTIVITY, SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			SourceDestinationOwnerEntity.SOURCE_WIFI, SourceDestinationOwnerEntity.SOURCE_CELL))
		val capture = HistoryCapture.Exact(listOf(HistoryCaptureRevision(1L, EpochMs(100L),
			setOf(HistorySource.LOCATION, HistorySource.ACTIVITY, HistorySource.PRESSURE,
				HistorySource.WIFI, HistorySource.CELL), emptySet())))
		val activity = ActivityHistoryQuery.Found(localUnavailableActivity().copy(
			state = ActivityHistoryProductState.MATERIALIZING, causes = setOf(ActivityHistoryCause.SESSION_ACTIVE),
			latestObservation = ActivityHistoryObservation(EpochMs(150L), ActivityHistoryType.STILL, 90)))
		val pressure = PressureSessionHistoryQuery.Found(PressureSessionHistory(41L, capture,
			setOf(HistorySource.PRESSURE), PressureHistory(HistoryAvailability.AVAILABLE, HistoryEvidence.RECORDED,
				HistoryProductState.READY, PressureHistoryCoverage.COMPLETE, listOf(PressureHistoryWindow(
					EpochMs(100L), EpochMs(200L), 1, 1, 1012.0, 0.0, 1012f, 1012f, 1012f, 1012f, null, null,
					PressureSensorAccuracy.HIGH, 100_000, 0, 100_000_000L, 0L, PressureWindowClosure.TARGET_ELAPSED,
					PressureWindowQualification.COMPLETE, 0L, null, "UTC")))))
		val wifi = WifiHistoryQuery.Failed(WifiHistoryCause.FACT_INTEGRITY_FAILED)
		val cell = CellHistoryQuery.Found(localUnavailableCell())
		val products = HistoricalSessionSourceRead.Available(41L, wifi, cell, activity, pressure)
		sourceReader.session = products

		val compact = repository.observeLiveSession(41L, LiveSessionHistoryDetail.COMPACT).first()
		compact.activity shouldBe activity
		compact.pressure shouldBe pressure
		compact.wifi shouldBe null
		compact.cell shouldBe null
		val history = (compact.session as SessionHistoryQuery.Found).history
		history.sourceProducts?.activity shouldBe activity
		history.sourceProducts?.wifi shouldBe null
		history.sourceProducts?.cell shouldBe null
		history.qualifiedSources shouldBe setOf(HistorySource.PRESSURE)
		pressure.history.pressure.summary?.latestHectopascals shouldBe 1012f
		sourceReader.radioRequests shouldBe listOf(false)
		val full = repository.observeLiveSession(41L).first()
		full.wifi shouldBe wifi
		full.cell shouldBe cell
		full.activity shouldBe activity
		full.pressure shouldBe pressure

		val failedActivity = ActivityHistoryQuery.Found(localUnavailableActivity().copy(
			state = ActivityHistoryProductState.FAILED, causes = setOf(ActivityHistoryCause.FACT_INTEGRITY_FAILED)))
		sourceReader.session = products.copy(activity = failedActivity)
		val withFailedActivity = repository.observeLiveSession(41L, LiveSessionHistoryDetail.COMPACT).first()
		withFailedActivity.activity shouldBe failedActivity
		withFailedActivity.pressure shouldBe pressure
		(withFailedActivity.session as SessionHistoryQuery.Found).history.qualifiedSources shouldBe
			setOf(HistorySource.PRESSURE)

		sourceReader.session = products.copy(pressure = PressureSessionHistoryQuery.NotFound)
		val withMissingPressure = repository.observeLiveSession(41L, LiveSessionHistoryDetail.COMPACT).first()
		withMissingPressure.activity shouldBe activity
		withMissingPressure.pressure shouldBe PressureSessionHistoryQuery.NotFound
		sourceReader.sessionCalls shouldBe 4
		sourceReader.radioRequests shouldBe listOf(false, true, false, false)
	}

	@Test
	fun `compact Location-free live snapshot retains complete source products`() = runTest {
		installExactLiveOwner(setOf(SourceDestinationOwnerEntity.SOURCE_CELL))
		val cell = CellHistoryQuery.Found(localUnavailableCell())
		sourceReader.session = HistoricalSessionSourceRead.Available(41L, WifiHistoryQuery.NotFound, cell,
			ActivityHistoryQuery.NotFound, PressureSessionHistoryQuery.NotFound)

		val compact = repository.observeLiveSession(41L, LiveSessionHistoryDetail.COMPACT).first()

		compact.cell shouldBe cell
		(compact.session as SessionHistoryQuery.Found).history.sourceProducts?.cell shouldBe cell
		sourceReader.sessionCalls shouldBe 1
		sourceReader.radioRequests shouldBe listOf(true)
	}

	@Test
	fun `live snapshots refresh their epoch after full deletion authority changes`() = runTest {
		installExactLiveOwner(setOf(SourceDestinationOwnerEntity.SOURCE_LOCATION))
		val firstEmission = CompletableDeferred<Unit>()
		val collection = async {
			repository.observeLiveSession(41L, LiveSessionHistoryDetail.COMPACT)
				.onEach { firstEmission.complete(Unit) }
				.take(2)
				.toList()
		}
		firstEmission.await()
		database.sourceEvidenceStateDao().updateAfterFullDeletion(
			epoch = 4L,
			retainedFromMs = null,
			deletedSourceEventHighWaterOrdinal = 12L,
			updatedAtMs = 30L,
		) shouldBe 1

		val snapshots = collection.await().map { it.readSnapshot }
		snapshots.map { it?.sourceEvidenceRevision } shouldContainExactly listOf(4L, 5L)
		snapshots.map { it?.collectedDataEpoch } shouldContainExactly listOf(3L, 4L)
		sourceReader.sessionCalls shouldBe 0
	}

	@Test
	fun `production union retains other products after a native radio membership failure`() = runTest {
		val segment = legacySegment(id = 41L, sampleCount = 0).copy(
			logicalTrackingId = "missing-logical", serviceRunId = "missing-run",
		)
		database.sessionSegmentDao().insert(segment)
		val lane = SourceProductLaneExecutionAuthority { false }
		val dispatcher = UnconfinedTestDispatcher(testScheduler)
		val pressure = PressureHistorySelector(database, lane)
		val activity = DefaultActivityHistoryRepository(database, lane, dispatcher)
		val cell = DefaultCellHistoryRepository(database, lane, dispatcher)
		val union = DefaultTrackingHistorySourceUnionReader(
			wifi = emptyWifiRepository(lane, dispatcher), cell = cell, activity = activity,
			pressureSelector = pressure, wifiImported = emptyWifiEligibleReader(),
			cellImported = cell, activityImported = activity,
			pressureImported = PressureHistoryPageReader(
				database, pressure, ImportedPressureHistoryEvaluator(database), PortablePressureRoomReader(database, pressure),
			),
		)

		val result = database.withTransaction { union.sessionInTransaction(segment) } as
			HistoricalSessionSourceRead.Available
		(result.wifi as WifiHistoryQuery.Found).entry.state shouldBe WifiHistoryProductState.FAILED
		result.cell shouldBe cell.session(segment.id)
		result.activity shouldBe activity.session(segment.id)
		result.pressure shouldBe PressureSessionHistoryQuery.Found(
			database.withTransaction {
				pressure.selectManyInTransaction(listOf(segment)).single().toPublicPressureSessionHistory()
			},
		)
		result.segmentId shouldBe segment.id
		val sensors = database.withTransaction { union.sessionInTransaction(segment, includeRadio = false) } as
			HistoricalSessionSourceRead.Available
		sensors.wifi shouldBe null
		sensors.cell shouldBe null
		sensors.activity shouldBe result.activity
		sensors.pressure shouldBe result.pressure
		sensors.publicProducts?.activity shouldBe result.activity
		sensors.publicProducts?.wifi shouldBe null
		sensors.publicProducts?.cell shouldBe null
	}

	@Test
	fun `union unable to resolve coherent products remains a shared unavailable result`() = runTest {
		database.sessionSegmentDao().insert(legacySegment(id = 41L, sampleCount = 5))
		sourceReader.session = HistoricalSessionSourceRead.Unavailable(
			com.adsamcik.tracker.stats.api.repository.TrackingHistoryUnavailableReason
				.SOURCE_INTEGRITY_FAILURE,
			HistorySource.CELL,
		)

		repository.observeSession(41L).first() shouldBe SessionHistoryQuery.Unavailable(
			com.adsamcik.tracker.stats.api.repository.TrackingHistoryUnavailableReason
				.SOURCE_INTEGRITY_FAILURE,
			HistorySource.CELL,
			com.adsamcik.tracker.stats.api.repository.TrackingHistoryReadSnapshot(3L, 4L),
		)
	}

	private fun importedWifiRow(startMs: Long): HistoricalSourceUnionEntry =
		HistoricalSourceUnionEntry.ImportedWifi(wifiEligible(startMs))

	private fun wifiEligible(startMs: Long): WifiImportedHistoryEligibleEntry {
		val selection = WifiImportedHistorySelection(
			WifiImportedHistorySelectionKey("a".repeat(64)),
			2L,
			"b".repeat(64),
		)
		val entry = WifiHistoryEntry(
			key = WifiHistoryEntryKey("wifi-imported"),
			startTime = EpochMs(startMs),
			endTime = EpochMs(startMs + 10L),
			storedZoneIds = emptySet(),
			state = WifiHistoryProductState.FAILED,
			coverage = WifiHistoryCoverage.NONE,
			observations = emptyList(),
			causes = setOf(WifiHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE),
			origin = WifiHistoryOrigin.IMPORTED,
			importedSelection = selection,
		)
		return WifiImportedHistoryEligibleEntry(
			entry = entry,
			selection = selection,
			recency = importedRecency(HistorySource.WIFI, startMs, "wifi-$startMs"),
		)
	}

	private fun nativeWifiRow(
		startMs: Long,
		segmentId: Long,
	): HistoricalSourceUnionEntry {
		val entry = WifiHistoryEntry(
			key = WifiHistoryEntryKey("wifi-native"),
			startTime = EpochMs(startMs),
			endTime = EpochMs(startMs + 10L),
			storedZoneIds = emptySet(),
			state = WifiHistoryProductState.MATERIALIZING,
			coverage = WifiHistoryCoverage.NONE,
			observations = emptyList(),
			causes = setOf(WifiHistoryCause.MATERIALIZATION_BEHIND),
			localSelection = WifiLocalHistorySelectionKey("f".repeat(64)),
			capturesOnlyWifi = true,
		)
		return HistoricalSourceUnionEntry.Native(
			entry = SourceAwareHistoryPageEntry.WifiOnly(entry),
			recency = HistoricalSourceUnionRecency.Native(startMs, segmentId),
			nativeMembership = HistoricalSourceOnlyMembership(
				HistorySource.WIFI,
				"logical-wifi",
				listOf(segmentId),
			),
		)
	}

	private fun importedCellRow(startMs: Long): HistoricalSourceUnionEntry =
		HistoricalSourceUnionEntry.ImportedCell(cellEligible(startMs))

	private fun cellEligible(startMs: Long): CellImportedHistoryEligibleEntry {
		val selection = ImportedCellHistorySelection(
			ImportedCellHistoryIdentity("c".repeat(64)),
			3L,
			ImportedCellHistoryDigest("d".repeat(64)),
		)
		val entry = localUnavailableCell().copy(
			startTime = EpochMs(startMs),
			endTime = EpochMs(startMs + 10L),
			state = CellHistoryProductState.UNVERIFIABLE,
			causes = setOf(CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE),
			origin = CellHistoryOrigin.Imported(selection),
			selection = selection,
		)
		return CellImportedHistoryEligibleEntry(
			entry = entry,
			selection = selection,
			recency = importedRecency(HistorySource.CELL, startMs, "cell-$startMs"),
		)
	}

	private fun importedActivityRow(startMs: Long): HistoricalSourceUnionEntry =
		HistoricalSourceUnionEntry.ImportedActivity(activityEligible(startMs))

	private fun activityEligible(startMs: Long): ActivityImportedHistoryEligibleEntry {
		val entry = localUnavailableActivity().copy(
			startTime = EpochMs(startMs),
			endTime = EpochMs(startMs + 10L),
			state = ActivityHistoryProductState.FAILED,
			causes = setOf(ActivityHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE),
			origin = ActivityHistoryOrigin.IMPORTED,
		)
		return ActivityImportedHistoryEligibleEntry(
			entry = entry,
			selector = entry.key,
			identity = PortableActivityOpaqueIdentity("a".repeat(64)),
			importRevision = 1L,
			contentChecksum = PortableActivityDigest("b".repeat(64)),
			recency = importedRecency(
				HistorySource.ACTIVITY,
				startMs,
				"activity-$startMs",
			),
		)
	}

	private fun importedPressureRow(startMs: Long): HistoricalSourceUnionEntry =
		HistoricalSourceUnionEntry.ImportedPressure(pressureEligible(startMs))

	private fun pressureEligible(startMs: Long): PressureImportedHistoryEligibleEntry {
		val identity = ImportedPressureHistoryIdentity("sha256:${"e".repeat(64)}")
		val entry = PressureOnlyHistoryEntry(
			key = TrackingHistoryEntryKey("pressure-imported"),
			origin = PressureHistoryOrigin.Imported(identity),
			startTime = EpochMs(startMs),
			endTime = EpochMs(startMs + 10L),
			pressure = unavailablePressure().copy(
				causes = setOf(PressureHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE),
			),
		)
		return PressureImportedHistoryEligibleEntry(
			entry = entry,
			identity = identity,
			importRevision = 1L,
			contentChecksum = PortablePressureDigest("sha256:${"f".repeat(64)}"),
			recency = importedRecency(
				HistorySource.PRESSURE,
				startMs,
				"pressure-$startMs",
			),
		)
	}

	private fun importedRecency(
		source: HistorySource,
		startMs: Long,
		tieIdentity: String,
	) = ImportedHistoryRecency(
		source = source,
		newestMemberStartTimeMs = startMs,
		newestMemberTieIdentity = ImportedHistoryRecencyTieIdentity(tieIdentity),
	)

	private fun productionRepository(dispatcher: CoroutineDispatcher): DefaultTrackingHistoryRepository {
		val lane = SourceProductLaneExecutionAuthority { false }
		val pressure = PressureHistorySelector(database, lane)
		val activity = DefaultActivityHistoryRepository(database, lane, dispatcher)
		val cell = DefaultCellHistoryRepository(database, lane, dispatcher)
		val sourceReader = DefaultTrackingHistorySourceUnionReader(
			wifi = emptyWifiRepository(lane, dispatcher), cell = cell, activity = activity,
			pressureSelector = pressure, wifiImported = emptyWifiEligibleReader(), cellImported = cell,
			activityImported = activity, pressureImported = PressureHistoryPageReader(database, pressure,
				ImportedPressureHistoryEvaluator(database), PortablePressureRoomReader(database, pressure)))
		val steps = StepsSegmentHistorySelector(database, lane)
		return DefaultTrackingHistoryRepository(database = database, stepsSelector = steps,
			logicalHistoryReader = LogicalTrackingHistoryReader(database, steps), pressureSelector = pressure,
			sourceUnionReader = sourceReader, ioDispatcher = dispatcher)
	}

	private fun emptyWifiRepository(
		lane: SourceProductLaneExecutionAuthority,
		dispatcher: CoroutineDispatcher,
	) = DefaultWifiHistoryRepository(
		database = database,
		laneExecutionAuthority = lane,
		importedProductEvaluator = object : ImportedWifiProductEvaluator {
			override suspend fun selectIdentityInTransaction(
				selection: WifiImportedHistorySelectionKey,
			): ImportedWifiProductEvaluation? = null

			override suspend fun selectRecentInTransaction(
				limit: Int,
			): List<ImportedWifiProductEvaluation> = emptyList()

			override suspend fun selectExportRangeInTransaction(
				request: com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRangeRequest,
			): com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRangePage =
				error("Unexpected export dependency in a history fixture")

			override suspend fun selectRangeInTransaction(
				request: ImportedWifiProductRangeRequest,
			): ImportedWifiProductRangePage = ImportedWifiProductRangePage(emptyList(), false)
		},
		localPortableReader = object : ReadLocalPortableCapturedWifi {
			override suspend fun readInTransaction(
				request: ExportPortableCapturedWifiRequest,
			): ReadLocalPortableCapturedWifiResult =
				error("An empty database has no local Wi-Fi collision")
		},
		deletedHistoryReader = WifiDeletedHistoryReader { WifiDeletedHistoryResult.NotDeleted },
		bootClockDomainProvider = BootClockDomainProvider { "tracking-history-union-test-boot" },
		ioDispatcher = dispatcher,
	)

	private fun emptyWifiEligibleReader() = WifiImportedHistoryEligibleReaderAdapter(
		pageEvaluator = object : ImportedWifiProductRecentPageEvaluator {
			override suspend fun selectRecentPageInTransaction(
				request: ImportedWifiProductRecentRequest,
			): ImportedWifiProductRecentPage = ImportedWifiProductRecentPage(
				evaluations = emptyList(),
				hasMore = false,
			)
		},
		localPortableReader = object : ReadLocalPortableCapturedWifi {
			override suspend fun readInTransaction(
				request: ExportPortableCapturedWifiRequest,
			): ReadLocalPortableCapturedWifiResult =
				error("An empty Wi-Fi eligible page has no local collision")
		},
	)

	private fun localUnavailableWifi() = WifiHistoryEntry(
		key = WifiHistoryEntryKey("wifi-local"),
		startTime = EpochMs(100L),
		endTime = EpochMs(200L),
		storedZoneIds = emptySet(),
		state = WifiHistoryProductState.UNAVAILABLE,
		coverage = WifiHistoryCoverage.NONE,
		observations = emptyList(),
		causes = setOf(WifiHistoryCause.SOURCE_NOT_CAPTURED),
	)

	private fun localUnavailableCell() = CellHistoryEntry(
		key = CellHistoryEntryKey("cell-local"),
		startTime = EpochMs(100L),
		endTime = EpochMs(200L),
		storedZoneIds = emptySet(),
		state = CellHistoryProductState.UNAVAILABLE,
		coverage = CellHistoryCoverage.NONE,
		observations = emptyList(),
		causes = setOf(CellHistoryCause.SOURCE_NOT_CAPTURED),
	)

	private fun localUnavailableActivity() = ActivityHistoryEntry(
		key = ActivityHistoryEntryKey("activity-local"),
		startTime = EpochMs(100L),
		endTime = EpochMs(200L),
		storedZoneIds = emptySet(),
		state = ActivityHistoryProductState.UNAVAILABLE,
		coverage = ActivityHistoryCoverage.NONE,
		activeTime = null,
		fragments = emptyList(),
		causes = setOf(ActivityHistoryCause.SOURCE_NOT_CAPTURED),
	)

	private fun unavailablePressure() = PressureHistory(
		availability = HistoryAvailability.UNAVAILABLE,
		evidence = HistoryEvidence.NONE,
		productState = HistoryProductState.DEGRADED,
		coverage = PressureHistoryCoverage.NONE,
		windows = emptyList(),
		causes = setOf(PressureHistoryCause.SOURCE_NOT_CAPTURED),
	)

	private suspend fun installRecentPressureCandidates() {
		val pressure = (mixedSensorProducts().pressure as PressureSessionHistoryQuery.Found).history
		val capture = HistoryCapture.Exact(listOf(HistoryCaptureRevision(1L, EpochMs(100L),
			setOf(HistorySource.PRESSURE), emptySet())))
		for (id in 1L..10L) {
			installExactLiveOwner(setOf(SourceDestinationOwnerEntity.SOURCE_PRESSURE), id)
			val sessions = database.sourceSessionDao()
			val run = requireNotNull(sessions.serviceRun("live-run-$id"))
			sessions.updateServiceRun(run.copy(state = "FINALIZED", completedAtMs = 200L,
				presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_QUIESCED,
				presentationAcknowledgedAtMs = 200L))
			sourceReader.sessions[id] = HistoricalSessionSourceRead.Available(id, WifiHistoryQuery.NotFound,
				CellHistoryQuery.NotFound, ActivityHistoryQuery.NotFound,
				PressureSessionHistoryQuery.Found(pressure.copy(segmentId = id, capture = capture)))
		}
	}

	private suspend fun installClosedMixedOwner() {
		installExactLiveOwner(setOf(SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
			SourceDestinationOwnerEntity.SOURCE_PRESSURE))
		val sessions = database.sourceSessionDao()
		val run = requireNotNull(sessions.serviceRun("live-run"))
		sessions.updateServiceRun(run.copy(state = "FINALIZED", completedAtMs = 200L,
			presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_QUIESCED,
			presentationAcknowledgedAtMs = 200L))
	}

	private fun mixedSensorProducts(): HistoricalSessionSourceRead.Available {
		val capture = HistoryCapture.Exact(listOf(HistoryCaptureRevision(1L, EpochMs(100L),
			setOf(HistorySource.ACTIVITY, HistorySource.PRESSURE), emptySet())))
		val activity = ActivityHistoryQuery.Found(localUnavailableActivity().copy(
			state = ActivityHistoryProductState.PARTIAL, coverage = ActivityHistoryCoverage.PARTIAL,
			causes = setOf(ActivityHistoryCause.ACQUISITION_INCOMPLETE),
			activeTime = ActivityActiveTime(0L, 100_000_000L, 0L, 0L),
			fragments = listOf(ActivityHistoryFragment.Band("UTC", EpochMs(100L), EpochMs(200L),
				0L, 0L, ActivityHistoryType.STILL, ActivityHistoryMechanism.SAMPLED_CLASSIFICATION,
				null, ActivityHistoryConfidence.Sampled(90, 90, 1),
				ActivityHistoryWallTimeContinuity.SAME_ANCHOR, 100_000_000L))))
		val pressure = PressureSessionHistoryQuery.Found(PressureSessionHistory(41L, capture,
			setOf(HistorySource.PRESSURE), PressureHistory(HistoryAvailability.AVAILABLE, HistoryEvidence.RECORDED,
				HistoryProductState.READY, PressureHistoryCoverage.COMPLETE, listOf(PressureHistoryWindow(
					EpochMs(100L), EpochMs(200L), 1, 1, 1012.0, 0.0, 1012f, 1012f, 1012f, 1012f, null, null,
					PressureSensorAccuracy.HIGH, 100_000, 0, 100_000_000L, 0L, PressureWindowClosure.TARGET_ELAPSED,
					PressureWindowQualification.COMPLETE, 0L, null, "UTC")))))
		return HistoricalSessionSourceRead.Available(41L, WifiHistoryQuery.NotFound, CellHistoryQuery.NotFound,
			activity, pressure)
	}

	private suspend fun installExactLiveOwner(sourceKinds: Set<Int>, segmentId: Long = 41L) {
		val logicalId = if (segmentId == 41L) { "live-logical" } else { "live-logical-$segmentId" }
		val runId = if (segmentId == 41L) { "live-run" } else { "live-run-$segmentId" }
		val deliveryId = if (segmentId == 41L) { "live-delivery" } else { "live-delivery-$segmentId" }
		val segment = legacySegment(segmentId, 0).copy(logicalTrackingId = logicalId, serviceRunId = runId)
		database.sessionSegmentDao().insert(segment)
		val sessions = database.sourceSessionDao()
		sessions.insertServiceRun(SourceServiceRunEntity(runId, logicalId, "ACTIVE", 1L, 1L,
			0L, 100L, 10L, null, null, bootId = "boot", leaseGeneration = 1L,
			startOrigin = "MANUAL_FOREGROUND_START", runRevision = 1L, startDeliveryToken = deliveryId,
			startCommandGeneration = 1L, preparedManifestRevision = 1L, preparedIntentRevision = 1L,
			sessionSegmentId = segment.id))
		val sources = sourceKinds.map { kind ->
			if (kind == SourceDestinationOwnerEntity.SOURCE_PRESSURE) {
				SessionManifestSourceEntity(logicalId, 1L, kind,
					SessionManifestPurposeCode.SESSION_CAPTURE, 1L, true, 1,
					outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
					writerOwner = SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS,
					writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
					writerProjectionId = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
					writerProjectionVersion = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
					writerBindingGeneration = SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION)
			} else {
				SessionManifestSourceEntity(logicalId, 1L, kind,
					SessionManifestPurposeCode.SESSION_CAPTURE, 1L, true, 1)
			}
		}
		val unsigned = SessionManifestVersionEntity(logicalId, 1L, runId, "MANUAL", 1L, 1L,
			1L, "MANUAL_FOREGROUND_START", "boot", 10L, 100L, "UTC", null, "SESSION_START", "")
		sessions.insertManifest(unsigned.copy(manifestChecksum = SessionManifestIntegrity.compute(unsigned, sources)))
		sessions.insertManifestSources(sources)
	}

	private fun legacySegment(id: Long, sampleCount: Int) = SessionSegment(
		id = id,
		startTimeMs = 100L,
		endTimeMs = 200L,
		distanceM = 1f,
		steps = null,
		primaryActivity = null,
		activityConfidence = null,
		sampleCount = sampleCount,
		source = SegmentSource.USER_CREATED,
		inferenceVersion = "legacy",
		createdAt = 200L,
	)

	private class FakeSourceUnionReader(
		private val database: AppDatabase,
	) : TrackingHistorySourceUnionReader {
		var session: HistoricalSessionSourceRead = HistoricalSessionSourceRead.Unavailable(
			com.adsamcik.tracker.stats.api.repository.TrackingHistoryUnavailableReason
				.SOURCE_INTEGRITY_FAILURE,
			HistorySource.WIFI,
		)
		var candidates: HistoricalSourceUnionRead<List<HistoricalSourceOnlyMembership>> =
			HistoricalSourceUnionRead.Available(emptyList())
		var recent: HistoricalSourceUnionRead<List<HistoricalSourceUnionEntry>> =
			HistoricalSourceUnionRead.Available(emptyList())
		var recentFailure: CancellationException? = null
		var lifecycleTriggeredRecent: HistoricalSourceUnionEntry? = null
		var candidateCalls = 0
		var recentCalls = 0
		var sessionCalls = 0
		val radioRequests = mutableListOf<Boolean>()
		val sessions = mutableMapOf<Long, HistoricalSessionSourceRead>()
		val sessionSegmentRequests = mutableListOf<Long>()

		override suspend fun sessionInTransaction(
			segment: SessionSegment,
			includeRadio: Boolean,
		): HistoricalSessionSourceRead {
			sessionCalls += 1
			radioRequests += includeRadio
			sessionSegmentRequests += segment.id
			val selected = sessions[segment.id] ?: session
			return if (!includeRadio && selected is HistoricalSessionSourceRead.Available) {
				selected.copy(wifi = null, cell = null)
			} else {
				selected
			}
		}

		override suspend fun candidateNativeOnlyInTransaction(
			segmentIds: List<Long>,
		): HistoricalSourceUnionRead<List<HistoricalSourceOnlyMembership>> {
			candidateCalls += 1
			return candidates
		}

		override suspend fun recentInTransaction(
			limit: Int,
		): HistoricalSourceUnionRead<List<HistoricalSourceUnionEntry>> {
			recentCalls += 1
			recentFailure?.let { throw it }
			val lifecycleEntry = lifecycleTriggeredRecent
			if (
				lifecycleEntry != null &&
				database.sourceSessionDao().lifecycleAction(ACTION_ID) != null
			) {
				return HistoricalSourceUnionRead.Available(listOf(lifecycleEntry))
			}
			return recent
		}
	}

	private companion object {
		const val ACTION_ID = "shared-history-action"
	}
}
