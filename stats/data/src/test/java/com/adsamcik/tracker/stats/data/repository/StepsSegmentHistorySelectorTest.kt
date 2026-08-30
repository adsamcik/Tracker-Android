package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.model.SegmentSource
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StepsSegmentHistorySelectorTest {
	private lateinit var database: AppDatabase
	private lateinit var selector: StepsSegmentHistorySelector

	@Before
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState())
		selector = StepsSegmentHistorySelector(database)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun legacyRunUsesImmutableManifestInsteadOfCurrentOwnerAndNeverFabricatesZero() = runTest {
		insertRun(RUN_ONE)
		insertManifest(RUN_ONE, revision = 1L, owner = LEGACY_OWNER)
		database.sourceDestinationOwnerDao().insertIfAbsent(
			owner(owner = CANDIDATE_OWNER, generation = 2L),
		)

		selector.select(segment(RUN_ONE, steps = 14)) shouldBe StepsSegmentHistoryResult(
			count = 14L,
			availability = StepsHistoryAvailability.AVAILABLE,
			evidence = StepsHistoryEvidence.LEGACY_RECORDED,
			materialization = StepsHistoryMaterialization.DEGRADED,
			coverage = StepsHistoryCoverage.UNKNOWN,
			reasons = setOf(StepsHistoryReason.LEGACY_REPLAY_UNVERIFIED),
		)
		val zero = selector.select(segment(RUN_ONE, steps = 0))
		zero.count shouldBe null
		zero.evidence shouldBe StepsHistoryEvidence.NO_OBSERVATION
		zero.reasons shouldBe setOf(
			StepsHistoryReason.LEGACY_REPLAY_UNVERIFIED,
			StepsHistoryReason.LEGACY_ZERO_UNVERIFIED,
		)
	}

	@Test
	fun observableProductionLookupUsesOnePersistedSnapshotAndEndsAtNotFoundAfterDeletion() = runBlocking {
		insertRun(RUN_ONE)
		insertManifest(RUN_ONE, revision = 1L, owner = LEGACY_OWNER)
		val persisted = segment(RUN_ONE, steps = 14)
		val segmentId = database.sessionSegmentDao().insert(persisted)
		val repository = DefaultTrackingHistoryRepository(database, selector, Dispatchers.IO)
		val initialEmission = CompletableDeferred<Unit>()
		val collection = async {
			repository.observeSession(segmentId)
				.onEach { initialEmission.complete(Unit) }
				.take(2)
				.toList()
		}

		withTimeout(5_000L) { initialEmission.await() }
		// Current write ownership is intentionally not an observed historical dependency.
		database.sourceDestinationOwnerDao().insertIfAbsent(
			owner(owner = CANDIDATE_OWNER, generation = 2L),
		)
		database.sessionSegmentDao().delete(persisted.copy(id = segmentId))

		val emissions = withTimeout(5_000L) { collection.await() }
		val found = emissions.first() as com.adsamcik.tracker.stats.api.repository.SessionHistoryQuery.Found
		found.history.segmentId shouldBe segmentId
		found.history.steps.count shouldBe 14L
		found.history.steps.productState shouldBe
			com.adsamcik.tracker.stats.api.repository.HistoryProductState.DEGRADED
		emissions.last() shouldBe com.adsamcik.tracker.stats.api.repository.SessionHistoryQuery.NotFound
		Unit
	}

	@Test
	fun observableLookupReactsToHistoricalPolicyRepairWithoutGuessingDisabled() = runBlocking {
		insertRun(RUN_ONE)
		insertManifestWithoutSteps(RUN_ONE, revision = 1L)
		val segmentId = database.sessionSegmentDao().insert(segment(RUN_ONE, steps = null))
		val repository = DefaultTrackingHistoryRepository(database, selector, Dispatchers.IO)
		val initialEmission = CompletableDeferred<Unit>()
		val collection = async {
			repository.observeSession(segmentId)
				.onEach { initialEmission.complete(Unit) }
				.take(2)
				.toList()
		}

		withTimeout(5_000L) { initialEmission.await() }
		database.sourcePolicyDao().insertPolicies(listOf(stepPolicy(revision = 1L, enabled = false)))

		val emissions = withTimeout(5_000L) { collection.await() }
		val unresolved = emissions.first() as
			com.adsamcik.tracker.stats.api.repository.SessionHistoryQuery.Found
		unresolved.history.steps.availability shouldBe
			com.adsamcik.tracker.stats.api.repository.HistoryAvailability.UNAVAILABLE
		val disabled = emissions.last() as
			com.adsamcik.tracker.stats.api.repository.SessionHistoryQuery.Found
		disabled.history.steps.availability shouldBe
			com.adsamcik.tracker.stats.api.repository.HistoryAvailability.DISABLED
		Unit
	}

	@Test
	fun enabledButIncompletePolicyCannotBeMisreportedAsDisabled() = runTest {
		insertRun(RUN_ONE)
		insertManifestWithoutSteps(RUN_ONE, revision = 1L)
		database.sourcePolicyDao().insertPolicies(
			listOf(
				stepPolicy(revision = 1L, enabled = true).copy(
					capturePersistenceEligible = false,
					captureConsentEpoch = null,
				),
			),
		)

		val result = selector.select(segment(RUN_ONE, steps = null))
		result.availability shouldBe StepsHistoryAvailability.UNAVAILABLE
		result.reasons shouldBe setOf(StepsHistoryReason.SOURCE_NOT_CAPTURED)
	}

	@Test
	fun mixedDisabledAndEnabledPolicyRevisionsRemainUnavailable() = runTest {
		insertRun(RUN_ONE)
		insertManifestWithoutSteps(RUN_ONE, revision = 1L)
		insertManifestWithoutSteps(RUN_ONE, revision = 2L)
		database.sourcePolicyDao().insertPolicies(
			listOf(
				stepPolicy(revision = 1L, enabled = false),
				stepPolicy(revision = 2L, enabled = true),
			),
		)

		val result = selector.select(segment(RUN_ONE, steps = null))
		result.availability shouldBe StepsHistoryAvailability.UNAVAILABLE
		result.reasons shouldBe setOf(StepsHistoryReason.SOURCE_NOT_CAPTURED)
	}

	@Test
	fun observableLookupReactsWhenCandidateLaneReachesItsRunTarget() = runBlocking {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)),
			targetOrdinal = 2L,
			laneCursor = 1L,
		)
		val segmentId = database.sessionSegmentDao().insert(segment(RUN_ONE, steps = null))
		val repository = DefaultTrackingHistoryRepository(database, selector, Dispatchers.IO)
		val initialEmission = CompletableDeferred<Unit>()
		val collection = async {
			repository.observeSession(segmentId)
				.onEach { initialEmission.complete(Unit) }
				.take(2)
				.toList()
		}

		withTimeout(5_000L) { initialEmission.await() }
		advanceLane(expectedCursor = 1L, throughOrdinal = 2L)

		val emissions = withTimeout(5_000L) { collection.await() }
		val materializing = emissions.first() as
			com.adsamcik.tracker.stats.api.repository.SessionHistoryQuery.Found
		materializing.history.steps.productState shouldBe
			com.adsamcik.tracker.stats.api.repository.HistoryProductState.MATERIALIZING
		materializing.history.steps.coverage shouldBe
			com.adsamcik.tracker.stats.api.repository.StepsHistoryCoverage.PARTIAL
		val ready = emissions.last() as
			com.adsamcik.tracker.stats.api.repository.SessionHistoryQuery.Found
		ready.history.steps.productState shouldBe
			com.adsamcik.tracker.stats.api.repository.HistoryProductState.READY
		ready.history.steps.coverage shouldBe
			com.adsamcik.tracker.stats.api.repository.StepsHistoryCoverage.COMPLETE
		Unit
	}

	@Test
	fun candidateBaselineAndCoveredZeroRemainDistinct() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(fact(1L, StepFactRevisionEntity.COVERAGE_BASELINE, 0L)),
			targetOrdinal = 1L,
			laneCursor = 1L,
		)
		val baseline = selector.select(segment(RUN_ONE, steps = 999))
		baseline.count shouldBe null
		baseline.evidence shouldBe StepsHistoryEvidence.BASELINE
		baseline.materialization shouldBe StepsHistoryMaterialization.READY
		baseline.coverage shouldBe StepsHistoryCoverage.NONE

		val covered = fact(2L, StepFactRevisionEntity.COVERAGE_COVERED, 0L)
		database.stepFactRevisionDao().insert(covered)
		database.sourceSessionDao().saveCompleteness(completeness(RUN_ONE, lastOrdinal = 2L))
		advanceLane(expectedCursor = 1L, throughOrdinal = 2L)

		val zero = selector.select(segment(RUN_ONE, steps = 999))
		zero.count shouldBe 0L
		zero.evidence shouldBe StepsHistoryEvidence.COVERED_ZERO
		zero.materialization shouldBe StepsHistoryMaterialization.READY
		zero.coverage shouldBe StepsHistoryCoverage.COMPLETE
	}

	@Test
	fun candidateValueIsMaterializingUntilItsExactRunTargetIsDrained() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)),
			targetOrdinal = 2L,
			laneCursor = 1L,
		)

		val pending = selector.select(segment(RUN_ONE, steps = 900))
		pending.count shouldBe 5L
		pending.evidence shouldBe StepsHistoryEvidence.RECORDED
		pending.materialization shouldBe StepsHistoryMaterialization.MATERIALIZING
		pending.coverage shouldBe StepsHistoryCoverage.PARTIAL
		pending.reasons shouldBe setOf(StepsHistoryReason.PRODUCT_LANE_BEHIND)

		advanceLane(expectedCursor = 1L, throughOrdinal = 2L)
		val ready = selector.select(segment(RUN_ONE, steps = 900))
		ready.count shouldBe 5L
		ready.materialization shouldBe StepsHistoryMaterialization.READY
		ready.coverage shouldBe StepsHistoryCoverage.COMPLETE
	}

	@Test
	fun activeServiceRunCannotClaimReadyEvenWhenCurrentCursorIsCaughtUp() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)),
			targetOrdinal = 1L,
			laneCursor = 1L,
			serviceRunCompleted = false,
		)

		val result = selector.select(segment(RUN_ONE, steps = 5))
		result.count shouldBe 5L
		result.materialization shouldBe StepsHistoryMaterialization.MATERIALIZING
		result.coverage shouldBe StepsHistoryCoverage.PARTIAL
		result.reasons shouldBe setOf(StepsHistoryReason.SERVICE_RUN_ACTIVE)
	}

	@Test
	fun providerGapKeepsMaterializedCandidateValuePartial() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)),
			targetOrdinal = 1L,
			laneCursor = 1L,
			providerCoverage = "PROVIDER_COMPLETENESS_UNOBSERVABLE",
		)

		val result = selector.select(segment(RUN_ONE, steps = 5))
		result.count shouldBe 5L
		result.materialization shouldBe StepsHistoryMaterialization.READY
		result.coverage shouldBe StepsHistoryCoverage.PARTIAL
		result.reasons shouldBe setOf(StepsHistoryReason.PROVIDER_COMPLETENESS_UNOBSERVABLE)
	}

	@Test
	fun counterResetPreservesCoveredLowerBoundButMarksCoveragePartial() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(
				fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L),
				fact(2L, StepFactRevisionEntity.COVERAGE_RESET_GAP, 0L),
			),
			targetOrdinal = 2L,
			laneCursor = 2L,
		)

		val result = selector.select(segment(RUN_ONE, steps = 5))
		result.count shouldBe 5L
		result.evidence shouldBe StepsHistoryEvidence.RECORDED
		result.materialization shouldBe StepsHistoryMaterialization.READY
		result.coverage shouldBe StepsHistoryCoverage.PARTIAL
		result.reasons shouldBe setOf(StepsHistoryReason.RESET_GAP)
	}

	@Test
	fun coveredFactSumOverflowFailsAsTypedProductState() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(
				fact(
					ordinal = 1L,
					coverage = StepFactRevisionEntity.COVERAGE_COVERED,
					steps = Long.MAX_VALUE,
					cumulativeStart = 0L,
					cumulativeEnd = Long.MAX_VALUE,
				),
				fact(2L, StepFactRevisionEntity.COVERAGE_COVERED, 1L),
			),
			targetOrdinal = 2L,
			laneCursor = 2L,
		)

		selector.select(segment(RUN_ONE, steps = 999)) shouldBe StepsSegmentHistoryResult(
			count = null,
			availability = StepsHistoryAvailability.AVAILABLE,
			evidence = StepsHistoryEvidence.NO_OBSERVATION,
			materialization = StepsHistoryMaterialization.FAILED,
			coverage = StepsHistoryCoverage.UNKNOWN,
			reasons = setOf(StepsHistoryReason.COUNT_OVERFLOW),
		)
	}

	@Test
	fun retainedFloorNeverFallsBackToStaleLegacySegmentValue() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)),
			targetOrdinal = 1L,
			laneCursor = 1L,
		)
		database.sourceEvidenceStateDao().updateLifecycle(
			epoch = 0L,
			retainedFromMs = 2_001L,
			updatedAtMs = 3_000L,
		) shouldBe 1

		selector.select(segment(RUN_ONE, steps = 999)) shouldBe StepsSegmentHistoryResult(
			count = null,
			availability = StepsHistoryAvailability.UNAVAILABLE,
			evidence = StepsHistoryEvidence.NO_OBSERVATION,
			materialization = StepsHistoryMaterialization.FAILED,
			coverage = StepsHistoryCoverage.UNKNOWN,
			reasons = setOf(StepsHistoryReason.OUTSIDE_RETAINED_FLOOR),
		)
	}

	@Test
	fun retainedFloorCrossingDropsExpiredFactsAndMarksRemainingValuePartial() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(
				fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L),
				fact(2L, StepFactRevisionEntity.COVERAGE_COVERED, 3L),
			),
			targetOrdinal = 2L,
			laneCursor = 2L,
		)
		database.sourceEvidenceStateDao().updateLifecycle(
			epoch = 0L,
			retainedFromMs = 1_102L,
			updatedAtMs = 3_000L,
		) shouldBe 1

		val result = selector.select(segment(RUN_ONE, steps = 999))
		result.count shouldBe 3L
		result.evidence shouldBe StepsHistoryEvidence.RECORDED
		result.materialization shouldBe StepsHistoryMaterialization.READY
		result.coverage shouldBe StepsHistoryCoverage.PARTIAL
		result.reasons shouldBe setOf(StepsHistoryReason.RETENTION_CROSSES_SEGMENT)
	}

	@Test
	fun soleFactRetractionIsAnIntentionalDeletedResultRatherThanMissingMaterialization() = runTest {
		val original = fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(original),
			targetOrdinal = 1L,
			laneCursor = 1L,
		)
		database.stepFactRevisionDao().insert(retraction(original))

		selector.select(segment(RUN_ONE, steps = 999)) shouldBe StepsSegmentHistoryResult(
			count = null,
			availability = StepsHistoryAvailability.DELETED,
			evidence = StepsHistoryEvidence.NO_OBSERVATION,
			materialization = StepsHistoryMaterialization.READY,
			coverage = StepsHistoryCoverage.NONE,
			reasons = setOf(StepsHistoryReason.DELETED_FACTS),
		)
	}

	@Test
	fun partialFactRetractionKeepsOnlyTheSurvivingLowerBoundAndMarksItPartial() = runTest {
		val deleted = fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)
		val surviving = fact(2L, StepFactRevisionEntity.COVERAGE_COVERED, 3L)
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(deleted, surviving),
			targetOrdinal = 2L,
			laneCursor = 2L,
		)
		database.stepFactRevisionDao().insert(retraction(deleted))

		val result = selector.select(segment(RUN_ONE, steps = 999))
		result.count shouldBe 3L
		result.availability shouldBe StepsHistoryAvailability.AVAILABLE
		result.evidence shouldBe StepsHistoryEvidence.RECORDED
		result.materialization shouldBe StepsHistoryMaterialization.READY
		result.coverage shouldBe StepsHistoryCoverage.PARTIAL
		result.reasons shouldBe setOf(StepsHistoryReason.DELETED_FACTS)
	}

	@Test
	fun staleRetractionCannotDeleteAFactFromTheCurrentDataEpoch() = runTest {
		val current = fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L).copy(
			collectedDataEpoch = 1L,
		)
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(current),
			targetOrdinal = 1L,
			laneCursor = 1L,
		)
		database.sourceEvidenceStateDao().updateLifecycle(
			epoch = 1L,
			retainedFromMs = null,
			updatedAtMs = 3_000L,
		) shouldBe 1
		database.stepFactRevisionDao().insert(
			retraction(current, collectedDataEpoch = 0L),
		)

		selector.select(segment(RUN_ONE, steps = 999)) shouldBe StepsSegmentHistoryResult(
			count = null,
			availability = StepsHistoryAvailability.AVAILABLE,
			evidence = StepsHistoryEvidence.NO_OBSERVATION,
			materialization = StepsHistoryMaterialization.FAILED,
			coverage = StepsHistoryCoverage.UNKNOWN,
			reasons = setOf(StepsHistoryReason.STALE_COLLECTED_DATA_EPOCH),
		)
	}

	@Test
	fun currentRetractionSafelySuppressesAnUpsertFromThePriorDataEpoch() = runTest {
		val stale = fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(stale),
			targetOrdinal = 1L,
			laneCursor = 1L,
		)
		database.sourceEvidenceStateDao().updateLifecycle(
			epoch = 1L,
			retainedFromMs = null,
			updatedAtMs = 3_000L,
		) shouldBe 1
		database.stepFactRevisionDao().insert(
			retraction(stale, collectedDataEpoch = 1L),
		)

		val result = selector.select(segment(RUN_ONE, steps = 999))
		result.availability shouldBe StepsHistoryAvailability.DELETED
		result.materialization shouldBe StepsHistoryMaterialization.READY
		result.coverage shouldBe StepsHistoryCoverage.NONE
		result.reasons shouldBe setOf(StepsHistoryReason.DELETED_FACTS)
	}

	@Test
	fun retiredLaneBehindRunTargetFailsInsteadOfMaterializingForever() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)),
			targetOrdinal = 2L,
			laneCursor = 1L,
		)
		retireLane(expectedCursor = 1L)

		val result = selector.select(segment(RUN_ONE, steps = 5))
		result.count shouldBe 5L
		result.materialization shouldBe StepsHistoryMaterialization.FAILED
		result.reasons shouldBe setOf(StepsHistoryReason.PRODUCT_LANE_RETIRED_BEFORE_TARGET)
	}

	@Test
	fun retiredLaneThatReachedRunTargetRemainsValidHistoricalEvidence() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)),
			targetOrdinal = 1L,
			laneCursor = 1L,
		)
		retireLane(expectedCursor = 1L)

		val result = selector.select(segment(RUN_ONE, steps = 5))
		result.count shouldBe 5L
		result.materialization shouldBe StepsHistoryMaterialization.READY
		result.coverage shouldBe StepsHistoryCoverage.COMPLETE
		result.reasons shouldBe emptySet()
	}

	@Test
	fun fencedLaneCannotWaitForAServiceRunTargetBeyondItsCutoff() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)),
			targetOrdinal = 2L,
			laneCursor = 1L,
		)
		database.sourceProjectionStateDao().fenceProductLaneCaptureAdmission(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			bindingGeneration = BINDING_GENERATION,
			projectionId = WRITER_ID,
			projectionVersion = WRITER_VERSION,
			cutoffOrdinal = 1L,
			updatedAtMs = 2L,
		) shouldBe 1

		val result = selector.select(segment(RUN_ONE, steps = 5))
		result.materialization shouldBe StepsHistoryMaterialization.FAILED
		result.reasons shouldBe setOf(StepsHistoryReason.PRODUCT_LANE_CUTOFF_BEFORE_TARGET)
	}

	@Test
	fun unknownLaneLifecycleStatusFailsClosed() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)),
			targetOrdinal = 1L,
			laneCursor = 1L,
			installedLane = lane(cursor = 1L).copy(status = "UNKNOWN"),
		)

		selector.select(segment(RUN_ONE, steps = 5)).reasons shouldBe
			setOf(StepsHistoryReason.PRODUCT_LANE_INVALID)
	}

	@Test
	fun activeLaneWithTerminalFieldsFailsClosed() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)),
			targetOrdinal = 1L,
			laneCursor = 1L,
			installedLane = lane(cursor = 1L).copy(
				terminalDisposition =
					SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN,
				terminalAtMs = 1L,
			),
		)

		selector.select(segment(RUN_ONE, steps = 5)).reasons shouldBe
			setOf(StepsHistoryReason.PRODUCT_LANE_INVALID)
	}

	@Test
	fun nonPositiveLaneActivationOrdinalFailsClosedWithoutUnderflow() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)),
			targetOrdinal = 1L,
			laneCursor = 0L,
			installedLane = lane(cursor = 0L).copy(activationOrdinal = 0L),
		)

		selector.select(segment(RUN_ONE, steps = 5)).reasons shouldBe
			setOf(StepsHistoryReason.PRODUCT_LANE_INVALID)
	}

	@Test
	fun mixedWriterWithinOneServiceRunFailsClosed() = runTest {
		insertRun(RUN_ONE)
		insertManifest(RUN_ONE, revision = 1L, owner = LEGACY_OWNER)
		insertManifest(RUN_ONE, revision = 2L, owner = CANDIDATE_OWNER)

		selector.select(segment(RUN_ONE, steps = 11)) shouldBe StepsSegmentHistoryResult(
			count = null,
			availability = StepsHistoryAvailability.AVAILABLE,
			evidence = StepsHistoryEvidence.NO_OBSERVATION,
			materialization = StepsHistoryMaterialization.FAILED,
			coverage = StepsHistoryCoverage.UNKNOWN,
			reasons = setOf(StepsHistoryReason.MIXED_WRITER_WITHIN_SERVICE_RUN),
		)
	}

	@Test
	fun unattributedLegacySegmentRemainsVisibleButCannotClaimVerifiedAvailability() = runTest {
		val result = selector.select(segment(runId = null, logicalId = null, steps = 7))

		result.count shouldBe 7L
		result.availability shouldBe StepsHistoryAvailability.UNAVAILABLE
		result.materialization shouldBe StepsHistoryMaterialization.DEGRADED
		result.reasons shouldBe setOf(
			StepsHistoryReason.LEGACY_REPLAY_UNVERIFIED,
			StepsHistoryReason.LEGACY_UNATTRIBUTED,
		)
	}

	private suspend fun insertCandidateRun(
		runId: String,
		facts: List<StepFactRevisionEntity>,
		targetOrdinal: Long,
		laneCursor: Long,
		providerCoverage: String = COMPLETE_PROVIDER_COVERAGE,
		serviceRunCompleted: Boolean = true,
		installedLane: SourceProductProjectionLaneEntity = lane(cursor = laneCursor),
	) {
		insertRun(runId, completed = serviceRunCompleted)
		insertManifest(runId, revision = 1L, owner = CANDIDATE_OWNER)
		database.sourceProjectionStateDao().installProductLane(installedLane)
		database.sourceSessionDao().saveCompleteness(
			completeness(runId, lastOrdinal = targetOrdinal, providerCoverage = providerCoverage),
		)
		facts.forEach { database.stepFactRevisionDao().insert(it.copy(serviceRunId = runId)) }
	}

	private suspend fun insertRun(runId: String, completed: Boolean = true) {
		database.sourceSessionDao().insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = runId,
				logicalTrackingId = LOGICAL_ID,
				state = if (completed) "FINALIZED" else "ACTIVE",
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				foregroundCapabilityFlags = 0L,
				startedAtMs = 1_000L,
				startedElapsedNanos = 1_000L,
				completedAtMs = 2_000L.takeIf { completed },
				completionReason = "STOPPED".takeIf { completed },
			),
		)
	}

	private suspend fun insertManifest(runId: String, revision: Long, owner: String) {
		val candidate = owner == CANDIDATE_OWNER
		val source = SessionManifestSourceEntity(
			logicalTrackingId = LOGICAL_ID,
			manifestRevision = revision,
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			consentEpoch = 1L,
			persistenceEligible = true,
			qosCode = 1,
			outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
			writerOwner = owner,
			writerOwnerGeneration = if (candidate) 2L else 1L,
			writerProjectionId = WRITER_ID.takeIf { candidate },
			writerProjectionVersion = WRITER_VERSION.takeIf { candidate },
			writerBindingGeneration = BINDING_GENERATION.takeIf { candidate },
		)
		val unsigned = SessionManifestVersionEntity(
			logicalTrackingId = LOGICAL_ID,
			manifestRevision = revision,
			serviceRunId = runId,
			sessionMode = "MANUAL",
			sourcePolicyRevision = revision,
			acquisitionPlanRevision = revision,
			rolloutRevision = 1L,
			startOrigin = "MANUAL_FOREGROUND_START",
			effectiveBootId = "boot-1",
			effectiveElapsedRealtimeNanos = revision * 1_000L,
			effectiveWallTimeMs = revision * 1_000L,
			zoneId = "UTC",
			automationEpoch = null,
			changeReason = "TEST",
			manifestChecksum = "",
		)
		val manifest = unsigned.copy(
			manifestChecksum = SessionManifestIntegrity.compute(unsigned, listOf(source)),
		)
		database.sourceSessionDao().insertManifest(manifest)
		database.sourceSessionDao().insertManifestSources(listOf(source))
	}

	private suspend fun insertManifestWithoutSteps(runId: String, revision: Long) {
		val locationSource = SessionManifestSourceEntity(
			logicalTrackingId = LOGICAL_ID,
			manifestRevision = revision,
			sourceKind = SOURCE_LOCATION,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			consentEpoch = 1L,
			persistenceEligible = true,
			qosCode = 1,
		)
		val unsigned = SessionManifestVersionEntity(
			logicalTrackingId = LOGICAL_ID,
			manifestRevision = revision,
			serviceRunId = runId,
			sessionMode = "MANUAL",
			sourcePolicyRevision = revision,
			acquisitionPlanRevision = revision,
			rolloutRevision = 1L,
			startOrigin = "MANUAL_FOREGROUND_START",
			effectiveBootId = "boot-1",
			effectiveElapsedRealtimeNanos = revision * 1_000L,
			effectiveWallTimeMs = revision * 1_000L,
			zoneId = "UTC",
			automationEpoch = null,
			changeReason = "TEST",
			manifestChecksum = "",
		)
		val manifest = unsigned.copy(
			manifestChecksum = SessionManifestIntegrity.compute(unsigned, listOf(locationSource)),
		)
		database.sourceSessionDao().insertManifest(manifest)
		database.sourceSessionDao().insertManifestSources(listOf(locationSource))
	}

	private fun stepPolicy(revision: Long, enabled: Boolean) = SourcePolicyEntity(
		policyRevision = revision,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		enabled = enabled,
		qosCode = 1,
		locationMinTimeSeconds = null,
		locationMinDistanceMeters = null,
		locationRequiredAccuracyMeters = null,
		capturePersistenceEligible = enabled,
		controlPersistenceEligible = false,
		ambientPersistenceEligible = false,
		captureConsentEpoch = 1L.takeIf { enabled },
		controlConsentEpoch = null,
		ambientConsentEpoch = null,
		effectiveBootId = "boot-1",
		effectiveElapsedRealtimeNanos = revision * 1_000L,
		effectiveWallTimeMs = revision * 1_000L,
		changeReason = "TEST",
	)

	private fun segment(
		runId: String?,
		logicalId: String? = LOGICAL_ID,
		steps: Int?,
	) = SessionSegment(
		startTimeMs = 1_000L,
		endTimeMs = 2_000L,
		distanceM = 0f,
		steps = steps,
		primaryActivity = null,
		activityConfidence = null,
		sampleCount = 1,
		source = SegmentSource.USER_CREATED,
		inferenceVersion = "test",
		createdAt = 2_000L,
		logicalTrackingId = logicalId,
		serviceRunId = runId,
	)

	private fun completeness(
		runId: String,
		lastOrdinal: Long,
		providerCoverage: String = COMPLETE_PROVIDER_COVERAGE,
	) = SourceSessionCompletenessEntity(
		logicalTrackingId = LOGICAL_ID,
		serviceRunId = runId,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		sourceInstanceId = "steps-instance",
		registrationGeneration = 1L,
		lastAdmissionOrdinal = lastOrdinal,
		lastSourceSequence = lastOrdinal,
		appDrainComplete = true,
		providerCoverage = providerCoverage,
		stopStatus = "COMPLETE",
		unresolvedSequenceStart = null,
		unresolvedSequenceEnd = null,
		updatedAtMs = 2_000L,
	)

	private fun lane(cursor: Long) = SourceProductProjectionLaneEntity(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		bindingGeneration = BINDING_GENERATION,
		projectionId = WRITER_ID,
		projectionVersion = WRITER_VERSION,
		captureModeMask = 1L,
		productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
		activatedRolloutRevision = 2L,
		activationOrdinal = 1L,
		contiguousAdmissionOrdinal = cursor,
		retentionRequired = true,
		status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
		installedAtMs = 1L,
		updatedAtMs = 1L,
	)

	private suspend fun advanceLane(expectedCursor: Long, throughOrdinal: Long) {
		database.sourceProjectionStateDao().advanceExactProductLaneCursor(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			bindingGeneration = BINDING_GENERATION,
			projectionId = WRITER_ID,
			projectionVersion = WRITER_VERSION,
			captureModeMask = 1L,
			productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
			activatedRolloutRevision = 2L,
			activationOrdinal = 1L,
			expectedCutoffOrdinal = null,
			expectedCurrentOrdinal = expectedCursor,
			throughOrdinal = throughOrdinal,
			updatedAtMs = 2_000L,
		) shouldBe 1
	}

	private suspend fun retireLane(expectedCursor: Long) {
		database.sourceProjectionStateDao().retireProductLane(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			bindingGeneration = BINDING_GENERATION,
			projectionId = WRITER_ID,
			projectionVersion = WRITER_VERSION,
			expectedCurrentOrdinal = expectedCursor,
			updatedAtMs = 2L,
		) shouldBe 1
	}

	private fun fact(
		ordinal: Long,
		coverage: String,
		steps: Long,
		cumulativeStart: Long = 100L,
		cumulativeEnd: Long? = null,
	) = StepFactRevisionEntity(
		logicalFactId = "fact-$ordinal",
		semanticRevision = 1L,
		mutationId = "mutation-$ordinal",
		stepIntervalId = null,
		sourceEventId = "event-$ordinal",
		sourceAdmissionOrdinal = ordinal,
		originKind = StepFactRevisionEntity.ORIGIN_LIVE_WAL,
		originIdentity = "event-$ordinal",
		writerProjectionId = WRITER_ID,
		writerProjectionVersion = WRITER_VERSION,
		writerBindingGeneration = BINDING_GENERATION,
		operation = StepFactRevisionEntity.OPERATION_UPSERT,
		intervalStartTimeMs = 1_000L + ordinal,
		intervalEndTimeMs = 1_100L + ordinal,
		intervalStartElapsedRealtimeNanos = 1_000L + ordinal,
		intervalEndElapsedRealtimeNanos = 1_100L + ordinal,
		clockDomainId = "boot-1",
		bootClockDomainId = "boot-1",
		cumulativeStepCountStart = cumulativeStart,
		cumulativeStepCountEnd = cumulativeEnd ?: if (
			coverage == StepFactRevisionEntity.COVERAGE_RESET_GAP
		) {
			(cumulativeStart - 1L).coerceAtLeast(0L)
		} else {
			Math.addExact(cumulativeStart, steps)
		},
		wallTimeUncertaintyMs = 0L,
		coverageKind = coverage,
		effectiveStepCount = steps,
		logicalTrackingId = LOGICAL_ID,
		serviceRunId = RUN_ONE,
		purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
		manifestRevision = 1L,
		sourcePolicyRevision = 1L,
		captureConsentEpoch = 1L,
		collectedDataEpoch = 0L,
		scopeDeletionGeneration = 0L,
		effectChecksum = "checksum-$ordinal",
		appliedAtMs = 2_000L,
	)

	private fun retraction(
		fact: StepFactRevisionEntity,
		collectedDataEpoch: Long = fact.collectedDataEpoch,
	) = fact.copy(
		semanticRevision = fact.semanticRevision + 1L,
		mutationId = "delete-${fact.logicalFactId}",
		stepIntervalId = null,
		sourceEventId = null,
		sourceAdmissionOrdinal = null,
		originKind = StepFactRevisionEntity.ORIGIN_LOCAL_DELETE,
		originIdentity = "delete-request-${fact.logicalFactId}",
		operation = StepFactRevisionEntity.OPERATION_RETRACT,
		intervalStartTimeMs = null,
		intervalEndTimeMs = null,
		intervalStartElapsedRealtimeNanos = null,
		intervalEndElapsedRealtimeNanos = null,
		clockDomainId = null,
		bootClockDomainId = null,
		cumulativeStepCountStart = null,
		cumulativeStepCountEnd = null,
		wallTimeUncertaintyMs = null,
		coverageKind = null,
		effectiveStepCount = null,
		logicalTrackingId = null,
		serviceRunId = null,
		manifestRevision = null,
		sourcePolicyRevision = null,
		captureConsentEpoch = null,
		collectedDataEpoch = collectedDataEpoch,
		scopeDeletionGeneration = 1L,
		effectChecksum = "delete-checksum-${fact.logicalFactId}",
		appliedAtMs = 3_000L,
	)

	private fun owner(owner: String, generation: Long) = SourceDestinationOwnerEntity(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
		owner = owner,
		ownerGeneration = generation,
		updatedAtMs = 1L,
	)

	private companion object {
		const val LOGICAL_ID = "logical-1"
		const val RUN_ONE = "run-1"
		const val SOURCE_LOCATION = 0
		const val WRITER_ID = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID
		const val WRITER_VERSION = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION
		const val BINDING_GENERATION = SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION
		const val LEGACY_OWNER = SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL
		const val CANDIDATE_OWNER = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS
		const val COMPLETE_PROVIDER_COVERAGE = "CALLBACKS_ENTERED_BEFORE_BARRIER"
	}
}
