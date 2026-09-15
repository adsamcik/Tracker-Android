package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCause
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntry
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryProductState
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryQuery
import com.adsamcik.tracker.stats.api.repository.CellHistoryCause
import com.adsamcik.tracker.stats.api.repository.CellHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntry
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.CellHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.CellHistoryProductState
import com.adsamcik.tracker.stats.api.repository.CellHistoryQuery
import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.HistoryCapture
import com.adsamcik.tracker.stats.api.repository.HistoryEvidence
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.ImportedCellHistoryDigest
import com.adsamcik.tracker.stats.api.repository.ImportedCellHistoryIdentity
import com.adsamcik.tracker.stats.api.repository.ImportedCellHistorySelection
import com.adsamcik.tracker.stats.api.repository.ImportedPressureHistoryIdentity
import com.adsamcik.tracker.stats.api.repository.PressureHistory
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCause
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.PressureHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.PressureOnlyHistoryEntry
import com.adsamcik.tracker.stats.api.repository.PressureSessionHistory
import com.adsamcik.tracker.stats.api.repository.PressureSessionHistoryQuery
import com.adsamcik.tracker.stats.api.repository.SessionHistoryQuery
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageEntry
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageQuery
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageUnavailableReason
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCause
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.WifiHistoryEntry
import com.adsamcik.tracker.stats.api.repository.WifiHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.WifiHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.WifiHistoryProductState
import com.adsamcik.tracker.stats.api.repository.WifiHistoryQuery
import com.adsamcik.tracker.stats.api.repository.WifiImportedHistorySelection
import com.adsamcik.tracker.stats.api.repository.WifiImportedHistorySelectionKey
import com.adsamcik.tracker.stats.api.repository.WifiLocalHistorySelectionKey
import com.adsamcik.tracker.stats.api.value.EpochMs
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
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
		sourceReader = FakeSourceUnionReader()
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

	@Test
	fun `typed source budget failure never falls back to physical rows`() = runTest {
		sourceReader.recent = HistoricalSourceUnionRead.Unavailable(
			SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED,
			HistorySource.WIFI,
		)

		repository.observeRecentSourceAwarePage(emptyList(), 10).first() shouldBe
			SourceAwareHistoryPageQuery.Unavailable(
				SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED,
				HistorySource.WIFI,
			)
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
	fun `selected source integrity failure is a shared unavailable result`() = runTest {
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

	private fun importedWifiRow(startMs: Long): HistoricalSourceUnionEntry {
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
		return HistoricalSourceUnionEntry(
			SourceAwareHistoryPageEntry.WifiOnly(entry),
			startMs,
			startMs + 10L,
			null,
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
		return HistoricalSourceUnionEntry(
			entry = SourceAwareHistoryPageEntry.WifiOnly(entry),
			recencyStartTimeMs = startMs,
			recencyTieBreaker = segmentId,
			nativeMembership = HistoricalSourceOnlyMembership(
				HistorySource.WIFI,
				"logical-wifi",
				listOf(segmentId),
			),
		)
	}

	private fun importedCellRow(startMs: Long): HistoricalSourceUnionEntry {
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
		return HistoricalSourceUnionEntry(
			SourceAwareHistoryPageEntry.CellOnly(entry),
			startMs,
			startMs + 10L,
			null,
		)
	}

	private fun importedActivityRow(startMs: Long): HistoricalSourceUnionEntry {
		val entry = localUnavailableActivity().copy(
			startTime = EpochMs(startMs),
			endTime = EpochMs(startMs + 10L),
			state = ActivityHistoryProductState.FAILED,
			causes = setOf(ActivityHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE),
			origin = ActivityHistoryOrigin.IMPORTED,
		)
		return HistoricalSourceUnionEntry(
			SourceAwareHistoryPageEntry.ActivityOnly(entry),
			startMs,
			startMs + 10L,
			null,
		)
	}

	private fun importedPressureRow(startMs: Long): HistoricalSourceUnionEntry {
		val entry = PressureOnlyHistoryEntry(
			key = TrackingHistoryEntryKey("pressure-imported"),
			origin = PressureHistoryOrigin.Imported(
				ImportedPressureHistoryIdentity("sha256:${"e".repeat(64)}"),
			),
			startTime = EpochMs(startMs),
			endTime = EpochMs(startMs + 10L),
			pressure = unavailablePressure().copy(
				causes = setOf(PressureHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE),
			),
		)
		return HistoricalSourceUnionEntry(
			SourceAwareHistoryPageEntry.PressureOnly(entry),
			startMs,
			startMs + 10L,
			null,
		)
	}

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

	private class FakeSourceUnionReader : TrackingHistorySourceUnionReader {
		var session: HistoricalSessionSourceRead = HistoricalSessionSourceRead.Unavailable(
			com.adsamcik.tracker.stats.api.repository.TrackingHistoryUnavailableReason
				.SOURCE_INTEGRITY_FAILURE,
			HistorySource.WIFI,
		)
		var candidates: HistoricalSourceUnionRead<List<HistoricalSourceOnlyMembership>> =
			HistoricalSourceUnionRead.Available(emptyList())
		var recent: HistoricalSourceUnionRead<List<HistoricalSourceUnionEntry>> =
			HistoricalSourceUnionRead.Available(emptyList())
		var candidateCalls = 0
		var recentCalls = 0

		override suspend fun sessionInTransaction(
			segment: SessionSegment,
		): HistoricalSessionSourceRead = session

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
			return recent
		}
	}
}
