package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCause
import com.adsamcik.tracker.stats.api.repository.PressureHistoryPresentationState
import com.adsamcik.tracker.stats.api.repository.PressureAwareHistoryPageEntry
import com.adsamcik.tracker.stats.api.repository.PressureAwareHistoryPageQuery
import com.adsamcik.tracker.stats.api.repository.PressureAwareHistoryPageUnavailableReason
import com.adsamcik.tracker.stats.api.repository.PressureSessionHistoryQuery
import com.adsamcik.tracker.stats.api.repository.SessionHistoryQuery
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PressureHistorySelectorTest {
	private lateinit var database: AppDatabase
	private lateinit var selector: PressureHistorySelector

	@Before
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState())
		selector = PressureHistorySelector(database, executableLaneAuthority())
	}

	@After
	fun tearDown() = database.close()

	@Test
	@OptIn(ExperimentalCoroutinesApi::class)
	fun productionFacadeExposesBatchedLogicalListAndExactPhysicalDetail() = runTest {
		val first = insertFixture(
			zoneId = "Europe/Prague",
			factSemanticRevision = 1L,
			laneCursor = 2L,
		)
		insertReplacementFixture()
		val repository = DefaultTrackingHistoryRepository(
			database = database,
			stepsSelector = mockk(relaxed = true),
			logicalHistoryReader = mockk(relaxed = true),
			pressureSelector = selector,
			ioDispatcher = UnconfinedTestDispatcher(testScheduler),
		)

		val detail = repository.observePressureSession(first.segmentId).first() as
			PressureSessionHistoryQuery.Found
		val recent = repository.observeRecentPressureOnlyEntries(limit = 10).first()

		detail.history.segmentId shouldBe first.segmentId
		detail.history.pressure.presentationState shouldBe PressureHistoryPresentationState.READY
		detail.history.pressure.windows.single().zoneId shouldBe "Europe/Prague"
		recent.size shouldBe 1
		recent.single().key.toString() shouldBe "TrackingHistoryEntryKey"
		recent.single().state shouldBe PressureHistoryPresentationState.READY
		recent.single().pressure.windows.size shouldBe 2
		recent.single().pressure.zoneAuthorities shouldBe linkedSetOf("Europe/Prague", "UTC")
	}

	@Test
	fun liveFacadeReturnsSessionAndPressureFromOneCaptureAuthoritySnapshot() = runTest {
		val fixture = insertFixture(factSemanticRevision = 1L, laneCursor = 2L)
		val authority = executableLaneAuthority()
		val repository = DefaultTrackingHistoryRepository(
			database = database,
			stepsSelector = StepsSegmentHistorySelector(database, authority),
			logicalHistoryReader = mockk(relaxed = true),
			pressureSelector = PressureHistorySelector(database, authority),
			ioDispatcher = UnconfinedTestDispatcher(testScheduler),
		)

		val snapshot = repository.observeLiveSession(fixture.segmentId).first()
		val session = (snapshot.session as SessionHistoryQuery.Found).history
		val pressure = (snapshot.pressure as PressureSessionHistoryQuery.Found).history

		snapshot.segmentId shouldBe fixture.segmentId
		session.capture shouldBe pressure.capture
		pressure.pressure.presentationState shouldBe PressureHistoryPresentationState.READY
	}

	@Test
	fun pressureAwarePageReplacesCompleteDiscoverableLogicalGroupOnce() = runTest {
		val first = insertFixture(factSemanticRevision = 1L, laneCursor = 2L)
		val replacement = insertReplacementFixture()
		val repository = pressureAwareRepository()

		val query = repository.observeRecentPressureAwarePage(
			candidateSegmentIds = listOf(first.segmentId, replacement.segmentId),
			limit = 10,
		).first()
		val page = (query as PressureAwareHistoryPageQuery.Content).entries

		page.size shouldBe 1
		val pressureOnly = page.single() as PressureAwareHistoryPageEntry.PressureOnly
		pressureOnly.history.key.toString() shouldBe "TrackingHistoryEntryKey"
		pressureOnly.history.startTime.raw shouldBe RUN_START_MS
		pressureOnly.history.endTime.raw shouldBe REPLACEMENT_RUN_END_MS
		pressureOnly.history.pressure.windows.size shouldBe 2
		pressureOnly.history.state shouldBe PressureHistoryPresentationState.READY
	}

	@Test
	fun pressureAwarePageReplacesExactIntentBeforeFirstFact() = runTest {
		val fixture = insertFixture(factSemanticRevision = null, laneCursor = 0L)
		val repository = pressureAwareRepository()

		val query = repository.observeRecentPressureAwarePage(
			candidateSegmentIds = listOf(fixture.segmentId),
			limit = 10,
		).first()
		val page = (query as PressureAwareHistoryPageQuery.Content).entries

		val pressureOnly = page.single() as PressureAwareHistoryPageEntry.PressureOnly
		pressureOnly.history.state shouldBe PressureHistoryPresentationState.MATERIALIZING
		pressureOnly.history.pressure.summary shouldBe null
	}

	@Test
	fun pressureAwarePageRetainsExactPressureOnlyProviderUnavailableState() = runTest {
		val fixture = insertFixture(
			factSemanticRevision = null,
			laneCursor = 1L,
			unavailablePressure = true,
		)

		val query = pressureAwareRepository().observeRecentPressureAwarePage(
			candidateSegmentIds = listOf(fixture.segmentId),
			limit = 10,
		).first()
		val pressureOnly = (query as PressureAwareHistoryPageQuery.Content).entries.single() as
			PressureAwareHistoryPageEntry.PressureOnly

		pressureOnly.history.state shouldBe PressureHistoryPresentationState.UNAVAILABLE
		pressureOnly.history.pressure.summary shouldBe null
	}

	@Test
	fun pressureAwarePageReturnsTypedUnavailableWhenLogicalMembershipExceedsBudget() = runTest {
		val fixture = insertFixture(factSemanticRevision = null, laneCursor = 0L)
		repeat(64) { index -> insertBareLogicalMember(index + 1) }

		val query = pressureAwareRepository().observeRecentPressureAwarePage(
			candidateSegmentIds = listOf(fixture.segmentId),
			limit = 10,
		).first()

		query shouldBe PressureAwareHistoryPageQuery.Unavailable(
			PressureAwareHistoryPageUnavailableReason.LOGICAL_MEMBERSHIP_LIMIT,
		)
	}

	@Test
	fun pressureIntentDiscoveryReturnsTypedUnavailableAtCandidateScanBoundary() = runTest {
		insertFixture(factSemanticRevision = 1L)
		insertIndependentPressureFixture(
			index = 1,
			startTimeMs = 3_000L,
			factCollectedDataEpoch = 1L,
		)

		val query = database.withTransaction {
			selector.discoverRecentPressureOnlyIntentInTransaction(
				limit = 2,
				candidateBudget = 1,
			)
		}

		query shouldBe PressureOnlyDiscoveryResult.Unavailable(
			PressureAwareHistoryPageUnavailableReason.CANDIDATE_SCAN_LIMIT,
		)
	}

	@Test
	fun logicalPressureRecencyUsesOneNewestMemberTupleWhenPhysicalIdsRegress() = runTest {
		val first = insertFixture(factSemanticRevision = 1L)
		val replacement = insertReplacementFixture(segmentId = 40L)

		val logical = selector.selectLogicalBySegmentIds(listOf(first.segmentId)).single()

		logical.recencyMember.segment.id shouldBe replacement.segmentId
		logical.recencyMember.segment.startTimeMs shouldBe REPLACEMENT_RUN_START_MS
	}

	@Test
	fun pressureListOrdersByNewestReplacementBeyondFirstFactCandidatePage() = runTest {
		val target = insertFixture(factSemanticRevision = 1L, laneCursor = 100L)
		val newestReplacement = insertReplacementFixture(
			includeFact = false,
			unavailablePressure = true,
		)
		repeat(33) { index ->
			insertIndependentPressureFixture(
				index = index,
				startTimeMs = 1_100L + index,
			)
		}

		val recent = selector.discoverRecentPressureOnlyByPressureFacts(limit = 1).single()

		recent.identity shouldBe PressureHistoryEntryIdentity.Logical(target.logicalId)
		recent.physicalMembers.map { it.segment.id } shouldBe
			listOf(target.segmentId, newestReplacement.segmentId)
		recent.physicalMembers.maxOf { it.segment.startTimeMs } shouldBe REPLACEMENT_RUN_START_MS
	}

	@Test
	fun pressureListFillsPastRejectedPageAndRequiresQualifiedRetainedFacts() = runTest {
		val accepted = insertFixture(factSemanticRevision = 1L, laneCursor = 100L)
		repeat(33) { index ->
			insertIndependentPressureFixture(
				index = index,
				startTimeMs = 3_000L + index,
				factCollectedDataEpoch = 1L,
			)
		}

		val recent = selector.discoverRecentPressureOnlyByPressureFacts(limit = 1)

		recent.map(PressureLogicalHistoryEntry::identity) shouldBe
			listOf(PressureHistoryEntryIdentity.Logical(accepted.logicalId))
		recent.single().isOrdinarilyDiscoverable shouldBe true
	}

	@Test
	fun partiallyFencedLogicalGroupSurvivesAndFullyFencedGroupIsOmitted() = runTest {
		val first = insertFixture(factSemanticRevision = 1L, laneCursor = 2L)
		val replacement = insertReplacementFixture()
		database.sourceDeletionFenceDao().upsert(pressureFence(first.logicalId, first.runId))

		val partiallyRetained = selector.discoverRecentPressureOnlyByPressureFacts(limit = 10)

		partiallyRetained.size shouldBe 1
		partiallyRetained.single().physicalMembers.flatMap { it.windows }.map {
			it.serviceRunId
		} shouldBe listOf(replacement.runId)
		database.sourceDeletionFenceDao().upsert(pressureFence(replacement.logicalId, replacement.runId))

		selector.discoverRecentPressureOnlyByPressureFacts(limit = 10) shouldBe emptyList()
	}

	@Test
	fun pressureFactInvalidationReemitsRecentPressureList() = runBlocking {
		insertFixture(factSemanticRevision = null, laneCursor = 1L)
		val repository = DefaultTrackingHistoryRepository(
			database = database,
			stepsSelector = mockk(relaxed = true),
			logicalHistoryReader = mockk(relaxed = true),
			pressureSelector = selector,
			ioDispatcher = Dispatchers.IO,
		)
		val initialEmission = CompletableDeferred<Unit>()
		val collection = async {
			repository.observeRecentPressureOnlyEntries(limit = 1)
				.onEach { initialEmission.complete(Unit) }
				.take(2)
				.toList()
		}

		withTimeout(5_000L) { initialEmission.await() }
		database.pressureFactRevisionDao().insert(
			pressureFact(
				binding = pressureBinding(),
				semanticRevision = 1L,
				admissionOrdinal = 1L,
			),
		)

		val emissions = withTimeout(5_000L) { collection.await() }
		emissions.first() shouldBe emptyList()
		emissions.last().size shouldBe 1
		emissions.last().single().pressure.hasRetainedObservation shouldBe true
	}

	@Test
	fun pressureOnlyZeroSampleSegmentUsesQualifiedFactAndStoredManifestZone() = runTest {
		val fixture = insertFixture(zoneId = "Europe/Prague", factSemanticRevision = 1L)

		val result = requireNotNull(selector.selectBySegmentId(fixture.segmentId))
		val logical = selector.selectLogicalBySegmentIds(listOf(fixture.segmentId)).single()
		val discovered = selector.discoverRecentLogicalByPressureFacts(limit = 10).single()

		result.availability shouldBe PressureHistoryAvailability.AVAILABLE
		result.evidence shouldBe PressureHistoryEvidence.RECORDED
		result.materialization shouldBe PressureHistoryMaterialization.READY
		result.coverage shouldBe PressureHistoryCoverage.COMPLETE
		result.windows.single().zoneId shouldBe "Europe/Prague"
		result.windows.single().serviceRunId shouldBe fixture.runId
		result.windows.single().manifestRevision shouldBe 1L
		logical.isOrdinarilyDiscoverable shouldBe true
		logical.hasExactPressureOnlyIntent shouldBe true
		logical.summary?.latestHectopascals shouldBe 1_003f
		discovered.physicalMembers.single().segment.sampleCount shouldBe 0
		discovered.identity shouldBe PressureHistoryEntryIdentity.Logical(fixture.logicalId)
		result.windows.single().sampleCount shouldBe 4
		result.windows.single().sampleVarianceHectopascalsSquared shouldBe (5.0 / 3.0)
		result.windows.single().sensorAccuracy shouldBe PressureFactRevisionEntity.SENSOR_ACCURACY_HIGH
		result.windows.single().effectiveSamplePeriodMicros shouldBe 50_000
		result.windows.single().effectiveMaximumReportLatencyMicros shouldBe 0
		result.windows.single().targetWindowDurationNanos shouldBe 200_000_000L
		result.windows.single().expectedSampleCount shouldBe 4
		result.windows.single().actualToExpectedSampleRatio shouldBe 1.0
		result.windows.single().maximumInterSampleGapNanos shouldBe 50_000_000L
		result.windows.single().closureKind shouldBe PressureFactRevisionEntity.CLOSURE_TARGET_ELAPSED
		result.windows.single().sourceQualityFlags shouldBe 0L
		result.windows.single().sourceQualityConfidence shouldBe 1f
	}

	@Test
	fun retentionMarkerMakesSurvivingPressureFactsExplicitlyPartial() = runTest {
		val fixture = insertFixture(factSemanticRevision = 1L)
		establishPressureRetentionFloor()
		database.sourceDeletionFenceDao().upsert(
			pressureRetentionMarker(fixture.logicalId, fixture.runId),
		)

		val result = requireNotNull(selector.selectBySegmentId(fixture.segmentId))
		val public = result.toPublicPressureSessionHistory().pressure

		result.windows.size shouldBe 1
		result.materialization shouldBe PressureHistoryMaterialization.READY
		result.coverage shouldBe PressureHistoryCoverage.PARTIAL
		result.reasons shouldBe setOf(PressureHistoryReason.RETENTION_TRUNCATED)
		public.presentationState shouldBe PressureHistoryPresentationState.PARTIAL
		public.causes shouldBe setOf(PressureHistoryCause.RETENTION_TRUNCATED)
		public.summary?.windowCount shouldBe 1
	}

	@Test
	fun fullyPrunedRetentionMarkerIsPartialWithoutFabricatedPressure() = runTest {
		val fixture = insertFixture(factSemanticRevision = null, laneCursor = 1L)
		establishPressureRetentionFloor()
		database.sourceDeletionFenceDao().upsert(
			pressureRetentionMarker(fixture.logicalId, fixture.runId),
		)
		val repository = DefaultTrackingHistoryRepository(
			database = database,
			stepsSelector = mockk(relaxed = true),
			logicalHistoryReader = mockk(relaxed = true),
			pressureSelector = selector,
			ioDispatcher = UnconfinedTestDispatcher(testScheduler),
		)

		val result = requireNotNull(selector.selectBySegmentId(fixture.segmentId))
		val logical = selector.discoverRecentPressureOnlyByPressureFacts(limit = 1).single()
		val recent = repository.observeRecentPressureOnlyEntries(limit = 1).first().single()
		val public = result.toPublicPressureSessionHistory().pressure

		result.windows shouldBe emptyList()
		result.materialization shouldBe PressureHistoryMaterialization.READY
		result.coverage shouldBe PressureHistoryCoverage.PARTIAL
		result.reasons shouldBe setOf(PressureHistoryReason.RETENTION_TRUNCATED)
		public.presentationState shouldBe PressureHistoryPresentationState.PARTIAL
		public.summary shouldBe null
		public.windows shouldBe emptyList()
		PressureHistoryCause.FACTS_MISSING_FOR_ADMITTED_RUN in public.causes shouldBe false
		logical.qualifiedSources shouldBe emptySet()
		logical.hasAuthenticatedRetentionLoss shouldBe true
		logical.summary shouldBe null
		recent.state shouldBe PressureHistoryPresentationState.PARTIAL
		recent.pressure.summary shouldBe null
		recent.pressure.hasRetainedObservation shouldBe false
		recent.pressure.causes shouldBe setOf(PressureHistoryCause.RETENTION_TRUNCATED)
	}

	@Test
	fun corruptRetentionMarkerFailsClosed() = runTest {
		val fixture = insertFixture(factSemanticRevision = null, laneCursor = 1L)
		establishPressureRetentionFloor()
		database.sourceDeletionFenceDao().upsert(
			pressureRetentionMarker(
				logicalTrackingId = fixture.logicalId,
				serviceRunId = fixture.runId,
				collectedDataEpoch = 1L,
			),
		)
		val repository = DefaultTrackingHistoryRepository(
			database = database,
			stepsSelector = mockk(relaxed = true),
			logicalHistoryReader = mockk(relaxed = true),
			pressureSelector = selector,
			ioDispatcher = UnconfinedTestDispatcher(testScheduler),
		)

		val result = requireNotNull(selector.selectBySegmentId(fixture.segmentId))

		result.materialization shouldBe PressureHistoryMaterialization.FAILED
		result.coverage shouldBe PressureHistoryCoverage.UNKNOWN
		result.windows shouldBe emptyList()
		result.reasons shouldBe setOf(PressureHistoryReason.RETENTION_TRUNCATION_MARKER_INVALID)
		selector.discoverRecentPressureOnlyByPressureFacts(limit = 1) shouldBe emptyList()
		repository.observeRecentPressureOnlyEntries(limit = 1).first() shouldBe emptyList()
	}

	@Test
	fun retentionLossOnOneReplacementKeepsSurvivingSiblingFacts() = runTest {
		val first = insertFixture(factSemanticRevision = null, laneCursor = 2L)
		val replacement = insertReplacementFixture()
		establishPressureRetentionFloor()
		database.sourceDeletionFenceDao().upsert(
			pressureRetentionMarker(first.logicalId, first.runId),
		)

		val logical = selector.selectLogicalBySegmentIds(listOf(first.segmentId)).single()
		val public = requireNotNull(logical.toPublicPressureOnlyEntryOrNull()).pressure

		logical.physicalMembers.size shouldBe 2
		logical.windows.map(PressureHistoryWindow::serviceRunId) shouldBe listOf(replacement.runId)
		public.windows.size shouldBe 1
		public.presentationState shouldBe PressureHistoryPresentationState.PARTIAL
		public.causes shouldBe setOf(PressureHistoryCause.RETENTION_TRUNCATED)
		public.summary?.windowCount shouldBe 1
	}

	@Test
	fun fullyPrunedReplacementGroupRemainsOneMarkerBackedRecentEntry() = runTest {
		val first = insertFixture(factSemanticRevision = null, laneCursor = 2L)
		val replacement = insertReplacementFixture(includeFact = false)
		establishPressureRetentionFloor()
		database.sourceDeletionFenceDao().upsert(
			pressureRetentionMarker(first.logicalId, first.runId),
		)
		database.sourceDeletionFenceDao().upsert(
			pressureRetentionMarker(replacement.logicalId, replacement.runId),
		)
		val repository = DefaultTrackingHistoryRepository(
			database = database,
			stepsSelector = mockk(relaxed = true),
			logicalHistoryReader = mockk(relaxed = true),
			pressureSelector = selector,
			ioDispatcher = UnconfinedTestDispatcher(testScheduler),
		)

		val logical = selector.discoverRecentPressureOnlyByPressureFacts(limit = 1).single()
		val recent = repository.observeRecentPressureOnlyEntries(limit = 1).first().single()

		logical.physicalMembers.map { member -> member.segment.id } shouldBe
			listOf(first.segmentId, replacement.segmentId)
		logical.qualifiedSources shouldBe emptySet()
		logical.summary shouldBe null
		recent.startTime.raw shouldBe RUN_START_MS
		recent.endTime.raw shouldBe REPLACEMENT_RUN_END_MS
		recent.state shouldBe PressureHistoryPresentationState.PARTIAL
		recent.pressure.windows shouldBe emptyList()
		recent.pressure.summary shouldBe null
		recent.pressure.causes shouldBe setOf(PressureHistoryCause.RETENTION_TRUNCATED)
	}

	@Test
	fun admittedRunWithoutFactsStaysMaterializingWhileLaneIsBehindAndHasNoSummary() = runTest {
		val fixture = insertFixture(factSemanticRevision = null, laneCursor = 0L)

		val result = requireNotNull(selector.selectBySegmentId(fixture.segmentId))

		result.evidence shouldBe PressureHistoryEvidence.NO_OBSERVATION
		result.materialization shouldBe PressureHistoryMaterialization.MATERIALIZING
		result.coverage shouldBe PressureHistoryCoverage.NONE
		result.windows shouldBe emptyList()
		PressureHistoryReason.PRODUCT_LANE_BEHIND in result.reasons shouldBe true
		PressureHistoryReason.FACTS_MISSING_FOR_ADMITTED_RUN in result.reasons shouldBe true
	}

	@Test
	fun selectingEitherReplacementMemberExpandsCompleteLogicalMembership() = runTest {
		val first = insertFixture(factSemanticRevision = 1L, laneCursor = 2L)
		val replacement = insertReplacementFixture()

		val fromFirst = selector.selectLogicalBySegmentIds(listOf(first.segmentId)).single()
		val fromReplacement = selector.selectLogicalBySegmentIds(listOf(replacement.segmentId)).single()
		val physicalFromFirst = requireNotNull(selector.selectBySegmentId(first.segmentId))

		fromFirst.physicalMembers.map { it.segment.id } shouldBe
			listOf(first.segmentId, replacement.segmentId)
		fromFirst.identity shouldBe PressureHistoryEntryIdentity.Logical(LOGICAL_ID)
		fromReplacement shouldBe fromFirst
		physicalFromFirst.coverage shouldBe PressureHistoryCoverage.COMPLETE
		fromFirst.physicalMembers.map { it.coverage } shouldBe
			listOf(PressureHistoryCoverage.COMPLETE, PressureHistoryCoverage.COMPLETE)
	}

	@Test
	fun missingReplacementReverseBindingCannotLookLikeCompleteLogicalHistory() = runTest {
		val first = insertFixture(factSemanticRevision = 1L, laneCursor = 2L)
		val replacement = insertReplacementFixture()
		val replacementRun = requireNotNull(database.sourceSessionDao().serviceRun(replacement.runId))
		database.sourceSessionDao().updateServiceRun(replacementRun.copy(sessionSegmentId = null)) shouldBe 1

		val logical = selector.selectLogicalBySegmentIds(listOf(first.segmentId)).single()

		logical.physicalMembers.all {
			it.availability == PressureHistoryAvailability.UNAVAILABLE &&
				it.reasons == setOf(PressureHistoryReason.SEGMENT_MEMBERSHIP_INCOMPLETE)
		} shouldBe true
		logical.identity shouldBe PressureHistoryEntryIdentity.Physical(first.segmentId)
	}

	@Test
	fun partialLogicalMemberInputCannotLookCompleteAgainstItsSnapshotUniverse() = runTest {
		val first = insertFixture(factSemanticRevision = 1L, laneCursor = 2L)
		val replacement = insertReplacementFixture()
		val segments = database.trackingHistoryReadDao().segments(
			listOf(first.segmentId, replacement.segmentId),
		)
		val snapshot = database.withTransaction {
			loadPressureHistoryBatchSnapshot(database, segments)
		}

		val partial = selector.selectManyWithSnapshot(listOf(segments.first()), snapshot).single()

		partial.availability shouldBe PressureHistoryAvailability.UNAVAILABLE
		partial.reasons shouldBe setOf(PressureHistoryReason.SEGMENT_MEMBERSHIP_INCOMPLETE)
	}

	@Test
	fun duplicateServiceRunMembershipCannotLookComplete() = runTest {
		val fixture = insertFixture(factSemanticRevision = 1L)
		val segment = database.trackingHistoryReadDao().segments(listOf(fixture.segmentId)).single()
		val snapshot = database.withTransaction {
			loadPressureHistoryBatchSnapshot(database, listOf(segment))
		}

		val duplicate = segment.copy(id = REPLACEMENT_SEGMENT_ID)
		val selected = selector.selectManyWithSnapshot(listOf(segment, duplicate), snapshot)

		selected.all { history ->
			history.availability == PressureHistoryAvailability.UNAVAILABLE &&
				history.reasons == setOf(PressureHistoryReason.SEGMENT_MEMBERSHIP_INCOMPLETE)
		} shouldBe true
	}

	@Test
	fun logicalManifestRevisionGapOrDuplicatePreventsReplacementComposition() = runTest {
		val first = insertFixture(factSemanticRevision = 1L, laneCursor = 2L)
		val replacement = insertReplacementFixture()
		val segments = database.trackingHistoryReadDao().segments(
			listOf(first.segmentId, replacement.segmentId),
		)
		val snapshot = database.withTransaction {
			loadPressureHistoryBatchSnapshot(database, segments)
		}
		val replacementManifest = snapshot.manifestsByRun.getValue(replacement.runId).single()

		listOf(1L, 3L).forEach { invalidReplacementRevision ->
			val invalidSnapshot = snapshot.copy(
				manifestsByRun = snapshot.manifestsByRun + (
					replacement.runId to listOf(
						replacementManifest.copy(manifestRevision = invalidReplacementRevision),
					)
				),
			)
			val selected = selector.selectManyWithSnapshot(segments, invalidSnapshot)

			selected.all { history ->
				history.availability == PressureHistoryAvailability.UNAVAILABLE &&
					history.reasons == setOf(
						PressureHistoryReason.LOGICAL_MANIFEST_REVISION_UNION_INVALID,
					)
			} shouldBe true
			PressureLogicalHistoryComposer.compose(selected).size shouldBe segments.size
		}
	}

	@Test
	fun corruptReplacementManifestBlocksItsOtherwiseValidLogicalSibling() = runTest {
		val first = insertFixture(factSemanticRevision = 1L, laneCursor = 2L)
		val replacement = insertReplacementFixture()
		val segments = database.trackingHistoryReadDao().segments(
			listOf(first.segmentId, replacement.segmentId),
		)
		val snapshot = database.withTransaction {
			loadPressureHistoryBatchSnapshot(database, segments)
		}
		val replacementManifest = snapshot.manifestsByRun.getValue(replacement.runId).single()
		val replacementSources = snapshot.sourcesByManifest.getValue(
			PressureManifestKey(LOGICAL_ID, replacementManifest.manifestRevision),
		)
		val timelineUnsigned = replacementManifest.copy(
			effectiveElapsedRealtimeNanos = REPLACEMENT_RUN_START_ELAPSED_NANOS - 1L,
			manifestChecksum = "",
		)
		val corruptions = listOf(
			replacementManifest.copy(logicalTrackingId = "other-logical") to
				PressureHistoryReason.MANIFEST_MEMBERSHIP_MISMATCH,
			replacementManifest.copy(manifestChecksum = "0".repeat(64)) to
				PressureHistoryReason.MANIFEST_INTEGRITY_FAILED,
			timelineUnsigned.copy(
				manifestChecksum = SessionManifestIntegrity.compute(
					timelineUnsigned,
					replacementSources,
				),
			) to PressureHistoryReason.MANIFEST_INTEGRITY_FAILED,
		)

		corruptions.forEach { (corruptManifest, expectedReason) ->
			val corruptSnapshot = snapshot.copy(
				manifestsByRun = snapshot.manifestsByRun + (
					replacement.runId to listOf(corruptManifest)
				),
			)
			val selected = selector.selectManyWithSnapshot(segments, corruptSnapshot)

			selected.map { it.segment.id }.toSet() shouldBe
				setOf(first.segmentId, replacement.segmentId)
			selected.all { history ->
				history.availability == PressureHistoryAvailability.UNAVAILABLE &&
					history.materialization == PressureHistoryMaterialization.FAILED &&
					history.reasons == setOf(expectedReason)
			} shouldBe true
		}
	}

	@Test
	fun legitimateUnavailablePressureSettlementIsTypedUnavailable() = runTest {
		val fixture = insertFixture(factSemanticRevision = null, unavailablePressure = true)

		val result = requireNotNull(selector.selectBySegmentId(fixture.segmentId))

		result.availability shouldBe PressureHistoryAvailability.UNAVAILABLE
		result.materialization shouldBe PressureHistoryMaterialization.NOT_APPLICABLE
		result.coverage shouldBe PressureHistoryCoverage.NONE
		result.reasons shouldBe setOf(PressureHistoryReason.PROVIDER_UNAVAILABLE)
		result.windows shouldBe emptyList()
	}

	@Test
	fun unavailablePressureSentinelWithRetainedFactFailsAsCompletenessConflict() = runTest {
		val fixture = insertFixture(factSemanticRevision = 1L, unavailablePressure = true)

		val result = requireNotNull(selector.selectBySegmentId(fixture.segmentId))

		result.availability shouldBe PressureHistoryAvailability.AVAILABLE
		result.materialization shouldBe PressureHistoryMaterialization.FAILED
		result.coverage shouldBe PressureHistoryCoverage.UNKNOWN
		result.reasons shouldBe setOf(
			PressureHistoryReason.UNAVAILABLE_SENTINEL_WITH_RETAINED_FACTS,
		)
		result.windows shouldBe emptyList()
	}

	@Test
	fun movedCorrectionCannotHideBehindUnavailablePressureSentinel() = runTest {
		val fixture = insertFixture(factSemanticRevision = 1L, unavailablePressure = true)
		val first = pressureFact(pressureBinding(), semanticRevision = 1L, admissionOrdinal = 1L)
		val escapedBinding = pressureBinding().copy(
			logicalTrackingId = "other-logical",
			manifestRevision = 2L,
			consentEpoch = 2L,
		)
		val escapedUnsigned = first.copy(
			semanticRevision = 2L,
			mutationId = "${first.logicalFactId}:2",
			sourceAdmissionOrdinal = 2L,
			logicalTrackingId = "other-logical",
			serviceRunId = "other-run",
			manifestRevision = 2L,
			sourcePolicyRevision = 2L,
			captureConsentEpoch = 2L,
			effectChecksum = "pending",
		)
		val escaped = escapedUnsigned.copy(
			effectChecksum = PressureFactRevisionIntegrity.effectChecksum(
				escapedUnsigned,
				escapedBinding,
			),
		)
		database.pressureFactRevisionDao().insert(escaped)

		val result = requireNotNull(selector.selectBySegmentId(fixture.segmentId))

		result.availability shouldBe PressureHistoryAvailability.AVAILABLE
		result.materialization shouldBe PressureHistoryMaterialization.FAILED
		result.reasons shouldBe setOf(
			PressureHistoryReason.PRESSURE_FACT_CORRECTION_INCOMPLETE,
		)
		result.windows shouldBe emptyList()
	}

	@Test
	fun pressureFactTraversalBudgetFailsClosedWithoutAccumulatingPastLimit() = runTest {
		val fixture = insertFixture(factSemanticRevision = 1L, laneCursor = 2L)
		database.pressureFactRevisionDao().insert(
			pressureFact(
				binding = pressureBinding(),
				semanticRevision = 1L,
				admissionOrdinal = 2L,
				sourceEventId = "pressure-event-over-budget",
			),
		)
		val segment = database.trackingHistoryReadDao().segments(listOf(fixture.segmentId)).single()
		val snapshot = database.withTransaction {
			loadPressureHistoryBatchSnapshot(
				database = database,
				segments = listOf(segment),
				factRevisionBudget = 1,
			)
		}

		val result = selector.selectManyWithSnapshot(listOf(segment), snapshot).single()

		result.availability shouldBe PressureHistoryAvailability.UNAVAILABLE
		result.reasons shouldBe setOf(PressureHistoryReason.BATCH_DEPENDENCY_OVERFLOW)
		result.windows shouldBe emptyList()
	}

	@Test
	fun logicalMemberTraversalBudgetFailsClosedBeforeAccumulatingPastLimit() = runTest {
		val fixture = insertFixture(factSemanticRevision = null)
		insertBareLogicalMember(index = 1)
		val seed = database.trackingHistoryReadDao().segments(listOf(fixture.segmentId)).single()

		val expansion = database.withTransaction {
			expandPressureLogicalMembership(database, listOf(seed), memberBudget = 1)
		}

		expansion.segments shouldBe listOf(seed)
		expansion.failures shouldBe mapOf(
			LOGICAL_ID to PressureHistoryReason.BATCH_DEPENDENCY_OVERFLOW,
		)
	}

	@Test
	fun logicalMemberCursorCrossesPageBoundaryWithoutSkippingReplacementRuns() = runTest {
		val fixture = insertFixture(factSemanticRevision = null)
		val addedSegmentIds = (1..33).map { index -> insertBareLogicalMember(index) }
		val seed = database.trackingHistoryReadDao().segments(listOf(fixture.segmentId)).single()

		val expansion = database.withTransaction {
			expandPressureLogicalMembership(database, listOf(seed), memberBudget = 64)
		}

		expansion.failures shouldBe emptyMap()
		expansion.segments.map(SessionSegment::id).toSet() shouldBe
			(addedSegmentIds + fixture.segmentId).toSet()
	}

	@Test
	fun factLineageStartingAfterRevisionOneFailsClosedAsIncompleteCorrection() = runTest {
		val fixture = insertFixture(factSemanticRevision = 2L, factAdmissionOrdinal = 2L, laneCursor = 2L)

		val result = requireNotNull(selector.selectBySegmentId(fixture.segmentId))

		result.evidence shouldBe PressureHistoryEvidence.NO_OBSERVATION
		result.materialization shouldBe PressureHistoryMaterialization.FAILED
		result.windows shouldBe emptyList()
		result.reasons shouldBe setOf(PressureHistoryReason.PRESSURE_FACT_CORRECTION_INCOMPLETE)
	}

	@Test
	fun correctionEscapingSelectedPhysicalRunFailsClosed() = runTest {
		val fixture = insertFixture(factSemanticRevision = 1L)
		val first = pressureFact(pressureBinding(), semanticRevision = 1L, admissionOrdinal = 1L)
		val escapedBinding = pressureBinding().copy(
			logicalTrackingId = "other-logical",
			manifestRevision = 2L,
			consentEpoch = 2L,
		)
		val escapedUnsigned = first.copy(
			semanticRevision = 2L,
			mutationId = "${first.logicalFactId}:2",
			sourceAdmissionOrdinal = 2L,
			logicalTrackingId = "other-logical",
			serviceRunId = "other-run",
			manifestRevision = 2L,
			sourcePolicyRevision = 2L,
			captureConsentEpoch = 2L,
			effectChecksum = "pending",
		)
		val escaped = escapedUnsigned.copy(
			effectChecksum = PressureFactRevisionIntegrity.effectChecksum(
				escapedUnsigned,
				escapedBinding,
			),
		)
		database.pressureFactRevisionDao().insert(escaped)

		val result = requireNotNull(selector.selectBySegmentId(fixture.segmentId))

		result.materialization shouldBe PressureHistoryMaterialization.FAILED
		result.reasons shouldBe setOf(PressureHistoryReason.PRESSURE_FACT_CORRECTION_INCOMPLETE)
		result.windows shouldBe emptyList()
	}

	@Test
	@Suppress("LongMethod")
	fun correctionCannotRotateManifestPolicyConsentOrClockZoneAuthority() = runTest {
		val fixture = insertFixture(factSemanticRevision = 1L, laneCursor = 2L)
		val rotatedBinding = pressureBinding(manifestRevision = 2L, consentEpoch = 2L)
		val unsignedManifest = SessionManifestVersionEntity(
			logicalTrackingId = LOGICAL_ID,
			manifestRevision = 2L,
			serviceRunId = RUN_ID,
			sessionMode = MANUAL_SESSION_MODE,
			sourcePolicyRevision = 2L,
			acquisitionPlanRevision = 1L,
			rolloutRevision = ROLLOUT_REVISION,
			startOrigin = POLICY_RECONCILIATION,
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = ROTATED_MANIFEST_ELAPSED_NANOS,
			effectiveWallTimeMs = ROTATED_MANIFEST_WALL_TIME_MS,
			zoneId = "America/New_York",
			automationEpoch = null,
			changeReason = POLICY_RECONCILIATION,
			manifestChecksum = "",
		)
		val manifest = unsignedManifest.copy(
			manifestChecksum = SessionManifestIntegrity.compute(
				unsignedManifest,
				listOf(rotatedBinding),
			),
		)
		database.sourceSessionDao().insertManifest(manifest)
		database.sourceSessionDao().insertManifestSources(listOf(rotatedBinding))
		database.sourcePolicyDao().insertPolicies(listOf(
			pressurePolicy(
				policyRevision = 2L,
				captureConsentEpoch = 2L,
				effectiveElapsedRealtimeNanos = ROTATED_MANIFEST_ELAPSED_NANOS,
				effectiveWallTimeMs = ROTATED_MANIFEST_WALL_TIME_MS,
			),
		))
		database.sourcePolicyDao().insertConsentEpochs(listOf(
			pressureConsent(
				epoch = 2L,
				policyRevision = 2L,
				effectiveElapsedRealtimeNanos = ROTATED_MANIFEST_ELAPSED_NANOS,
				effectiveWallTimeMs = ROTATED_MANIFEST_WALL_TIME_MS,
			),
		))
		val rotatedFact = pressureFact(
			binding = rotatedBinding,
			semanticRevision = 2L,
			admissionOrdinal = 2L,
			manifestRevision = 2L,
			sourcePolicyRevision = 2L,
			captureConsentEpoch = 2L,
			intervalStartTimeMs = ROTATED_PRESSURE_START_MS,
			intervalEndTimeMs = ROTATED_PRESSURE_END_MS,
			windowStartElapsedRealtimeNanos = ROTATED_PRESSURE_START_ELAPSED_NANOS,
			windowEndElapsedRealtimeNanos = ROTATED_PRESSURE_END_ELAPSED_NANOS,
		)
		PressureFactRevisionIntegrity.hasValidEffectChecksum(
			rotatedFact,
			rotatedBinding,
		) shouldBe true
		database.pressureFactRevisionDao().insert(rotatedFact)
		val completeness = database.trackingHistoryReadDao().completeness(listOf(RUN_ID)).single()
		database.sourceSessionDao().saveCompleteness(
			completeness.copy(
				lastAdmissionOrdinal = 2L,
				lastSourceSequence = 2L,
				updatedAtMs = ROTATED_PRESSURE_END_MS,
			),
		)

		val result = requireNotNull(selector.selectBySegmentId(fixture.segmentId))

		result.materialization shouldBe PressureHistoryMaterialization.FAILED
		result.reasons shouldBe setOf(PressureHistoryReason.PRESSURE_FACT_CORRECTION_INCOMPLETE)
		result.windows shouldBe emptyList()
	}

	@Test
	fun correctionCannotRotateWallClockUncertaintyAuthority() = runTest {
		val fixture = insertFixture(factSemanticRevision = 1L, laneCursor = 2L)
		val segment = database.trackingHistoryReadDao().segments(listOf(fixture.segmentId)).single()
		val snapshot = database.withTransaction {
			loadPressureHistoryBatchSnapshot(database, listOf(segment))
		}
		val first = snapshot.factRevisionsByRun.getValue(RUN_ID).single()
		val correctedUnsigned = first.copy(
			semanticRevision = 2L,
			mutationId = "${first.logicalFactId}:2",
			sourceAdmissionOrdinal = 2L,
			wallTimeUncertaintyMs = first.wallTimeUncertaintyMs + 1L,
			effectChecksum = "pending",
		)
		val binding = pressureBinding()
		val corrected = correctedUnsigned.copy(
			effectChecksum = PressureFactRevisionIntegrity.effectChecksum(
				correctedUnsigned,
				binding,
			),
		)
		PressureFactRevisionIntegrity.hasValidEffectChecksum(corrected, binding) shouldBe true
		val completeness = snapshot.completenessByRun.getValue(RUN_ID).single().copy(
			lastAdmissionOrdinal = 2L,
			lastSourceSequence = 2L,
		)
		val rotatedSnapshot = snapshot.copy(
			factRevisionsByRun = snapshot.factRevisionsByRun + (RUN_ID to listOf(first, corrected)),
			completenessByRun = snapshot.completenessByRun + (RUN_ID to listOf(completeness)),
		)

		val result = selector.selectManyWithSnapshot(listOf(segment), rotatedSnapshot).single()

		result.materialization shouldBe PressureHistoryMaterialization.FAILED
		result.reasons shouldBe setOf(PressureHistoryReason.PRESSURE_FACT_CORRECTION_INCOMPLETE)
		result.windows shouldBe emptyList()
	}

	@Test
	fun wholeFactLineageMovedOutsideExpectedRunFailsClosed() = runTest {
		val fixture = insertFixture(factSemanticRevision = null)
		val moved = pressureFact(
			binding = pressureBinding(),
			semanticRevision = 1L,
			admissionOrdinal = 1L,
			serviceRunId = "moved-run",
		)
		database.pressureFactRevisionDao().insert(moved)

		val result = requireNotNull(selector.selectBySegmentId(fixture.segmentId))

		result.materialization shouldBe PressureHistoryMaterialization.FAILED
		result.reasons shouldBe setOf(PressureHistoryReason.PRESSURE_FACT_CORRECTION_INCOMPLETE)
		result.windows shouldBe emptyList()
	}

	@Test
	fun pressureFactHistoryQueryUsesExactPrimaryKeyCursorAcrossPages() = runTest {
		insertFixture(factSemanticRevision = 1L)
		val second = pressureFact(
			binding = pressureBinding(),
			semanticRevision = 1L,
			admissionOrdinal = 2L,
			sourceEventId = "pressure-event-2",
		)
		database.pressureFactRevisionDao().insert(second)
		val dao = database.pressureFactRevisionDao()
		val firstPage = dao.historyRevisionPage(
			serviceRunIds = listOf(RUN_ID),
			logicalTrackingIds = listOf(LOGICAL_ID),
			limit = 1,
			afterServiceRunId = null,
			afterWriterProjectionId = null,
			afterWriterProjectionVersion = null,
			afterLogicalFactId = null,
			afterSemanticRevision = null,
		)
		val first = firstPage.single()
		val secondPage = dao.historyRevisionPage(
			serviceRunIds = listOf(RUN_ID),
			logicalTrackingIds = listOf(LOGICAL_ID),
			limit = 1,
			afterServiceRunId = first.serviceRunId,
			afterWriterProjectionId = first.writerProjectionId,
			afterWriterProjectionVersion = first.writerProjectionVersion,
			afterLogicalFactId = first.logicalFactId,
			afterSemanticRevision = first.semanticRevision,
		)

		(firstPage + secondPage).map(PressureFactRevisionEntity::sourceEventId) shouldBe
			listOf(SOURCE_EVENT_ID, "pressure-event-2")
	}

	@Suppress("LongMethod")
	private suspend fun insertFixture(
		zoneId: String = "UTC",
		factSemanticRevision: Long?,
		factAdmissionOrdinal: Long = 1L,
		laneCursor: Long = factAdmissionOrdinal,
		unavailablePressure: Boolean = false,
	): PressureFixture {
		val segment = segment()
		database.sessionSegmentDao().insert(segment)
		val run = serviceRun()
		database.sourceSessionDao().insertServiceRun(run)
		val binding = pressureBinding()
		val unsignedManifest = SessionManifestVersionEntity(
			logicalTrackingId = LOGICAL_ID,
			manifestRevision = 1L,
			serviceRunId = RUN_ID,
			sessionMode = MANUAL_SESSION_MODE,
			sourcePolicyRevision = 1L,
			acquisitionPlanRevision = 1L,
			rolloutRevision = ROLLOUT_REVISION,
			startOrigin = MANUAL_START_ORIGIN,
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = RUN_START_ELAPSED_NANOS,
			effectiveWallTimeMs = RUN_START_MS,
			zoneId = zoneId,
			automationEpoch = null,
			changeReason = "TEST",
			manifestChecksum = "",
		)
		val manifest = unsignedManifest.copy(
			manifestChecksum = SessionManifestIntegrity.compute(unsignedManifest, listOf(binding)),
		)
		database.sourceSessionDao().insertManifest(manifest)
		database.sourceSessionDao().insertManifestSources(listOf(binding))
		database.sourcePolicyDao().insertPolicies(listOf(pressurePolicy()))
		database.sourcePolicyDao().insertConsentEpochs(listOf(pressureConsent()))
		database.sourceProjectionStateDao().installProductLane(lane(laneCursor))
		database.sourceSessionDao().saveCompleteness(
			SourceSessionCompletenessEntity(
				logicalTrackingId = LOGICAL_ID,
				serviceRunId = RUN_ID,
				sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				sourceInstanceId = if (unavailablePressure) {
					"unavailable-pressure"
				} else {
					"pressure-provider-1"
				},
				registrationGeneration = if (unavailablePressure) 0L else 1L,
				lastAdmissionOrdinal = if (unavailablePressure) null else factAdmissionOrdinal,
				lastSourceSequence = if (unavailablePressure) null else factAdmissionOrdinal,
				appDrainComplete = true,
				providerCoverage = if (unavailablePressure) {
					"PROVIDER_COMPLETENESS_UNOBSERVABLE"
				} else {
					COMPLETE_PROVIDER_COVERAGE
				},
				stopStatus = "COMPLETE",
				unresolvedSequenceStart = null,
				unresolvedSequenceEnd = null,
				updatedAtMs = RUN_END_MS,
			),
		)
		factSemanticRevision?.let { revision ->
			database.pressureFactRevisionDao().insert(
				pressureFact(
					binding = binding,
					semanticRevision = revision,
					admissionOrdinal = factAdmissionOrdinal,
				),
			)
		}
		return PressureFixture(segment.id, LOGICAL_ID, RUN_ID)
	}

	@Suppress("LongMethod")
	private suspend fun insertReplacementFixture(
		includeFact: Boolean = true,
		unavailablePressure: Boolean = false,
		segmentId: Long = REPLACEMENT_SEGMENT_ID,
	): PressureFixture {
		val segment = segment(
			id = segmentId,
			runId = REPLACEMENT_RUN_ID,
			startTimeMs = REPLACEMENT_RUN_START_MS,
			endTimeMs = REPLACEMENT_RUN_END_MS,
		)
		database.sessionSegmentDao().insert(segment)
		database.sourceSessionDao().insertServiceRun(
			serviceRun(
				runId = REPLACEMENT_RUN_ID,
				segmentId = segmentId,
				startTimeMs = REPLACEMENT_RUN_START_MS,
				endTimeMs = REPLACEMENT_RUN_END_MS,
				startElapsedNanos = REPLACEMENT_RUN_START_ELAPSED_NANOS,
				manifestRevision = 2L,
			),
		)
		val binding = pressureBinding(manifestRevision = 2L)
		val unsignedManifest = SessionManifestVersionEntity(
			logicalTrackingId = LOGICAL_ID,
			manifestRevision = 2L,
			serviceRunId = REPLACEMENT_RUN_ID,
			sessionMode = MANUAL_SESSION_MODE,
			sourcePolicyRevision = 1L,
			acquisitionPlanRevision = 1L,
			rolloutRevision = ROLLOUT_REVISION,
			startOrigin = MANUAL_START_ORIGIN,
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = REPLACEMENT_RUN_START_ELAPSED_NANOS,
			effectiveWallTimeMs = REPLACEMENT_RUN_START_MS,
			zoneId = "UTC",
			automationEpoch = null,
			changeReason = "TEST_REPLACEMENT",
			manifestChecksum = "",
		)
		database.sourceSessionDao().insertManifest(
			unsignedManifest.copy(
				manifestChecksum = SessionManifestIntegrity.compute(unsignedManifest, listOf(binding)),
			),
		)
		database.sourceSessionDao().insertManifestSources(listOf(binding))
		database.sourceSessionDao().saveCompleteness(
			SourceSessionCompletenessEntity(
				logicalTrackingId = LOGICAL_ID,
				serviceRunId = REPLACEMENT_RUN_ID,
				sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				sourceInstanceId = if (unavailablePressure) {
					"unavailable-pressure"
				} else {
					"pressure-provider-2"
				},
				registrationGeneration = if (unavailablePressure) 0L else 2L,
				lastAdmissionOrdinal = if (unavailablePressure) null else 2L,
				lastSourceSequence = if (unavailablePressure) null else 2L,
				appDrainComplete = true,
				providerCoverage = if (unavailablePressure) {
					"PROVIDER_COMPLETENESS_UNOBSERVABLE"
				} else {
					COMPLETE_PROVIDER_COVERAGE
				},
				stopStatus = "COMPLETE",
				unresolvedSequenceStart = null,
				unresolvedSequenceEnd = null,
				updatedAtMs = REPLACEMENT_RUN_END_MS,
			),
		)
		if (includeFact) {
			database.pressureFactRevisionDao().insert(
				pressureFact(
					binding = binding,
					semanticRevision = 1L,
					admissionOrdinal = 2L,
					sourceEventId = "pressure-event-2",
					serviceRunId = REPLACEMENT_RUN_ID,
					manifestRevision = 2L,
					intervalStartTimeMs = REPLACEMENT_PRESSURE_START_MS,
					intervalEndTimeMs = REPLACEMENT_PRESSURE_END_MS,
					windowStartElapsedRealtimeNanos = REPLACEMENT_PRESSURE_START_ELAPSED_NANOS,
					windowEndElapsedRealtimeNanos = REPLACEMENT_PRESSURE_END_ELAPSED_NANOS,
				),
			)
		}
		return PressureFixture(segment.id, LOGICAL_ID, REPLACEMENT_RUN_ID)
	}

	@Suppress("LongMethod")
	private suspend fun insertIndependentPressureFixture(
		index: Int,
		startTimeMs: Long,
		factCollectedDataEpoch: Long = 0L,
	): PressureFixture {
		val segmentId = 2_000L + index
		val logicalTrackingId = "logical-pressure-independent-$index"
		val serviceRunId = "run-pressure-independent-$index"
		val endTimeMs = startTimeMs + 1_000L
		val startElapsedNanos = Math.multiplyExact(startTimeMs, 1_000_000L)
		val segment = segment(
			id = segmentId,
			runId = serviceRunId,
			logicalTrackingId = logicalTrackingId,
			startTimeMs = startTimeMs,
			endTimeMs = endTimeMs,
		)
		database.sessionSegmentDao().insert(segment)
		database.sourceSessionDao().insertServiceRun(
			serviceRun(
				runId = serviceRunId,
				logicalTrackingId = logicalTrackingId,
				segmentId = segmentId,
				startTimeMs = startTimeMs,
				endTimeMs = endTimeMs,
				startElapsedNanos = startElapsedNanos,
				manifestRevision = 1L,
			),
		)
		val binding = pressureBinding(logicalTrackingId = logicalTrackingId)
		val unsignedManifest = SessionManifestVersionEntity(
			logicalTrackingId = logicalTrackingId,
			manifestRevision = 1L,
			serviceRunId = serviceRunId,
			sessionMode = MANUAL_SESSION_MODE,
			sourcePolicyRevision = 1L,
			acquisitionPlanRevision = 1L,
			rolloutRevision = ROLLOUT_REVISION,
			startOrigin = MANUAL_START_ORIGIN,
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = startElapsedNanos,
			effectiveWallTimeMs = startTimeMs,
			zoneId = "UTC",
			automationEpoch = null,
			changeReason = "TEST_INDEPENDENT",
			manifestChecksum = "",
		)
		database.sourceSessionDao().insertManifest(
			unsignedManifest.copy(
				manifestChecksum = SessionManifestIntegrity.compute(unsignedManifest, listOf(binding)),
			),
		)
		database.sourceSessionDao().insertManifestSources(listOf(binding))
		val admissionOrdinal = 10L + index
		database.sourceSessionDao().saveCompleteness(
			SourceSessionCompletenessEntity(
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
				sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				sourceInstanceId = "pressure-provider-independent-$index",
				registrationGeneration = 1L,
				lastAdmissionOrdinal = admissionOrdinal,
				lastSourceSequence = admissionOrdinal,
				appDrainComplete = true,
				providerCoverage = COMPLETE_PROVIDER_COVERAGE,
				stopStatus = "COMPLETE",
				unresolvedSequenceStart = null,
				unresolvedSequenceEnd = null,
				updatedAtMs = endTimeMs,
			),
		)
		database.pressureFactRevisionDao().insert(
			pressureFact(
				binding = binding,
				semanticRevision = 1L,
				admissionOrdinal = admissionOrdinal,
				sourceEventId = "pressure-event-independent-$index",
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
				collectedDataEpoch = factCollectedDataEpoch,
				intervalStartTimeMs = startTimeMs + 100L,
				intervalEndTimeMs = startTimeMs + 250L,
				windowStartElapsedRealtimeNanos = startElapsedNanos + 100_000_000L,
				windowEndElapsedRealtimeNanos = startElapsedNanos + 250_000_000L,
			),
		)
		return PressureFixture(segmentId, logicalTrackingId, serviceRunId)
	}

	private suspend fun insertBareLogicalMember(index: Int): Long {
		val segmentId = 1_000L + index
		val runId = "run-pressure-bare-$index"
		val startTimeMs = 10_000L + index
		val endTimeMs = startTimeMs + 1L
		database.sessionSegmentDao().insert(
			segment(
				id = segmentId,
				runId = runId,
				startTimeMs = startTimeMs,
				endTimeMs = endTimeMs,
			),
		)
		database.sourceSessionDao().insertServiceRun(
			serviceRun(
				runId = runId,
				segmentId = segmentId,
				startTimeMs = startTimeMs,
				endTimeMs = endTimeMs,
				startElapsedNanos = 1_000_000_000L + index,
				manifestRevision = 10L + index,
			),
		)
		return segmentId
	}

	private fun segment(
		id: Long = SEGMENT_ID,
		runId: String = RUN_ID,
		logicalTrackingId: String = LOGICAL_ID,
		startTimeMs: Long = RUN_START_MS,
		endTimeMs: Long = RUN_END_MS,
	) = SessionSegment(
		id = id,
		startTimeMs = startTimeMs,
		endTimeMs = endTimeMs,
		distanceM = 0f,
		steps = null,
		primaryActivity = null,
		activityConfidence = null,
		sampleCount = 0,
		source = SegmentSource.USER_CREATED,
		inferenceVersion = "test",
		createdAt = endTimeMs,
		logicalTrackingId = logicalTrackingId,
		serviceRunId = runId,
	)

	private fun serviceRun(
		runId: String = RUN_ID,
		logicalTrackingId: String = LOGICAL_ID,
		segmentId: Long = SEGMENT_ID,
		startTimeMs: Long = RUN_START_MS,
		endTimeMs: Long = RUN_END_MS,
		startElapsedNanos: Long = RUN_START_ELAPSED_NANOS,
		manifestRevision: Long = 1L,
	) = SourceServiceRunEntity(
		serviceRunId = runId,
		logicalTrackingId = logicalTrackingId,
		state = "FINALIZED",
		desiredPlanRevision = 1L,
		rolloutRevision = ROLLOUT_REVISION,
		foregroundCapabilityFlags = 0L,
		startedAtMs = startTimeMs,
		startedElapsedNanos = startElapsedNanos,
		completedAtMs = endTimeMs,
		completionReason = "STOPPED",
		bootId = BOOT_ID,
		leaseGeneration = 1L,
		startOrigin = MANUAL_START_ORIGIN,
		runRevision = 1L,
		startDeliveryToken = "delivery-$runId",
		startCommandGeneration = 1L,
		preparedManifestRevision = manifestRevision,
		preparedIntentRevision = 1L,
		startIsUserInitiated = true,
		sessionSegmentId = segmentId,
		presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_QUIESCED,
		presentationAcknowledgedAtMs = endTimeMs,
	)

	private fun pressureBinding(
		manifestRevision: Long = 1L,
		consentEpoch: Long = 1L,
		logicalTrackingId: String = LOGICAL_ID,
	) = SessionManifestSourceEntity(
		logicalTrackingId = logicalTrackingId,
		manifestRevision = manifestRevision,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
		consentEpoch = consentEpoch,
		persistenceEligible = true,
		qosCode = 1,
		outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
		writerOwner = SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS,
		writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
		writerProjectionId = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
		writerProjectionVersion = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
		writerBindingGeneration = SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION,
	)

	private fun pressurePolicy(
		policyRevision: Long = 1L,
		captureConsentEpoch: Long = 1L,
		effectiveElapsedRealtimeNanos: Long = RUN_START_ELAPSED_NANOS,
		effectiveWallTimeMs: Long = RUN_START_MS,
	) = SourcePolicyEntity(
		policyRevision = policyRevision,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		enabled = true,
		qosCode = 1,
		locationMinTimeSeconds = null,
		locationMinDistanceMeters = null,
		locationRequiredAccuracyMeters = null,
		capturePersistenceEligible = true,
		controlPersistenceEligible = false,
		ambientPersistenceEligible = false,
		captureConsentEpoch = captureConsentEpoch,
		controlConsentEpoch = null,
		ambientConsentEpoch = null,
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = effectiveElapsedRealtimeNanos,
		effectiveWallTimeMs = effectiveWallTimeMs,
		changeReason = "TEST",
	)

	private fun pressureConsent(
		epoch: Long = 1L,
		policyRevision: Long = 1L,
		effectiveElapsedRealtimeNanos: Long = RUN_START_ELAPSED_NANOS,
		effectiveWallTimeMs: Long = RUN_START_MS,
	) = SourceConsentEpochEntity(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
		epoch = epoch,
		eligible = true,
		persistenceEligible = true,
		policyRevision = policyRevision,
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = effectiveElapsedRealtimeNanos,
		effectiveWallTimeMs = effectiveWallTimeMs,
		changeReason = "TEST",
	)

	private fun lane(cursor: Long) = SourceProductProjectionLaneEntity(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		bindingGeneration = SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION,
		projectionId = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
		projectionVersion = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
		captureModeMask = MANUAL_CAPTURE_MASK,
		productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
		activatedRolloutRevision = ROLLOUT_REVISION,
		activationOrdinal = 1L,
		contiguousAdmissionOrdinal = cursor,
		retentionRequired = true,
		status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
		installedAtMs = 1L,
		updatedAtMs = 1L,
	)

	@Suppress("LongMethod")
	private fun pressureFact(
		binding: SessionManifestSourceEntity,
		semanticRevision: Long,
		admissionOrdinal: Long,
		sourceEventId: String = SOURCE_EVENT_ID,
		logicalTrackingId: String = LOGICAL_ID,
		serviceRunId: String = RUN_ID,
		manifestRevision: Long = 1L,
		sourcePolicyRevision: Long = 1L,
		captureConsentEpoch: Long = 1L,
		intervalStartTimeMs: Long = PRESSURE_START_MS,
		intervalEndTimeMs: Long = PRESSURE_END_MS,
		windowStartElapsedRealtimeNanos: Long = PRESSURE_START_ELAPSED_NANOS,
		windowEndElapsedRealtimeNanos: Long = PRESSURE_END_ELAPSED_NANOS,
		collectedDataEpoch: Long = 0L,
	): PressureFactRevisionEntity {
		val logicalFactId =
			"${SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID}:$sourceEventId"
		val unsigned = PressureFactRevisionEntity(
			logicalFactId = logicalFactId,
			semanticRevision = semanticRevision,
			mutationId = "$logicalFactId:$semanticRevision",
			sourceEventId = sourceEventId,
			sourceAdmissionOrdinal = admissionOrdinal,
			writerProjectionId = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
			writerProjectionVersion = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
			writerBindingGeneration = SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION,
			payloadVersion = PressureFactRevisionEntity.QUALIFIED_PRESSURE_PAYLOAD_VERSION,
			intervalStartTimeMs = intervalStartTimeMs,
			intervalEndTimeMs = intervalEndTimeMs,
			windowStartElapsedRealtimeNanos = windowStartElapsedRealtimeNanos,
			windowEndElapsedRealtimeNanos = windowEndElapsedRealtimeNanos,
			clockDomainId = BOOT_ID,
			wallTimeUncertaintyMs = 1L,
			sampleCount = 4,
			meanHectopascals = 1_001.5,
			sumSquaredDeviations = 5.0,
			minimumHectopascals = 1_000f,
			maximumHectopascals = 1_003f,
			firstProviderSequence = 1L,
			lastProviderSequence = 4L,
			firstHectopascals = 1_000f,
			lastHectopascals = 1_003f,
			slopeHectopascalsPerSecond = 20.0,
			rSquared = 1.0,
			sensorAccuracy = PressureFactRevisionEntity.SENSOR_ACCURACY_HIGH,
			effectiveSamplePeriodMicros = 50_000,
			effectiveMaximumReportLatencyMicros = 0,
			targetWindowDurationNanos = 200_000_000L,
			expectedSampleCount = 4,
			maximumInterSampleGapNanos = 50_000_000L,
			closureKind = PressureFactRevisionEntity.CLOSURE_TARGET_ELAPSED,
			qualification = PressureFactRevisionEntity.QUALIFICATION_COMPLETE,
			sourceQualityFlags = 0L,
			sourceQualityConfidence = 1f,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			purpose = PressureFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			manifestRevision = manifestRevision,
			sourcePolicyRevision = sourcePolicyRevision,
			captureConsentEpoch = captureConsentEpoch,
			collectedDataEpoch = collectedDataEpoch,
			effectChecksum = "pending",
			appliedAtMs = intervalEndTimeMs,
		)
		return unsigned.copy(
			effectChecksum = PressureFactRevisionIntegrity.effectChecksum(unsigned, binding),
		)
	}

	private fun pressureFence(
		logicalTrackingId: String,
		serviceRunId: String,
	) = SourceDeletionFenceEntity.createLogicalServiceRun(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		purpose = PressureFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
		logicalTrackingId = logicalTrackingId,
		serviceRunId = serviceRunId,
		fenceGeneration = 1L,
		collectedDataEpoch = 0L,
		deletedAtMs = 10_000L,
	)

	private fun pressureRetentionMarker(
		logicalTrackingId: String,
		serviceRunId: String,
		collectedDataEpoch: Long = 0L,
	) = PressureFactRevisionIntegrity.retentionTruncationFence(
		logicalTrackingId = logicalTrackingId,
		serviceRunId = serviceRunId,
		collectedDataEpoch = collectedDataEpoch,
		markedAtMs = 10_000L,
	)

	private suspend fun establishPressureRetentionFloor() {
		check(
			database.sourceEvidenceStateDao().updateLifecycle(
				epoch = 0L,
				retainedFromMs = RUN_START_MS,
				updatedAtMs = 10_000L,
			) == 1,
		)
	}

	private fun executableLaneAuthority() = SourceProductLaneExecutionAuthority { lane ->
		lane.sourceKind == SourceDestinationOwnerEntity.SOURCE_PRESSURE &&
			lane.bindingGeneration == SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION &&
			lane.projectionId == SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID &&
			lane.projectionVersion == SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION &&
			lane.captureModeMask == MANUAL_CAPTURE_MASK
	}

	private fun pressureAwareRepository(): DefaultTrackingHistoryRepository {
		val stepsSelector = StepsSegmentHistorySelector(database, executableLaneAuthority())
		return DefaultTrackingHistoryRepository(
			database = database,
			stepsSelector = stepsSelector,
			logicalHistoryReader = LogicalTrackingHistoryReader(database, stepsSelector),
			pressureSelector = selector,
			ioDispatcher = Dispatchers.IO,
		)
	}

	private data class PressureFixture(
		val segmentId: Long,
		val logicalId: String,
		val runId: String,
	)

	private companion object {
		const val SEGMENT_ID = 41L
		const val LOGICAL_ID = "logical-pressure"
		const val RUN_ID = "run-pressure"
		const val REPLACEMENT_SEGMENT_ID = 42L
		const val REPLACEMENT_RUN_ID = "run-pressure-2"
		const val BOOT_ID = "boot-pressure"
		const val SOURCE_EVENT_ID = "pressure-event"
		const val MANUAL_SESSION_MODE = "MANUAL"
		const val MANUAL_START_ORIGIN = "MANUAL_FOREGROUND_START"
		const val POLICY_RECONCILIATION = "POLICY_RECONCILIATION"
		const val MANUAL_CAPTURE_MASK = 1L
		const val ROLLOUT_REVISION = 2L
		const val RUN_START_MS = 1_000L
		const val RUN_END_MS = 2_000L
		const val RUN_START_ELAPSED_NANOS = 10_000_000L
		const val PRESSURE_START_MS = 1_100L
		const val PRESSURE_END_MS = 1_250L
		const val PRESSURE_START_ELAPSED_NANOS = 110_000_000L
		const val PRESSURE_END_ELAPSED_NANOS = 260_000_000L
		const val ROTATED_MANIFEST_WALL_TIME_MS = 1_400L
		const val ROTATED_MANIFEST_ELAPSED_NANOS = 300_000_000L
		const val ROTATED_PRESSURE_START_MS = 1_500L
		const val ROTATED_PRESSURE_END_MS = 1_650L
		const val ROTATED_PRESSURE_START_ELAPSED_NANOS = 310_000_000L
		const val ROTATED_PRESSURE_END_ELAPSED_NANOS = 460_000_000L
		const val REPLACEMENT_RUN_START_MS = 2_000L
		const val REPLACEMENT_RUN_END_MS = 3_000L
		const val REPLACEMENT_RUN_START_ELAPSED_NANOS = 300_000_000L
		const val REPLACEMENT_PRESSURE_START_MS = 2_100L
		const val REPLACEMENT_PRESSURE_END_MS = 2_250L
		const val REPLACEMENT_PRESSURE_START_ELAPSED_NANOS = 410_000_000L
		const val REPLACEMENT_PRESSURE_END_ELAPSED_NANOS = 560_000_000L
		const val COMPLETE_PROVIDER_COVERAGE = "CALLBACKS_ENTERED_BEFORE_BARRIER"
	}
}
