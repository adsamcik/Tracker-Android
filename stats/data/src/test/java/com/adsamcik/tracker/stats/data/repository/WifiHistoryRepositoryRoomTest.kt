package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.LEGACY_WIFI_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.data.WIFI_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.data.WifiWalPayloadDecodeResult
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedChildEntity
import com.adsamcik.tracker.shared.base.database.data.RadioChildOwner
import com.adsamcik.tracker.shared.base.database.data.RadioChildPurpose
import com.adsamcik.tracker.shared.base.database.data.RadioObservationChildIntegrity
import com.adsamcik.tracker.shared.base.database.data.WifiChildChecksumInput
import com.adsamcik.tracker.shared.base.database.data.WifiWalPayload
import com.adsamcik.tracker.shared.base.database.data.WifiWalPayloadCodec
import com.adsamcik.tracker.shared.base.database.data.WifiWalPayloadObservation
import com.adsamcik.tracker.shared.base.database.data.canonicalWifiProviderDeliveryIdentity
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.stats.api.repository.ExportPortableCapturedWifiRequest
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluation
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluator
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRangePage
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRangeRequest
import com.adsamcik.tracker.stats.api.repository.ReadLocalPortableCapturedWifi
import com.adsamcik.tracker.stats.api.repository.ReadLocalPortableCapturedWifiResult
import com.adsamcik.tracker.stats.api.repository.MaterializedProofState
import com.adsamcik.tracker.stats.api.repository.SourceEvidenceIdentity
import com.adsamcik.tracker.stats.api.repository.SourceFactIdentity
import com.adsamcik.tracker.stats.api.repository.SourceFactLookupRequest
import com.adsamcik.tracker.stats.api.repository.SourceFactLookupResult
import com.adsamcik.tracker.stats.api.repository.SourceProofCompleteness
import com.adsamcik.tracker.stats.api.repository.SourceProofCoverage
import com.adsamcik.tracker.stats.api.repository.SourceProofKey
import com.adsamcik.tracker.stats.api.repository.SourceProofLineage
import com.adsamcik.tracker.stats.api.repository.SourceProofReason
import com.adsamcik.tracker.stats.api.repository.SourceProofScope
import com.adsamcik.tracker.stats.api.repository.SourceProofWriterIdentity
import com.adsamcik.tracker.stats.api.repository.WifiImportedHistorySelectionKey
import com.adsamcik.tracker.stats.api.repository.WifiHistoryBand
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCause
import com.adsamcik.tracker.stats.api.repository.WifiHistoryDayAllocation
import com.adsamcik.tracker.stats.api.repository.WifiHistoryPage
import com.adsamcik.tracker.stats.api.repository.WifiHistoryProductState
import com.adsamcik.tracker.stats.api.repository.WifiHistoryQuery
import com.adsamcik.tracker.stats.api.repository.WifiHistoryRangeContinuation
import com.adsamcik.tracker.stats.api.repository.WifiHistoryRangePage
import com.adsamcik.tracker.stats.api.repository.WifiHistoryRangeRequest
import com.adsamcik.tracker.stats.api.repository.WifiHistoryStructuralDayPage
import com.adsamcik.tracker.stats.api.repository.WifiDeletedHistoryReader
import com.adsamcik.tracker.stats.api.repository.WifiDeletedHistoryResult
import com.adsamcik.tracker.stats.api.value.EpochMs
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertFailsWith
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Suppress("LargeClass")
class WifiHistoryRepositoryRoomTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `Wi-Fi export discovery excludes an unrelated unreadable old source cohort`() = runTest {
		val old = buildGroup(910, 1, setOf(0))
		val current = buildGroup(911, 1, setOf(0))
		persist(listOf(old, current))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE wifi_captured_fact_revision SET effect_checksum = ? WHERE logical_tracking_id = ?",
			arrayOf("f".repeat(64), old.session.logicalTrackingId))
		val selected = database.wifiCapturedFactDao().completedExportCandidateIdsPage(
			WIFI_SOURCE, current.runs.single().segment.startTimeMs,
			current.runs.single().segment.endTimeMs, null, 100)
		selected shouldBe listOf(current.session.logicalTrackingId)
	}

	@Test
	fun `Wi-Fi export discovery retains uncertainty outside the original run header`() = runTest {
		val group = buildGroup(911, 1, setOf(0))
		persist(listOf(group))
		val from = group.runs.single().segment.endTimeMs + 1L
		database.wifiCapturedFactDao().completedExportCandidateIdsPage(
			WIFI_SOURCE, from, from + 100, null, 100) shouldBe emptyList()
		// Altered source clocks must still be qualified later; discovery grants no payload authority.
		database.openHelper.writableDatabase.execSQL(
			"UPDATE wifi_captured_fact_revision SET observed_wall_time_ms = ?, " +
				"wall_time_uncertainty_ms = 2 WHERE logical_tracking_id = ?",
			arrayOf(from - 1, group.session.logicalTrackingId))
		database.wifiCapturedFactDao().completedExportCandidateIdsPage(
			WIFI_SOURCE, from, from + 100, null, 100) shouldBe listOf(group.session.logicalTrackingId)
	}

	@Test
	fun `Wi-Fi export discovery expands coverage with submillisecond rounding and retains bad clocks`() = runTest {
		val group = buildGroup(911, 1, setOf(0))
		persist(listOf(group))
		val from = group.runs.single().segment.endTimeMs + 1L
		database.openHelper.writableDatabase.execSQL(
			"UPDATE wifi_captured_fact_revision SET observed_wall_time_ms = ?, wall_time_uncertainty_ms = 0, " +
				"coverage_interval_end_nanos = coverage_interval_start_nanos + 100000001 " +
				"WHERE logical_tracking_id = ?",
			arrayOf(from + 200, group.session.logicalTrackingId))
		// Rounded coverage starts at from + 99, just before this exclusive end.
		database.wifiCapturedFactDao().completedExportCandidateIdsPage(
			WIFI_SOURCE, from, from + 100, null, 100) shouldBe listOf(group.session.logicalTrackingId)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE wifi_captured_fact_revision SET wall_time_uncertainty_ms = -1 WHERE logical_tracking_id = ?",
			arrayOf(group.session.logicalTrackingId))
		database.wifiCapturedFactDao().completedExportCandidateIdsPage(
			WIFI_SOURCE, from + 1_000, from + 2_000, null, 100) shouldBe listOf(group.session.logicalTrackingId)
	}

	@Test
	fun `Wi-Fi export discovery keeps negative earliest clocks outside selected owner dates`() = runTest {
		val group = buildGroup(911, 1, setOf(0))
		persist(listOf(group))
		val from = group.runs.single().segment.endTimeMs + 1L
		val dao = database.wifiCapturedFactDao()
		dao.completedExportCandidateIdsPage(WIFI_SOURCE, from, from + 100, null, 100) shouldBe emptyList()
		val sqlite = database.openHelper.writableDatabase
		// A valid earliest bound of exactly zero remains provably outside these future dates.
		sqlite.execSQL("UPDATE wifi_captured_fact_revision SET observed_wall_time_ms = 2000, " +
			"wall_time_uncertainty_ms = 1, coverage_interval_end_nanos = coverage_interval_start_nanos + 1999000000 " +
			"WHERE logical_tracking_id = ?", arrayOf(group.session.logicalTrackingId))
		dao.completedExportCandidateIdsPage(WIFI_SOURCE, from, from + 100, null, 100) shouldBe emptyList()
		// One additional nanosecond rounds the span up and makes the canonical earliest bound negative.
		sqlite.execSQL("UPDATE wifi_captured_fact_revision SET " +
			"coverage_interval_end_nanos = coverage_interval_start_nanos + 1999000001 " +
			"WHERE logical_tracking_id = ?", arrayOf(group.session.logicalTrackingId))
		dao.completedExportCandidateIdsPage(WIFI_SOURCE, from, from + 100, null, 100) shouldBe
			listOf(group.session.logicalTrackingId)
		sqlite.execSQL("UPDATE wifi_captured_fact_revision SET " +
			"coverage_interval_end_nanos = coverage_interval_start_nanos + 2000000000 " +
			"WHERE logical_tracking_id = ?", arrayOf(group.session.logicalTrackingId))
		dao.completedExportCandidateIdsPage(WIFI_SOURCE, from, from + 100, null, 100) shouldBe
			listOf(group.session.logicalTrackingId)
	}

	@Test
	fun `paired Wi-Fi map reader uses the real captured history snapshot without Location collection`() = runTest {
		val group = buildGroup(911, 1, setOf(0))
		persist(listOf(group))
		var authorityChecks = 0
		val dispatcher = UnconfinedTestDispatcher(testScheduler)
		val paired = RoomRadioLocationContextRepository(database, repository {
			authorityChecks += 1
			database.inTransaction()
		}, io.mockk.mockk(), RoomAmbientWifiRepository(database, dispatcher),
			RoomAmbientCellRepository(database, dispatcher), RetainedLocationContextReader(database),
			dispatcher)
		val result = kotlin.test.assertIs<com.adsamcik.tracker.stats.api.repository.RadioLocationContextReadResult.Ready>(
			paired.read(com.adsamcik.tracker.stats.api.repository.RadioLocationContextRequest(
				com.adsamcik.tracker.stats.api.repository.RadioContextSource.WIFI, 0L, Long.MAX_VALUE)))
		val observation = result.observations.single()
		observation.sourceEventId shouldBe group.runs.single().facts.first().sourceEventId
		observation.purpose shouldBe com.adsamcik.tracker.stats.api.repository.RadioContextPurpose.CAPTURE
		(observation.signalQuality as com.adsamcik.tracker.stats.api.repository.RadioContextSignalQuality.Wifi)
			.value.sampleCount shouldBe 2
		observation.location shouldBe com.adsamcik.tracker.stats.api.repository.OptionalRetainedLocationContext.Unlocated(
			com.adsamcik.tracker.stats.api.repository.RadioLocationContextGap.NO_COMPATIBLE_LOCATION)
		result.gaps shouldBe emptySet()
		authorityChecks shouldBe 1
		database.locationSampleDao().countAll() shouldBe 0L
	}

	@Test
	fun `paired Wi-Fi map reader uses accepted child clocks when the later callback reading is rejected`() = runTest {
		val group = v6RotatedGroup(buildGroup(914, 1, setOf(0)), excludeLast = true)
		persist(listOf(group))
		persistV6Children(group)
		val fact = group.runs.single().facts.maxBy { it.semanticRevision }
		val wal = group.runs.single().admissions.single()
		kotlin.test.assertTrue(fact.coverageIntervalEndNanos < wal.observedElapsedNanos)
		kotlin.test.assertTrue(fact.observedWallTimeMs < requireNotNull(wal.wallTimeMs))
		val inputs = io.mockk.slot<List<RadioContextInput>>()
		val location = io.mockk.mockk<RetainedLocationContextReader>()
		io.mockk.coEvery { location.readInTransaction(capture(inputs)) } coAnswers {
			database.inTransaction() shouldBe true
			RetainedLocationContextSnapshot(listOf(LocationContextInput("accepted-location",
				RadioContextScope.Session(SessionContextScope(fact.logicalTrackingId, fact.serviceRunId,
					fact.sessionSegmentId, BOOT_ID, fact.clockDomainId, fact.collectedDataEpoch)),
				fact.coverageIntervalEndNanos, 500_000_000, 140_000_000, 1_200f, true,
				fact.observedWallTimeMs)))
		}
		val dispatcher = UnconfinedTestDispatcher(testScheduler)
		val paired = RoomRadioLocationContextRepository(database, repository { database.inTransaction() },
			io.mockk.mockk(), RoomAmbientWifiRepository(database, dispatcher), RoomAmbientCellRepository(database, dispatcher),
			location, dispatcher)
		val result = kotlin.test.assertIs<com.adsamcik.tracker.stats.api.repository.RadioLocationContextReadResult.Ready>(
			paired.read(com.adsamcik.tracker.stats.api.repository.RadioLocationContextRequest(
				com.adsamcik.tracker.stats.api.repository.RadioContextSource.WIFI, 0L, Long.MAX_VALUE)))
		inputs.captured.single().observedFromElapsedNanos shouldBe fact.coverageIntervalStartNanos
		inputs.captured.single().observedElapsedNanos shouldBe fact.coverageIntervalEndNanos
		val observation = result.observations.single()
		(observation.signalQuality as com.adsamcik.tracker.stats.api.repository.RadioContextSignalQuality.Wifi)
			.value.sampleCount shouldBe 1
		val located = kotlin.test.assertIs<com.adsamcik.tracker.stats.api.repository.OptionalRetainedLocationContext.Located>(observation.location)
		located.value.sourceEventId shouldBe "accepted-location"
		located.value.separationMillis shouldBe 0L
		result.gaps shouldBe emptySet()
	}

	@Test
	fun `paired Wi-Fi map reader retains unchanged deliveries and retracts invalid aggregate owners`() = runTest {
		val group = buildGroup(915, 1, setOf(0), includeReuse = true)
		persist(listOf(group))
		val dispatcher = UnconfinedTestDispatcher(testScheduler)
		val paired = RoomRadioLocationContextRepository(database, repository { database.inTransaction() },
			io.mockk.mockk(), RoomAmbientWifiRepository(database, dispatcher), RoomAmbientCellRepository(database, dispatcher),
			RetainedLocationContextReader(database), dispatcher)
		val request = com.adsamcik.tracker.stats.api.repository.RadioLocationContextRequest(
			com.adsamcik.tracker.stats.api.repository.RadioContextSource.WIFI, 0L, Long.MAX_VALUE)
		val ready = kotlin.test.assertIs<com.adsamcik.tracker.stats.api.repository.RadioLocationContextReadResult.Ready>(paired.read(request))
		ready.observations shouldHaveSize 2
		ready.observations.all { (it.signalQuality as com.adsamcik.tracker.stats.api.repository.RadioContextSignalQuality.Wifi)
			.value.sampleCount == 2 } shouldBe true
		val run = group.runs.single()
		val owner = run.facts.filter { it.factKind == WifiCapturedFactRevisionEntity.FACT_KIND_AGGREGATE }.maxBy { it.semanticRevision }
		val dependent = run.facts.single { it.factKind == WifiCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY }
		val unsigned = owner.copy(semanticRevision = owner.semanticRevision + 1L,
			supersedesSemanticRevision = owner.semanticRevision,
			mutationId = WifiCapturedFactRevisionIntegrity.mutationId(owner.logicalFactId, owner.semanticRevision + 1L))
		val corrected = unsigned.copy(effectChecksum = WifiCapturedFactRevisionIntegrity.effectChecksum(unsigned))
		val dao = database.wifiCapturedFactDao()
		database.withTransaction {
			dao.insertRevision(corrected)
			dao.deleteExactCursors(owner.writerProjectionId, owner.writerProjectionVersion, listOf(owner.logicalFactId))
			dao.insertCursor(cursor(corrected))
		}
		val afterCorrection = kotlin.test.assertIs<com.adsamcik.tracker.stats.api.repository.RadioLocationContextReadResult.Ready>(paired.read(request))
		afterCorrection.observations.none { it.sourceEventId == dependent.sourceEventId } shouldBe true
		kotlin.test.assertTrue(afterCorrection.gaps.isNotEmpty())
		database.withTransaction {
			dao.deleteExactCursors(owner.writerProjectionId, owner.writerProjectionVersion, listOf(owner.logicalFactId))
			dao.deleteExactRevisionLineages(owner.writerProjectionId, owner.writerProjectionVersion, listOf(owner.logicalFactId))
		}
		val afterDeletion = kotlin.test.assertIs<com.adsamcik.tracker.stats.api.repository.RadioLocationContextReadResult.Ready>(paired.read(request))
		afterDeletion.observations.none { it.sourceEventId == dependent.sourceEventId } shouldBe true
		kotlin.test.assertTrue(afterDeletion.gaps.isNotEmpty())
	}

	@Test
	fun `paired Wi-Fi map reader rejects stale captured epoch and reports source limit explicitly`() = runTest {
		persist(listOf(buildGroup(912, 1, setOf(0)), buildGroup(913, 1, setOf(0))))
		val dispatcher = UnconfinedTestDispatcher(testScheduler)
		val paired = RoomRadioLocationContextRepository(database, repository { database.inTransaction() },
			io.mockk.mockk(), RoomAmbientWifiRepository(database, dispatcher), RoomAmbientCellRepository(database, dispatcher),
			RetainedLocationContextReader(database), dispatcher)
		val request = com.adsamcik.tracker.stats.api.repository.RadioLocationContextRequest(
			com.adsamcik.tracker.stats.api.repository.RadioContextSource.WIFI, 0L, Long.MAX_VALUE, 1)
		val limited = kotlin.test.assertIs<com.adsamcik.tracker.stats.api.repository.RadioLocationContextReadResult.Ready>(paired.read(request))
		limited.observations shouldBe emptyList()
		limited.gaps shouldBe setOf(com.adsamcik.tracker.stats.api.repository.RadioContextReadGap(
			com.adsamcik.tracker.stats.api.repository.RadioContextPurpose.CAPTURE,
			com.adsamcik.tracker.stats.api.repository.RadioContextReadGapReason.DEPENDENCY_LIMIT))
		database.sourceEvidenceStateDao().updateLifecycle(1L, null, WALL_BASE_MS)
		val cleared = kotlin.test.assertIs<com.adsamcik.tracker.stats.api.repository.RadioLocationContextReadResult.Ready>(
			paired.read(request.copy(limit = 10)))
		cleared.observations shouldBe emptyList()
		kotlin.test.assertTrue(cleared.gaps.isNotEmpty())
	}

	@Test
	fun `writer admission reverse lookup selects cursor authenticated revision two`() = runTest {
		val run = buildGroup(901, 1, setOf(0)).runs.single()
		val lineage = run.facts.groupBy(WifiCapturedFactRevisionEntity::logicalFactId)
			.values.single { revisions -> revisions.size == 2 }
			.sortedBy(WifiCapturedFactRevisionEntity::semanticRevision)
		lineage.forEach { database.wifiCapturedFactDao().insertRevision(it) }
		database.wifiCapturedFactDao().insertCursor(cursor(lineage.last()))

		database.wifiCapturedFactDao().currentRevisionsForWriterAdmission(
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION,
			lineage.first().sourceAdmissionOrdinal,
			2,
		) shouldBe listOf(lineage.last())
	}

	@Test
	fun `writer admission reverse lookup rejects a mismatched cursor`() = runTest {
		val run = buildGroup(902, 1, setOf(0)).runs.single()
		val lineage = run.facts.groupBy(WifiCapturedFactRevisionEntity::logicalFactId)
			.values.single { revisions -> revisions.size == 2 }
			.sortedBy(WifiCapturedFactRevisionEntity::semanticRevision)
		lineage.forEach { database.wifiCapturedFactDao().insertRevision(it) }
		database.wifiCapturedFactDao().insertCursor(
			cursor(lineage.last()).copy(latestMutationId = lineage.first().mutationId),
		)

		database.wifiCapturedFactDao().currentRevisionsForWriterAdmission(
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION,
			lineage.first().sourceAdmissionOrdinal,
			2,
		) shouldBe emptyList()
	}

	@Test
	fun `zero sample Wi-Fi fact is ordinarily discovered with identity-free metrics`() = runTest {
		val group = buildGroup(1, 1, setOf(0))
		persist(listOf(group))
		var authorityChecks = 0
		val repository = repository {
			authorityChecks += 1
			database.inTransaction()
		}

		val selected = repository.session(group.runs.single().segment.id)
		val recent = repository.recent(1)

		val selectedEntry = (selected as WifiHistoryQuery.Found).entry
		val recentEntry = (recent as WifiHistoryPage.Available).entries.single()
		selectedEntry shouldBe recentEntry
		(repository.lookup(requireNotNull(selectedEntry.selection)) as WifiHistoryQuery.Found).entry shouldBe
			selectedEntry
		selectedEntry.state shouldBe WifiHistoryProductState.PARTIAL
		selectedEntry.capturesOnlyWifi shouldBe true
		selectedEntry.localSelection?.value shouldBe
			com.adsamcik.tracker.stats.api.repository.PortableWifiOpaqueIdentity.derive(
				com.adsamcik.tracker.stats.api.repository.PortableWifiIdentityKind.LOGICAL_ENTRY,
				group.session.logicalTrackingId,
			).value
		selectedEntry.observations shouldHaveSize 1
		selectedEntry.observations.single().observationCount shouldBe 2
		selectedEntry.observations.single().localSourceEventId shouldBe
			group.runs.single().facts.first().sourceEventId
		selectedEntry.observations.single().bandMix shouldBe mapOf(
			WifiHistoryBand.TWO_POINT_FOUR_GHZ to 1,
			WifiHistoryBand.FIVE_GHZ to 1,
		)
		group.runs.single().segment.sampleCount shouldBe 0
		authorityChecks shouldBe 3
	}

	@Test
	fun `production first Wi-Fi source sequence zero remains discoverable`() = runTest {
		val group = withSourceSequence(buildGroup(301, 1, setOf(0)), 0L)
		persist(listOf(group))

		val entry = (repository { true }.session(group.runs.single().segment.id) as
			WifiHistoryQuery.Found).entry

		entry.state shouldBe WifiHistoryProductState.PARTIAL
		entry.observations shouldHaveSize 1
		entry.observations.single().observationCount shouldBe 2
	}

	@Test
	fun `two distinct callback identities may both use source sequence zero`() = runTest {
		val group = withSourceSequence(
			buildGroup(306, 2, setOf(0, 1)),
			0L,
		)
		persist(listOf(group))

		val entry = (repository { true }.recent(1) as WifiHistoryPage.Available).entries.single()

		entry.state shouldBe WifiHistoryProductState.PARTIAL
		entry.observations shouldHaveSize 2
		group.runs.flatMap(BuiltRun::admissions).map { it.deliveryIdentity }.distinct().size shouldBe 2
		group.runs.flatMap(BuiltRun::admissions).all { it.sourceSequence == 0L } shouldBe true
	}

	@Test
	fun `missing callback carrier and two facts reusing one callback fail typed`() = runTest {
		val missing = buildGroup(307, 1, setOf(0)).let { group ->
			group.copy(runs = group.runs.map { it.copy(admissions = emptyList()) })
		}
		persist(listOf(missing))
		assertRecentWriterFailure()

		database.close()
		setUp()
		val base = buildGroup(308, 1, setOf(0), includeReuse = true)
		val firstWal = base.runs.single().admissions.first()
		val transformed = transformFacts(base) { fact ->
			if (fact.logicalFactId == base.runs.single().facts.last().logicalFactId) {
				fact.copy(
					sourceEventId = firstWal.eventId,
					sourceAdmissionOrdinal = firstWal.admissionOrdinal,
					walIntegrityIdentity = firstWal.integrityIdentity,
					payloadChecksum = firstWal.payloadChecksum,
					sourceSequence = firstWal.sourceSequence,
				)
			} else {
				fact
			}
		}
		val reused = transformed.copy(runs = transformed.runs.map { built -> built.copy(
			admissions = listOf(firstWal),
			completeness = built.completeness?.copy(
				lastAdmissionOrdinal = firstWal.admissionOrdinal,
				lastSourceSequence = firstWal.sourceSequence,
			),
		) })
		persist(listOf(reused))
		assertRecentWriterFailure()
	}

	@Test
	fun `rehashed Wi-Fi fact inconsistent with its canonical WAL fails history`() = runTest {
		val group = transformFacts(buildGroup(309, 1, setOf(0))) { fact ->
			if (fact.factKind == WifiCapturedFactRevisionEntity.FACT_KIND_AGGREGATE) {
				fact.copy(strongestSignalDbm = -41)
			} else {
				fact
			}
		}
		persist(listOf(group))

		assertRecentWriterFailure()
	}

	@Test
	fun `replacement siblings and correction revisions compose one logical entry`() = runTest {
		val group = buildGroup(2, 33, setOf(0))
		persist(listOf(group))

		val entry = (repository { true }.session(group.runs.last().segment.id) as WifiHistoryQuery.Found).entry

		entry.startTime.raw shouldBe group.runs.first().segment.startTimeMs
		entry.endTime.raw shouldBe group.runs.last().segment.endTimeMs
		entry.observations shouldHaveSize 1
		group.runs.first().facts shouldHaveSize 2
	}

	@Test
	fun `source facade returns complete physical groups and intent-first factless Wi-Fi-only rows`() =
		runTest {
			val replacement = buildGroup(302, 2, setOf(0))
			val factlessBase = buildGroup(303, 1, emptySet())
			val factless = factlessBase.copy(
				runs = factlessBase.runs.map { it.copy(completeness = null, admissions = emptyList()) },
			)
			persist(listOf(replacement, factless))
			val repository = repository { true }

			database.withTransaction {
				val selected = repository.selectBySegmentIdsInTransaction(
					listOf(replacement.runs.last().segment.id),
				) as WifiComposedPage.Available
				val group = selected.entries.single()
				group.logicalTrackingId shouldBe replacement.session.logicalTrackingId
				group.physicalSegmentIds shouldBe replacement.runs.map { it.segment.id }
				group.recencyStartTimeMs shouldBe replacement.runs.last().segment.startTimeMs
				group.recencySegmentId shouldBe replacement.runs.last().segment.id
				group.entry.capturesOnlyWifi shouldBe true
				repository.selectBySegmentIdsInTransaction(listOf(Long.MAX_VALUE)) shouldBe
					WifiComposedPage.Failed(WifiHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)

				val bySession = repository.sessionInTransaction(
					replacement.runs.first().segment.id,
				) as WifiHistoryQuery.Found
				bySession.entry shouldBe group.entry

				val onlyWifi = repository.recentWifiOnlyInTransaction(10) as
					WifiComposedPage.Available
				onlyWifi.entries.map { it.logicalTrackingId }.toSet() shouldBe setOf(
					replacement.session.logicalTrackingId,
					factless.session.logicalTrackingId,
				)
				onlyWifi.entries.all { it.entry.capturesOnlyWifi } shouldBe true
				onlyWifi.entries.single {
					it.logicalTrackingId == factless.session.logicalTrackingId
				}.entry.observations shouldBe emptyList()

				val mixed = repository.recentInTransaction(10) as WifiSourceRecentPage.Available
				mixed.entries.all { it is WifiSourceRecentEntry.Local } shouldBe true
				mixed.entries.filterIsInstance<WifiSourceRecentEntry.Local>().forEach { local ->
					local.composed.physicalSegmentIds.isEmpty() shouldBe false
				}
			}
		}

	@Test
	fun `raw reverse membership rejects an extra segment reparented across logical owners`() = runTest {
		val first = buildGroup(304, 1, setOf(0))
		val second = buildGroup(305, 1, setOf(0))
		persist(listOf(first, second))
		val repository = repository { true }
		val firstSegmentId = first.runs.single().segment.id
		val selection = requireNotNull(
			(repository.session(firstSegmentId) as WifiHistoryQuery.Found).entry.selection,
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_segment SET logical_tracking_id = ? WHERE id = ?",
			arrayOf<Any?>(first.session.logicalTrackingId, second.runs.single().segment.id),
		)

		(repository.session(firstSegmentId) as WifiHistoryQuery.Found).entry.state shouldBe
			WifiHistoryProductState.FAILED
		repository.lookup(selection) shouldBe
			WifiHistoryQuery.Failed(WifiHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
		database.withTransaction {
			repository.selectBySegmentIdsInTransaction(listOf(firstSegmentId)) shouldBe
				WifiComposedPage.Failed(WifiHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
			repository.recentWifiOnlyInTransaction(10) shouldBe
				WifiComposedPage.Failed(WifiHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
			repository.recentInTransaction(10) shouldBe
				WifiSourceRecentPage.Failed(WifiHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
			repository.rangeInTransaction(
				WifiHistoryRangeRequest(EpochMs(0L), EpochMs(Long.MAX_VALUE), 10),
			) shouldBe WifiHistoryRangePage.Failed(
				WifiHistoryCause.PHYSICAL_MEMBERSHIP_INVALID,
			)
		}
	}

	@Test
	fun `one-hop unchanged coverage reuses only an authenticated current aggregate`() = runTest {
		val group = buildGroup(7, 1, setOf(0), includeReuse = true)
		persist(listOf(group))
		val dependent = group.runs.single().facts.single {
			it.factKind == WifiCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY
		}

		val entry = (repository { true }.recent(1) as WifiHistoryPage.Available).entries.single()

		entry.state shouldBe WifiHistoryProductState.PARTIAL
		entry.observations shouldHaveSize 2
		entry.observations.map { it.bandMix }.distinct() shouldHaveSize 1
		promoteWifiLaneToCanonical()
		val lookup = RoomSourceFactLookupReader(
			database,
			UnconfinedTestDispatcher(),
			BootClockDomainProvider { BOOT_ID },
		).lookup(wifiFactLookupRequest(dependent))
		check(lookup is SourceFactLookupResult.Found) { "Unexpected Wi-Fi lookup: $lookup" }
	}

	@Test
	fun `coverage-only materialization and query reject a superseded aggregate owner`() = runTest {
		val group = buildGroup(8, 1, setOf(0), includeReuse = true)
		persist(listOf(group))
		val built = group.runs.single()
		val owner = built.facts.filter {
			it.factKind == WifiCapturedFactRevisionEntity.FACT_KIND_AGGREGATE
		}.maxBy(WifiCapturedFactRevisionEntity::semanticRevision)
		val dependent = built.facts.single {
			it.factKind == WifiCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY
		}
		val unsignedSuccessor = owner.copy(
			semanticRevision = owner.semanticRevision + 1L,
			supersedesSemanticRevision = owner.semanticRevision,
			mutationId = WifiCapturedFactRevisionIntegrity.mutationId(
				owner.logicalFactId,
				owner.semanticRevision + 1L,
			),
			effectChecksum = ZERO_SHA,
			appliedAtMs = owner.appliedAtMs,
		)
		val successor = unsignedSuccessor.copy(
			effectChecksum = WifiCapturedFactRevisionIntegrity.effectChecksum(unsignedSuccessor),
		)
		database.wifiCapturedFactDao().insertRevision(successor)
		database.wifiCapturedFactDao().advanceCursorExact(
			writerProjectionId = owner.writerProjectionId,
			writerProjectionVersion = owner.writerProjectionVersion,
			logicalFactId = owner.logicalFactId,
			logicalTrackingId = owner.logicalTrackingId,
			serviceRunId = owner.serviceRunId,
			sessionSegmentId = owner.sessionSegmentId,
			writerOwnerGeneration = owner.writerOwnerGeneration,
			collectedDataEpoch = owner.collectedDataEpoch,
			scopeDeletionGeneration = owner.scopeDeletionGeneration,
			expectedSemanticRevision = owner.semanticRevision,
			expectedMutationId = owner.mutationId,
			expectedEffectChecksum = owner.effectChecksum,
			expectedSourceAdmissionOrdinal = owner.sourceAdmissionOrdinal,
			expectedCursorRevision = owner.semanticRevision,
			newSemanticRevision = successor.semanticRevision,
			newMutationId = successor.mutationId,
			newEffectChecksum = successor.effectChecksum,
			newSourceAdmissionOrdinal = successor.sourceAdmissionOrdinal,
			newCursorRevision = successor.semanticRevision,
			updatedAtMs = successor.appliedAtMs,
		) shouldBe 1

		assertRecentFactFailure()
		promoteWifiLaneToCanonical()
		RoomSourceFactLookupReader(
			database,
			UnconfinedTestDispatcher(),
			BootClockDomainProvider { BOOT_ID },
		).lookup(wifiFactLookupRequest(dependent)) shouldBe
			SourceFactLookupResult.NotFound(SourceProofReason.STALE)
	}

	@Test
	fun `cursor carrier exposes dangling and corrupt current heads as typed failures`() = runTest {
		val group = buildGroup(3, 1, setOf(0))
		persist(listOf(group))
		val cursor = group.runs.single().cursors.single()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE wifi_captured_fact_cursor SET latest_effect_checksum = ? WHERE logical_fact_id = ?",
			arrayOf(sha256("corrupt-head"), cursor.logicalFactId),
		)
		assertRecentFactFailure()

		database.openHelper.writableDatabase.execSQL(
			"UPDATE wifi_captured_fact_cursor SET latest_effect_checksum = ? WHERE logical_fact_id = ?",
			arrayOf(cursor.latestEffectChecksum, cursor.logicalFactId),
		)
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM wifi_captured_fact_revision WHERE logical_fact_id = ? AND semantic_revision = ?",
			arrayOf<Any?>(cursor.logicalFactId, cursor.latestSemanticRevision),
		)
		assertRecentFactFailure()
	}

	@Test
	fun `privacy epoch and full uncertainty retention fences suppress Wi-Fi values`() = runTest {
		val deleted = buildGroup(8, 1, setOf(0))
		val fact = deleted.runs.single().facts.last()
		persist(listOf(deleted), SourceEvidenceState(
			collectedDataEpoch = 0L,
			deletedSourceEventHighWaterOrdinal = fact.sourceAdmissionOrdinal,
		))
		val deletedEntry = (repository { true }.session(deleted.runs.single().segment.id) as
			WifiHistoryQuery.Found).entry
		deletedEntry.state shouldBe WifiHistoryProductState.UNAVAILABLE
		deletedEntry.causes shouldBe setOf(WifiHistoryCause.PRIVACY_EPOCH_MISMATCH)
		deletedEntry.observations shouldBe emptyList()
		deletedEntry.capturesOnlyWifi shouldBe true

		database.close()
		setUp()
		val expired = buildGroup(9, 1, setOf(0))
		val expiredFact = expired.runs.single().facts.last()
		persist(listOf(expired), SourceEvidenceState(
			collectedDataEpoch = 0L,
			retainedFromMs = expiredFact.observedWallTimeMs + expiredFact.wallTimeUncertaintyMs + 1L,
		))
		val expiredEntry = (repository { true }.session(expired.runs.single().segment.id) as
			WifiHistoryQuery.Found).entry
		expiredEntry.state shouldBe WifiHistoryProductState.UNAVAILABLE
		expiredEntry.causes shouldBe setOf(WifiHistoryCause.RETENTION_LIMIT)
		expiredEntry.observations shouldBe emptyList()
		expiredEntry.capturesOnlyWifi shouldBe true
	}

	@Test
	fun `partial results and no-fact active capture remain truthful`() = runTest {
		val partial = buildGroup(4, 1, setOf(0), partialResult = true)
		persist(listOf(partial))
		val partialEntry = (repository { true }.recent(1) as WifiHistoryPage.Available).entries.single()
		partialEntry.state shouldBe WifiHistoryProductState.PARTIAL
		partialEntry.causes shouldBe setOf(
			WifiHistoryCause.ACQUISITION_INCOMPLETE,
			WifiHistoryCause.RESULT_SET_PARTIAL,
		)

		database.close()
		setUp()
		val active = buildGroup(5, 1, emptySet(), active = true)
		persist(listOf(active))
		val activeEntry = (repository { true }.recent(1) as WifiHistoryPage.Available).entries.single()
		activeEntry.state shouldBe WifiHistoryProductState.MATERIALIZING
		activeEntry.observations shouldBe emptyList()
		activeEntry.capturesOnlyWifi shouldBe true

		database.close()
		setUp()
		val unavailableBase = buildGroup(6, 1, emptySet())
		val unavailable = unavailableBase.copy(runs = unavailableBase.runs.map { built ->
			built.copy(completeness = requireNotNull(built.completeness).copy(stopStatus = "PERMISSION_LOST"))
		})
		persist(listOf(unavailable))
		val unavailableEntry = (repository { true }.recent(1) as WifiHistoryPage.Available).entries.single()
		unavailableEntry.state shouldBe WifiHistoryProductState.UNAVAILABLE
		unavailableEntry.causes shouldBe setOf(WifiHistoryCause.PROVIDER_UNAVAILABLE)
		unavailableEntry.capturesOnlyWifi shouldBe true
	}

	@Test
	fun `candidate paging uses logical recency and membership overflow and cancellation are typed`() = runTest {
		val groups = (1..33).map { buildGroup(100 + it, 1, setOf(0)) }
		persist(groups)
		val page = repository { true }.recent(33) as WifiHistoryPage.Available
		page.entries shouldHaveSize 33
		page.entries.map { it.startTime.raw } shouldBe groups.asReversed().map { it.runs.single().segment.startTimeMs }

		database.close()
		setUp()
		val overflow = buildGroup(200, 129, setOf(0))
		persist(listOf(overflow))
		repository { true }.recent(1) shouldBe WifiHistoryPage.Failed(WifiHistoryCause.READ_BUDGET_EXCEEDED)
		database.withTransaction {
			repository { true }.selectBySegmentIdsInTransaction(
				listOf(overflow.runs.first().segment.id),
			) shouldBe WifiComposedPage.Failed(WifiHistoryCause.READ_BUDGET_EXCEEDED)
			repository { true }.recentWifiOnlyInTransaction(1) shouldBe
				WifiComposedPage.Failed(WifiHistoryCause.READ_BUDGET_EXCEEDED)
		}
		assertFailsWith<CancellationException> {
			withContext(Job().apply { cancel() }) { repository { true }.recent(1) }
		}
	}

	@Test
	fun `range keyset discovers entries older than recent limit without a global scan`() = runTest {
		val groups = (1..105).map { buildGroup(400 + it, 1, setOf(0)) }
		persist(groups)
		val repository = repository { true }
		val recent = (repository.recent(100) as WifiHistoryPage.Available).entries
		val ranged = mutableListOf<com.adsamcik.tracker.stats.api.repository.WifiHistoryEntry>()
		var continuation: WifiHistoryRangeContinuation? = null
		do {
			val page = repository.range(
				WifiHistoryRangeRequest(EpochMs(0L), EpochMs(Long.MAX_VALUE), 40, continuation),
			) as WifiHistoryRangePage.Available
			ranged += page.entries
			continuation = page.continuation
		} while (continuation != null)

		ranged shouldHaveSize 105
		recent shouldHaveSize 100
		ranged.last().startTime.raw shouldBe groups.first().runs.single().segment.startTimeMs
		recent.none { it.startTime == ranged.last().startTime } shouldBe true

		val firstPage = repository.range(
			WifiHistoryRangeRequest(EpochMs(0L), EpochMs(Long.MAX_VALUE), 1),
		) as WifiHistoryRangePage.Available
		val token = requireNotNull(firstPage.continuation).value
		repository.range(
			WifiHistoryRangeRequest(
				EpochMs(0L),
				EpochMs(Long.MAX_VALUE),
				1,
				WifiHistoryRangeContinuation(token.dropLast(1) + if (token.last() == '0') "1" else "0"),
			),
		) shouldBe WifiHistoryRangePage.Failed(WifiHistoryCause.RANGE_CONTINUATION_INVALID)
	}

	@Test
	fun `structural days retain an empty run beside a fact-bearing run in its own stored zone`() =
		runTest {
			val base = buildGroup(6, 2, setOf(0))
			val group = base.copy(
				runs = base.runs.mapIndexed { index, built ->
					if (index == 0) {
						built
					} else {
						val unsigned = built.manifest.copy(
							zoneId = "Europe/Prague",
							manifestChecksum = "pending",
						)
						built.copy(
							manifest = unsigned.copy(
								manifestChecksum = SessionManifestIntegrity.compute(
									unsigned,
									listOf(built.source),
								),
							),
						)
					}
				},
			)
			persist(listOf(group))

			val page = repository { true }.structuralDays(
				WifiHistoryRangeRequest(
					EpochMs(group.runs.first().segment.startTimeMs),
					EpochMs(group.runs.last().segment.endTimeMs + 1L),
					10,
				),
			) as WifiHistoryStructuralDayPage.Available

			page.days.map { it.storedZoneId }.toSet() shouldBe setOf("UTC", "Europe/Prague")
			page.days.single { it.storedZoneId == "UTC" }
				.memberships.single().allocation shouldBe WifiHistoryDayAllocation.PARTIAL
			page.days.single { it.storedZoneId == "Europe/Prague" }
				.memberships.single().allocation shouldBe WifiHistoryDayAllocation.UNAVAILABLE
		}

	@Test
	fun `fact-only history rejects stale and half-open boundary observations`() = runTest {
		val stale = transformFacts(buildGroup(210, 1, setOf(0))) { fact ->
			fact.copy(receivedElapsedNanos = Math.addExact(
				fact.coverageIntervalStartNanos, fact.maximumObservationAgeNanos + 1L,
			))
		}
		persist(listOf(stale))
		assertRecentWriterFailure()

		database.close()
		setUp()
		val base = buildGroup(211, 1, setOf(0))
		val runEnd = base.runs.single().segment.endTimeMs
		val elapsedEnd = base.runs.single().facts.last().sessionRunEffectEndNanos
		val boundary = transformFacts(base) { fact ->
			fact.copy(
				observedElapsedNanos = elapsedEnd,
				receivedElapsedNanos = elapsedEnd,
				coverageIntervalEndNanos = elapsedEnd,
				observedWallTimeMs = runEnd,
				acquiredAtMs = runEnd,
				appliedAtMs = runEnd,
			)
		}
		persist(listOf(boundary))
		assertRecentWriterFailure()
	}

	@Test
	fun `one physical registration may serve a later replacement manifest`() = runTest {
		val group = shareRegistrationAcrossReplacement(buildGroup(212, 2, setOf(1)))

		persist(listOf(group))

		val entry = (repository { true }.recent(1) as WifiHistoryPage.Available).entries.single()
		entry.state shouldBe WifiHistoryProductState.PARTIAL
		entry.observations shouldHaveSize 1
		val reserved = requireNotNull(group.runs.first().provider).reservedElapsedRealtimeNanos
		(reserved < group.runs.last().manifest.effectiveElapsedRealtimeNanos) shouldBe true
	}

	@Test
	fun `source-local attribution queries enforce their SQL result limits`() = runTest {
		val group = buildGroup(213, 2, setOf(1))
		persist(listOf(group))
		val dao = database.wifiCapturedFactDao()
		val runs = group.runs.map { it.run.serviceRunId }

		dao.historyPolicies(WIFI_SOURCE, runs, 1) shouldHaveSize 1
		dao.historyConsentEpochs(
			WIFI_SOURCE,
			SourceBrokerPurpose.SESSION_CAPTURE,
			group.runs.map { it.consent.epoch },
			1,
		) shouldHaveSize 1
		dao.historyDemands(
			group.runs.flatMap(BuiltRun::demands).map(SourceDemandEntity::demandId),
			1,
		) shouldHaveSize 1
		dao.historyProductLanes(WIFI_SOURCE, SourceBrokerPurpose.SESSION_CAPTURE, runs, 1) shouldHaveSize 1
	}

	@Test
	fun `lane stage rollout chronology and retired cursor shape fail closed`() = runTest {
		val mutations = listOf(
			"UPDATE source_product_projection_lane SET product_stage = 'UNKNOWN'",
			"UPDATE source_product_projection_lane SET activated_rollout_revision = 0",
			"UPDATE source_product_projection_lane SET installed_at_ms = 2, updated_at_ms = 1",
			"UPDATE source_product_projection_lane SET status = 'RETIRED', retention_required = 0, " +
				"capture_admission_cutoff_ordinal = contiguous_admission_ordinal + 1, " +
				"terminal_disposition = 'CONTAINED_AFTER_DRAIN', terminal_at_ms = 1, updated_at_ms = 1",
		)
		mutations.forEachIndexed { index, mutation ->
			if (index > 0) {
				database.close()
				setUp()
			}
			persist(listOf(buildGroup(220 + index, 1, setOf(0))))
			database.openHelper.writableDatabase.execSQL(mutation)
			assertRecentWriterFailure()
		}
	}

	@Test
	fun `fully drained retired lane remains valid`() = runTest {
		val group = buildGroup(230, 1, setOf(0))
		persist(listOf(group))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_product_projection_lane SET status = 'RETIRED', retention_required = 0, " +
				"capture_admission_cutoff_ordinal = contiguous_admission_ordinal, " +
				"terminal_disposition = 'CONTAINED_AFTER_DRAIN', terminal_at_ms = 1, updated_at_ms = 1",
		)

		val entry = (repository { true }.recent(1) as WifiHistoryPage.Available).entries.single()
		entry.state shouldBe WifiHistoryProductState.PARTIAL
	}

	@Test
	fun `compatible shared authorization member does not steal captured fact ownership`() = runTest {
		val group = withAdditionalAuthorizationMember(
			buildGroup(240, 1, setOf(0)),
			compatible = true,
		)
		persist(listOf(group))

		val entry = (repository { true }.recent(1) as WifiHistoryPage.Available).entries.single()

		entry.state shouldBe WifiHistoryProductState.PARTIAL
		entry.observations shouldHaveSize 1
		group.runs.single().authorizations
			.filter { authorization -> authorization.authorizationRevision == 1L } shouldHaveSize 2
	}

	@Test
	fun `incompatible shared authorization member fails product authority closed`() = runTest {
		val group = withAdditionalAuthorizationMember(
			buildGroup(241, 1, setOf(0)),
			compatible = false,
		)
		persist(listOf(group))

		assertRecentWriterFailure()
	}

	@Test
	fun `pending WAL authenticates compatible shared members and rejects incompatible ones`() = runTest {
		val compatible = withAdditionalAuthorizationMember(
			buildGroup(242, 1, emptySet(), active = true),
			compatible = true,
		)
		persist(listOf(compatible))
		val materializing = (repository { true }.recent(1) as WifiHistoryPage.Available).entries.single()
		materializing.state shouldBe WifiHistoryProductState.MATERIALIZING

		database.close()
		setUp()
		val incompatible = withAdditionalAuthorizationMember(
			buildGroup(243, 1, emptySet(), active = true),
			compatible = false,
		)
		persist(listOf(incompatible))
		assertRecentWriterFailure()
	}

	private fun promoteWifiLaneToCanonical() {
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_product_projection_lane SET product_stage = 'EVENT_CANONICAL' " +
				"WHERE source_kind = ?",
			arrayOf<Any?>(WIFI_SOURCE),
		)
	}

	@Test
	fun `denied v6 callback after End keeps Wi-Fi native history and exact fact lookup`() = runTest {
		val group = deniedV6CallbackGroup(buildGroup(249, 1, setOf(0)))
		persist(listOf(group))
		persistV6Children(group)
		val entry = (repository { true }.recent(1) as WifiHistoryPage.Available).entries.single()
		entry.observations shouldHaveSize 1
		entry.observations.single().networks shouldHaveSize 2
		val fact = group.runs.single().facts.last()
		val lookup = RoomSourceFactLookupReader(database, Dispatchers.IO, BootClockDomainProvider { BOOT_ID }).lookup(wifiFactLookupRequest(fact))
		assertIs<SourceFactLookupResult.Found>(lookup)
		database.openHelper.writableDatabase.execSQL("DELETE FROM wifi_captured_child WHERE item_index = 0")
		assertIs<SourceFactLookupResult.Failed>(RoomSourceFactLookupReader(database, Dispatchers.IO, BootClockDomainProvider { BOOT_ID })
			.lookup(wifiFactLookupRequest(fact)))
		assertRecentWriterFailure()
	}

	@Test
	fun `v6 history keeps readings on both sides of a callback authorization rotation`() = runTest {
		val group = v6RotatedGroup(buildGroup(244, 1, setOf(0)))
		persist(listOf(group))
		persistV6Children(group)
		val entry = (repository { true }.recent(1) as WifiHistoryPage.Available).entries.single()
		entry.state shouldBe WifiHistoryProductState.PARTIAL
		entry.observations shouldHaveSize 1
		entry.observations.single().acceptedResultCount shouldBe 2
		entry.observations.single().networks shouldHaveSize 2
	}

	@Test
	fun `v6 history shows only readings authorized for the capture purpose`() = runTest {
		val group = v6RotatedGroup(buildGroup(245, 1, setOf(0)), excludeFirst = true)
		persist(listOf(group))
		persistV6Children(group)
		val entry = (repository { true }.recent(1) as WifiHistoryPage.Available).entries.single()
		entry.observations shouldHaveSize 1
		entry.observations.single().acceptedResultCount shouldBe 1
		entry.observations.single().networks shouldHaveSize 1
	}

	@Test
	fun `new marked Wi-Fi scans cannot show a summary after losing all native details`() = runTest {
		val group = v6RotatedGroup(buildGroup(247, 1, setOf(0)))
		persist(listOf(group))
		persistV6Children(group)
		val entry = (repository { true }.recent(1) as WifiHistoryPage.Available).entries.single()
		entry.observations.single().networks shouldHaveSize 2
		database.openHelper.writableDatabase.execSQL("DELETE FROM wifi_captured_child")
		assertRecentWriterFailure()
	}

	@Test
	fun `native Wi-Fi history rejects a resealed marker that contradicts its original carrier`() = runTest {
		val original = v6RotatedGroup(buildGroup(248, 1, setOf(0)))
		val group = original.copy(runs = original.runs.map { run ->
			val facts = run.facts.map { fact -> fact.copy(payloadVersion = LEGACY_WIFI_WAL_PAYLOAD_VERSION).let {
				it.copy(effectChecksum = WifiCapturedFactRevisionIntegrity.effectChecksum(it))
			} }
			run.copy(facts = facts, cursors = listOf(cursor(facts.last())))
		})
		persist(listOf(group))
		persistV6Children(group)
		assertRecentWriterFailure()
	}

	@Test
	fun `v6 rehashed child outside capture authority cannot lend a reading to history`() = runTest {
		val complete = v6RotatedGroup(buildGroup(246, 1, setOf(0)))
		val group = complete.copy(runs = complete.runs.map { run ->
			val opening = SourceBrokerAuthorization.rows(WIFI_SOURCE, requireNotNull(run.provider).registrationGeneration,
				1L, emptyList(), BOOT_ID, run.manifest.effectiveElapsedRealtimeNanos, run.manifest.effectiveWallTimeMs)
			run.copy(authorizations = opening + run.authorizations.filter { it.authorizationRevision != 1L })
		})
		persist(listOf(group))
		persistV6Children(group)
		assertRecentWriterFailure()
	}

	@Test
	fun `late Wi-Fi history keeps each saved network on its own local date across midnight`() = runTest {
		val midnight = java.time.LocalDate.of(2026, 10, 4).atStartOfDay(java.time.ZoneId.of(ZONE_ID)).toInstant().toEpochMilli()
		val group = deniedV6CallbackGroup(buildGroup(250, 1, setOf(0), firstWallTimeMs = midnight - 150L))
		persist(listOf(group))
		persistV6Children(group)
		val entry = (repository { true }.recent(1) as WifiHistoryPage.Available).entries.single()
		val networks = entry.observations.single().networks
		networks shouldHaveSize 2
		networks.map { java.time.Instant.ofEpochMilli(it.observedTimeMs).atZone(java.time.ZoneId.of(ZONE_ID)).toLocalDate() }
			shouldBe listOf(java.time.LocalDate.of(2026, 10, 3), java.time.LocalDate.of(2026, 10, 4))
		val raw = group.runs.single().admissions.single()
		kotlin.test.assertTrue(requireNotNull(raw.receivedWallTimeMs) > midnight)
		kotlin.test.assertTrue(group.session.cutoffAtMs!! < requireNotNull(raw.receivedWallTimeMs))
	}

	@Test
	fun `exact Wi-Fi lookup cannot borrow an aggregate whose own provider delivery is missing`() = runTest {
		val group = buildGroup(251, 1, setOf(0), includeReuse = true)
		persist(listOf(group))
		promoteWifiLaneToCanonical()
		val facts = group.runs.single().facts
		val dependent = facts.single { it.factKind == WifiCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY }
		val owner = facts.single { it.logicalFactId == dependent.aggregateOwnerLogicalFactId &&
			it.semanticRevision == dependent.aggregateOwnerSemanticRevision }
		val reader = RoomSourceFactLookupReader(database, UnconfinedTestDispatcher(), BootClockDomainProvider { BOOT_ID })
		assertIs<SourceFactLookupResult.Found>(reader.lookup(wifiFactLookupRequest(dependent)))
		database.openHelper.writableDatabase.execSQL("DELETE FROM source_event_wal WHERE event_id = ?", arrayOf(owner.sourceEventId))
		reader.lookup(wifiFactLookupRequest(dependent)) shouldBe
			SourceFactLookupResult.Unavailable(SourceProofReason.IMMUTABLE_AUTHORITY_MISSING)
		assertRecentWriterFailure()
	}

	private fun deniedV6CallbackGroup(input: Group): Group {
		val original = v6RotatedGroup(input)
		return original.copy(session = original.session.copy(finalAdmissionOrdinal = 0L),
			runs = original.runs.map { run ->
				val closing = run.authorizations.first { it.authorizationRevision == 3L }
				val unsignedWal = run.admissions.single().copy(logicalTrackingId = null, serviceRunId = null,
					configRevision = run.manifest.acquisitionPlanRevision, sourcePolicyRevision = null, captureConsentEpoch = null,
					sessionManifestRevision = null, lifecycleLeaseGeneration = null, planAttribution = 2,
					authorizationRevision = 3L, authorizationFingerprint = closing.authorizationFingerprint,
					authorizationPurposeEligibilityMask = 0L,
					receivedElapsedNanos = closing.effectiveElapsedRealtimeNanos + 1L,
					receivedWallTimeMs = closing.effectiveWallTimeMs + 1L)
				val wal = unsignedWal.copy(integrityIdentity = unsignedWal.calculatedIntegrityIdentity())
				val facts = run.facts.map { fact -> fact.copy(walIntegrityIdentity = wal.integrityIdentity,
					receivedElapsedNanos = wal.receivedElapsedNanos).let {
					it.copy(effectChecksum = WifiCapturedFactRevisionIntegrity.effectChecksum(it))
				} }
				run.copy(admissions = listOf(wal), facts = facts, cursors = listOf(cursor(facts.last())),
					completeness = run.completeness?.copy(lastAdmissionOrdinal = null, lastSourceSequence = null))
			})
	}

	private fun v6RotatedGroup(group: Group, excludeFirst: Boolean = false, excludeLast: Boolean = false): Group = group.copy(
		runs = group.runs.map { run ->
			val firstWal = run.admissions.single()
			val midpoint = (requireNotNull(firstWal.observedIntervalStartNanos) + firstWal.observedElapsedNanos) / 2L
			val rotated = SourceBrokerAuthorization.rows(WIFI_SOURCE, firstWal.registrationGeneration,
				2L, run.demands, BOOT_ID, midpoint, requireNotNull(firstWal.wallTimeMs))
			val opening = if (excludeFirst) SourceBrokerAuthorization.rows(WIFI_SOURCE,
				firstWal.registrationGeneration, 1L, emptyList(), BOOT_ID,
				run.manifest.effectiveElapsedRealtimeNanos, run.manifest.effectiveWallTimeMs)
			else run.authorizations.filter { it.authorizationRevision == 1L }
			val closing = run.authorizations.filter { it.authorizationRevision == 2L }
				.map { it.copy(authorizationRevision = 3L) }.let { rows ->
					val first = rows.first()
					SourceBrokerAuthorization.rows(WIFI_SOURCE, firstWal.registrationGeneration,
						3L, emptyList(), BOOT_ID, first.effectiveElapsedRealtimeNanos, first.effectiveWallTimeMs)
				}
			val decoded = (WifiWalPayloadCodec.decodeCanonical(firstWal.payloadVersion, firstWal.payload)
				as WifiWalPayloadDecodeResult.Decoded).payload
			// A still-authorized callback can contain a later malformed network item. The real
			// writer rejects that item's frequency while retaining the earlier valid reading.
			val payload = if (excludeLast) decoded.copy(observations = decoded.observations.map { item ->
				if (item.providerTimestampNanos == firstWal.observedElapsedNanos) item.copy(frequencyMhz = 100) else item
			}.sortedBy { it.frequencyMhz }) else decoded
			val encoded = WifiWalPayloadCodec.encode(payload, WIFI_WAL_PAYLOAD_VERSION)
			val delivery = canonicalWifiProviderDeliveryIdentity(BOOT_ID, payload.observations, WIFI_WAL_PAYLOAD_VERSION)
			val unsignedWal = firstWal.copy(payloadVersion = WIFI_WAL_PAYLOAD_VERSION,
				payload = encoded.toByteArray(), payloadChecksum = encoded.checksum, deliveryIdentity = delivery,
				authorizationRevision = 2L, authorizationFingerprint = rotated.first().authorizationFingerprint,
				authorizationPurposeEligibilityMask = rotated.first().purposeEligibilityMask)
			val wal = unsignedWal.copy(integrityIdentity = unsignedWal.calculatedIntegrityIdentity())
			val facts = run.facts.map { original ->
				val id = WifiCapturedFactRevisionIntegrity.logicalFactId(delivery, original.logicalTrackingId,
					original.serviceRunId, original.sessionSegmentId, original.manifestRevision,
					original.collectedDataEpoch, original.scopeDeletionGeneration)
				val selectedStart = if (excludeFirst) original.coverageIntervalEndNanos else original.coverageIntervalStartNanos
				val selectedEnd = if (excludeLast) original.coverageIntervalStartNanos else original.coverageIntervalEndNanos
				val partial = excludeFirst || excludeLast
				val unsigned = original.copy(logicalFactId = id,
					mutationId = WifiCapturedFactRevisionIntegrity.mutationId(id, original.semanticRevision),
					sourceDeliveryIdentity = delivery, walIntegrityIdentity = wal.integrityIdentity,
					payloadChecksum = encoded.checksum, payloadVersion = WIFI_WAL_PAYLOAD_VERSION, authorizationRevision = 2L,
					authorizationFingerprint = rotated.first().authorizationFingerprint,
					purposeEligibilityMask = rotated.first().purposeEligibilityMask,
					authorizationEffectStartNanos = selectedStart, coverageIntervalStartNanos = selectedStart,
					coverageIntervalEndNanos = selectedEnd,
					observedWallTimeMs = original.observedWallTimeMs - (original.coverageIntervalEndNanos - selectedEnd) / 1_000_000L,
					acceptedResultCount = if (partial) 1 else 2, staleResultCount = if (excludeFirst) 1 else 0,
					malformedResultCount = if (excludeLast) 1 else 0,
					coverageCompleteness = if (partial) WifiCapturedFactRevisionEntity.COVERAGE_PARTIAL else
						WifiCapturedFactRevisionEntity.COVERAGE_COMPLETE,
					observationCount = if (partial) 1 else 2, twoPointFourGhzCount = if (excludeFirst) 0 else 1,
					fiveGhzCount = if (excludeLast) 0 else 1,
					strongestSignalDbm = if (excludeLast) -60 else -40,
					weakestSignalDbm = if (excludeFirst) -40 else -60,
					signalSumDbm = if (excludeFirst) -40L else if (excludeLast) -60L else -100L)
				unsigned.copy(effectChecksum = WifiCapturedFactRevisionIntegrity.effectChecksum(unsigned))
			}
			run.copy(authorizations = opening + rotated + closing, admissions = listOf(wal), facts = facts,
				cursors = listOf(cursor(facts.last())))
		},
	)

	private suspend fun persistV6Children(group: Group) {
		val children = group.runs.flatMap { run ->
			val wal = run.admissions.single()
			val payload = (WifiWalPayloadCodec.decodeCanonical(wal.payloadVersion, wal.payload)
				as WifiWalPayloadDecodeResult.Decoded).payload
			run.facts.flatMap { fact ->
				payload.observations.mapIndexedNotNull { index, item ->
					val providerTime = requireNotNull(item.providerTimestampNanos)
					if (providerTime !in fact.coverageIntervalStartNanos..fact.coverageIntervalEndNanos) return@mapIndexedNotNull null
					val age = wal.observedElapsedNanos - providerTime
					val wall = requireNotNull(wal.wallTimeMs) - age / 1_000_000L
					val uncertainty = requireNotNull(wal.wallTimeUncertaintyMs) + if (age % 1_000_000L == 0L) 0L else 1L
					val owner = RadioChildOwner(RadioChildPurpose.SESSION, fact.writerProjectionId,
						fact.writerProjectionVersion, fact.logicalFactId, fact.semanticRevision, index)
					WifiCapturedChildEntity(fact.writerProjectionId, fact.writerProjectionVersion,
						fact.logicalFactId, fact.semanticRevision, index, providerTime, wall, uncertainty,
						null, null, item.frequencyMhz, item.signalLevelDbm,
						RadioObservationChildIntegrity.wifi(WifiChildChecksumInput(owner, providerTime, wall,
							uncertainty, null, null, item.frequencyMhz, item.signalLevelDbm)))
				}
			}
		}
		database.radioObservationChildDao().insertWifiSession(children)
	}

	private fun repository(authority: () -> Boolean) = DefaultWifiHistoryRepository(
		database,
		SourceProductLaneExecutionAuthority { authority() },
		object : ImportedWifiProductEvaluator {
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
		object : ReadLocalPortableCapturedWifi {
			override suspend fun readInTransaction(
				request: ExportPortableCapturedWifiRequest,
			): ReadLocalPortableCapturedWifiResult = error("No imported collision expected")
		},
		WifiDeletedHistoryReader { WifiDeletedHistoryResult.NotDeleted },
		BootClockDomainProvider { BOOT_ID },
		UnconfinedTestDispatcher(),
	)

	private suspend fun assertRecentFactFailure() {
		val entry = (repository { true }.recent(1) as WifiHistoryPage.Available).entries.single()
		entry.state shouldBe WifiHistoryProductState.FAILED
		entry.causes shouldBe setOf(WifiHistoryCause.FACT_INTEGRITY_FAILED)
		entry.observations shouldBe emptyList()
		entry.selection shouldBe null
	}

	private suspend fun assertRecentWriterFailure() {
		val entry = (repository { true }.recent(1) as WifiHistoryPage.Available).entries.single()
		entry.state shouldBe WifiHistoryProductState.FAILED
		entry.causes shouldBe setOf(WifiHistoryCause.WRITER_PROVENANCE_INVALID)
		entry.observations shouldBe emptyList()
		entry.selection shouldBe null
	}

	private suspend fun persist(
		groups: List<Group>,
		evidence: SourceEvidenceState = SourceEvidenceState(collectedDataEpoch = 0L),
	) {
		val runs = groups.flatMap(Group::runs)
		val sessionDao = database.sourceSessionDao()
		val policyDao = database.sourcePolicyDao()
		val planDao = database.sourcePlanStateDao()
		val brokerDao = database.sourceBrokerDao()
		val factDao = database.wifiCapturedFactDao()
		database.sourceEvidenceStateDao().ensure(evidence)
		groups.map(Group::session).forEach { sessionDao.insertSession(it) }
		database.sessionSegmentDao().insert(runs.map(BuiltRun::segment))
		runs.sortedBy { it.run.startedAtMs }.forEach { sessionDao.insertServiceRun(it.run) }
		runs.sortedBy { it.manifest.manifestRevision }.forEach { sessionDao.insertManifest(it.manifest) }
		sessionDao.insertManifestSources(runs.map(BuiltRun::source))
		policyDao.insertPolicies(runs.map(BuiltRun::policy).sortedBy(SourcePolicyEntity::policyRevision))
		policyDao.insertConsentEpochs(runs.map(BuiltRun::consent).sortedBy(SourceConsentEpochEntity::epoch))
		runs.map(BuiltRun::planHeader).sortedBy(AcquisitionPlanRevisionEntity::revision)
			.forEach { planDao.insertRevision(it) }
		planDao.insertDesiredPlans(runs.map(BuiltRun::desiredPlan).sortedBy(SourceDesiredPlanEntity::revision))
		brokerDao.insertDemands(runs.flatMap(BuiltRun::demands))
		runs.mapNotNull(BuiltRun::provider).forEach { brokerDao.insertRegistration(it) }
		brokerDao.insertAuthorizations(runs.flatMap(BuiltRun::authorizations))
		sessionDao.insertLifecycleActions(runs.flatMap(BuiltRun::actions))
		database.sourceProjectionStateDao().installProductLane(lane(runs.flatMap(BuiltRun::facts)
			.maxOfOrNull(WifiCapturedFactRevisionEntity::sourceAdmissionOrdinal) ?: 1L))
		runs.mapNotNull(BuiltRun::completeness).forEach { sessionDao.saveCompleteness(it) }
		runs.flatMap(BuiltRun::admissions).forEach { database.sourceEventWalDao().insertIgnoringDuplicate(it) }
		runs.flatMap(BuiltRun::facts).sortedWith(compareBy(
			{ it.factKind == WifiCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY },
			WifiCapturedFactRevisionEntity::logicalFactId,
			WifiCapturedFactRevisionEntity::semanticRevision,
		)).forEach { factDao.insertRevision(it) }
		runs.flatMap(BuiltRun::cursors).forEach { factDao.insertCursor(it) }
	}

	private fun buildGroup(
		groupIndex: Int,
		runCount: Int,
		factRunIndexes: Set<Int>,
		partialResult: Boolean = false,
		active: Boolean = false,
		includeReuse: Boolean = false,
		firstWallTimeMs: Long? = null,
	): Group {
		require(groupIndex > 0 && runCount > 0)
		val logicalId = "logical-wifi-$groupIndex"
		val specs = (0 until runCount).map { local ->
			val global = groupIndex * 1_000L + local + 1L
			RunSpec(
				manifestRevision = local + 1L,
				globalRevision = global,
				runId = "wifi-run-$groupIndex-${local + 1}",
				startWallMs = (firstWallTimeMs ?: (WALL_BASE_MS + groupIndex * 1_000_000L)) + local * RUN_DURATION_MS,
				startElapsedNanos = 1_000_000_000L + groupIndex * 100_000_000_000L +
					local * RUN_DURATION_NANOS,
				hasFact = local in factRunIndexes,
			)
		}
		val sessionEndElapsed = specs.last().startElapsedNanos + RUN_DURATION_NANOS
		val sessionEndWall = specs.last().startWallMs + RUN_DURATION_MS
		val built = specs.map { buildRun(logicalId, it, partialResult, active, includeReuse) }
		val finalAdmission = built.flatMap(BuiltRun::facts)
			.maxOfOrNull(WifiCapturedFactRevisionEntity::sourceAdmissionOrdinal) ?: 1L
		val last = built.last()
		return Group(
			LogicalTrackingSessionEntity(
				logicalTrackingId = logicalId,
				state = if (active) "ACTIVE" else "FINALIZED",
				lifecycleRevision = 2L,
				desiredPlanRevision = last.run.desiredPlanRevision,
				rolloutRevision = ROLLOUT_REVISION,
				startOrigin = START_ORIGIN,
				clockDomainId = BOOT_ID,
				startedAtMs = built.first().run.startedAtMs,
				startedElapsedNanos = built.first().run.startedElapsedNanos,
				cutoffAtMs = sessionEndWall.takeUnless { active },
				cutoffElapsedNanos = sessionEndElapsed.takeUnless { active },
				completedAtMs = sessionEndWall.takeUnless { active },
				finalAdmissionOrdinal = finalAdmission.takeUnless { active },
				failureCode = null,
				sessionMode = "MANUAL",
				currentManifestRevision = specs.last().manifestRevision,
				currentIntentRevision = 1L,
				currentServiceRunId = last.run.serviceRunId.takeIf { active },
				lifecycleLeaseGeneration = LEASE_GENERATION,
				lifecycleBootId = BOOT_ID,
				automationEpoch = null,
			),
			built,
		)
	}

	private fun transformFacts(
		group: Group,
		transform: (WifiCapturedFactRevisionEntity) -> WifiCapturedFactRevisionEntity,
	): Group = group.copy(runs = group.runs.map { built ->
		val facts = built.facts.map { original ->
			val changed = transform(original).copy(effectChecksum = ZERO_SHA)
			changed.copy(effectChecksum = WifiCapturedFactRevisionIntegrity.effectChecksum(changed))
		}
		val current = facts.groupBy(WifiCapturedFactRevisionEntity::logicalFactId)
			.values.map { it.maxBy(WifiCapturedFactRevisionEntity::semanticRevision) }
		built.copy(facts = facts, cursors = current.map(::cursor))
	})

	private fun withSourceSequence(group: Group, sourceSequence: Long): Group =
		group.copy(runs = group.runs.map { built ->
			val admissions = built.admissions.map { original ->
				val unsigned = original.copy(
					sourceSequence = sourceSequence,
					integrityIdentity = SourceEventWalEntity.LEGACY_PENDING_CHECKSUM,
				)
				unsigned.copy(integrityIdentity = unsigned.calculatedIntegrityIdentity())
			}
			val admissionsByEvent = admissions.associateBy(SourceEventWalEntity::eventId)
			val facts = built.facts.map { original ->
				val wal = requireNotNull(admissionsByEvent[original.sourceEventId])
				val unsigned = original.copy(
					sourceSequence = sourceSequence,
					walIntegrityIdentity = wal.integrityIdentity,
					effectChecksum = ZERO_SHA,
				)
				unsigned.copy(effectChecksum = WifiCapturedFactRevisionIntegrity.effectChecksum(unsigned))
			}
			val current = facts.groupBy(WifiCapturedFactRevisionEntity::logicalFactId)
				.values.map { it.maxBy(WifiCapturedFactRevisionEntity::semanticRevision) }
			built.copy(
				admissions = admissions,
				facts = facts,
				cursors = current.map(::cursor),
				completeness = built.completeness?.copy(lastSourceSequence = sourceSequence),
			)
		})

	private fun shareRegistrationAcrossReplacement(group: Group): Group {
		require(group.runs.size == 2)
		val first = group.runs.first()
		val later = group.runs.last()
		val firstProvider = requireNotNull(first.provider)
		val laterProvider = requireNotNull(later.provider)
		val sharedProvider = firstProvider.copy(
			retiredAtMs = laterProvider.retiredAtMs,
			retiredElapsedRealtimeNanos = laterProvider.retiredElapsedRealtimeNanos,
		)
		val authorization = SourceBrokerAuthorization.rows(
			WIFI_SOURCE,
			sharedProvider.registrationGeneration,
			1L,
			later.demands,
			BOOT_ID,
			later.manifest.effectiveElapsedRealtimeNanos,
			later.manifest.effectiveWallTimeMs,
		)
		val deny = SourceBrokerAuthorization.rows(
			WIFI_SOURCE,
			sharedProvider.registrationGeneration,
			2L,
			emptyList(),
			BOOT_ID,
			requireNotNull(laterProvider.retiredElapsedRealtimeNanos),
			requireNotNull(laterProvider.retiredAtMs),
		)
		val fingerprint = authorization.first().authorizationFingerprint
		val transformed = transformFacts(
			group.copy(runs = listOf(later)),
		) { fact ->
			fact.copy(
				sourceInstanceId = sharedProvider.sourceInstanceId,
				registrationGeneration = sharedProvider.registrationGeneration,
				authorizationFingerprint = fingerprint,
				providerAcceptanceStartNanos = requireNotNull(
					sharedProvider.acceptedElapsedRealtimeNanos,
				),
			)
		}.runs.single()
		val admissions = transformed.admissions.map { original ->
			val unsigned = original.copy(
				sourceInstanceId = sharedProvider.sourceInstanceId,
				registrationGeneration = sharedProvider.registrationGeneration,
				authorizationFingerprint = fingerprint,
				integrityIdentity = SourceEventWalEntity.LEGACY_PENDING_CHECKSUM,
			)
			unsigned.copy(integrityIdentity = unsigned.calculatedIntegrityIdentity())
		}
		val admissionsByEvent = admissions.associateBy(SourceEventWalEntity::eventId)
		val facts = transformed.facts.map { original ->
			val wal = requireNotNull(admissionsByEvent[original.sourceEventId])
			val unsigned = original.copy(
				walIntegrityIdentity = wal.integrityIdentity,
				effectChecksum = ZERO_SHA,
			)
			unsigned.copy(effectChecksum = WifiCapturedFactRevisionIntegrity.effectChecksum(unsigned))
		}
		val current = facts.groupBy(WifiCapturedFactRevisionEntity::logicalFactId)
			.values.map { it.maxBy(WifiCapturedFactRevisionEntity::semanticRevision) }
		val laterWithSharedRegistration = transformed.copy(
			provider = null,
			authorizations = authorization + deny,
			admissions = admissions,
			facts = facts,
			cursors = current.map(::cursor),
			actions = later.actions.map { action -> action.copy(
				sourceInstanceId = sharedProvider.sourceInstanceId,
				registrationGeneration = sharedProvider.registrationGeneration,
			) },
			completeness = later.completeness?.copy(
				sourceInstanceId = sharedProvider.sourceInstanceId,
				registrationGeneration = sharedProvider.registrationGeneration,
			),
		)
		return group.copy(runs = listOf(
			first.copy(provider = sharedProvider, demands = emptyList(), authorizations = emptyList()),
			laterWithSharedRegistration,
		))
	}

	private fun withAdditionalAuthorizationMember(
		group: Group,
		compatible: Boolean,
	): Group {
		require(group.runs.size == 1)
		val built = group.runs.single()
		val capture = built.demands.single()
		val shared = capture.copy(
			demandId = "${capture.demandId}-shared-control",
			consumerId = "wifi-control",
			purpose = SourceBrokerPurpose.CONTROL_AUTOSTART,
			logicalTrackingId = null,
			serviceRunId = null,
			manifestRevision = null,
			lifecycleLeaseGeneration = null,
			persistenceEligible = false,
			minimumAcquisitionSpec = if (compatible) {
				"wifi:v1:required=BROADCAST_CALLBACK"
			} else {
				"wifi:v1:required=ACTIVE_SCAN"
			},
		)
		val demands = listOf(capture, shared)
		val authorization = SourceBrokerAuthorization.rows(
			WIFI_SOURCE,
			requireNotNull(built.provider).registrationGeneration,
			1L,
			demands,
			BOOT_ID,
			built.manifest.effectiveElapsedRealtimeNanos,
			built.manifest.effectiveWallTimeMs,
		)
		val first = authorization.first()
		val facts = built.facts.map { fact ->
			val unsigned = fact.copy(
				authorizationFingerprint = first.authorizationFingerprint,
				purposeEligibilityMask = first.purposeEligibilityMask,
				effectChecksum = ZERO_SHA,
			)
			unsigned.copy(effectChecksum = WifiCapturedFactRevisionIntegrity.effectChecksum(unsigned))
		}
		val admissions = built.admissions.map { wal ->
			val unsigned = wal.copy(
				authorizationFingerprint = first.authorizationFingerprint,
				authorizationPurposeEligibilityMask = first.purposeEligibilityMask,
				integrityIdentity = SourceEventWalEntity.LEGACY_PENDING_CHECKSUM,
			)
			unsigned.copy(integrityIdentity = unsigned.calculatedIntegrityIdentity())
		}
		val admissionsByEvent = admissions.associateBy(SourceEventWalEntity::eventId)
		val boundFacts = facts.map { fact ->
			val unsigned = fact.copy(
				walIntegrityIdentity = requireNotNull(admissionsByEvent[fact.sourceEventId]).integrityIdentity,
				effectChecksum = ZERO_SHA,
			)
			unsigned.copy(effectChecksum = WifiCapturedFactRevisionIntegrity.effectChecksum(unsigned))
		}
		val boundCurrent = boundFacts.groupBy(WifiCapturedFactRevisionEntity::logicalFactId)
			.values.map { lineage -> lineage.maxBy(WifiCapturedFactRevisionEntity::semanticRevision) }
		val updated = built.copy(
			demands = demands,
			authorizations = authorization + built.authorizations.filter { row ->
				row.authorizationRevision != 1L
			},
			facts = boundFacts,
			cursors = boundCurrent.map(::cursor),
			admissions = admissions,
		)
		return group.copy(runs = listOf(updated))
	}

	@Suppress("LongMethod")
	private fun buildRun(
		logicalId: String,
		spec: RunSpec,
		partialResult: Boolean,
		active: Boolean,
		includeReuse: Boolean,
	): BuiltRun {
		val runEndElapsed = spec.startElapsedNanos + RUN_DURATION_NANOS
		val runEndWall = spec.startWallMs + RUN_DURATION_MS
		val segment = SessionSegment(
			id = spec.globalRevision,
			startTimeMs = spec.startWallMs,
			endTimeMs = runEndWall,
			distanceM = 0f,
			steps = null,
			primaryActivity = null,
			activityConfidence = null,
			sampleCount = 0,
			source = SegmentSource.USER_CREATED,
			inferenceVersion = null,
			createdAt = runEndWall,
			logicalTrackingId = logicalId,
			serviceRunId = spec.runId,
		)
		val run = SourceServiceRunEntity(
			serviceRunId = spec.runId,
			logicalTrackingId = logicalId,
			state = if (active) "ACTIVE" else "FINALIZED",
			desiredPlanRevision = spec.globalRevision,
			rolloutRevision = ROLLOUT_REVISION,
			foregroundCapabilityFlags = 0L,
			startedAtMs = spec.startWallMs,
			startedElapsedNanos = spec.startElapsedNanos,
			completedAtMs = runEndWall.takeUnless { active },
			completionReason = "USER_STOP".takeUnless { active },
			bootId = BOOT_ID,
			leaseGeneration = LEASE_GENERATION,
			startOrigin = START_ORIGIN,
			desiredForegroundCapabilityFlags = 0L,
			appliedForegroundCapabilityFlags = 0L,
			runtimeAcknowledgement = if (active) "START_ACCEPTED" else "STOP_ACCEPTED",
			runtimeFailureCode = null,
			runRevision = 2L,
			startDeliveryToken = "wifi-start-${spec.globalRevision}",
			startCommandGeneration = 1L,
			preparedManifestRevision = spec.manifestRevision,
			preparedIntentRevision = 1L,
			androidDeliveryState = "FOREGROUND_ACCEPTED",
			androidDeliveryUpdatedAtMs = spec.startWallMs,
			startIsUserInitiated = true,
			startIsAmbient = false,
			sessionSegmentId = segment.id,
			presentationAcknowledgement = if (active) SourceServiceRunEntity.PRESENTATION_PENDING else
				SourceServiceRunEntity.PRESENTATION_QUIESCED,
			presentationAcknowledgedAtMs = runEndWall.takeUnless { active },
		)
		val source = SessionManifestSourceEntity(
			logicalTrackingId = logicalId,
			manifestRevision = spec.manifestRevision,
			sourceKind = WIFI_SOURCE,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			consentEpoch = spec.globalRevision,
			persistenceEligible = true,
			qosCode = QOS_CODE,
			outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_WIFI,
			writerOwner = SourceDestinationOwnerEntity.OWNER_WIFI_SESSION_FACTS,
			writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
			writerProjectionId = SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID,
			writerProjectionVersion = SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION,
			writerBindingGeneration = SourceDestinationOwnerEntity.WIFI_FACT_BINDING_GENERATION,
		)
		val unsignedManifest = SessionManifestVersionEntity(
			logicalTrackingId = logicalId,
			manifestRevision = spec.manifestRevision,
			serviceRunId = spec.runId,
			sessionMode = "MANUAL",
			sourcePolicyRevision = spec.globalRevision,
			acquisitionPlanRevision = spec.globalRevision,
			rolloutRevision = ROLLOUT_REVISION,
			startOrigin = START_ORIGIN,
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = spec.startElapsedNanos,
			effectiveWallTimeMs = spec.startWallMs,
			zoneId = ZONE_ID,
			automationEpoch = null,
			changeReason = "REPLACEMENT_START",
			manifestChecksum = "pending",
		)
		val manifest = unsignedManifest.copy(
			manifestChecksum = SessionManifestIntegrity.compute(unsignedManifest, listOf(source)),
		)
		val policy = SourcePolicyEntity(
			policyRevision = spec.globalRevision,
			sourceKind = WIFI_SOURCE,
			enabled = true,
			qosCode = QOS_CODE,
			locationMinTimeSeconds = null,
			locationMinDistanceMeters = null,
			locationRequiredAccuracyMeters = null,
			capturePersistenceEligible = true,
			controlPersistenceEligible = false,
			ambientPersistenceEligible = false,
			captureConsentEpoch = spec.globalRevision,
			controlConsentEpoch = null,
			ambientConsentEpoch = null,
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = spec.startElapsedNanos,
			effectiveWallTimeMs = spec.startWallMs,
			changeReason = "TEST",
		)
		val consent = SourceConsentEpochEntity(
			sourceKind = WIFI_SOURCE,
			purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
			epoch = spec.globalRevision,
			eligible = true,
			persistenceEligible = true,
			policyRevision = spec.globalRevision,
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = spec.startElapsedNanos,
			effectiveWallTimeMs = spec.startWallMs,
			changeReason = "TEST",
		)
		val desiredPlan = desiredPlan(spec.globalRevision)
		val plan = requireNotNull(WifiHistoryPlanIntegrity.decode(desiredPlan))
		val planHeader = AcquisitionPlanRevisionEntity(spec.globalRevision,
			"wifi-plan-${spec.globalRevision}", spec.startWallMs, "EFFECTIVE", spec.globalRevision)
		val demand = demand(logicalId, spec, runEndElapsed, runEndWall, active)
		val authorization = SourceBrokerAuthorization.rows(WIFI_SOURCE, spec.globalRevision, 1L,
			listOf(demand), BOOT_ID, spec.startElapsedNanos, spec.startWallMs)
		val deny = SourceBrokerAuthorization.rows(WIFI_SOURCE, spec.globalRevision, 2L, emptyList(),
			BOOT_ID, runEndElapsed, runEndWall)
		val provider = ProviderRegistrationGenerationEntity(
			WIFI_SOURCE, spec.globalRevision, "wifi-instance-${spec.globalRevision}",
			"source-broker:$WIFI_SOURCE", BOOT_ID, plan.physicalFingerprint, 0L,
			ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND, "process-${spec.globalRevision}",
			if (active) ProviderRegistrationGenerationEntity.STATUS_ACTIVE else
				ProviderRegistrationGenerationEntity.STATUS_RETIRED,
			spec.startWallMs, spec.startElapsedNanos, spec.startWallMs, spec.startElapsedNanos,
			runEndWall.takeUnless { active }, runEndElapsed.takeUnless { active }, null, 1L,
		)
		val action = startAction(logicalId, spec)
		val authorizationHistory = if (active) authorization else authorization + deny
		if (!spec.hasFact) return BuiltRun(
			segment, run, manifest, source, policy, consent, planHeader, desiredPlan, listOf(demand), provider,
			authorizationHistory, listOf(action), if (active) listOf(admission(logicalId, spec, plan,
				authorization.first().authorizationFingerprint)) else emptyList(), emptyList(), emptyList(),
			completeness(logicalId, spec, null).takeUnless { active },
		)
		val firstUnsigned = fact(logicalId, spec, segment.id, plan, authorization.first().authorizationFingerprint,
			partialResult, 1L, Long.MAX_VALUE, admissionOffset = 1L)
		val firstAdmission = admission(firstUnsigned)
		val first = bindToAdmission(firstUnsigned, firstAdmission)
		val settled = settle(first, runEndElapsed)
		val dependentUnsigned = if (includeReuse) fact(
			logicalId,
			spec,
			segment.id,
			plan,
			authorization.first().authorizationFingerprint,
			partialResult,
			1L,
			runEndElapsed,
			admissionOffset = 2L,
		) else null
		val dependentAdmission = dependentUnsigned?.let(::admission)
		val dependent = if (dependentUnsigned != null && dependentAdmission != null) {
			coverageFact(bindToAdmission(dependentUnsigned, dependentAdmission), settled)
		} else {
			null
		}
		val facts = listOfNotNull(first, settled, dependent)
		val cursors = listOf(cursor(settled)) + listOfNotNull(dependent?.let(::cursor))
		val lastFact = dependent ?: settled
		return BuiltRun(segment, run, manifest, source, policy, consent, planHeader, desiredPlan,
			listOf(demand), provider, authorizationHistory, listOf(action),
			listOfNotNull(firstAdmission, dependentAdmission), facts,
			cursors, completeness(logicalId, spec, lastFact.sourceAdmissionOrdinal))
	}

	@Suppress("LongMethod")
	private fun fact(
		logicalId: String,
		spec: RunSpec,
		segmentId: Long,
		plan: WifiPlanEvidence,
		authorizationFingerprint: String,
		partial: Boolean,
		semanticRevision: Long,
		temporalEnd: Long,
		admissionOffset: Long,
	): WifiCapturedFactRevisionEntity {
		val admission = spec.globalRevision * 10L + admissionOffset
		val observationOffsetNanos = admissionOffset * 1_000L
		val coverageStartNanos =
			spec.startElapsedNanos + 100_000_000L + observationOffsetNanos
		val coverageEndNanos =
			spec.startElapsedNanos + 200_000_000L + observationOffsetNanos
		val payload = wifiPayload(
			coverageStartNanos = coverageStartNanos,
			coverageEndNanos = coverageEndNanos,
			partial = partial,
			zoneId = ZONE_ID,
		)
		val encoded = WifiWalPayloadCodec.encode(payload, LEGACY_WIFI_WAL_PAYLOAD_VERSION)
		val delivery = canonicalWifiProviderDeliveryIdentity(BOOT_ID, payload.observations, LEGACY_WIFI_WAL_PAYLOAD_VERSION)
		val logicalFactId = WifiCapturedFactRevisionIntegrity.logicalFactId(delivery, logicalId,
			spec.runId, segmentId, spec.manifestRevision, 0L, 0L)
		val unsigned = WifiCapturedFactRevisionEntity(
			writerProjectionId = SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID,
			writerProjectionVersion = SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION,
			writerBindingGeneration = SourceDestinationOwnerEntity.WIFI_FACT_BINDING_GENERATION,
			writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
			logicalFactId = logicalFactId,
			semanticRevision = semanticRevision,
			supersedesSemanticRevision = (semanticRevision - 1L).takeIf { it > 0L },
			mutationId = WifiCapturedFactRevisionIntegrity.mutationId(logicalFactId, semanticRevision),
			factKind = WifiCapturedFactRevisionEntity.FACT_KIND_AGGREGATE,
			aggregateOwnerLogicalFactId = null,
			aggregateOwnerSemanticRevision = null,
			aggregateOwnerCursorRevision = null,
			logicalTrackingId = logicalId,
			serviceRunId = spec.runId,
			sessionSegmentId = segmentId,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			capturedSourceCodes = WIFI_SOURCE.toString(),
			controlSourceCodes = "",
			sourceEventId = "wifi-event-$admission",
			sourceAdmissionOrdinal = admission,
			walIntegrityIdentity = ZERO_SHA,
			payloadChecksum = encoded.checksum,
			sourceDeliveryIdentity = delivery,
			deliveryUnitIndex = 0,
			deliveryUnitCount = 1,
			sourceSequence = admission,
			planAttribution = WifiCapturedFactRevisionEntity.PLAN_ATTRIBUTION_CAPTURED_REGISTRATION,
			sourceInstanceId = "wifi-instance-${spec.globalRevision}",
			registrationGeneration = spec.globalRevision,
			configurationRevision = spec.globalRevision,
			physicalConfigurationFingerprint = plan.physicalFingerprint,
			authorizationRevision = 1L,
			authorizationFingerprint = authorizationFingerprint,
			purposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			sourcePolicyRevision = spec.globalRevision,
			captureConsentEpoch = spec.globalRevision,
			manifestRevision = spec.manifestRevision,
			lifecycleLeaseGeneration = LEASE_GENERATION,
			collectedDataEpoch = 0L,
			scopeDeletionGeneration = 0L,
			clockDomainId = BOOT_ID,
			storedZoneId = ZONE_ID,
			planPayloadVersion = plan.payloadVersion,
			planPayloadChecksum = plan.payloadChecksum,
			maximumObservationAgeNanos = plan.maximumAgeMs * 1_000_000L,
			resultContract = "ANDROID_SCAN_RESULTS_V1",
			registrationAppliedAtNanos = spec.startElapsedNanos,
			providerAcceptanceStartNanos = spec.startElapsedNanos,
			providerAcceptanceEndNanos = temporalEnd,
			authorizationEffectStartNanos = spec.startElapsedNanos,
			authorizationEffectEndNanos = temporalEnd,
			sessionRunEffectStartNanos = spec.startElapsedNanos,
			sessionRunEffectEndNanos = temporalEnd,
			observedIntervalStartNanos = payload.observations.minOf {
				requireNotNull(it.providerTimestampNanos)
			},
			observedElapsedNanos = coverageEndNanos,
			receivedElapsedNanos = coverageEndNanos + 10_000_000L,
			coverageIntervalStartNanos = coverageStartNanos,
			coverageIntervalEndNanos = coverageEndNanos,
			observedWallTimeMs = spec.startWallMs + 200L,
			wallTimeUncertaintyMs = 1L,
			acquiredAtMs = spec.startWallMs + 200L,
			qualityFlags = 0L,
			qualityConfidence = null,
			availability = WifiCapturedFactRevisionEntity.AVAILABILITY_AVAILABLE,
			submittedResultCount = if (partial) 3 else 2,
			acceptedResultCount = 2,
			staleResultCount = 0,
			clockUnverifiableResultCount = 0,
			malformedResultCount = if (partial) 1 else 0,
			coverageCompleteness = if (partial) WifiCapturedFactRevisionEntity.COVERAGE_PARTIAL else
				WifiCapturedFactRevisionEntity.COVERAGE_COMPLETE,
			observationCount = 2,
			twoPointFourGhzCount = 1,
			fiveGhzCount = 1,
			sixGhzCount = 0,
			otherBandCount = 0,
			strongestSignalDbm = -40,
			weakestSignalDbm = -60,
			signalSumDbm = -100L,
			effectChecksum = ZERO_SHA,
			appliedAtMs = spec.startWallMs + 200L,
		)
		return unsigned.copy(effectChecksum = WifiCapturedFactRevisionIntegrity.effectChecksum(unsigned))
	}

	private fun admission(fact: WifiCapturedFactRevisionEntity): SourceEventWalEntity {
		val payload = WifiWalPayloadCodec.encode(
			wifiPayload(
				coverageStartNanos = fact.coverageIntervalStartNanos,
				coverageEndNanos = fact.coverageIntervalEndNanos,
				partial = fact.submittedResultCount > fact.acceptedResultCount,
				zoneId = fact.storedZoneId,
			),
			LEGACY_WIFI_WAL_PAYLOAD_VERSION,
		)
		val unsigned = SourceEventWalEntity(
			admissionOrdinal = fact.sourceAdmissionOrdinal,
			eventId = fact.sourceEventId,
			providerDedupKey = null,
			deliveryIdentity = fact.sourceDeliveryIdentity,
			deliveryUnitIndex = fact.deliveryUnitIndex,
			deliveryUnitCount = fact.deliveryUnitCount,
			logicalTrackingId = fact.logicalTrackingId,
			serviceRunId = fact.serviceRunId,
			sourceKind = WIFI_SOURCE,
			sourceInstanceId = fact.sourceInstanceId,
			registrationGeneration = fact.registrationGeneration,
			physicalConfigurationFingerprint = fact.physicalConfigurationFingerprint,
			authorizationRevision = fact.authorizationRevision,
			authorizationPurposeEligibilityMask = fact.purposeEligibilityMask,
			authorizationFingerprint = fact.authorizationFingerprint,
			sourceSequence = fact.sourceSequence,
			configRevision = fact.configurationRevision,
			planAttribution = 0,
			clockDomainId = fact.clockDomainId,
			observedElapsedNanos = fact.observedElapsedNanos,
			observedIntervalStartNanos = fact.observedIntervalStartNanos,
			receivedElapsedNanos = fact.receivedElapsedNanos,
			receivedWallTimeMs = fact.observedWallTimeMs +
				(fact.receivedElapsedNanos - fact.observedElapsedNanos) / 1_000_000L,
			wallTimeMs = fact.observedWallTimeMs,
			wallTimeUncertaintyMs = fact.wallTimeUncertaintyMs,
			capturedCollectedDataEpoch = fact.collectedDataEpoch,
			activityAutomationEpoch = null,
			sourcePolicyRevision = fact.sourcePolicyRevision,
			captureConsentEpoch = fact.captureConsentEpoch,
			sessionManifestRevision = fact.manifestRevision,
			lifecycleLeaseGeneration = fact.lifecycleLeaseGeneration,
			acquiredAtMs = fact.acquiredAtMs,
			qualityFlags = fact.qualityFlags,
			qualityConfidence = fact.qualityConfidence,
			payloadVersion = LEGACY_WIFI_WAL_PAYLOAD_VERSION,
			payload = payload.toByteArray(),
			payloadChecksum = fact.payloadChecksum,
			createdAtMs = fact.appliedAtMs,
		)
		return unsigned.copy(integrityIdentity = unsigned.calculatedIntegrityIdentity())
	}

	private fun bindToAdmission(
		fact: WifiCapturedFactRevisionEntity,
		wal: SourceEventWalEntity,
	): WifiCapturedFactRevisionEntity {
		val unsigned = fact.copy(
			walIntegrityIdentity = wal.integrityIdentity,
			effectChecksum = ZERO_SHA,
		)
		return unsigned.copy(effectChecksum = WifiCapturedFactRevisionIntegrity.effectChecksum(unsigned))
	}

	private fun wifiPayload(
		coverageStartNanos: Long,
		coverageEndNanos: Long,
		partial: Boolean,
		zoneId: String,
	): WifiWalPayload {
		val observations = buildList {
			if (partial) {
				add(
					WifiWalPayloadObservation(
						identifierToken = "",
						frequencyMhz = 100,
						signalLevelDbm = -70,
						providerTimestampNanos = coverageStartNanos,
					),
				)
			}
			add(WifiWalPayloadObservation("", 2_412, -60, coverageStartNanos))
			add(WifiWalPayloadObservation("", 5_180, -40, coverageEndNanos))
		}.sortedWith(
			compareBy<WifiWalPayloadObservation>(
				WifiWalPayloadObservation::frequencyMhz,
				WifiWalPayloadObservation::signalLevelDbm,
				WifiWalPayloadObservation::providerTimestampNanos,
			),
		)
		return WifiWalPayload(
			observations = observations,
			platformTimestampMs = coverageEndNanos / 1_000_000L,
			resultAgeMs = null,
			observationZoneId = zoneId,
		)
	}

	private fun coverageFact(
		base: WifiCapturedFactRevisionEntity,
		owner: WifiCapturedFactRevisionEntity,
	): WifiCapturedFactRevisionEntity {
		val unsigned = base.copy(
			factKind = WifiCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY,
			aggregateOwnerLogicalFactId = owner.logicalFactId,
			aggregateOwnerSemanticRevision = owner.semanticRevision,
			aggregateOwnerCursorRevision = owner.semanticRevision,
			observationCount = null,
			twoPointFourGhzCount = null,
			fiveGhzCount = null,
			sixGhzCount = null,
			otherBandCount = null,
			strongestSignalDbm = null,
			weakestSignalDbm = null,
			signalSumDbm = null,
			effectChecksum = ZERO_SHA,
		)
		return unsigned.copy(effectChecksum = WifiCapturedFactRevisionIntegrity.effectChecksum(unsigned))
	}

	private fun settle(
		first: WifiCapturedFactRevisionEntity,
		runEndElapsed: Long,
	): WifiCapturedFactRevisionEntity {
		val unsigned = first.copy(
			semanticRevision = 2L,
			supersedesSemanticRevision = 1L,
			mutationId = WifiCapturedFactRevisionIntegrity.mutationId(first.logicalFactId, 2L),
			providerAcceptanceEndNanos = runEndElapsed,
			authorizationEffectEndNanos = runEndElapsed,
			sessionRunEffectEndNanos = runEndElapsed,
			effectChecksum = ZERO_SHA,
		)
		return unsigned.copy(effectChecksum = WifiCapturedFactRevisionIntegrity.effectChecksum(unsigned))
	}

	private fun cursor(fact: WifiCapturedFactRevisionEntity) = WifiCapturedFactCursorEntity(
		fact.writerProjectionId, fact.writerProjectionVersion, fact.logicalFactId, fact.logicalTrackingId,
		fact.serviceRunId, fact.sessionSegmentId, fact.writerOwnerGeneration, fact.collectedDataEpoch,
		fact.scopeDeletionGeneration, fact.semanticRevision, fact.mutationId, fact.effectChecksum,
		fact.sourceAdmissionOrdinal, fact.semanticRevision, fact.appliedAtMs,
	)

	private fun wifiFactLookupRequest(
		fact: WifiCapturedFactRevisionEntity,
	): SourceFactLookupRequest = SourceFactLookupRequest(
		key = SourceProofKey(
			source = TrackingSource.WIFI,
			purpose = TrackingPurpose.SESSION_CAPTURE,
			evidenceIdentity = SourceEvidenceIdentity(fact.sourceEventId),
			sourcePolicyRevision = fact.sourcePolicyRevision,
			captureConsentEpoch = fact.captureConsentEpoch,
			collectedDataEpoch = fact.collectedDataEpoch,
			scope = SourceProofScope.Run(fact.logicalTrackingId, fact.serviceRunId),
		),
		materialized = MaterializedProofState(
			lineage = SourceProofLineage(
				evidenceIdentity = SourceEvidenceIdentity(fact.sourceEventId),
				sourceAdmissionOrdinal = fact.sourceAdmissionOrdinal,
				sourceEvidenceRevision = 0L,
				factIdentity = SourceFactIdentity(fact.logicalFactId),
				semanticRevision = fact.semanticRevision,
				writer = SourceProofWriterIdentity(
					fact.writerProjectionId,
					fact.writerProjectionVersion,
					fact.writerBindingGeneration,
					fact.writerOwnerGeneration,
				),
				cursorRevision = fact.semanticRevision,
			),
			coverage = if (fact.coverageIntervalEndNanos > fact.coverageIntervalStartNanos) {
				SourceProofCoverage.ProviderInterval(
					fact.coverageIntervalStartNanos,
					fact.coverageIntervalEndNanos,
				)
			} else {
				SourceProofCoverage.ProviderPoint(fact.coverageIntervalStartNanos)
			},
			completeness = if (
				fact.coverageCompleteness == WifiCapturedFactRevisionEntity.COVERAGE_COMPLETE
			) {
				SourceProofCompleteness.COMPLETE
			} else {
				SourceProofCompleteness.PARTIAL
			},
		),
	)

	private fun startAction(logicalId: String, spec: RunSpec) = LifecycleDesiredActionEntity(
		actionId = "wifi-start-action-${spec.globalRevision}",
		logicalTrackingId = logicalId,
		serviceRunId = spec.runId,
		manifestRevision = spec.manifestRevision,
		actionRevision = spec.globalRevision,
		actionFamily = "SOURCE_RUNTIME",
		sourceKind = WIFI_SOURCE,
		desiredState = "STARTED",
		desiredPlanRevision = spec.globalRevision,
		sourcePolicyRevision = spec.globalRevision,
		consentEpoch = spec.globalRevision,
		startOrigin = START_ORIGIN,
		bootId = BOOT_ID,
		leaseGeneration = LEASE_GENERATION,
		requestedAtMs = spec.startWallMs,
		requestedElapsedRealtimeNanos = spec.startElapsedNanos,
		status = "START_ACCEPTED",
		attemptCount = 1,
		acknowledgedAtMs = spec.startWallMs + 50L,
		acknowledgedElapsedRealtimeNanos = spec.startElapsedNanos + 50_000_000L,
		failureCode = null,
		retryTrigger = null,
		sourceInstanceId = "wifi-instance-${spec.globalRevision}",
		registrationGeneration = spec.globalRevision,
	)

	private fun admission(
		logicalId: String,
		spec: RunSpec,
		plan: WifiPlanEvidence,
		authorizationFingerprint: String,
	): SourceEventWalEntity {
		val payload = wifiPayload(
			coverageStartNanos = spec.startElapsedNanos + 100_000_000L,
			coverageEndNanos = spec.startElapsedNanos + 200_000_000L,
			partial = false,
			zoneId = ZONE_ID,
		)
		val encoded = WifiWalPayloadCodec.encode(payload, LEGACY_WIFI_WAL_PAYLOAD_VERSION)
		val unsigned = SourceEventWalEntity(
			eventId = "wifi-pending-event-${spec.globalRevision}",
			providerDedupKey = null,
			deliveryIdentity = canonicalWifiProviderDeliveryIdentity(BOOT_ID, payload.observations, LEGACY_WIFI_WAL_PAYLOAD_VERSION),
			deliveryUnitIndex = 0,
			deliveryUnitCount = 1,
			logicalTrackingId = logicalId,
			serviceRunId = spec.runId,
			sourceKind = WIFI_SOURCE,
			sourceInstanceId = "wifi-instance-${spec.globalRevision}",
			registrationGeneration = spec.globalRevision,
			physicalConfigurationFingerprint = plan.physicalFingerprint,
			authorizationRevision = 1L,
			authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			authorizationFingerprint = authorizationFingerprint,
			sourceSequence = spec.globalRevision,
			configRevision = spec.globalRevision,
			planAttribution = 0,
			clockDomainId = BOOT_ID,
			observedElapsedNanos = spec.startElapsedNanos + 200_000_000L,
			observedIntervalStartNanos = spec.startElapsedNanos + 100_000_000L,
			receivedElapsedNanos = spec.startElapsedNanos + 210_000_000L,
			receivedWallTimeMs = spec.startWallMs + 210L,
			wallTimeMs = spec.startWallMs + 200L,
			wallTimeUncertaintyMs = 1L,
			capturedCollectedDataEpoch = 0L,
			activityAutomationEpoch = null,
			sourcePolicyRevision = spec.globalRevision,
			captureConsentEpoch = spec.globalRevision,
			sessionManifestRevision = spec.manifestRevision,
			lifecycleLeaseGeneration = LEASE_GENERATION,
			acquiredAtMs = spec.startWallMs + 200L,
			qualityFlags = 0L,
			qualityConfidence = null,
			payloadVersion = LEGACY_WIFI_WAL_PAYLOAD_VERSION,
			payload = encoded.toByteArray(),
			payloadChecksum = encoded.checksum,
			createdAtMs = spec.startWallMs + 201L,
		)
		return unsigned.copy(integrityIdentity = unsigned.calculatedIntegrityIdentity())
	}

	private fun demand(
		logicalId: String,
		spec: RunSpec,
		runEndElapsed: Long,
		runEndWall: Long,
		active: Boolean,
	) =
		SourceDemandEntity(
			demandId = "wifi-demand-${spec.globalRevision}",
			consumerId = "session:${spec.runId}",
			sourceKind = WIFI_SOURCE,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			logicalTrackingId = logicalId,
			serviceRunId = spec.runId,
			manifestRevision = spec.manifestRevision,
			lifecycleLeaseGeneration = LEASE_GENERATION,
			sourcePolicyRevision = spec.globalRevision,
			consentEpoch = spec.globalRevision,
			persistenceEligible = true,
			qosCode = QOS_CODE,
			minimumAcquisitionSpec = "wifi:v1:required=BROADCAST_CALLBACK",
			adaptiveReductionAllowed = false,
			maximumAgeMs = 1_000L,
			desiredLatencyMs = 1_000L,
			requestedDeliveryLatencyMs = null,
			requestedBootId = BOOT_ID,
			requestedElapsedRealtimeNanos = spec.startElapsedNanos,
			requestedAtMs = spec.startWallMs,
			status = if (active) SourceDemandEntity.STATUS_ACTIVE else SourceDemandEntity.STATUS_RETIRED,
			retireBootId = BOOT_ID.takeUnless { active },
			retireElapsedRealtimeNanos = runEndElapsed.takeUnless { active },
			retiredAtMs = runEndWall.takeUnless { active },
		)

	private fun completeness(logicalId: String, spec: RunSpec, lastOrdinal: Long?) =
		SourceSessionCompletenessEntity(
			logicalTrackingId = logicalId,
			serviceRunId = spec.runId,
			sourceKind = WIFI_SOURCE,
			sourceInstanceId = "wifi-instance-${spec.globalRevision}",
			registrationGeneration = spec.globalRevision,
			lastAdmissionOrdinal = lastOrdinal,
			lastSourceSequence = lastOrdinal,
			appDrainComplete = true,
			providerCoverage = "PROVIDER_COMPLETENESS_UNOBSERVABLE",
			stopStatus = "COMPLETE",
			unresolvedSequenceStart = null,
			unresolvedSequenceEnd = null,
			updatedAtMs = spec.startWallMs + RUN_DURATION_MS,
		)

	private fun desiredPlan(revision: Long): SourceDesiredPlanEntity {
		val payload = ByteArrayOutputStream().use { bytes ->
			DataOutputStream(bytes).use { output ->
				output.writeInt(1)
				output.writeUTF("WIFI")
				output.writeLong(revision)
				output.writeUTF("BROADCAST_DRIVEN")
				output.writeLong(60_000L)
				output.writeLong(1_000L)
				output.writeLong(5_000L)
				output.writeLong(1_000L)
				output.writeLong(60_000L)
				output.writeDouble(2.0)
			}
			bytes.toByteArray()
		}
		return SourceDesiredPlanEntity(revision, WIFI_SOURCE, 1, payload, sha256(payload))
	}

	private fun lane(throughOrdinal: Long) = SourceProductProjectionLaneEntity(
		sourceKind = WIFI_SOURCE,
		bindingGeneration = SourceDestinationOwnerEntity.WIFI_FACT_BINDING_GENERATION,
		projectionId = SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID,
		projectionVersion = SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION,
		captureModeMask = 1L,
		productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
		activatedRolloutRevision = ROLLOUT_REVISION,
		activationOrdinal = 1L,
		contiguousAdmissionOrdinal = throughOrdinal,
		captureAdmissionCutoffOrdinal = null,
		retentionRequired = true,
		status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
		terminalDisposition = null,
		terminalAtMs = null,
		installedAtMs = 0L,
		updatedAtMs = 0L,
	)

	private fun sha256(value: String): String = sha256(value.toByteArray(Charsets.UTF_8))

	private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
		.digest(value).joinToString("") { "%02x".format(it) }

	private data class Group(val session: LogicalTrackingSessionEntity, val runs: List<BuiltRun>)

	@Suppress("LongParameterList")
	private data class BuiltRun(
		val segment: SessionSegment,
		val run: SourceServiceRunEntity,
		val manifest: SessionManifestVersionEntity,
		val source: SessionManifestSourceEntity,
		val policy: SourcePolicyEntity,
		val consent: SourceConsentEpochEntity,
		val planHeader: AcquisitionPlanRevisionEntity,
		val desiredPlan: SourceDesiredPlanEntity,
		val demands: List<SourceDemandEntity>,
		val provider: ProviderRegistrationGenerationEntity?,
		val authorizations: List<com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity>,
		val actions: List<LifecycleDesiredActionEntity>,
		val admissions: List<SourceEventWalEntity>,
		val facts: List<WifiCapturedFactRevisionEntity>,
		val cursors: List<WifiCapturedFactCursorEntity>,
		val completeness: SourceSessionCompletenessEntity?,
	)

	private data class RunSpec(
		val manifestRevision: Long,
		val globalRevision: Long,
		val runId: String,
		val startWallMs: Long,
		val startElapsedNanos: Long,
		val hasFact: Boolean,
	)

	private companion object {
		const val WIFI_SOURCE = SourceDestinationOwnerEntity.SOURCE_WIFI
		const val BOOT_ID = "boot-wifi-history"
		const val ZONE_ID = "UTC"
		const val START_ORIGIN = "MANUAL_FOREGROUND_START"
		const val QOS_CODE = 2
		const val LEASE_GENERATION = 1L
		const val ROLLOUT_REVISION = 1L
		const val RUN_DURATION_NANOS = 800_000_000L
		const val RUN_DURATION_MS = 800L
		const val WALL_BASE_MS = 1_700_000_000_000L
		const val ZERO_SHA = "0000000000000000000000000000000000000000000000000000000000000000"
	}
}
