package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.stats.api.repository.HistoryCapture
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.SessionHistoryQuery
import com.adsamcik.tracker.stats.api.repository.StepsAwareHistoryPageEntry
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCause
import com.adsamcik.tracker.stats.api.repository.StepsOnlyHistoryListState
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.first
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
	private lateinit var logicalHistoryReader: LogicalTrackingHistoryReader

	@Before
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState())
		selector = StepsSegmentHistorySelector(database, executableLaneAuthority())
		logicalHistoryReader = LogicalTrackingHistoryReader(database, selector)
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
	fun newV28RunRequiresItsExactPresentationSegmentBinding() = runTest {
		insertRun(RUN_ONE, sessionSegmentId = null)
		database.sourceDeletionFenceDao().upsert(
			SourceDeletionFenceEntity.createLogicalServiceRun(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
				logicalTrackingId = LOGICAL_ID,
				serviceRunId = RUN_ONE,
				fenceGeneration = 1L,
				collectedDataEpoch = 0L,
				deletedAtMs = 3_000L,
			),
		)

		selector.select(segment(RUN_ONE, steps = 14)) shouldBe StepsSegmentHistoryResult(
			count = null,
			availability = StepsHistoryAvailability.UNAVAILABLE,
			evidence = StepsHistoryEvidence.NO_OBSERVATION,
			materialization = StepsHistoryMaterialization.FAILED,
			coverage = StepsHistoryCoverage.UNKNOWN,
			reasons = setOf(StepsHistoryReason.SERVICE_RUN_SEGMENT_BINDING_MISMATCH),
		)

		val run = requireNotNull(database.sourceSessionDao().serviceRun(RUN_ONE))
		database.sourceSessionDao().updateServiceRun(
			run.copy(sessionSegmentId = OTHER_SEGMENT_ID),
		) shouldBe 1
		selector.select(segment(RUN_ONE, steps = 14)).reasons shouldBe
			setOf(StepsHistoryReason.SERVICE_RUN_SEGMENT_BINDING_MISMATCH)
	}

	@Test
	fun migratedRunWithUnverifiablePresentationOwnershipRemainsTypedAndBlocked() = runTest {
		insertRun(
			runId = RUN_ONE,
			sessionSegmentId = null,
			presentationAcknowledgement =
				SourceServiceRunEntity.PRESENTATION_LEGACY_UNVERIFIABLE,
		)

		selector.select(segment(RUN_ONE, steps = 14)) shouldBe StepsSegmentHistoryResult(
			count = null,
			availability = StepsHistoryAvailability.UNAVAILABLE,
			evidence = StepsHistoryEvidence.NO_OBSERVATION,
			materialization = StepsHistoryMaterialization.FAILED,
			coverage = StepsHistoryCoverage.UNKNOWN,
			reasons = setOf(StepsHistoryReason.SERVICE_RUN_SEGMENT_BINDING_UNVERIFIABLE),
		)
		database.sessionSegmentDao().insert(segment(RUN_ONE, steps = 14, sampleCount = 1))
		val migrated = selectRecentEvidence(limit = 1).single()
		migrated.captureAuthority shouldBe HistoricalCaptureAuthority.Unverifiable(
			HistoricalCaptureFailure.SERVICE_RUN_SEGMENT_BINDING_UNVERIFIABLE,
		)
		migrated.qualifiedSources shouldBe emptySet()
		val publicHistory = DefaultTrackingHistoryRepository(
			database,
			selector,
			logicalHistoryReader,
			Dispatchers.IO,
		).observeSession(SEGMENT_ID).first() as
			com.adsamcik.tracker.stats.api.repository.SessionHistoryQuery.Found
		publicHistory.history.capture shouldBe HistoryCapture.Unverifiable
		publicHistory.history.qualifiedSources shouldBe emptySet()
		publicHistory.history.steps.causes shouldBe setOf(
			com.adsamcik.tracker.stats.api.repository.StepsHistoryCause.LEGACY_UNVERIFIED,
			com.adsamcik.tracker.stats.api.repository.StepsHistoryCause.AVAILABILITY_UNAVAILABLE,
		)
	}

	@Test
	fun exactQuiescedBindingRemainsReadableWithoutBecomingAProductPredicate() = runTest {
		insertRun(
			runId = RUN_ONE,
			presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_QUIESCED,
			presentationAcknowledgedAtMs = 2_100L,
		)
		insertManifest(RUN_ONE, revision = 1L, owner = LEGACY_OWNER)

		selector.select(segment(RUN_ONE, steps = 14)) shouldBe StepsSegmentHistoryResult(
			count = 14L,
			availability = StepsHistoryAvailability.AVAILABLE,
			evidence = StepsHistoryEvidence.LEGACY_RECORDED,
			materialization = StepsHistoryMaterialization.DEGRADED,
			coverage = StepsHistoryCoverage.UNKNOWN,
			reasons = setOf(StepsHistoryReason.LEGACY_REPLAY_UNVERIFIED),
		)
		database.sessionSegmentDao().insert(segment(RUN_ONE, steps = 14, sampleCount = 0))
		selectRecentEvidence(limit = 1).single().segment.id shouldBe SEGMENT_ID
	}

	@Test
	fun zeroSampleCoveredStepsCandidateIsOrdinarilyDiscoverableWithExactCaptureAuthority() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)),
			targetOrdinal = 1L,
			laneCursor = 1L,
		)
		database.sessionSegmentDao().insert(segment(RUN_ONE, steps = null, sampleCount = 0))

		val evidence = selectRecentEvidence(limit = 10).single()
		evidence.segment.id shouldBe SEGMENT_ID
		evidence.steps.evidence shouldBe StepsHistoryEvidence.RECORDED
		evidence.steps.count shouldBe 5L
		evidence.qualifiedSources shouldBe setOf(TrackingSourceComponent.STEPS)
		val capture = evidence.captureAuthority as HistoricalCaptureAuthority.Exact
		capture.revisions.size shouldBe 1
		capture.capturedInAnyRevision shouldBe setOf(TrackingSourceComponent.STEPS)
		capture.capturedForWholeRun shouldBe setOf(TrackingSourceComponent.STEPS)
		capture.revisions.single().controlSources shouldBe emptySet()
	}

	@Test
	fun corruptedLiveWalFactFailsClosedAcrossSelectorAndPublicHistoryProducts() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)),
			targetOrdinal = 1L,
			laneCursor = 1L,
		)
		database.sessionSegmentDao().insert(segment(RUN_ONE, steps = null, sampleCount = 0))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE step_fact_revision SET effect_checksum = 'tampered' WHERE service_run_id = ?",
			arrayOf(RUN_ONE),
		)

		selector.select(segment(RUN_ONE, steps = null, sampleCount = 0)) shouldBe
			StepsSegmentHistoryResult(
				count = null,
				availability = StepsHistoryAvailability.AVAILABLE,
				evidence = StepsHistoryEvidence.NO_OBSERVATION,
				materialization = StepsHistoryMaterialization.FAILED,
				coverage = StepsHistoryCoverage.UNKNOWN,
				reasons = setOf(StepsHistoryReason.STEP_FACT_INTEGRITY_FAILED),
			)

		val repository = historyRepository()
		val publicHistory = repository.observeSession(SEGMENT_ID).first() as SessionHistoryQuery.Found
		publicHistory.history.qualifiedSources shouldBe emptySet()
		publicHistory.history.steps.count shouldBe null
		publicHistory.history.steps.productState shouldBe HistoryProductState.FAILED
		publicHistory.history.steps.causes shouldBe setOf(
			StepsHistoryCause.HISTORY_INTEGRITY_FAILED,
		)
		repository.observeRecentStepsOnlyEntries(limit = 10).first() shouldBe emptyList()
		repository.observeRecentStepsAwarePage(
			candidateSegmentIds = listOf(SEGMENT_ID),
			limit = 10,
		).first() shouldBe emptyList()
	}

	@Test
	fun checksumValidCoveredFactsWithAnImpossibleRunTimelineFailClosed() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(
				fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L),
				fact(
					ordinal = 2L,
					coverage = StepFactRevisionEntity.COVERAGE_COVERED,
					steps = 6L,
					cumulativeStart = 500L,
				),
			),
			targetOrdinal = 2L,
			laneCursor = 2L,
			factTransform = { fact ->
				val startElapsed = if (fact.sourceAdmissionOrdinal == 1L) {
					TEST_MANIFEST_ELAPSED_STRIDE + 1L
				} else {
					TEST_MANIFEST_ELAPSED_STRIDE + 51L
				}
				val transformed = fact.copy(
					intervalStartElapsedRealtimeNanos = startElapsed,
					intervalEndElapsedRealtimeNanos = startElapsed + 100L,
				).withValidLiveWalEffectChecksum()
				StepFactRevisionIntegrity.hasValidCanonicalLiveWalFact(transformed) shouldBe true
				transformed
			},
		)

		selector.select(segment(RUN_ONE, steps = null, sampleCount = 0)) shouldBe
			failedFactIntegrityResult()
	}

	@Test
	fun checksumValidBackwardAuthorizationBaselineFailsClosed() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(
				fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L),
				fact(
					ordinal = 2L,
					coverage = StepFactRevisionEntity.COVERAGE_BASELINE,
					steps = 0L,
					cumulativeStart = 500L,
					cumulativeEnd = 500L,
				),
			),
			targetOrdinal = 2L,
			laneCursor = 2L,
			factTransform = { fact ->
				if (fact.sourceAdmissionOrdinal == 2L) {
					fact.copy(
						intervalStartElapsedRealtimeNanos = TEST_MANIFEST_ELAPSED_STRIDE + 1L,
						intervalEndElapsedRealtimeNanos = TEST_MANIFEST_ELAPSED_STRIDE + 1L,
					).withValidLiveWalEffectChecksum()
				} else {
					fact
				}
			},
		)

		selector.select(segment(RUN_ONE, steps = null, sampleCount = 0)) shouldBe
			failedFactIntegrityResult()
	}

	@Test
	fun portableShapedOriginTamperCannotBypassNativeFactIntegrity() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)),
			targetOrdinal = 1L,
			laneCursor = 1L,
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE step_fact_revision SET origin_kind = 'PORTABLE_IMPORT', " +
				"source_event_id = NULL, source_admission_ordinal = NULL WHERE service_run_id = ?",
			arrayOf(RUN_ONE),
		)

		selector.select(segment(RUN_ONE, steps = null, sampleCount = 0)).reasons shouldBe
			setOf(StepsHistoryReason.STEP_FACT_INTEGRITY_FAILED)
	}

	@Test
	fun checksumCoveredAttributionTamperFailsBeforeFactFiltering() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)),
			targetOrdinal = 1L,
			laneCursor = 1L,
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE step_fact_revision SET logical_tracking_id = 'other-logical' " +
				"WHERE service_run_id = ?",
			arrayOf(RUN_ONE),
		)

		selector.select(segment(RUN_ONE, steps = null, sampleCount = 0)).reasons shouldBe
			setOf(StepsHistoryReason.STEP_FACT_INTEGRITY_FAILED)
	}

	@Test
	fun checksumCoveredRunAndPurposeTamperCannotHideBesideAValidFact() = runTest {
		val hidden = fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 4L)
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(
				hidden,
				fact(2L, StepFactRevisionEntity.COVERAGE_COVERED, 6L),
			),
			targetOrdinal = 2L,
			laneCursor = 2L,
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE step_fact_revision SET service_run_id = 'tampered-run', purpose = 'CONTROL' " +
				"WHERE logical_fact_id = ? AND operation = 'UPSERT'",
			arrayOf(hidden.logicalFactId),
		)

		selector.select(segment(RUN_ONE, steps = null, sampleCount = 0)) shouldBe
			failedFactIntegrityResult()
	}

	@Test
	fun retargetToExistingReplacementRunCannotHideCorruptFactFromSelectedRun() = runTest {
		insertRun(RUN_ONE, sessionSegmentId = SEGMENT_ID)
		insertRun(RUN_TWO, sessionSegmentId = OTHER_SEGMENT_ID)
		insertManifest(RUN_ONE, revision = 1L, owner = CANDIDATE_OWNER)
		insertManifest(RUN_TWO, revision = 2L, owner = CANDIDATE_OWNER)
		database.sourceProjectionStateDao().installProductLane(lane(cursor = 2L))
		database.sourceSessionDao().saveCompleteness(
			completeness(RUN_ONE, lastOrdinal = 2L, registrationGeneration = 1L),
		)
		val hidden = fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 4L)
		val survivor = fact(2L, StepFactRevisionEntity.COVERAGE_COVERED, 6L)
		database.stepFactRevisionDao().insert(hidden)
		database.stepFactRevisionDao().insert(survivor)
		database.sessionSegmentDao().insert(segment(RUN_ONE, steps = null, sampleCount = 0))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE step_fact_revision SET service_run_id = ? " +
				"WHERE logical_fact_id = ? AND operation = 'UPSERT'",
			arrayOf(RUN_TWO, hidden.logicalFactId),
		)

		selector.select(segment(RUN_ONE, steps = null, sampleCount = 0)) shouldBe
			failedFactIntegrityResult()
		val publicHistory = historyRepository().observeSession(SEGMENT_ID).first() as
			SessionHistoryQuery.Found
		publicHistory.history.steps.count shouldBe null
		publicHistory.history.steps.productState shouldBe HistoryProductState.FAILED
		publicHistory.history.steps.causes shouldBe setOf(
			StepsHistoryCause.HISTORY_INTEGRITY_FAILED,
		)
	}

	@Test
	fun checksumCoveredRunAndLogicalMembershipTamperIsFoundByOrdinalAnchor() = runTest {
		val hidden = fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 4L)
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(
				hidden,
				fact(2L, StepFactRevisionEntity.COVERAGE_COVERED, 6L),
			),
			targetOrdinal = 2L,
			laneCursor = 2L,
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE step_fact_revision SET service_run_id = 'tampered-run', " +
				"logical_tracking_id = 'tampered-logical' " +
				"WHERE logical_fact_id = ? AND operation = 'UPSERT'",
			arrayOf(hidden.logicalFactId),
		)

		selector.select(segment(RUN_ONE, steps = null, sampleCount = 0)) shouldBe
			failedFactIntegrityResult()
	}

	@Test
	fun checksumValidForeignWriterAndManifestCannotHideBesideSelectedRunFact() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 4L)),
			targetOrdinal = 2L,
			laneCursor = 2L,
		)
		val foreign = fact(2L, StepFactRevisionEntity.COVERAGE_COVERED, 6L).copy(
			writerProjectionId = "foreign-steps-writer",
			writerProjectionVersion = 7,
			writerBindingGeneration = BINDING_GENERATION + 5L,
			manifestRevision = 99L,
			sourcePolicyRevision = 99L,
		).withSourceEventIdentity("foreign-writer-event").withValidLiveWalEffectChecksum()
		database.stepFactRevisionDao().insert(foreign)

		selector.select(segment(RUN_ONE, steps = null, sampleCount = 0)) shouldBe
			failedFactIntegrityResult()
	}

	@Test
	fun checksumValidWrongPolicyRevisionCannotBecomeNumericHistory() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)),
			targetOrdinal = 1L,
			laneCursor = 1L,
			factTransform = { fact -> fact.copy(sourcePolicyRevision = 2L) },
		)

		selector.select(segment(RUN_ONE, steps = null, sampleCount = 0)) shouldBe
			failedFactIntegrityResult()
	}

	@Test
	fun checksumValidWrongRunCannotDisappearBeforeMembershipValidation() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)),
			targetOrdinal = 1L,
			laneCursor = 1L,
			factTransform = { fact -> fact.copy(serviceRunId = "foreign-run") },
		)

		selector.select(segment(RUN_ONE, steps = null, sampleCount = 0)) shouldBe
			failedFactIntegrityResult()
	}

	@Test
	fun checksumValidWrongCaptureConsentCannotBecomeNumericHistory() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)),
			targetOrdinal = 1L,
			laneCursor = 1L,
			factTransform = { fact -> fact.copy(captureConsentEpoch = 2L) },
		)

		selector.select(segment(RUN_ONE, steps = null, sampleCount = 0)) shouldBe
			failedFactIntegrityResult()
	}

	@Test
	fun checksumValidImpossibleCoveredDeltaCannotBecomeNumericHistory() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)),
			targetOrdinal = 1L,
			laneCursor = 1L,
			factTransform = { fact ->
				fact.copy(
					cumulativeStepCountStart = 100L,
					cumulativeStepCountEnd = 101L,
					effectiveStepCount = 999L,
				)
			},
		)

		selector.select(segment(RUN_ONE, steps = null, sampleCount = 0)) shouldBe
			failedFactIntegrityResult()
	}

	@Test
	fun checksumValidFactCannotHideBehindControlOnlyStepsManifest() = runTest {
		insertRun(RUN_ONE, sessionSegmentId = SEGMENT_ID)
		insertExactManifest(
			runId = RUN_ONE,
			revision = 1L,
			sources = listOf(
				sourceMembership(
					revision = 1L,
					source = TrackingSourceComponent.LOCATION,
					purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				),
				sourceMembership(
					revision = 1L,
					source = TrackingSourceComponent.STEPS,
					purpose = SessionManifestPurposeCode.CONTROL,
				),
			),
		)
		database.sourceProjectionStateDao().installProductLane(lane(cursor = 1L))
		database.sourceSessionDao().saveCompleteness(completeness(RUN_ONE, lastOrdinal = 1L))
		val strayCaptureFact = fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)
			.withTestAttribution(LOGICAL_ID, RUN_ONE, manifestRevision = 1L)
			.withValidLiveWalEffectChecksum()
		database.stepFactRevisionDao().insert(strayCaptureFact)

		selector.select(segment(RUN_ONE, steps = null, sampleCount = 0)) shouldBe
			failedFactIntegrityResult()
	}

	@Test
	fun checksumCoveredOperationTamperCannotHideBesideAValidFact() = runTest {
		val hidden = fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 4L)
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(
				hidden,
				fact(2L, StepFactRevisionEntity.COVERAGE_COVERED, 6L),
			),
			targetOrdinal = 2L,
			laneCursor = 2L,
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE step_fact_revision SET operation = 'RETRACT' " +
				"WHERE logical_fact_id = ? AND semantic_revision = 1",
			arrayOf(hidden.logicalFactId),
		)

		selector.select(segment(RUN_ONE, steps = null, sampleCount = 0)) shouldBe
			failedFactIntegrityResult()
	}

	@Test
	fun movedCorrectionOperationTamperRemainsAnchoredToItsLatestProvisionalRun() = runTest {
		insertRun(RUN_ONE, sessionSegmentId = SEGMENT_ID)
		insertRun(RUN_TWO, sessionSegmentId = OTHER_SEGMENT_ID)
		insertManifest(RUN_ONE, revision = 1L, owner = CANDIDATE_OWNER)
		insertManifest(RUN_TWO, revision = 2L, owner = CANDIDATE_OWNER)
		database.sourceProjectionStateDao().installProductLane(lane(cursor = 3L))
		database.sourceSessionDao().saveCompleteness(
			completeness(RUN_ONE, lastOrdinal = 1L, registrationGeneration = 1L),
		)
		database.sourceSessionDao().saveCompleteness(
			completeness(RUN_TWO, lastOrdinal = 3L, registrationGeneration = 2L),
		)
		val original = fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 4L)
			.withSourceEventIdentity("moved-operation-event")
			.withTestAttribution(LOGICAL_ID, RUN_ONE, manifestRevision = 1L)
			.withValidLiveWalEffectChecksum()
		val moved = original.copy(
			semanticRevision = 2L,
			mutationId = "${original.logicalFactId}:2:${StepFactRevisionEntity.OPERATION_UPSERT}",
			sourceAdmissionOrdinal = 2L,
		).withTestAttribution(LOGICAL_ID, RUN_TWO, manifestRevision = 2L)
			.withValidLiveWalEffectChecksum()
		val runTwoSurvivor = fact(3L, StepFactRevisionEntity.COVERAGE_COVERED, 6L)
			.withTestAttribution(LOGICAL_ID, RUN_TWO, manifestRevision = 2L)
			.withValidLiveWalEffectChecksum()
		listOf(original, moved, runTwoSurvivor).forEach {
			database.stepFactRevisionDao().insert(it)
		}
		database.openHelper.writableDatabase.execSQL(
			"UPDATE step_fact_revision SET operation = 'RETRACT' " +
				"WHERE logical_fact_id = ? AND semantic_revision = 2",
			arrayOf(original.logicalFactId),
		)

		selector.select(
			segment(
				runId = RUN_TWO,
				steps = null,
				id = OTHER_SEGMENT_ID,
				startTimeMs = 2_100L,
				endTimeMs = 3_000L,
			),
		) shouldBe failedFactIntegrityResult()
	}

	@Test
	fun corruptedScopeCarrierBelowRetractionCannotHideBesideAValidFact() = runTest {
		val deleted = fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 4L)
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(
				deleted,
				fact(2L, StepFactRevisionEntity.COVERAGE_COVERED, 6L),
			),
			targetOrdinal = 2L,
			laneCursor = 2L,
		)
		database.stepFactRevisionDao().insert(retraction(deleted))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE step_fact_revision SET effect_checksum = 'tampered-scope' " +
				"WHERE logical_fact_id = ? AND operation = 'UPSERT'",
			arrayOf(deleted.logicalFactId),
		)

		selector.select(segment(RUN_ONE, steps = null, sampleCount = 0)) shouldBe
			failedFactIntegrityResult()
	}

	@Test
	fun selfConsistentRetractionForAnotherScopeCannotDeleteSelectedFact() = runTest {
		val deleted = fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 4L)
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(
				deleted,
				fact(2L, StepFactRevisionEntity.COVERAGE_COVERED, 6L),
			),
			targetOrdinal = 2L,
			laneCursor = 2L,
		)
		val otherScope = SourceDeletionFenceEntity.logicalServiceRunIdentity(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			logicalTrackingId = "other-logical",
			serviceRunId = "other-run",
		)
		val otherScopeRetraction = retraction(deleted).let { canonical ->
			val unsigned = canonical.copy(
				originIdentity = otherScope,
				mutationId = StepFactRevisionIntegrity.localDeleteMutationId(
					scopeIdentityDigest = otherScope,
					logicalFactId = canonical.logicalFactId,
					semanticRevision = canonical.semanticRevision,
					scopeDeletionGeneration = canonical.scopeDeletionGeneration,
				),
				effectChecksum = "pending-other-scope-effect",
			)
			unsigned.copy(
				effectChecksum = StepFactRevisionIntegrity.localDeleteEffectChecksum(unsigned),
			)
		}
		database.stepFactRevisionDao().insert(otherScopeRetraction)

		selector.select(segment(RUN_ONE, steps = null, sampleCount = 0)) shouldBe
			failedFactIntegrityResult()
	}

	@Test
	fun malformedLatestRetractionsCannotHideBesideAValidFact() = runTest {
		val zeroGeneration = fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 2L)
		val nonRedacted = fact(2L, StepFactRevisionEntity.COVERAGE_COVERED, 3L)
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(
				zeroGeneration,
				nonRedacted,
				fact(3L, StepFactRevisionEntity.COVERAGE_COVERED, 5L),
			),
			targetOrdinal = 3L,
			laneCursor = 3L,
		)
		database.stepFactRevisionDao().insert(retraction(zeroGeneration))
		database.stepFactRevisionDao().insert(retraction(nonRedacted))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE step_fact_revision SET scope_deletion_generation = 0 " +
				"WHERE logical_fact_id = ? AND operation = 'RETRACT'",
			arrayOf(zeroGeneration.logicalFactId),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE step_fact_revision SET effective_step_count = 3 " +
				"WHERE logical_fact_id = ? AND operation = 'RETRACT'",
			arrayOf(nonRedacted.logicalFactId),
		)

		selector.select(segment(RUN_ONE, steps = null, sampleCount = 0)) shouldBe
			failedFactIntegrityResult()
	}

	@Test
	fun zeroSampleCoveredZeroIsDiscoverableButNoncoveredFactsAreNot() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 0L)),
			targetOrdinal = 1L,
			laneCursor = 1L,
		)
		database.sessionSegmentDao().insert(segment(RUN_ONE, steps = null, sampleCount = 0))

		selectRecentEvidence(limit = 10).single().steps.evidence shouldBe
			StepsHistoryEvidence.COVERED_ZERO
	}

	@Test
	fun zeroSampleRetractedOrRunFencedStepsAreNotOrdinaryCandidates() = runTest {
		val original = fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(original),
			targetOrdinal = 1L,
			laneCursor = 1L,
		)
		database.sessionSegmentDao().insert(segment(RUN_ONE, steps = null, sampleCount = 0))
		database.stepFactRevisionDao().insert(retraction(original))

		selectRecentEvidence(limit = 10) shouldBe emptyList()

		// Removing a retraction is intentionally unsupported; use the still-exact bound row to prove
		// that a durable run fence also suppresses ordinary discovery independently of sampleCount.
		database.stepFactRevisionDao().deleteAll()
		database.stepFactRevisionDao().insert(original)
		database.sourceDeletionFenceDao().upsert(
			SourceDeletionFenceEntity.createLogicalServiceRun(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
				logicalTrackingId = LOGICAL_ID,
				serviceRunId = RUN_ONE,
				fenceGeneration = 1L,
				collectedDataEpoch = 0L,
				deletedAtMs = 3_000L,
			),
		)

		selectRecentEvidence(limit = 10) shouldBe emptyList()
	}

	@Test
	fun zeroSampleBaselinePartialAndResetFactsDoNotEnterRecentCandidates() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(
				fact(1L, StepFactRevisionEntity.COVERAGE_BASELINE, 0L),
				fact(2L, StepFactRevisionEntity.COVERAGE_PARTIAL, 3L),
				fact(3L, StepFactRevisionEntity.COVERAGE_RESET_GAP, 0L),
			),
			targetOrdinal = 3L,
			laneCursor = 3L,
		)
		database.sessionSegmentDao().insert(segment(RUN_ONE, steps = null, sampleCount = 0))

		selectRecentEvidence(limit = 10) shouldBe emptyList()
	}

	@Test
	fun zeroSampleStaleEpochCoveredFactDoesNotQualifyRecentHistory() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)),
			targetOrdinal = 1L,
			laneCursor = 1L,
		)
		database.sessionSegmentDao().insert(segment(RUN_ONE, steps = null, sampleCount = 0))
		database.sourceEvidenceStateDao().updateLifecycle(
			epoch = 1L,
			retainedFromMs = null,
			updatedAtMs = 3_000L,
		) shouldBe 1

		selectRecentEvidence(limit = 10) shouldBe emptyList()
	}

	@Test
	fun positiveSampleLegacyCompatibilityRowQualifiesNoCaptureSource() = runTest {
		database.sessionSegmentDao().insert(
			segment(
				runId = null,
				logicalId = null,
				steps = null,
				sampleCount = 1,
			),
		)

		val evidence = selectRecentEvidence(limit = 10).single()
		evidence.captureAuthority shouldBe HistoricalCaptureAuthority.Unverifiable(
			HistoricalCaptureFailure.LEGACY_UNATTRIBUTED,
		)
		evidence.steps.evidence shouldBe StepsHistoryEvidence.NO_OBSERVATION
		evidence.qualifiedSources shouldBe emptySet()
	}

	@Test
	fun attributedPositiveSampleControlOnlyStepsQualifiesNoCapturedSource() = runTest {
		insertRun(RUN_ONE)
		insertExactManifest(
			runId = RUN_ONE,
			revision = 1L,
			sources = listOf(
				sourceMembership(
					revision = 1L,
					source = TrackingSourceComponent.LOCATION,
					purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				),
				sourceMembership(
					revision = 1L,
					source = TrackingSourceComponent.STEPS,
					purpose = SessionManifestPurposeCode.CONTROL,
				),
			),
		)
		val segment = segment(RUN_ONE, steps = 42, sampleCount = 1)
		database.sessionSegmentDao().insert(segment)

		val evidence = selector.selectEvidence(segment)
		val capture = evidence.captureAuthority as HistoricalCaptureAuthority.Exact
		capture.capturedInAnyRevision shouldBe setOf(TrackingSourceComponent.LOCATION)
		capture.revisions.single().controlSources shouldBe setOf(TrackingSourceComponent.STEPS)
		evidence.steps.count shouldBe null
		evidence.steps.reasons shouldBe setOf(StepsHistoryReason.SOURCE_NOT_CAPTURED)
		evidence.qualifiedSources shouldBe emptySet()
		selectRecentEvidence(limit = 10) shouldBe emptyList()
	}

	@Test
	fun attributedPositiveSampleFenceCannotResurrectDeletedSteps() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)),
			targetOrdinal = 1L,
			laneCursor = 1L,
		)
		val segment = segment(RUN_ONE, steps = null, sampleCount = 1)
		database.sessionSegmentDao().insert(segment)
		database.sourceDeletionFenceDao().upsert(
			SourceDeletionFenceEntity.createLogicalServiceRun(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
				logicalTrackingId = LOGICAL_ID,
				serviceRunId = RUN_ONE,
				fenceGeneration = 1L,
				collectedDataEpoch = 0L,
				deletedAtMs = 3_000L,
			),
		)

		val evidence = selector.selectEvidence(segment)
		evidence.steps.availability shouldBe StepsHistoryAvailability.DELETED
		evidence.qualifiedSources shouldBe emptySet()
		selectRecentEvidence(limit = 10) shouldBe emptyList()
	}

	@Test
	fun keysetPagingFindsOlderValidStepsAfterMoreThanOneBatchOfRejectedRows() = runTest {
		database.sourceProjectionStateDao().installProductLane(lane(cursor = 66L))
		for (index in 1L..66L) {
			val logicalId = "logical-$index"
			val runId = "run-$index"
			val segmentId = 1_000L + index
			insertRun(runId = runId, logicalId = logicalId, sessionSegmentId = segmentId)
			insertManifest(
				runId = runId,
				logicalId = logicalId,
				revision = 1L,
				owner = CANDIDATE_OWNER,
			)
			database.sourceSessionDao().saveCompleteness(
				completeness(runId = runId, logicalId = logicalId, lastOrdinal = index),
			)
			database.stepFactRevisionDao().insert(
				fact(index, StepFactRevisionEntity.COVERAGE_COVERED, index)
					.withTestAttribution(logicalId, runId, manifestRevision = 1L)
					.withValidLiveWalEffectChecksum(),
			)
			database.sessionSegmentDao().insert(
				segment(
					runId = runId,
					logicalId = logicalId,
					steps = null,
					sampleCount = 0,
					id = segmentId,
				),
			)
			if (index > 1L) {
				database.sourceDeletionFenceDao().upsert(
					SourceDeletionFenceEntity.createLogicalServiceRun(
						sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
						purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
						logicalTrackingId = logicalId,
						serviceRunId = runId,
						fenceGeneration = 1L,
						collectedDataEpoch = 0L,
						deletedAtMs = 3_000L,
					),
				)
			}
		}

		selectRecentEvidence(limit = 1).map { it.segment.id } shouldBe listOf(1_001L)
	}

	@Test
	@Suppress("LongMethod")
	fun replacementRunsAreGroupedBeforeLimitAcrossPhysicalBatchBoundary() = runTest {
		val logicalA = "logical-a"
		val logicalB = "logical-b"
		database.sourceProjectionStateDao().installProductLane(lane(cursor = 66L))
		for (index in 1L..65L) {
			val runId = "run-a-$index"
			val segmentId = 2_000L + index
			insertRun(runId, logicalA, sessionSegmentId = segmentId)
			insertManifest(runId, logicalA, revision = index, owner = CANDIDATE_OWNER)
			database.sourceSessionDao().saveCompleteness(
				completeness(runId, logicalA, lastOrdinal = index),
			)
			database.stepFactRevisionDao().insert(
				fact(index, StepFactRevisionEntity.COVERAGE_COVERED, 1L)
					.withTestAttribution(logicalA, runId, manifestRevision = index)
					.withValidLiveWalEffectChecksum(),
			)
			database.sessionSegmentDao().insert(
				segment(
					runId = runId,
					logicalId = logicalA,
					steps = null,
					sampleCount = 0,
					id = segmentId,
					startTimeMs = 5_000L + index,
					endTimeMs = 5_100L + index,
				),
			)
		}
		val runB = "run-b"
		val segmentB = 3_000L
		insertRun(runB, logicalB, sessionSegmentId = segmentB)
		insertManifest(runB, logicalB, revision = 1L, owner = CANDIDATE_OWNER)
		database.sourceSessionDao().saveCompleteness(
			completeness(runB, logicalB, lastOrdinal = 66L),
		)
		database.stepFactRevisionDao().insert(
			fact(66L, StepFactRevisionEntity.COVERAGE_COVERED, 2L)
				.withTestAttribution(logicalB, runB, manifestRevision = 1L)
				.withValidLiveWalEffectChecksum(),
		)
		database.sessionSegmentDao().insert(
			segment(
				runId = runB,
				logicalId = logicalB,
				steps = null,
				sampleCount = 0,
				id = segmentB,
				startTimeMs = 1_000L,
				endTimeMs = 1_100L,
			),
		)

		val first = logicalHistoryReader.selectRecentEntries(limit = 1).single()
		first.identity shouldBe HistoricalEntryIdentity.Logical(logicalA)
		first.physicalMembers.size shouldBe 65
		first.physicalMembers.map { it.segment.id } shouldBe (2_001L..2_065L).toList()
		first.physicalMembers.map { it.segment.serviceRunId }.toSet().size shouldBe 65
		first.physicalMembers.forEachIndexed { index, member ->
			val expectedRevision = index + 1L
			val capture = member.captureAuthority as HistoricalCaptureAuthority.Exact
			capture.revisions.map { it.manifestRevision } shouldBe listOf(expectedRevision)
			member.steps.count shouldBe 1L
			member.steps.evidence shouldBe StepsHistoryEvidence.RECORDED
			member.steps.materialization shouldBe StepsHistoryMaterialization.READY
			member.steps.coverage shouldBe StepsHistoryCoverage.COMPLETE
			member.qualifiedSources shouldBe setOf(TrackingSourceComponent.STEPS)
		}
		first.qualifiedSources shouldBe setOf(TrackingSourceComponent.STEPS)

		val twoEntries = logicalHistoryReader.selectRecentEntries(limit = 2)
		twoEntries.map { it.identity } shouldBe listOf(
			HistoricalEntryIdentity.Logical(logicalA),
			HistoricalEntryIdentity.Logical(logicalB),
		)
		twoEntries.first().physicalMembers.size shouldBe 65
		twoEntries.last().physicalMembers.single().segment.id shouldBe segmentB
	}

	@Test
	@Suppress("LongMethod")
	fun equalLatestTimesUseTheLatestSeedsIdInsteadOfAnOlderSiblingsId() = runTest {
		val logicalA = "logical-a"
		val logicalB = "logical-b"
		database.sourceProjectionStateDao().installProductLane(lane(cursor = 3L))
		val runs = listOf(
			HistoryRunFixture("run-a-old", logicalA, 1L, 100L, 1_000L, 1L),
			HistoryRunFixture("run-a-latest", logicalA, 2L, 10L, 3_000L, 2L),
			HistoryRunFixture("run-b-latest", logicalB, 1L, 50L, 3_000L, 3L),
		)
		for (fixture in runs) {
			insertRun(
				fixture.runId,
				fixture.logicalId,
				sessionSegmentId = fixture.segmentId,
			)
			insertManifest(
				fixture.runId,
				fixture.logicalId,
				revision = fixture.manifestRevision,
				owner = CANDIDATE_OWNER,
			)
			database.sourceSessionDao().saveCompleteness(
				completeness(
					fixture.runId,
					fixture.logicalId,
					lastOrdinal = fixture.ordinal,
				),
			)
			database.stepFactRevisionDao().insert(
				fact(fixture.ordinal, StepFactRevisionEntity.COVERAGE_COVERED, 1L)
					.withTestAttribution(
						fixture.logicalId,
						fixture.runId,
						manifestRevision = fixture.manifestRevision,
					)
					.withValidLiveWalEffectChecksum(),
			)
			database.sessionSegmentDao().insert(
				segment(
					fixture.runId,
					fixture.logicalId,
					steps = null,
					sampleCount = 0,
					id = fixture.segmentId,
					startTimeMs = fixture.startTimeMs,
					endTimeMs = fixture.startTimeMs + 100L,
				),
			)
		}

		logicalHistoryReader.selectRecentEntries(limit = 1).single().identity shouldBe
			HistoricalEntryIdentity.Logical(logicalB)
		logicalHistoryReader.selectRecentEntries(limit = 2).map { it.identity } shouldBe listOf(
			HistoricalEntryIdentity.Logical(logicalB),
			HistoricalEntryIdentity.Logical(logicalA),
		)
	}

	@Test
	@Suppress("LongMethod")
	fun newestMembershipEligibleSiblingDeterminesLogicalEntryRecency() = runTest {
		val logicalA = "logical-a"
		val logicalB = "logical-b"
		val runs = listOf(
			HistoryRunFixture("run-a-covered", logicalA, 1L, 10L, 100L, 1L),
			HistoryRunFixture("run-a-baseline", logicalA, 2L, 20L, 1_000L, 2L),
			HistoryRunFixture("run-b-covered", logicalB, 1L, 30L, 500L, 3L),
		)
		database.sourceProjectionStateDao().installProductLane(lane(cursor = 3L))
		for (fixture in runs) {
			insertRun(
				fixture.runId,
				fixture.logicalId,
				sessionSegmentId = fixture.segmentId,
			)
			insertManifest(
				fixture.runId,
				fixture.logicalId,
				revision = fixture.manifestRevision,
				owner = CANDIDATE_OWNER,
			)
			database.sourceSessionDao().saveCompleteness(
				completeness(
					fixture.runId,
					fixture.logicalId,
					lastOrdinal = fixture.ordinal,
				),
			)
			val coverage = if (fixture.runId == "run-a-baseline") {
				StepFactRevisionEntity.COVERAGE_BASELINE
			} else {
				StepFactRevisionEntity.COVERAGE_COVERED
			}
			val stepCount = if (coverage == StepFactRevisionEntity.COVERAGE_BASELINE) 0L else 1L
			database.stepFactRevisionDao().insert(
				fact(fixture.ordinal, coverage, stepCount)
					.withTestAttribution(
						fixture.logicalId,
						fixture.runId,
						manifestRevision = fixture.manifestRevision,
					)
					.withValidLiveWalEffectChecksum(),
			)
			database.sessionSegmentDao().insert(
				segment(
					fixture.runId,
					fixture.logicalId,
					steps = null,
					sampleCount = 0,
					id = fixture.segmentId,
					startTimeMs = fixture.startTimeMs,
					endTimeMs = fixture.startTimeMs + 50L,
				),
			)
		}

		val first = logicalHistoryReader.selectRecentEntries(limit = 1).single()
		first.identity shouldBe HistoricalEntryIdentity.Logical(logicalA)
		first.physicalMembers.map { it.segment.id } shouldBe listOf(10L, 20L)
		first.physicalMembers.map { it.steps.evidence } shouldBe listOf(
			StepsHistoryEvidence.RECORDED,
			StepsHistoryEvidence.BASELINE,
		)
		logicalHistoryReader.selectRecentEntries(limit = 2).map { it.identity } shouldBe listOf(
			HistoricalEntryIdentity.Logical(logicalA),
			HistoricalEntryIdentity.Logical(logicalB),
		)
	}

	@Test
	fun logicalReaderExcludesSameLogicalRowWithoutExactRunSegmentBinding() = runTest {
		insertRun(RUN_ONE, sessionSegmentId = SEGMENT_ID)
		insertManifest(RUN_ONE, revision = 1L, owner = LEGACY_OWNER)
		insertRun(RUN_TWO, sessionSegmentId = 999L)
		insertManifest(RUN_TWO, revision = 2L, owner = LEGACY_OWNER)
		database.sessionSegmentDao().insert(
			segment(RUN_ONE, steps = 5, sampleCount = 0, id = SEGMENT_ID),
		)
		database.sessionSegmentDao().insert(
			segment(
				runId = RUN_TWO,
				steps = 9,
				sampleCount = 0,
				id = OTHER_SEGMENT_ID,
				startTimeMs = 2_100L,
				endTimeMs = 3_000L,
			),
		)

		val entry = logicalHistoryReader.selectRecentEntries(limit = 1).single()
		entry.identity shouldBe HistoricalEntryIdentity.Logical(LOGICAL_ID)
		entry.physicalMembers.map { it.segment.id } shouldBe listOf(SEGMENT_ID)
		entry.physicalMembers.single().steps.count shouldBe 5L
	}

	@Test
	fun soleLaneBehindLogicalEntryRemainsMaterializingWithoutFabricatedZero() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)),
			targetOrdinal = 2L,
			laneCursor = 1L,
		)
		database.sessionSegmentDao().insert(
			segment(RUN_ONE, steps = null, sampleCount = 0),
		)

		val entry = logicalHistoryReader.selectRecentEntries(limit = 1).single()
		val member = entry.physicalMembers.single()
		entry.identity shouldBe HistoricalEntryIdentity.Logical(LOGICAL_ID)
		member.steps.count shouldBe 5L
		member.steps.evidence shouldBe StepsHistoryEvidence.RECORDED
		member.steps.materialization shouldBe StepsHistoryMaterialization.MATERIALIZING
		member.steps.coverage shouldBe StepsHistoryCoverage.PARTIAL
		member.steps.reasons shouldBe setOf(StepsHistoryReason.PRODUCT_LANE_BEHIND)
		entry.qualifiedSources shouldBe setOf(TrackingSourceComponent.STEPS)
	}

	@Test
	fun logicalEntryRetainsReadyAndMaterializingPhysicalTruthWithoutAggregateZero() = runTest {
		database.sourceProjectionStateDao().installProductLane(lane(cursor = 2L))
		insertRun(RUN_ONE, sessionSegmentId = SEGMENT_ID)
		insertRun(RUN_TWO, completed = false, sessionSegmentId = OTHER_SEGMENT_ID)
		insertManifest(RUN_ONE, revision = 1L, owner = CANDIDATE_OWNER)
		insertManifest(RUN_TWO, revision = 2L, owner = CANDIDATE_OWNER)
		database.sourceSessionDao().saveCompleteness(completeness(RUN_ONE, lastOrdinal = 1L))
		database.sourceSessionDao().saveCompleteness(completeness(RUN_TWO, lastOrdinal = 2L))
		database.stepFactRevisionDao().insert(
			fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 3L),
		)
		database.stepFactRevisionDao().insert(
			fact(2L, StepFactRevisionEntity.COVERAGE_BASELINE, 0L)
				.withTestAttribution(LOGICAL_ID, RUN_TWO, manifestRevision = 2L)
				.withValidLiveWalEffectChecksum(),
		)
		database.sessionSegmentDao().insert(
			segment(RUN_ONE, steps = null, sampleCount = 0),
		)
		database.sessionSegmentDao().insert(
			segment(
				runId = RUN_TWO,
				steps = null,
				sampleCount = 0,
				id = OTHER_SEGMENT_ID,
				startTimeMs = 2_100L,
				endTimeMs = 3_000L,
			),
		)

		val entry = logicalHistoryReader.selectRecentEntries(limit = 1).single()
		entry.identity shouldBe HistoricalEntryIdentity.Logical(LOGICAL_ID)
		entry.physicalMembers.map { it.segment.serviceRunId } shouldBe listOf(RUN_ONE, RUN_TWO)
		entry.physicalMembers.map { it.steps.count } shouldBe listOf(3L, null)
		entry.physicalMembers.map { it.steps.evidence } shouldBe listOf(
			StepsHistoryEvidence.RECORDED,
			StepsHistoryEvidence.BASELINE,
		)
		entry.physicalMembers.map { it.steps.materialization } shouldBe listOf(
			StepsHistoryMaterialization.READY,
			StepsHistoryMaterialization.MATERIALIZING,
		)
		entry.qualifiedSources shouldBe setOf(TrackingSourceComponent.STEPS)
	}

	@Test
	fun logicalStepsOnlyQualificationRejectsMissingRevisionAcrossReplacementRuns() = runTest {
		database.sourceProjectionStateDao().installProductLane(lane(cursor = 2L))
		insertRun(RUN_ONE, sessionSegmentId = SEGMENT_ID)
		insertRun(RUN_TWO, sessionSegmentId = OTHER_SEGMENT_ID)
		insertManifest(RUN_ONE, revision = 1L, owner = CANDIDATE_OWNER)
		insertManifest(RUN_TWO, revision = 3L, owner = CANDIDATE_OWNER)
		database.sourceSessionDao().saveCompleteness(completeness(RUN_ONE, lastOrdinal = 1L))
		database.sourceSessionDao().saveCompleteness(completeness(RUN_TWO, lastOrdinal = 2L))
		database.stepFactRevisionDao().insert(
			fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 3L),
		)
		database.stepFactRevisionDao().insert(
			fact(2L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)
				.withTestAttribution(LOGICAL_ID, RUN_TWO, manifestRevision = 3L)
				.withValidLiveWalEffectChecksum(),
		)
		database.sessionSegmentDao().insert(
			segment(RUN_ONE, steps = null, sampleCount = 0),
		)
		database.sessionSegmentDao().insert(
			segment(
				runId = RUN_TWO,
				steps = null,
				sampleCount = 0,
				id = OTHER_SEGMENT_ID,
				startTimeMs = 2_100L,
				endTimeMs = 3_000L,
			),
		)

		val genericEntry = logicalHistoryReader.selectRecentEntries(limit = 1).single()
		genericEntry.qualifiedSources shouldBe setOf(TrackingSourceComponent.STEPS)
		genericEntry.hasExactStepsOnlyIntent shouldBe false
		genericEntry.isExactStepsOnlyCapture shouldBe false
		logicalHistoryReader.selectRecentStepsOnlyEntries(limit = 10) shouldBe emptyList()
	}

	@Test
	fun stepsOnlyFilterRunsBeforeLimitSoNewerMixedCaptureCannotStarveExactEntry() = runTest {
		val mixedLogicalId = "logical-mixed"
		val stepsLogicalId = "logical-steps"
		val mixedSegmentId = 101L
		val stepsSegmentId = 102L
		insertRun("mixed-run", mixedLogicalId, sessionSegmentId = mixedSegmentId)
		insertManifest(
			runId = "mixed-run",
			logicalId = mixedLogicalId,
			revision = 1L,
			owner = LEGACY_OWNER,
			additionalSources = listOf(
				sourceMembership(
					revision = 1L,
					source = TrackingSourceComponent.LOCATION,
					purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
					logicalId = mixedLogicalId,
				),
			),
		)
		database.sessionSegmentDao().insert(
			segment(
				runId = "mixed-run",
				logicalId = mixedLogicalId,
				steps = 8,
				id = mixedSegmentId,
				startTimeMs = 3_000L,
				endTimeMs = 4_000L,
			),
		)
		insertRun("steps-run", stepsLogicalId, sessionSegmentId = stepsSegmentId)
		insertManifest(
			runId = "steps-run",
			logicalId = stepsLogicalId,
			revision = 1L,
			owner = LEGACY_OWNER,
		)
		database.sessionSegmentDao().insert(
			segment(
				runId = "steps-run",
				logicalId = stepsLogicalId,
				steps = 4,
				sampleCount = 0,
				id = stepsSegmentId,
			),
		)

		logicalHistoryReader.selectRecentStepsOnlyEntries(limit = 1).single().identity shouldBe
			HistoricalEntryIdentity.Logical(stepsLogicalId)
	}

	@Test
	fun observableStepsOnlyListGroupsReplacementRunsWithoutCreatingANumericTotal() = runBlocking {
		database.sourceProjectionStateDao().installProductLane(lane(cursor = 2L))
		insertRun(RUN_ONE, sessionSegmentId = SEGMENT_ID)
		insertRun(RUN_TWO, sessionSegmentId = OTHER_SEGMENT_ID)
		insertManifest(RUN_ONE, revision = 1L, owner = CANDIDATE_OWNER)
		insertManifest(RUN_TWO, revision = 2L, owner = CANDIDATE_OWNER)
		database.sourceSessionDao().saveCompleteness(completeness(RUN_ONE, lastOrdinal = 1L))
		database.sourceSessionDao().saveCompleteness(completeness(RUN_TWO, lastOrdinal = 2L))
		database.stepFactRevisionDao().insert(
			fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 3L),
		)
		database.stepFactRevisionDao().insert(
			fact(2L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)
				.withTestAttribution(LOGICAL_ID, RUN_TWO, manifestRevision = 2L)
				.withValidLiveWalEffectChecksum(),
		)
		database.sessionSegmentDao().insert(
			segment(RUN_ONE, steps = null, sampleCount = 2),
		)
		database.sessionSegmentDao().insert(
			segment(
				runId = RUN_TWO,
				steps = null,
				sampleCount = 0,
				id = OTHER_SEGMENT_ID,
				startTimeMs = 2_100L,
				endTimeMs = 3_000L,
			),
		)
		val repository = DefaultTrackingHistoryRepository(
			database,
			selector,
			logicalHistoryReader,
			Dispatchers.IO,
		)

		val initialEmission = CompletableDeferred<Unit>()
		val collection = async {
			repository.observeRecentStepsOnlyEntries(limit = 10)
				.onEach { initialEmission.complete(Unit) }
				.take(2)
				.toList()
		}
		withTimeout(5_000L) { initialEmission.await() }
		insertRunFence(RUN_TWO, deletedAtMs = 3_500L)

		val emissions = withTimeout(5_000L) { collection.await() }
		emissions.first().single().state shouldBe StepsOnlyHistoryListState.AVAILABLE
		emissions.last().single().state shouldBe StepsOnlyHistoryListState.PARTIAL
		Unit
	}

	@Test
	fun stepsAwarePageCollapsesReplacementCandidatesIntoOneOpaqueLogicalRow() = runTest {
		insertRun(RUN_ONE, sessionSegmentId = SEGMENT_ID)
		insertRun(RUN_TWO, sessionSegmentId = OTHER_SEGMENT_ID)
		insertManifest(RUN_ONE, revision = 1L, owner = LEGACY_OWNER)
		insertManifest(RUN_TWO, revision = 2L, owner = LEGACY_OWNER)
		database.sessionSegmentDao().insert(segment(RUN_ONE, steps = 3, sampleCount = 1))
		database.sessionSegmentDao().insert(
			segment(
				runId = RUN_TWO,
				steps = 5,
				sampleCount = 1,
				id = OTHER_SEGMENT_ID,
				startTimeMs = 2_100L,
				endTimeMs = 3_000L,
			),
		)

		val page = historyRepository().observeRecentStepsAwarePage(
			candidateSegmentIds = listOf(SEGMENT_ID, OTHER_SEGMENT_ID),
			limit = 10,
		).first()

		page.size shouldBe 1
		val row = page.single() as StepsAwareHistoryPageEntry.StepsOnly
		row.history.startTime.raw shouldBe 1_000L
		row.history.endTime.raw shouldBe 3_000L
		row.history.state shouldBe StepsOnlyHistoryListState.PARTIAL
	}

	@Test
	fun baselineOnlyExactStepsCandidateCannotResurrectAsAPhysicalRow() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(fact(1L, StepFactRevisionEntity.COVERAGE_BASELINE, 0L)),
			targetOrdinal = 1L,
			laneCursor = 1L,
		)
		database.sessionSegmentDao().insert(segment(RUN_ONE, steps = null, sampleCount = 1))

		historyRepository().observeRecentStepsAwarePage(
			candidateSegmentIds = listOf(SEGMENT_ID),
			limit = 10,
		).first() shouldBe emptyList()
	}

	@Test
	fun fencedExactStepsCandidateCannotResurrectAsAPhysicalRow() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)),
			targetOrdinal = 1L,
			laneCursor = 1L,
		)
		database.sessionSegmentDao().insert(segment(RUN_ONE, steps = null, sampleCount = 1))
		insertRunFence(RUN_ONE, deletedAtMs = 3_000L)

		historyRepository().observeRecentStepsAwarePage(
			candidateSegmentIds = listOf(SEGMENT_ID),
			limit = 10,
		).first() shouldBe emptyList()
	}

	@Test
	fun mixedAndUnverifiableCandidatesRemainPhysicalWithoutStepsInference() = runTest {
		insertRun(RUN_ONE, sessionSegmentId = SEGMENT_ID)
		insertManifest(
			runId = RUN_ONE,
			revision = 1L,
			owner = LEGACY_OWNER,
			additionalSources = listOf(
				sourceMembership(
					revision = 1L,
					source = TrackingSourceComponent.LOCATION,
					purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				),
			),
		)
		insertRun(
			runId = RUN_TWO,
			sessionSegmentId = null,
			presentationAcknowledgement =
				SourceServiceRunEntity.PRESENTATION_LEGACY_UNVERIFIABLE,
		)
		database.sessionSegmentDao().insert(segment(RUN_ONE, steps = 4, sampleCount = 1))
		database.sessionSegmentDao().insert(
			segment(
				runId = RUN_TWO,
				steps = null,
				sampleCount = 1,
				id = OTHER_SEGMENT_ID,
				startTimeMs = 2_100L,
				endTimeMs = 3_000L,
			),
		)

		historyRepository().observeRecentStepsAwarePage(
			candidateSegmentIds = listOf(SEGMENT_ID, OTHER_SEGMENT_ID),
			limit = 10,
		).first() shouldBe listOf(
			StepsAwareHistoryPageEntry.Physical(OTHER_SEGMENT_ID),
			StepsAwareHistoryPageEntry.Physical(SEGMENT_ID),
		)
	}

	@Test
	fun qualifiedZeroSampleStepsEntryIsDiscoveredWithoutAPhysicalCandidate() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)),
			targetOrdinal = 1L,
			laneCursor = 1L,
		)
		database.sessionSegmentDao().insert(segment(RUN_ONE, steps = null, sampleCount = 0))

		val page = historyRepository().observeRecentStepsAwarePage(
			candidateSegmentIds = emptyList(),
			limit = 10,
		).first()

		page.single() as StepsAwareHistoryPageEntry.StepsOnly
	}

	@Test
	fun stepsAwarePageOrdersLogicalRowByNewestPhysicalMemberBeforeFinalLimit() = runTest {
		val physicalLogicalId = "logical-physical"
		val physicalRun = "run-physical"
		val physicalSegmentId = 43L
		insertRun(RUN_ONE, sessionSegmentId = SEGMENT_ID)
		insertRun(RUN_TWO, sessionSegmentId = OTHER_SEGMENT_ID)
		insertManifest(RUN_ONE, revision = 1L, owner = LEGACY_OWNER)
		insertManifest(RUN_TWO, revision = 2L, owner = LEGACY_OWNER)
		database.sessionSegmentDao().insert(
			segment(RUN_ONE, steps = 2, sampleCount = 0, startTimeMs = 100L, endTimeMs = 10_000L),
		)
		database.sessionSegmentDao().insert(
			segment(
				runId = RUN_TWO,
				steps = 3,
				sampleCount = 0,
				id = OTHER_SEGMENT_ID,
				startTimeMs = 3_000L,
				endTimeMs = 4_000L,
			),
		)
		insertRun(physicalRun, physicalLogicalId, sessionSegmentId = physicalSegmentId)
		insertManifest(
			runId = physicalRun,
			logicalId = physicalLogicalId,
			revision = 1L,
			owner = LEGACY_OWNER,
			additionalSources = listOf(
				sourceMembership(
					revision = 1L,
					source = TrackingSourceComponent.LOCATION,
					purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
					logicalId = physicalLogicalId,
				),
			),
		)
		database.sessionSegmentDao().insert(
			segment(
				runId = physicalRun,
				logicalId = physicalLogicalId,
				steps = 1,
				sampleCount = 1,
				id = physicalSegmentId,
				startTimeMs = 2_500L,
				endTimeMs = 2_600L,
			),
		)

		val page = historyRepository().observeRecentStepsAwarePage(
			candidateSegmentIds = listOf(physicalSegmentId),
			limit = 1,
		).first()

		page.single() as StepsAwareHistoryPageEntry.StepsOnly
	}

	@Test
	fun stepsAwarePageValidatesBoundsAndOmitsMissingCandidates() = runTest {
		val repository = historyRepository()
		listOf(
			listOf(0L),
			listOf(-1L),
			listOf(1L, 1L),
			(1L..101L).toList(),
		).forEach { invalidCandidates ->
			(runCatching {
				repository.observeRecentStepsAwarePage(invalidCandidates, limit = 1)
			}.exceptionOrNull() is IllegalArgumentException) shouldBe true
		}
		listOf(0, 101).forEach { invalidLimit ->
			(runCatching {
				repository.observeRecentStepsAwarePage(emptyList(), limit = invalidLimit)
			}.exceptionOrNull() is IllegalArgumentException) shouldBe true
		}

		repository.observeRecentStepsAwarePage(
			candidateSegmentIds = listOf(999L),
			limit = 1,
		).first() shouldBe emptyList()
	}

	@Test
	fun stepsAwarePageReactsWhenAMissingCandidateGenerationAppears() = runBlocking {
		val initialEmission = CompletableDeferred<Unit>()
		val collection = async {
			historyRepository().observeRecentStepsAwarePage(
				candidateSegmentIds = listOf(SEGMENT_ID),
				limit = 1,
			).onEach { initialEmission.complete(Unit) }
				.take(2)
				.toList()
		}
		withTimeout(5_000L) { initialEmission.await() }
		database.sessionSegmentDao().insert(
			segment(null, logicalId = null, steps = null, sampleCount = 1),
		)

		val emissions = withTimeout(5_000L) { collection.await() }
		emissions.first() shouldBe emptyList()
		emissions.last() shouldBe listOf(StepsAwareHistoryPageEntry.Physical(SEGMENT_ID))
		Unit
	}

	@Test
	fun unattributedLegacyRowsRemainIndependentPhysicalEntries() = runTest {
		database.sessionSegmentDao().insert(
			segment(null, logicalId = null, steps = null, sampleCount = 1, id = SEGMENT_ID),
		)
		database.sessionSegmentDao().insert(
			segment(null, logicalId = null, steps = null, sampleCount = 1, id = OTHER_SEGMENT_ID),
		)
		database.sessionSegmentDao().insert(
			segment(
				runId = "partial-run",
				logicalId = null,
				steps = null,
				sampleCount = 1,
				id = 43L,
			),
		)

		val entries = logicalHistoryReader.selectRecentEntries(limit = 10)
		entries.map { it.identity } shouldBe listOf(
			HistoricalEntryIdentity.LegacyPhysical(OTHER_SEGMENT_ID),
			HistoricalEntryIdentity.LegacyPhysical(SEGMENT_ID),
		)
		entries.map { it.physicalMembers.single().segment.id } shouldBe
			listOf(OTHER_SEGMENT_ID, SEGMENT_ID)
		entries.all { it.qualifiedSources.isEmpty() } shouldBe true
	}

	@Test
	fun attributedMigratedRowsShareKnownLogicalIdentityWithoutQualifyingASource() = runTest {
		listOf(RUN_ONE, RUN_TWO).forEach { runId ->
			insertRun(
				runId,
				sessionSegmentId = null,
				presentationAcknowledgement =
					SourceServiceRunEntity.PRESENTATION_LEGACY_UNVERIFIABLE,
			)
		}
		database.sessionSegmentDao().insert(
			segment(RUN_ONE, steps = null, sampleCount = 1, id = SEGMENT_ID),
		)
		database.sessionSegmentDao().insert(
			segment(
				runId = RUN_TWO,
				steps = null,
				sampleCount = 1,
				id = OTHER_SEGMENT_ID,
				startTimeMs = 2_100L,
				endTimeMs = 3_000L,
			),
		)

		val entry = logicalHistoryReader.selectRecentEntries(limit = 10).single()
		entry.identity shouldBe HistoricalEntryIdentity.Logical(LOGICAL_ID)
		entry.physicalMembers.map { it.segment.id } shouldBe
			listOf(SEGMENT_ID, OTHER_SEGMENT_ID)
		entry.physicalMembers.map { it.captureAuthority }.toSet() shouldBe setOf(
			HistoricalCaptureAuthority.Unverifiable(
				HistoricalCaptureFailure.SERVICE_RUN_SEGMENT_BINDING_UNVERIFIABLE,
			),
		)
		entry.qualifiedSources shouldBe emptySet()
	}

	@Test
	@Suppress("LongMethod")
	fun attributedMigratedRowsComposeWithExactSiblingUnderKnownLogicalIdentity() = runTest {
		val thirdRun = "run-3"
		val thirdSegmentId = 43L
		insertRun(
			RUN_ONE,
			sessionSegmentId = null,
			presentationAcknowledgement =
				SourceServiceRunEntity.PRESENTATION_LEGACY_UNVERIFIABLE,
		)
		insertRun(
			RUN_TWO,
			sessionSegmentId = null,
			presentationAcknowledgement =
				SourceServiceRunEntity.PRESENTATION_LEGACY_UNVERIFIABLE,
		)
		insertRun(thirdRun, sessionSegmentId = thirdSegmentId)
		insertManifest(thirdRun, revision = 3L, owner = LEGACY_OWNER)
		database.sessionSegmentDao().insert(
			segment(RUN_ONE, steps = null, sampleCount = 1, id = SEGMENT_ID),
		)
		database.sessionSegmentDao().insert(
			segment(
				runId = RUN_TWO,
				steps = null,
				sampleCount = 1,
				id = OTHER_SEGMENT_ID,
				startTimeMs = 2_100L,
				endTimeMs = 3_000L,
			),
		)
		database.sessionSegmentDao().insert(
			segment(
				runId = thirdRun,
				steps = 4,
				sampleCount = 0,
				id = thirdSegmentId,
				startTimeMs = 3_100L,
				endTimeMs = 4_000L,
			),
		)

		val entry = logicalHistoryReader.selectRecentEntries(limit = 10).single()
		entry.identity shouldBe HistoricalEntryIdentity.Logical(LOGICAL_ID)
		entry.physicalMembers.map { it.segment.id } shouldBe
			listOf(SEGMENT_ID, OTHER_SEGMENT_ID, thirdSegmentId)
		entry.physicalMembers.take(2).map { it.captureAuthority } shouldBe listOf(
			HistoricalCaptureAuthority.Unverifiable(
				HistoricalCaptureFailure.SERVICE_RUN_SEGMENT_BINDING_UNVERIFIABLE,
			),
			HistoricalCaptureAuthority.Unverifiable(
				HistoricalCaptureFailure.SERVICE_RUN_SEGMENT_BINDING_UNVERIFIABLE,
			),
		)
		entry.physicalMembers.take(2).all { it.qualifiedSources.isEmpty() } shouldBe true
		entry.physicalMembers.last().qualifiedSources shouldBe
			setOf(TrackingSourceComponent.STEPS)
		logicalHistoryReader.selectRecentStepsOnlyEntries(limit = 10) shouldBe emptyList()
	}

	@Test
	fun oneRunFenceCannotDeleteItsLogicalSiblingAndQuiescenceIsNotAFilter() = runTest {
		database.sourceProjectionStateDao().installProductLane(lane(cursor = 2L))
		insertRun(
			RUN_ONE,
			sessionSegmentId = SEGMENT_ID,
			presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_QUIESCED,
			presentationAcknowledgedAtMs = 2_100L,
		)
		insertRun(RUN_TWO, sessionSegmentId = OTHER_SEGMENT_ID)
		insertManifest(RUN_ONE, revision = 1L, owner = CANDIDATE_OWNER)
		insertManifest(RUN_TWO, revision = 2L, owner = CANDIDATE_OWNER)
		database.sourceSessionDao().saveCompleteness(completeness(RUN_ONE, lastOrdinal = 1L))
		database.sourceSessionDao().saveCompleteness(completeness(RUN_TWO, lastOrdinal = 2L))
		database.stepFactRevisionDao().insert(
			fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 3L),
		)
		database.stepFactRevisionDao().insert(
			fact(2L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)
				.withTestAttribution(LOGICAL_ID, RUN_TWO, manifestRevision = 2L)
				.withValidLiveWalEffectChecksum(),
		)
		database.sessionSegmentDao().insert(segment(RUN_ONE, steps = null, sampleCount = 0))
		database.sessionSegmentDao().insert(
			segment(
				RUN_TWO,
				steps = null,
				sampleCount = 0,
				id = OTHER_SEGMENT_ID,
				startTimeMs = 2_100L,
				endTimeMs = 3_000L,
			),
		)
		insertRunFence(RUN_TWO, deletedAtMs = 3_000L)

		val surviving = logicalHistoryReader.selectRecentEntries(limit = 1).single()
		surviving.physicalMembers.map { it.steps.availability } shouldBe listOf(
			StepsHistoryAvailability.AVAILABLE,
			StepsHistoryAvailability.DELETED,
		)
		surviving.qualifiedSources shouldBe setOf(TrackingSourceComponent.STEPS)

		insertRunFence(RUN_ONE, deletedAtMs = 3_100L)
		logicalHistoryReader.selectRecentEntries(limit = 1) shouldBe emptyList()
	}

	@Test
	fun logicalEntryLimitIsExplicitlyBounded() = runTest {
		(
			runCatching { logicalHistoryReader.selectRecentEntries(limit = 0) }
				.exceptionOrNull() is IllegalArgumentException
		) shouldBe true
		(
			runCatching { logicalHistoryReader.selectRecentEntries(limit = 101) }
				.exceptionOrNull() is IllegalArgumentException
		) shouldBe true
	}

	@Test
	@Suppress("LongMethod")
	fun unsupportedCorrectionTaintsEveryProvisionalReplacementRunWithoutFabricatingCount() = runTest {
		insertRun(RUN_ONE, sessionSegmentId = SEGMENT_ID)
		insertRun(RUN_TWO, sessionSegmentId = OTHER_SEGMENT_ID)
		insertManifest(RUN_ONE, revision = 1L, owner = CANDIDATE_OWNER)
		insertManifest(RUN_TWO, revision = 2L, owner = CANDIDATE_OWNER)
		database.sourceProjectionStateDao().installProductLane(lane(cursor = 3L))
		database.sourceSessionDao().saveCompleteness(completeness(RUN_ONE, lastOrdinal = 1L))
		database.sourceSessionDao().saveCompleteness(completeness(RUN_TWO, lastOrdinal = 3L))

		val stable = fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 3L)
			.withSourceEventIdentity("stable-event")
			.withTestAttribution(LOGICAL_ID, RUN_ONE, manifestRevision = 1L)
			.withValidLiveWalEffectChecksum()
		val moved = fact(2L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)
			.withSourceEventIdentity("moved-event")
			.withTestAttribution(LOGICAL_ID, RUN_ONE, manifestRevision = 1L)
			.withValidLiveWalEffectChecksum()
		val corrected = moved.copy(
			semanticRevision = 2L,
			mutationId = "${moved.logicalFactId}:2:${StepFactRevisionEntity.OPERATION_UPSERT}",
			sourceAdmissionOrdinal = 3L,
			cumulativeStepCountStart = 200L,
			cumulativeStepCountEnd = 207L,
			effectiveStepCount = 7L,
		).withTestAttribution(LOGICAL_ID, RUN_TWO, manifestRevision = 2L)
			.withValidLiveWalEffectChecksum()
		listOf(stable, moved, corrected).forEach { database.stepFactRevisionDao().insert(it) }
		database.sessionSegmentDao().insert(segment(RUN_ONE, steps = null, sampleCount = 0))
		database.sessionSegmentDao().insert(
			segment(
				runId = RUN_TWO,
				steps = null,
				sampleCount = 0,
				id = OTHER_SEGMENT_ID,
				startTimeMs = 2_100L,
				endTimeMs = 3_000L,
			),
		)

		val selected = selector.selectEvidenceBySegmentIds(
			listOf(OTHER_SEGMENT_ID, SEGMENT_ID, OTHER_SEGMENT_ID, 999L),
		)
		selected.keys shouldBe setOf(SEGMENT_ID, OTHER_SEGMENT_ID)
		selected.getValue(SEGMENT_ID).steps shouldBe failedFactIntegrityResult()
		selected.getValue(OTHER_SEGMENT_ID).steps shouldBe failedFactIntegrityResult()
		selected.getValue(SEGMENT_ID).segment.serviceRunId shouldBe RUN_ONE
		selected.getValue(OTHER_SEGMENT_ID).segment.serviceRunId shouldBe RUN_TWO
		(selected.getValue(SEGMENT_ID).captureAuthority as HistoricalCaptureAuthority.Exact)
			.revisions.map(HistoricalCaptureRevision::manifestRevision) shouldBe listOf(1L)
		(selected.getValue(OTHER_SEGMENT_ID).captureAuthority as HistoricalCaptureAuthority.Exact)
			.revisions.map(HistoricalCaptureRevision::manifestRevision) shouldBe listOf(2L)
		logicalHistoryReader.selectRecentEntries(limit = 1) shouldBe emptyList()
	}

	@Test
	fun sharedGenerationHighWaterAttachesCorruptOrdinalEvidenceToEveryPlausibleRun() = runTest {
		insertRun(RUN_ONE, sessionSegmentId = SEGMENT_ID)
		insertRun(RUN_TWO, sessionSegmentId = OTHER_SEGMENT_ID)
		insertManifest(RUN_ONE, revision = 1L, owner = CANDIDATE_OWNER)
		insertManifest(RUN_TWO, revision = 2L, owner = CANDIDATE_OWNER)
		database.sourceProjectionStateDao().installProductLane(lane(cursor = 3L))
		database.sourceSessionDao().saveCompleteness(
			completeness(RUN_ONE, lastOrdinal = 3L, registrationGeneration = 1L),
		)
		database.sourceSessionDao().saveCompleteness(
			completeness(RUN_TWO, lastOrdinal = 3L, registrationGeneration = 1L),
		)
		val hidden = fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 2L)
		val runOneSurvivor = fact(2L, StepFactRevisionEntity.COVERAGE_COVERED, 3L)
		val runTwoSurvivor = fact(3L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)
			.withTestAttribution(LOGICAL_ID, RUN_TWO, manifestRevision = 2L)
			.withValidLiveWalEffectChecksum()
		listOf(hidden, runOneSurvivor, runTwoSurvivor).forEach {
			database.stepFactRevisionDao().insert(it)
		}
		database.openHelper.writableDatabase.execSQL(
			"UPDATE step_fact_revision SET service_run_id = 'tampered-run', " +
				"logical_tracking_id = 'tampered-logical' " +
				"WHERE logical_fact_id = ? AND operation = 'UPSERT'",
			arrayOf(hidden.logicalFactId),
		)

		selector.select(segment(RUN_ONE, steps = null)) shouldBe failedFactIntegrityResult()
		selector.select(
			segment(
				runId = RUN_TWO,
				steps = null,
				id = OTHER_SEGMENT_ID,
				startTimeMs = 2_100L,
				endTimeMs = 3_000L,
			),
		) shouldBe failedFactIntegrityResult()
	}

	@Test
	fun nullHighWaterBetweenReplacementGenerationsDoesNotExcludeOrDuplicateValidFacts() = runTest {
		insertRun(RUN_ONE, sessionSegmentId = SEGMENT_ID)
		insertRun(RUN_TWO, sessionSegmentId = OTHER_SEGMENT_ID)
		insertManifest(RUN_ONE, revision = 1L, owner = CANDIDATE_OWNER)
		insertManifest(RUN_TWO, revision = 2L, owner = CANDIDATE_OWNER)
		database.sourceProjectionStateDao().installProductLane(lane(cursor = 4L))
		database.sourceSessionDao().saveCompleteness(
			completeness(RUN_ONE, lastOrdinal = 2L, registrationGeneration = 1L),
		)
		database.sourceSessionDao().saveCompleteness(
			completeness(RUN_TWO, lastOrdinal = null, registrationGeneration = 2L),
		)
		database.sourceSessionDao().saveCompleteness(
			completeness(RUN_TWO, lastOrdinal = 4L, registrationGeneration = 3L),
		)
		val runOneFact = fact(2L, StepFactRevisionEntity.COVERAGE_COVERED, 3L)
		val runTwoFact = fact(3L, StepFactRevisionEntity.COVERAGE_COVERED, 7L)
			.withTestAttribution(LOGICAL_ID, RUN_TWO, manifestRevision = 2L)
			.withValidLiveWalEffectChecksum()
		database.stepFactRevisionDao().insert(runOneFact)
		database.stepFactRevisionDao().insert(runTwoFact)

		selector.select(segment(RUN_ONE, steps = null)).also { result ->
			result.count shouldBe 3L
			result.materialization shouldBe StepsHistoryMaterialization.READY
			result.coverage shouldBe StepsHistoryCoverage.COMPLETE
		}
		selector.select(
			segment(
				runId = RUN_TWO,
				steps = null,
				id = OTHER_SEGMENT_ID,
				startTimeMs = 2_100L,
				endTimeMs = 3_000L,
			),
		).also { result ->
			result.count shouldBe 7L
			result.materialization shouldBe StepsHistoryMaterialization.READY
			result.coverage shouldBe StepsHistoryCoverage.COMPLETE
		}
	}

	@Test
	fun nativeFactWithNullCompletenessHighWaterFailsClosedWithoutExposingCount() = runTest {
		insertRun(RUN_ONE, sessionSegmentId = SEGMENT_ID)
		insertManifest(RUN_ONE, revision = 1L, owner = CANDIDATE_OWNER)
		database.sourceProjectionStateDao().installProductLane(lane(cursor = 1L))
		database.sourceSessionDao().saveCompleteness(
			completeness(RUN_ONE, lastOrdinal = null),
		)
		database.stepFactRevisionDao().insert(
			fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L),
		)

		selector.select(segment(RUN_ONE, steps = null)) shouldBe invalidCompletenessResult()
	}

	@Test
	fun notOwnedGenerationZeroRetirementRemainsTruthfulUnavailableEvidence() = runTest {
		insertRun(RUN_ONE, sessionSegmentId = SEGMENT_ID)
		insertManifest(RUN_ONE, revision = 1L, owner = CANDIDATE_OWNER)
		database.sourceProjectionStateDao().installProductLane(lane(cursor = 0L))
		database.sourceSessionDao().saveCompleteness(
			completeness(
				runId = RUN_ONE,
				lastOrdinal = null,
				providerCoverage = "PROVIDER_COMPLETENESS_UNOBSERVABLE",
				registrationGeneration = 0L,
				sourceInstanceId = "not-owned-steps",
			),
		)

		selector.select(segment(RUN_ONE, steps = null, sampleCount = 0)) shouldBe
			StepsSegmentHistoryResult(
				count = null,
				availability = StepsHistoryAvailability.AVAILABLE,
				evidence = StepsHistoryEvidence.NO_OBSERVATION,
				materialization = StepsHistoryMaterialization.READY,
				coverage = StepsHistoryCoverage.NONE,
				reasons = setOf(StepsHistoryReason.PROVIDER_COMPLETENESS_UNOBSERVABLE),
			)
	}

	@Test
	fun unresolvedGenerationZeroRetirementRemainsPartialRatherThanCorrupt() = runTest {
		insertRun(RUN_ONE, sessionSegmentId = SEGMENT_ID)
		insertManifest(RUN_ONE, revision = 1L, owner = CANDIDATE_OWNER)
		database.sourceProjectionStateDao().installProductLane(lane(cursor = 0L))
		database.sourceSessionDao().saveCompleteness(
			completeness(
				runId = RUN_ONE,
				lastOrdinal = null,
				providerCoverage = "PROVIDER_COMPLETENESS_UNOBSERVABLE",
				registrationGeneration = 0L,
				sourceInstanceId = "unresolved-steps",
			).copy(
				appDrainComplete = false,
				stopStatus = "TIMED_OUT",
			),
		)

		selector.select(segment(RUN_ONE, steps = null, sampleCount = 0)) shouldBe
			StepsSegmentHistoryResult(
				count = null,
				availability = StepsHistoryAvailability.AVAILABLE,
				evidence = StepsHistoryEvidence.NO_OBSERVATION,
				materialization = StepsHistoryMaterialization.READY,
				coverage = StepsHistoryCoverage.NONE,
				reasons = setOf(
					StepsHistoryReason.APP_DRAIN_INCOMPLETE,
					StepsHistoryReason.STOP_INCOMPLETE,
					StepsHistoryReason.PROVIDER_COMPLETENESS_UNOBSERVABLE,
				),
			)
	}

	@Test
	fun unavailableGenerationZeroAckRemainsPartialRatherThanCorrupt() = runTest {
		insertRun(RUN_ONE, sessionSegmentId = SEGMENT_ID)
		insertManifest(RUN_ONE, revision = 1L, owner = CANDIDATE_OWNER)
		database.sourceProjectionStateDao().installProductLane(lane(cursor = 0L))
		database.sourceSessionDao().saveCompleteness(
			completeness(
				runId = RUN_ONE,
				lastOrdinal = null,
				providerCoverage = "PROVIDER_COMPLETENESS_UNOBSERVABLE",
				registrationGeneration = 0L,
				sourceInstanceId = "unavailable-steps",
			).copy(stopStatus = "PROVIDER_FAILED"),
		)

		selector.select(segment(RUN_ONE, steps = null, sampleCount = 0)) shouldBe
			StepsSegmentHistoryResult(
				count = null,
				availability = StepsHistoryAvailability.AVAILABLE,
				evidence = StepsHistoryEvidence.NO_OBSERVATION,
				materialization = StepsHistoryMaterialization.READY,
				coverage = StepsHistoryCoverage.NONE,
				reasons = setOf(
					StepsHistoryReason.STOP_INCOMPLETE,
					StepsHistoryReason.PROVIDER_COMPLETENESS_UNOBSERVABLE,
				),
			)
	}

	@Test
	fun nativeFactAboveCompletenessHighWaterCannotHideBesideValidSibling() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(
				fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 4L),
				fact(2L, StepFactRevisionEntity.COVERAGE_COVERED, 6L),
			),
			targetOrdinal = 1L,
			laneCursor = 2L,
		)

		selector.select(segment(RUN_ONE, steps = null)) shouldBe invalidCompletenessResult()
	}

	@Test
	fun duplicateNonNullCompletenessHighWaterWithinRunFailsClosed() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)),
			targetOrdinal = 1L,
			laneCursor = 1L,
		)
		database.sourceSessionDao().saveCompleteness(
			completeness(RUN_ONE, lastOrdinal = 1L, registrationGeneration = 2L),
		)

		selector.select(segment(RUN_ONE, steps = null)) shouldBe invalidCompletenessResult()
	}

	@Test
	fun revisionedCaptureKeepsWholeRunStepsAndExcludesControlFromCapture() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)),
			targetOrdinal = 1L,
			laneCursor = 1L,
		)
		insertExactManifest(
			runId = RUN_ONE,
			revision = 2L,
			sources = listOf(
				sourceMembership(
					revision = 2L,
					source = TrackingSourceComponent.LOCATION,
					purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				),
				sourceMembership(
					revision = 2L,
					source = TrackingSourceComponent.ACTIVITY,
					purpose = SessionManifestPurposeCode.CONTROL,
				),
				sourceMembership(
					revision = 2L,
					source = TrackingSourceComponent.PRESSURE,
					purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
					persistenceEligible = false,
				),
			),
		)

		val capture = selector.selectEvidence(segment(RUN_ONE, steps = null)).captureAuthority as
			HistoricalCaptureAuthority.Exact
		capture.revisions.map(HistoricalCaptureRevision::capturedSources) shouldBe listOf(
			setOf(TrackingSourceComponent.STEPS),
			setOf(TrackingSourceComponent.LOCATION),
		)
		capture.revisions.map { it.manifestRevision to it.effectiveWallTimeMs } shouldBe listOf(
			1L to 1_000L,
			2L to 2_000L,
		)
		capture.revisions.last().controlSources shouldBe setOf(TrackingSourceComponent.ACTIVITY)
		capture.capturedInAnyRevision shouldBe setOf(
			TrackingSourceComponent.STEPS,
			TrackingSourceComponent.LOCATION,
		)
		capture.capturedForWholeRun shouldBe emptySet()
		database.sessionSegmentDao().insert(segment(RUN_ONE, steps = null, sampleCount = 0))
		logicalHistoryReader.selectRecentStepsOnlyEntries(limit = 10) shouldBe emptyList()
	}

	@Test
	fun unknownPersistedManifestPurposeFailsCaptureAuthorityClosed() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)),
			targetOrdinal = 1L,
			laneCursor = 1L,
		)
		insertManifest(
			runId = RUN_ONE,
			revision = 2L,
			owner = CANDIDATE_OWNER,
			additionalSources = listOf(
				sourceMembership(
					revision = 2L,
					source = TrackingSourceComponent.ACTIVITY,
					purpose = "FUTURE_PURPOSE",
				),
			),
		)
		val segment = segment(RUN_ONE, steps = null, sampleCount = 0)
		database.sessionSegmentDao().insert(segment)

		val evidence = selector.selectEvidence(segment)
		evidence.captureAuthority shouldBe HistoricalCaptureAuthority.Unverifiable(
			HistoricalCaptureFailure.UNKNOWN_PURPOSE,
		)
		evidence.qualifiedSources shouldBe emptySet()
		selectRecentEvidence(limit = 10) shouldBe emptyList()
	}

	@Test
	fun preparedManifestRevisionMismatchFailsClosed() = runTest {
		insertRun(RUN_ONE, preparedManifestRevision = 2L)
		insertManifest(
			runId = RUN_ONE,
			revision = 1L,
			owner = LEGACY_OWNER,
			synchronizeRunTimeline = false,
		)

		assertManifestTimelineRejected()
	}

	@Test
	fun desiredPlanAndLastManifestMismatchFailsClosed() = runTest {
		insertRun(RUN_ONE, desiredPlanRevision = 2L)
		insertManifest(
			runId = RUN_ONE,
			revision = 1L,
			owner = LEGACY_OWNER,
			acquisitionPlanRevision = 1L,
			synchronizeRunTimeline = false,
		)

		assertManifestTimelineRejected()
	}

	@Test
	fun manifestRolloutMismatchFailsClosed() = runTest {
		insertRun(RUN_ONE, rolloutRevision = TEST_ROLLOUT_REVISION)
		insertManifest(
			runId = RUN_ONE,
			revision = 1L,
			owner = LEGACY_OWNER,
			rolloutRevision = TEST_ROLLOUT_REVISION + 1L,
			synchronizeRunTimeline = false,
		)

		assertManifestTimelineRejected()
	}

	@Test
	fun firstManifestOriginMismatchFailsClosed() = runTest {
		insertRun(RUN_ONE, startOrigin = MANUAL_START_ORIGIN)
		insertManifest(
			runId = RUN_ONE,
			revision = 1L,
			owner = LEGACY_OWNER,
			startOrigin = POLICY_RECONCILIATION_ORIGIN,
			synchronizeRunTimeline = false,
		)

		assertManifestTimelineRejected()
	}

	@Test
	fun successorManifestMustUsePolicyReconciliationOrigin() = runTest {
		insertRun(RUN_ONE)
		insertManifest(RUN_ONE, revision = 1L, owner = LEGACY_OWNER)
		insertManifest(
			runId = RUN_ONE,
			revision = 2L,
			owner = LEGACY_OWNER,
			startOrigin = MANUAL_START_ORIGIN,
		)

		assertManifestTimelineRejected()
	}

	@Test
	fun noncontiguousManifestRevisionFailsClosed() = runTest {
		insertRun(RUN_ONE)
		insertManifest(RUN_ONE, revision = 1L, owner = LEGACY_OWNER)
		insertManifest(RUN_ONE, revision = 3L, owner = LEGACY_OWNER)

		assertManifestTimelineRejected()
	}

	@Test
	fun successorManifestElapsedRegressionFailsClosed() = runTest {
		insertRun(RUN_ONE)
		insertManifest(RUN_ONE, revision = 1L, owner = LEGACY_OWNER)
		insertManifest(
			runId = RUN_ONE,
			revision = 2L,
			owner = LEGACY_OWNER,
			effectiveElapsedRealtimeNanos = TEST_RUN_STARTED_ELAPSED_NANOS - 1L,
		)

		assertManifestTimelineRejected()
	}

	@Test
	fun missingHistoricalStepsPolicyFailsClosed() = runTest {
		assertCandidateCaptureAuthorityRejected(
			policy = null,
			consent = stepCaptureConsent(epoch = 1L, policyRevision = 1L),
		)
	}

	@Test
	fun disabledHistoricalStepsPolicyFailsClosed() = runTest {
		assertCandidateCaptureAuthorityRejected(
			policy = stepPolicy(revision = 1L, enabled = false),
			consent = stepCaptureConsent(epoch = 1L, policyRevision = 1L),
		)
	}

	@Test
	fun nonPersistentHistoricalStepsPolicyFailsClosed() = runTest {
		assertCandidateCaptureAuthorityRejected(
			policy = stepPolicy(revision = 1L, enabled = true).copy(
				capturePersistenceEligible = false,
			),
			consent = stepCaptureConsent(epoch = 1L, policyRevision = 1L),
		)
	}

	@Test
	fun mismatchedHistoricalStepsPolicyConsentFailsClosed() = runTest {
		assertCandidateCaptureAuthorityRejected(
			policy = stepPolicy(revision = 1L, enabled = true).copy(captureConsentEpoch = 2L),
			consent = stepCaptureConsent(epoch = 1L, policyRevision = 1L),
		)
	}

	@Test
	fun mismatchedHistoricalStepsPolicyQosFailsClosed() = runTest {
		assertCandidateCaptureAuthorityRejected(
			policy = stepPolicy(revision = 1L, enabled = true).copy(qosCode = 2),
			consent = stepCaptureConsent(epoch = 1L, policyRevision = 1L),
		)
	}

	@Test
	fun missingHistoricalStepsConsentFailsClosed() = runTest {
		assertCandidateCaptureAuthorityRejected(
			policy = stepPolicy(revision = 1L, enabled = true),
			consent = null,
		)
	}

	@Test
	fun ineligibleHistoricalStepsConsentFailsClosed() = runTest {
		assertCandidateCaptureAuthorityRejected(
			policy = stepPolicy(revision = 1L, enabled = true),
			consent = stepCaptureConsent(epoch = 1L, policyRevision = 1L).copy(eligible = false),
		)
	}

	@Test
	fun nonPersistentHistoricalStepsConsentFailsClosed() = runTest {
		assertCandidateCaptureAuthorityRejected(
			policy = stepPolicy(revision = 1L, enabled = true),
			consent = stepCaptureConsent(epoch = 1L, policyRevision = 1L).copy(
				persistenceEligible = false,
			),
		)
	}

	@Test
	fun futurePolicyHistoricalStepsConsentFailsClosed() = runTest {
		assertCandidateCaptureAuthorityRejected(
			policy = stepPolicy(revision = 1L, enabled = true),
			consent = stepCaptureConsent(epoch = 1L, policyRevision = 2L),
		)
	}

	@Test
	fun generationOneAutomaticCaptureFailsClosed() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)),
			targetOrdinal = 1L,
			laneCursor = 1L,
			sessionMode = AUTOMATIC_SESSION_MODE,
			writerBindingGeneration = BINDING_GENERATION,
		)

		selector.select(segment(RUN_ONE, steps = null)) shouldBe
			failedResult(StepsHistoryReason.PRODUCT_LANE_INVALID)
	}

	@Test
	fun generationTwoAutomaticCaptureUsesTheExactExecutableLane() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)),
			targetOrdinal = 1L,
			laneCursor = 1L,
			sessionMode = AUTOMATIC_SESSION_MODE,
			writerBindingGeneration = AUTOMATIC_BINDING_GENERATION,
		)

		selector.select(segment(RUN_ONE, steps = null)) shouldBe StepsSegmentHistoryResult(
			count = 5L,
			availability = StepsHistoryAvailability.AVAILABLE,
			evidence = StepsHistoryEvidence.RECORDED,
			materialization = StepsHistoryMaterialization.READY,
			coverage = StepsHistoryCoverage.COMPLETE,
			reasons = emptySet(),
		)
	}

	@Test
	fun unknownStepsWriterGenerationFailsClosed() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)),
			targetOrdinal = 1L,
			laneCursor = 1L,
			writerBindingGeneration = UNKNOWN_BINDING_GENERATION,
		)

		selector.select(segment(RUN_ONE, steps = null)) shouldBe
			failedResult(StepsHistoryReason.PRODUCT_LANE_INVALID)
	}

	@Test
	fun observableLookupReactsWhenMissingCaptureConsentIsRestored() = runBlocking {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)),
			targetOrdinal = 1L,
			laneCursor = 1L,
			seedStepCaptureAuthority = false,
		)
		database.sourcePolicyDao().insertPolicies(
			listOf(stepPolicy(revision = 1L, enabled = true)),
		)
		val segmentId = database.sessionSegmentDao().insert(segment(RUN_ONE, steps = null))
		val repository = historyRepository()
		val initialEmission = CompletableDeferred<Unit>()
		val collection = async {
			repository.observeSession(segmentId)
				.onEach { initialEmission.complete(Unit) }
				.take(2)
				.toList()
		}

		withTimeout(5_000L) { initialEmission.await() }
		database.sourcePolicyDao().insertConsentEpochs(
			listOf(stepCaptureConsent(epoch = 1L, policyRevision = 1L)),
		)

		val emissions = withTimeout(5_000L) { collection.await() }
		val rejected = emissions.first() as SessionHistoryQuery.Found
		rejected.history.qualifiedSources shouldBe emptySet()
		rejected.history.steps.count shouldBe null
		rejected.history.steps.productState shouldBe HistoryProductState.FAILED
		rejected.history.steps.causes shouldBe setOf(StepsHistoryCause.HISTORY_INTEGRITY_FAILED)
		val restored = emissions.last() as SessionHistoryQuery.Found
		restored.history.qualifiedSources shouldBe setOf(HistorySource.STEPS)
		restored.history.steps.count shouldBe 5L
		restored.history.steps.productState shouldBe HistoryProductState.READY
		Unit
	}

	@Test
	fun observableProductionLookupUsesOnePersistedSnapshotAndEndsAtNotFoundAfterDeletion() = runBlocking {
		insertRun(RUN_ONE)
		insertManifest(RUN_ONE, revision = 1L, owner = LEGACY_OWNER)
		val persisted = segment(RUN_ONE, steps = 14)
		val segmentId = database.sessionSegmentDao().insert(persisted)
		val repository = DefaultTrackingHistoryRepository(
			database,
			selector,
			logicalHistoryReader,
			Dispatchers.IO,
		)
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
		found.history.capture shouldBe HistoryCapture.Exact(
			listOf(
				com.adsamcik.tracker.stats.api.repository.HistoryCaptureRevision(
					revision = 1L,
					effectiveAt = com.adsamcik.tracker.stats.api.value.EpochMs(1_000L),
					capturedSources = setOf(HistorySource.STEPS),
					controlSources = emptySet(),
				),
			),
		)
		found.history.qualifiedSources shouldBe setOf(HistorySource.STEPS)
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
		val repository = DefaultTrackingHistoryRepository(
			database,
			selector,
			logicalHistoryReader,
			Dispatchers.IO,
		)
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
		val repository = DefaultTrackingHistoryRepository(
			database,
			selector,
			logicalHistoryReader,
			Dispatchers.IO,
		)
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
	fun observableLookupReactsToRunFenceAsDeletedWithoutRemovingTheSegment() = runBlocking {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)),
			targetOrdinal = 1L,
			laneCursor = 1L,
		)
		val persisted = segment(RUN_ONE, steps = 5)
		val segmentId = database.sessionSegmentDao().insert(persisted)
		val repository = DefaultTrackingHistoryRepository(
			database,
			selector,
			logicalHistoryReader,
			Dispatchers.IO,
		)
		val initialEmission = CompletableDeferred<Unit>()
		val collection = async {
			repository.observeSession(segmentId)
				.onEach { initialEmission.complete(Unit) }
				.take(2)
				.toList()
		}

		withTimeout(5_000L) { initialEmission.await() }
		database.sourceDeletionFenceDao().upsert(
			SourceDeletionFenceEntity.createLogicalServiceRun(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
				logicalTrackingId = LOGICAL_ID,
				serviceRunId = RUN_ONE,
				fenceGeneration = 1L,
				collectedDataEpoch = 0L,
				deletedAtMs = 3_000L,
			),
		)

		val emissions = withTimeout(5_000L) { collection.await() }
		val ready = emissions.first() as
			com.adsamcik.tracker.stats.api.repository.SessionHistoryQuery.Found
		ready.history.steps.count shouldBe 5L
		val deleted = emissions.last() as
			com.adsamcik.tracker.stats.api.repository.SessionHistoryQuery.Found
		deleted.history.steps.availability shouldBe
			com.adsamcik.tracker.stats.api.repository.HistoryAvailability.AVAILABLE
		deleted.history.steps.productState shouldBe
			com.adsamcik.tracker.stats.api.repository.HistoryProductState.PARTIAL
		deleted.history.steps.causes shouldBe setOf(
			com.adsamcik.tracker.stats.api.repository.StepsHistoryCause.DELETED,
		)
		deleted.history.steps.count shouldBe null
		database.sessionSegmentDao().getById(segmentId) shouldBe persisted.copy(id = segmentId)
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
	@Suppress("LongMethod")
	fun validFactsRemainQueryableAcrossEveryBoundedIdentityPage() = runTest {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = (1L..FORMER_FACT_DEPENDENCY_CAP).map { ordinal ->
				fact(
					ordinal = ordinal,
					coverage = StepFactRevisionEntity.COVERAGE_COVERED,
					steps = 1L,
					cumulativeStart = ordinal - 1L,
					cumulativeEnd = ordinal,
				)
			},
			targetOrdinal = FORMER_FACT_DEPENDENCY_CAP,
			laneCursor = FORMER_FACT_DEPENDENCY_CAP,
		)

		selector.select(segment(RUN_ONE, steps = null)).also { exactCap ->
			exactCap.count shouldBe FORMER_FACT_DEPENDENCY_CAP
			exactCap.materialization shouldBe StepsHistoryMaterialization.READY
			exactCap.coverage shouldBe StepsHistoryCoverage.COMPLETE
		}

		val nextPageOrdinal = FORMER_FACT_DEPENDENCY_CAP + 1L
		database.stepFactRevisionDao().insert(
			fact(
				ordinal = nextPageOrdinal,
				coverage = StepFactRevisionEntity.COVERAGE_COVERED,
				steps = 1L,
				cumulativeStart = nextPageOrdinal - 1L,
				cumulativeEnd = nextPageOrdinal,
			),
		)
		database.sourceSessionDao().saveCompleteness(
			completeness(RUN_ONE, lastOrdinal = nextPageOrdinal),
		)
		advanceLane(
			expectedCursor = FORMER_FACT_DEPENDENCY_CAP,
			throughOrdinal = nextPageOrdinal,
		)

		selector.select(segment(RUN_ONE, steps = null)).also { nextPage ->
			nextPage.count shouldBe nextPageOrdinal
			nextPage.materialization shouldBe StepsHistoryMaterialization.READY
			nextPage.coverage shouldBe StepsHistoryCoverage.COMPLETE
		}
	}

	@Test
	fun identityPagingUsesDatabaseOrderingAcrossUnicodeBoundary() = runTest {
		val sourceEventIds = (1..255).map { index ->
			"ascii-${index.toString().padStart(3, '0')}"
		} + listOf("\uE000-fact", "\uD800\uDC00-fact")
		insertCandidateRun(
			runId = RUN_ONE,
			facts = sourceEventIds.mapIndexed { index, sourceEventId ->
				fact(
					ordinal = index + 1L,
					coverage = StepFactRevisionEntity.COVERAGE_COVERED,
					steps = 1L,
					cumulativeStart = index.toLong(),
					cumulativeEnd = index + 1L,
				).withSourceEventIdentity(sourceEventId)
			},
			targetOrdinal = sourceEventIds.size.toLong(),
			laneCursor = sourceEventIds.size.toLong(),
		)

		selector.select(segment(RUN_ONE, steps = null)).also { result ->
			result.count shouldBe sourceEventIds.size.toLong()
			result.materialization shouldBe StepsHistoryMaterialization.READY
			result.coverage shouldBe StepsHistoryCoverage.COMPLETE
		}
	}

	@Test
	// Keep the complete cap+1 counterfactual together so dependency truncation alone is proven fail-closed.
	@Suppress("LongMethod")
	fun terminalFailureDependencyOverflowMakesAffectedHistoryUnavailableBeforeTruncatedRows() =
		runTest {
			val candidateFailureOrdinal = TERMINAL_FAILURE_DEPENDENCY_CAP + 2L
			insertCandidateRun(
				runId = RUN_ONE,
				facts = listOf(fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)),
				targetOrdinal = candidateFailureOrdinal,
				laneCursor = candidateFailureOrdinal,
			)
			insertRun(RUN_TWO, sessionSegmentId = OTHER_SEGMENT_ID)
			val otherWriterSource = SessionManifestSourceEntity(
				logicalTrackingId = LOGICAL_ID,
				manifestRevision = 2L,
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				consentEpoch = 1L,
				persistenceEligible = true,
				qosCode = 1,
				outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
				writerOwner = CANDIDATE_OWNER,
				writerOwnerGeneration = 2L,
				writerProjectionId = OVERFLOW_OTHER_WRITER_ID,
				writerProjectionVersion = OVERFLOW_OTHER_WRITER_VERSION,
				writerBindingGeneration = OVERFLOW_OTHER_BINDING_GENERATION,
			)
			insertExactManifest(
				runId = RUN_TWO,
				revision = 2L,
				sources = listOf(otherWriterSource),
			)
			database.sourceSessionDao().saveCompleteness(
				completeness(RUN_TWO, lastOrdinal = TERMINAL_FAILURE_DEPENDENCY_CAP + 1L),
			)
			database.sourceProjectionStateDao().installProductLane(
				lane(
					cursor = TERMINAL_FAILURE_DEPENDENCY_CAP + 1L,
					projectionId = OVERFLOW_OTHER_WRITER_ID,
					projectionVersion = OVERFLOW_OTHER_WRITER_VERSION,
					bindingGeneration = OVERFLOW_OTHER_BINDING_GENERATION,
				),
			)
			database.sessionSegmentDao().insert(segment(RUN_ONE, steps = 900, id = SEGMENT_ID))
			database.sessionSegmentDao().insert(
				segment(
					runId = RUN_TWO,
					steps = 700,
					id = OTHER_SEGMENT_ID,
					startTimeMs = 2_100L,
					endTimeMs = 3_000L,
				),
			)
			for (ordinal in 1L..TERMINAL_FAILURE_DEPENDENCY_CAP + 1L) {
				database.sourceProjectionStateDao().saveFailure(
					SourceProjectionFailureEntity(
						projectionId = OVERFLOW_OTHER_WRITER_ID,
						projectionVersion = OVERFLOW_OTHER_WRITER_VERSION,
						admissionOrdinal = ordinal,
						attemptCount = 1,
						failureCode = "terminal-$ordinal",
						terminal = true,
						lastAttemptAtMs = ordinal,
					),
				)
			}
			database.sourceProjectionStateDao().saveFailure(
				SourceProjectionFailureEntity(
					projectionId = WRITER_ID,
					projectionVersion = WRITER_VERSION,
					admissionOrdinal = candidateFailureOrdinal,
					attemptCount = 1,
					failureCode = "candidate-terminal",
					terminal = true,
					lastAttemptAtMs = candidateFailureOrdinal,
				),
			)

			val evidence = selector.selectEvidenceBySegmentIds(
				listOf(SEGMENT_ID, OTHER_SEGMENT_ID),
			).getValue(SEGMENT_ID)
			evidence.captureAuthority shouldBe HistoricalCaptureAuthority.Unverifiable(
				HistoricalCaptureFailure.BATCH_DEPENDENCY_OVERFLOW,
			)
			evidence.steps shouldBe StepsSegmentHistoryResult(
				count = null,
				availability = StepsHistoryAvailability.UNAVAILABLE,
				evidence = StepsHistoryEvidence.NO_OBSERVATION,
				materialization = StepsHistoryMaterialization.FAILED,
				coverage = StepsHistoryCoverage.UNKNOWN,
				reasons = setOf(StepsHistoryReason.BATCH_DEPENDENCY_OVERFLOW),
			)
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
				fact(
					ordinal = 2L,
					coverage = StepFactRevisionEntity.COVERAGE_RESET_GAP,
					steps = 0L,
					cumulativeStart = 105L,
					cumulativeEnd = 0L,
				),
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
				fact(
					ordinal = 2L,
					coverage = StepFactRevisionEntity.COVERAGE_BASELINE,
					steps = 0L,
					cumulativeStart = 0L,
					cumulativeEnd = 0L,
				),
				fact(
					ordinal = 3L,
					coverage = StepFactRevisionEntity.COVERAGE_COVERED,
					steps = 1L,
					cumulativeStart = 0L,
					cumulativeEnd = 1L,
				),
			),
			targetOrdinal = 3L,
			laneCursor = 3L,
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
				fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L).copy(
					intervalStartTimeMs = 1_101L,
					intervalEndTimeMs = 1_101L,
				),
				fact(
					ordinal = 2L,
					coverage = StepFactRevisionEntity.COVERAGE_COVERED,
					steps = 3L,
					cumulativeStart = 105L,
					cumulativeEnd = 108L,
				),
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
	fun unfencedSoleFactRetractionFailsIntegrityInsteadOfClaimingDeletion() = runTest {
		val original = fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(original),
			targetOrdinal = 1L,
			laneCursor = 1L,
		)
		database.stepFactRevisionDao().insert(retraction(original))

		selector.select(segment(RUN_ONE, steps = 999)) shouldBe failedFactIntegrityResult()
	}

	@Test
	fun unfencedPartialFactRetractionCannotReduceTheVisibleTotal() = runTest {
		val deleted = fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)
		val surviving = fact(2L, StepFactRevisionEntity.COVERAGE_COVERED, 3L)
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(deleted, surviving),
			targetOrdinal = 2L,
			laneCursor = 2L,
		)
		database.stepFactRevisionDao().insert(retraction(deleted))

		selector.select(segment(RUN_ONE, steps = 999)) shouldBe failedFactIntegrityResult()
	}

	@Test
	fun staleUnfencedRetractionFailsDeletionAuthorityBeforeEpochInterpretation() = runTest {
		val current = fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L).copy(
			collectedDataEpoch = 1L,
		).withValidLiveWalEffectChecksum()
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

		selector.select(segment(RUN_ONE, steps = 999)) shouldBe failedFactIntegrityResult()
	}

	@Test
	fun currentEpochRetractionStillRequiresDurableDeletionFence() = runTest {
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

		selector.select(segment(RUN_ONE, steps = 999)) shouldBe failedFactIntegrityResult()
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

	private suspend fun selectRecentEvidence(limit: Int): List<HistoricalSegmentEvidence> =
		logicalHistoryReader.selectRecentEntries(limit).flatMap { entry ->
			entry.physicalMembers.filter(HistoricalSegmentEvidence::isOrdinarilyDiscoverable)
		}

	private fun failedFactIntegrityResult() = failedResult(
		StepsHistoryReason.STEP_FACT_INTEGRITY_FAILED,
	)

	private fun invalidCompletenessResult() = failedResult(
		StepsHistoryReason.COMPLETENESS_INVALID,
	)

	private fun failedResult(reason: StepsHistoryReason) = StepsSegmentHistoryResult(
		count = null,
		availability = StepsHistoryAvailability.AVAILABLE,
		evidence = StepsHistoryEvidence.NO_OBSERVATION,
		materialization = StepsHistoryMaterialization.FAILED,
		coverage = StepsHistoryCoverage.UNKNOWN,
		reasons = setOf(reason),
	)

	private fun unavailableResult(reason: StepsHistoryReason) = StepsSegmentHistoryResult(
		count = null,
		availability = StepsHistoryAvailability.UNAVAILABLE,
		evidence = StepsHistoryEvidence.NO_OBSERVATION,
		materialization = StepsHistoryMaterialization.FAILED,
		coverage = StepsHistoryCoverage.UNKNOWN,
		reasons = setOf(reason),
	)

	private fun historyRepository() = DefaultTrackingHistoryRepository(
		database = database,
		stepsSelector = selector,
		logicalHistoryReader = logicalHistoryReader,
		ioDispatcher = Dispatchers.IO,
	)

	private suspend fun insertRunFence(runId: String, deletedAtMs: Long) {
		database.sourceDeletionFenceDao().upsert(
			SourceDeletionFenceEntity.createLogicalServiceRun(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
				logicalTrackingId = LOGICAL_ID,
				serviceRunId = runId,
				fenceGeneration = 1L,
				collectedDataEpoch = 0L,
				deletedAtMs = deletedAtMs,
			),
		)
	}

	private suspend fun assertManifestTimelineRejected() {
		val evidence = selector.selectEvidence(segment(RUN_ONE, steps = 14))
		evidence.captureAuthority shouldBe HistoricalCaptureAuthority.Unverifiable(
			HistoricalCaptureFailure.MANIFEST_INTEGRITY_FAILED,
		)
		evidence.steps shouldBe unavailableResult(StepsHistoryReason.MANIFEST_INTEGRITY_FAILED)
		evidence.qualifiedSources shouldBe emptySet()
	}

	private suspend fun assertCandidateCaptureAuthorityRejected(
		policy: SourcePolicyEntity?,
		consent: SourceConsentEpochEntity?,
	) {
		insertCandidateRun(
			runId = RUN_ONE,
			facts = listOf(fact(1L, StepFactRevisionEntity.COVERAGE_COVERED, 5L)),
			targetOrdinal = 1L,
			laneCursor = 1L,
			seedStepCaptureAuthority = false,
		)
		policy?.let { database.sourcePolicyDao().insertPolicies(listOf(it)) }
		consent?.let { database.sourcePolicyDao().insertConsentEpochs(listOf(it)) }

		val evidence = selector.selectEvidence(segment(RUN_ONE, steps = null))
		evidence.steps shouldBe failedResult(StepsHistoryReason.SOURCE_POLICY_ATTRIBUTION_INVALID)
		evidence.qualifiedSources shouldBe emptySet()
	}

	private suspend fun insertCandidateRun(
		runId: String,
		facts: List<StepFactRevisionEntity>,
		targetOrdinal: Long,
		laneCursor: Long,
		logicalId: String = LOGICAL_ID,
		segmentId: Long = SEGMENT_ID,
		manifestRevision: Long = 1L,
		providerCoverage: String = COMPLETE_PROVIDER_COVERAGE,
		serviceRunCompleted: Boolean = true,
		sessionMode: String = MANUAL_SESSION_MODE,
		writerBindingGeneration: Long = BINDING_GENERATION,
		captureModeMask: Long = if (writerBindingGeneration == AUTOMATIC_BINDING_GENERATION) {
			MANUAL_AND_AUTOMATIC_CAPTURE_MASK
		} else {
			MANUAL_CAPTURE_MASK
		},
		seedStepCaptureAuthority: Boolean = true,
		installedLane: SourceProductProjectionLaneEntity = lane(
			cursor = laneCursor,
			bindingGeneration = writerBindingGeneration,
			captureModeMask = captureModeMask,
		),
		factTransform: (StepFactRevisionEntity) -> StepFactRevisionEntity = { it },
	) {
		insertRun(
			runId = runId,
			logicalId = logicalId,
			completed = serviceRunCompleted,
			sessionSegmentId = segmentId,
			startOrigin = if (sessionMode == AUTOMATIC_SESSION_MODE) {
				AUTOMATIC_START_ORIGIN
			} else {
				MANUAL_START_ORIGIN
			},
		)
		insertManifest(
			runId = runId,
			logicalId = logicalId,
			revision = manifestRevision,
			owner = CANDIDATE_OWNER,
			sessionMode = sessionMode,
			writerBindingGeneration = writerBindingGeneration,
			seedStepCaptureAuthority = seedStepCaptureAuthority,
		)
		database.sourceProjectionStateDao().installProductLane(installedLane)
		database.sourceSessionDao().saveCompleteness(
			completeness(
				runId = runId,
				logicalId = logicalId,
				lastOrdinal = targetOrdinal,
				providerCoverage = providerCoverage,
			),
		)
		facts.forEach { fact ->
			val transformed = factTransform(
				fact.withTestAttribution(
					logicalId = logicalId,
					runId = runId,
					manifestRevision = manifestRevision,
					writerBindingGeneration = writerBindingGeneration,
				),
			)
			database.stepFactRevisionDao().insert(
				transformed.withValidLiveWalEffectChecksum(),
			)
		}
	}

	private fun StepFactRevisionEntity.withTestAttribution(
		logicalId: String,
		runId: String,
		manifestRevision: Long,
		writerBindingGeneration: Long = BINDING_GENERATION,
	): StepFactRevisionEntity {
		val endTimeMs = requireNotNull(intervalEndTimeMs)
		val admissionOrdinal = requireNotNull(sourceAdmissionOrdinal)
		val elapsedEndNanos = Math.addExact(
			Math.addExact(
				Math.multiplyExact(manifestRevision, TEST_MANIFEST_ELAPSED_STRIDE),
				Math.multiplyExact(admissionOrdinal, TEST_FACT_ELAPSED_STRIDE),
			),
			1L,
		)
		val elapsedStartNanos = if (coverageKind == StepFactRevisionEntity.COVERAGE_BASELINE) {
			elapsedEndNanos
		} else {
			elapsedEndNanos - 1L
		}
		return copy(
			intervalStartTimeMs = endTimeMs,
			intervalEndTimeMs = endTimeMs,
			intervalStartElapsedRealtimeNanos = elapsedStartNanos,
			intervalEndElapsedRealtimeNanos = elapsedEndNanos,
			logicalTrackingId = logicalId,
			serviceRunId = runId,
			manifestRevision = manifestRevision,
			sourcePolicyRevision = manifestRevision,
			writerBindingGeneration = writerBindingGeneration,
			appliedAtMs = endTimeMs,
		)
	}

	private suspend fun insertRun(
		runId: String,
		logicalId: String = LOGICAL_ID,
		completed: Boolean = true,
		sessionSegmentId: Long? = SEGMENT_ID,
		presentationAcknowledgement: String = SourceServiceRunEntity.PRESENTATION_PENDING,
		presentationAcknowledgedAtMs: Long? = null,
		desiredPlanRevision: Long = 1L,
		rolloutRevision: Long = TEST_ROLLOUT_REVISION,
		startedAtMs: Long = TEST_RUN_STARTED_AT_MS,
		startedElapsedNanos: Long = TEST_RUN_STARTED_ELAPSED_NANOS,
		startOrigin: String = MANUAL_START_ORIGIN,
		preparedManifestRevision: Long = 1L,
	) {
		database.sourceSessionDao().insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = runId,
				logicalTrackingId = logicalId,
				state = if (completed) "FINALIZED" else "ACTIVE",
				desiredPlanRevision = desiredPlanRevision,
				rolloutRevision = rolloutRevision,
				foregroundCapabilityFlags = 0L,
				startedAtMs = startedAtMs,
				startedElapsedNanos = startedElapsedNanos,
				completedAtMs = (startedAtMs + TEST_RUN_DURATION_MS).takeIf { completed },
				completionReason = "STOPPED".takeIf { completed },
				bootId = "boot-1",
				leaseGeneration = 1L,
				startOrigin = startOrigin,
				runRevision = 1L,
				startDeliveryToken = "delivery-$runId",
				startCommandGeneration = 1L,
				preparedManifestRevision = preparedManifestRevision,
				preparedIntentRevision = 1L,
				startIsUserInitiated = startOrigin == MANUAL_START_ORIGIN,
				sessionSegmentId = sessionSegmentId,
				presentationAcknowledgement = presentationAcknowledgement,
				presentationAcknowledgedAtMs = presentationAcknowledgedAtMs,
			),
		)
	}

	private suspend fun insertManifest(
		runId: String,
		logicalId: String = LOGICAL_ID,
		revision: Long,
		owner: String,
		additionalSources: List<SessionManifestSourceEntity> = emptyList(),
		sessionMode: String = MANUAL_SESSION_MODE,
		writerBindingGeneration: Long = BINDING_GENERATION,
		seedStepCaptureAuthority: Boolean = true,
		acquisitionPlanRevision: Long = revision,
		rolloutRevision: Long = TEST_ROLLOUT_REVISION,
		startOrigin: String? = null,
		effectiveElapsedRealtimeNanos: Long? = null,
		effectiveWallTimeMs: Long? = null,
		synchronizeRunTimeline: Boolean = true,
	) {
		val candidate = owner == CANDIDATE_OWNER
		val source = SessionManifestSourceEntity(
			logicalTrackingId = logicalId,
			manifestRevision = revision,
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
			consentEpoch = 1L,
			persistenceEligible = true,
			qosCode = 1,
			outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
			writerOwner = owner,
			writerOwnerGeneration = if (candidate) 2L else 1L,
			writerProjectionId = WRITER_ID.takeIf { candidate },
			writerProjectionVersion = WRITER_VERSION.takeIf { candidate },
			writerBindingGeneration = writerBindingGeneration.takeIf { candidate },
		)
		val sources = listOf(source) + additionalSources
		insertExactManifest(
			runId = runId,
			logicalId = logicalId,
			revision = revision,
			sources = sources,
			sessionMode = sessionMode,
			seedStepCaptureAuthority = seedStepCaptureAuthority,
			acquisitionPlanRevision = acquisitionPlanRevision,
			rolloutRevision = rolloutRevision,
			startOrigin = startOrigin,
			effectiveElapsedRealtimeNanos = effectiveElapsedRealtimeNanos,
			effectiveWallTimeMs = effectiveWallTimeMs,
			synchronizeRunTimeline = synchronizeRunTimeline,
		)
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod")
	private suspend fun insertExactManifest(
		runId: String,
		logicalId: String = LOGICAL_ID,
		revision: Long,
		sources: List<SessionManifestSourceEntity>,
		sessionMode: String = MANUAL_SESSION_MODE,
		seedStepCaptureAuthority: Boolean = true,
		acquisitionPlanRevision: Long = revision,
		rolloutRevision: Long = TEST_ROLLOUT_REVISION,
		startOrigin: String? = null,
		effectiveElapsedRealtimeNanos: Long? = null,
		effectiveWallTimeMs: Long? = null,
		synchronizeRunTimeline: Boolean = true,
	) {
		val sourceSessionDao = database.sourceSessionDao()
		val priorManifest = sourceSessionDao.manifestsForServiceRun(runId).lastOrNull()
		val run = requireNotNull(sourceSessionDao.serviceRun(runId))
		val resolvedElapsedRealtimeNanos = effectiveElapsedRealtimeNanos ?: priorManifest
			?.effectiveElapsedRealtimeNanos
			?.plus(TEST_MANIFEST_ELAPSED_STRIDE)
			?: run.startedElapsedNanos
		val resolvedWallTimeMs = effectiveWallTimeMs ?: priorManifest?.effectiveWallTimeMs
			?.plus(TEST_MANIFEST_WALL_STRIDE_MS)
			?: run.startedAtMs
		val resolvedStartOrigin = startOrigin ?: if (priorManifest == null) {
			run.startOrigin
		} else {
			POLICY_RECONCILIATION_ORIGIN
		}
		if (synchronizeRunTimeline) {
			val completedAtMs = run.completedAtMs?.coerceAtLeast(resolvedWallTimeMs)
			val synchronizedRun = if (priorManifest == null) {
				run.copy(
					desiredPlanRevision = acquisitionPlanRevision,
					rolloutRevision = rolloutRevision,
					startedAtMs = resolvedWallTimeMs,
					startedElapsedNanos = resolvedElapsedRealtimeNanos,
					completedAtMs = completedAtMs,
					startOrigin = resolvedStartOrigin,
					preparedManifestRevision = revision,
					startIsUserInitiated = resolvedStartOrigin == MANUAL_START_ORIGIN,
				)
			} else {
				run.copy(
					desiredPlanRevision = acquisitionPlanRevision,
					completedAtMs = completedAtMs,
				)
			}
			if (synchronizedRun != run) {
				sourceSessionDao.updateServiceRun(synchronizedRun) shouldBe 1
			}
		}
		val unsigned = SessionManifestVersionEntity(
			logicalTrackingId = logicalId,
			manifestRevision = revision,
			serviceRunId = runId,
			sessionMode = sessionMode,
			sourcePolicyRevision = revision,
			acquisitionPlanRevision = acquisitionPlanRevision,
			rolloutRevision = rolloutRevision,
			startOrigin = resolvedStartOrigin,
			effectiveBootId = "boot-1",
			effectiveElapsedRealtimeNanos = resolvedElapsedRealtimeNanos,
			effectiveWallTimeMs = resolvedWallTimeMs,
			zoneId = "UTC",
			automationEpoch = FIRST_AUTOMATION_EPOCH.takeIf {
				sessionMode == AUTOMATIC_SESSION_MODE
			},
			changeReason = if (priorManifest == null) "TEST" else POLICY_RECONCILIATION_ORIGIN,
			manifestChecksum = "",
		)
		val manifest = unsigned.copy(
			manifestChecksum = SessionManifestIntegrity.compute(unsigned, sources),
		)
		sourceSessionDao.insertManifest(manifest)
		sourceSessionDao.insertManifestSources(sources)
		if (seedStepCaptureAuthority) {
			seedValidStepCaptureAuthority(
				manifestPolicyRevision = manifest.sourcePolicyRevision,
				sources = sources,
			)
		}
	}

	private fun sourceMembership(
		revision: Long,
		source: TrackingSourceComponent,
		purpose: String,
		logicalId: String = LOGICAL_ID,
		persistenceEligible: Boolean = purpose == SessionManifestPurposeCode.SESSION_CAPTURE,
	) = SessionManifestSourceEntity(
		logicalTrackingId = logicalId,
		manifestRevision = revision,
		sourceKind = source.stableCode,
		purpose = purpose,
		consentEpoch = 1L,
		persistenceEligible = persistenceEligible,
		qosCode = 1,
	)

	private suspend fun insertManifestWithoutSteps(runId: String, revision: Long) {
		val locationSource = SessionManifestSourceEntity(
			logicalTrackingId = LOGICAL_ID,
			manifestRevision = revision,
			sourceKind = SOURCE_LOCATION,
			purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
			consentEpoch = 1L,
			persistenceEligible = true,
			qosCode = 1,
		)
		insertExactManifest(
			runId = runId,
			revision = revision,
			sources = listOf(locationSource),
			seedStepCaptureAuthority = false,
		)
	}

	private suspend fun seedValidStepCaptureAuthority(
		manifestPolicyRevision: Long,
		sources: List<SessionManifestSourceEntity>,
	) {
		val sourcePolicyDao = database.sourcePolicyDao()
		sources.filter { source ->
			source.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
				source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
				source.persistenceEligible
		}.forEach { binding ->
			if (
				sourcePolicyDao.policyAtRevision(
					manifestPolicyRevision,
					SourceDestinationOwnerEntity.SOURCE_STEPS,
				) == null
			) {
				sourcePolicyDao.insertPolicies(
					listOf(
						stepPolicy(revision = manifestPolicyRevision, enabled = true).copy(
							qosCode = binding.qosCode,
							captureConsentEpoch = binding.consentEpoch,
						),
					),
				)
			}
			if (
				sourcePolicyDao.consentEpoch(
					SourceDestinationOwnerEntity.SOURCE_STEPS,
					SessionManifestPurposeCode.SESSION_CAPTURE,
					binding.consentEpoch,
				) == null
			) {
				sourcePolicyDao.insertConsentEpochs(
					listOf(
						stepCaptureConsent(
							epoch = binding.consentEpoch,
							policyRevision = FIRST_POLICY_REVISION,
						),
					),
				)
			}
		}
	}

	private fun stepPolicy(revision: Long, enabled: Boolean) = SourcePolicyEntity(
		policyRevision = revision,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		enabled = enabled,
		qosCode = if (enabled) 1 else 0,
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
		effectiveElapsedRealtimeNanos = revision * TEST_MANIFEST_ELAPSED_STRIDE,
		effectiveWallTimeMs = revision * 1_000L,
		changeReason = "TEST",
	)

	private fun stepCaptureConsent(
		epoch: Long,
		policyRevision: Long,
	) = SourceConsentEpochEntity(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
		epoch = epoch,
		eligible = true,
		persistenceEligible = true,
		policyRevision = policyRevision,
		effectiveBootId = "boot-1",
		effectiveElapsedRealtimeNanos = policyRevision * TEST_MANIFEST_ELAPSED_STRIDE,
		effectiveWallTimeMs = policyRevision * 1_000L,
		changeReason = "TEST",
	)

	private fun segment(
		runId: String?,
		logicalId: String? = LOGICAL_ID,
		steps: Int?,
		sampleCount: Int = 1,
		id: Long = SEGMENT_ID,
		startTimeMs: Long = 1_000L,
		endTimeMs: Long = 2_000L,
	) = SessionSegment(
		id = id,
		startTimeMs = startTimeMs,
		endTimeMs = endTimeMs,
		distanceM = 0f,
		steps = steps,
		primaryActivity = null,
		activityConfidence = null,
		sampleCount = sampleCount,
		source = SegmentSource.USER_CREATED,
		inferenceVersion = "test",
		createdAt = 2_000L,
		logicalTrackingId = logicalId,
		serviceRunId = runId,
	)

	private fun completeness(
		runId: String,
		logicalId: String = LOGICAL_ID,
		lastOrdinal: Long?,
		providerCoverage: String = COMPLETE_PROVIDER_COVERAGE,
		registrationGeneration: Long = 1L,
		sourceInstanceId: String = "steps-instance-$registrationGeneration",
	) = SourceSessionCompletenessEntity(
		logicalTrackingId = logicalId,
		serviceRunId = runId,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		sourceInstanceId = sourceInstanceId,
		registrationGeneration = registrationGeneration,
		lastAdmissionOrdinal = lastOrdinal,
		lastSourceSequence = lastOrdinal,
		appDrainComplete = true,
		providerCoverage = providerCoverage,
		stopStatus = "COMPLETE",
		unresolvedSequenceStart = null,
		unresolvedSequenceEnd = null,
		updatedAtMs = 2_000L,
	)

	private fun lane(
		cursor: Long,
		projectionId: String = WRITER_ID,
		projectionVersion: Int = WRITER_VERSION,
		bindingGeneration: Long = BINDING_GENERATION,
		captureModeMask: Long = MANUAL_CAPTURE_MASK,
	) = SourceProductProjectionLaneEntity(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		bindingGeneration = bindingGeneration,
		projectionId = projectionId,
		projectionVersion = projectionVersion,
		captureModeMask = captureModeMask,
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
	): StepFactRevisionEntity {
		val sourceEventId = "event-$ordinal"
		val logicalFactId = "$WRITER_ID:$sourceEventId"
		return StepFactRevisionEntity(
		logicalFactId = logicalFactId,
		semanticRevision = 1L,
		mutationId = "$logicalFactId:1:${StepFactRevisionEntity.OPERATION_UPSERT}",
		stepIntervalId = null,
		sourceEventId = sourceEventId,
		sourceAdmissionOrdinal = ordinal,
		originKind = StepFactRevisionEntity.ORIGIN_LIVE_WAL,
		originIdentity = sourceEventId,
		writerProjectionId = WRITER_ID,
		writerProjectionVersion = WRITER_VERSION,
		writerBindingGeneration = BINDING_GENERATION,
		operation = StepFactRevisionEntity.OPERATION_UPSERT,
		intervalStartTimeMs = 1_500L,
		intervalEndTimeMs = 1_500L,
		intervalStartElapsedRealtimeNanos = TEST_MANIFEST_ELAPSED_STRIDE +
			ordinal * TEST_FACT_ELAPSED_STRIDE +
			if (coverage == StepFactRevisionEntity.COVERAGE_BASELINE) 1L else 0L,
		intervalEndElapsedRealtimeNanos = TEST_MANIFEST_ELAPSED_STRIDE +
			ordinal * TEST_FACT_ELAPSED_STRIDE + 1L,
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
		effectiveStepCount = if (
			coverage == StepFactRevisionEntity.COVERAGE_BASELINE ||
			coverage == StepFactRevisionEntity.COVERAGE_RESET_GAP ||
			coverage == StepFactRevisionEntity.COVERAGE_PARTIAL
		) 0L else steps,
		logicalTrackingId = LOGICAL_ID,
		serviceRunId = RUN_ONE,
		purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
		manifestRevision = 1L,
		sourcePolicyRevision = 1L,
		captureConsentEpoch = 1L,
		collectedDataEpoch = 0L,
		scopeDeletionGeneration = 0L,
		effectChecksum = "checksum-$ordinal",
		appliedAtMs = 1_500L,
	).withValidLiveWalEffectChecksum()
	}

	private fun StepFactRevisionEntity.withValidLiveWalEffectChecksum(): StepFactRevisionEntity =
		copy(effectChecksum = StepFactRevisionIntegrity.liveWalEffectChecksum(this))

	private fun StepFactRevisionEntity.withSourceEventIdentity(
		sourceEventId: String,
	): StepFactRevisionEntity {
		val logicalFactId = "$writerProjectionId:$sourceEventId"
		return copy(
			logicalFactId = logicalFactId,
			mutationId = "$logicalFactId:$semanticRevision:${StepFactRevisionEntity.OPERATION_UPSERT}",
			sourceEventId = sourceEventId,
			originIdentity = sourceEventId,
		).withValidLiveWalEffectChecksum()
	}

	private fun retraction(
		fact: StepFactRevisionEntity,
		collectedDataEpoch: Long = fact.collectedDataEpoch,
	): StepFactRevisionEntity {
		val scopeIdentityDigest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			purpose = fact.purpose,
			logicalTrackingId = requireNotNull(fact.logicalTrackingId),
			serviceRunId = requireNotNull(fact.serviceRunId),
		)
		val semanticRevision = fact.semanticRevision + 1L
		val deletionGeneration = 1L
		val unsigned = fact.copy(
			semanticRevision = semanticRevision,
			mutationId = StepFactRevisionIntegrity.localDeleteMutationId(
				scopeIdentityDigest = scopeIdentityDigest,
				logicalFactId = fact.logicalFactId,
				semanticRevision = semanticRevision,
				scopeDeletionGeneration = deletionGeneration,
			),
			stepIntervalId = null,
			sourceEventId = null,
			sourceAdmissionOrdinal = null,
			originKind = StepFactRevisionEntity.ORIGIN_LOCAL_DELETE,
			originIdentity = scopeIdentityDigest,
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
			scopeDeletionGeneration = deletionGeneration,
			effectChecksum = "pending-local-delete-checksum",
			appliedAtMs = 3_000L,
		)
		return unsigned.copy(
			effectChecksum = StepFactRevisionIntegrity.localDeleteEffectChecksum(unsigned),
		)
	}

	private fun owner(owner: String, generation: Long) = SourceDestinationOwnerEntity(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
		owner = owner,
		ownerGeneration = generation,
		updatedAtMs = 1L,
	)

	private fun executableLaneAuthority() = SourceProductLaneExecutionAuthority { lane ->
		if (
			lane.sourceKind != SourceDestinationOwnerEntity.SOURCE_STEPS ||
			lane.projectionId != WRITER_ID || lane.projectionVersion != WRITER_VERSION
		) {
			false
		} else {
			when (lane.bindingGeneration) {
				BINDING_GENERATION -> lane.captureModeMask == MANUAL_CAPTURE_MASK
				AUTOMATIC_BINDING_GENERATION ->
					lane.captureModeMask == MANUAL_AND_AUTOMATIC_CAPTURE_MASK
				else -> false
			}
		}
	}

	private data class HistoryRunFixture(
		val runId: String,
		val logicalId: String,
		val manifestRevision: Long,
		val segmentId: Long,
		val startTimeMs: Long,
		val ordinal: Long,
	)

	private companion object {
		const val LOGICAL_ID = "logical-1"
		const val RUN_ONE = "run-1"
		const val RUN_TWO = "run-2"
		const val SEGMENT_ID = 41L
		const val OTHER_SEGMENT_ID = 42L
		const val SOURCE_LOCATION = 1
		const val WRITER_ID = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID
		const val WRITER_VERSION = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION
		const val BINDING_GENERATION = SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION
		const val AUTOMATIC_BINDING_GENERATION =
			SourceDestinationOwnerEntity.STEPS_FACT_AUTOMATIC_BINDING_GENERATION
		const val UNKNOWN_BINDING_GENERATION = 99L
		const val MANUAL_CAPTURE_MASK = 1L
		const val AUTOMATIC_CAPTURE_MASK = 1L shl 1
		const val MANUAL_AND_AUTOMATIC_CAPTURE_MASK = MANUAL_CAPTURE_MASK + AUTOMATIC_CAPTURE_MASK
		const val MANUAL_SESSION_MODE = "MANUAL"
		const val AUTOMATIC_SESSION_MODE = "AUTOMATIC"
		const val MANUAL_START_ORIGIN = "MANUAL_FOREGROUND_START"
		const val AUTOMATIC_START_ORIGIN = "AUTOMATIC_BACKGROUND_START"
		const val POLICY_RECONCILIATION_ORIGIN = "POLICY_RECONCILIATION"
		const val TEST_ROLLOUT_REVISION = 2L
		const val TEST_RUN_STARTED_AT_MS = 1_000L
		const val TEST_RUN_STARTED_ELAPSED_NANOS = 10_000_000L
		const val TEST_RUN_DURATION_MS = 1_000L
		const val FIRST_AUTOMATION_EPOCH = 1L
		const val FIRST_POLICY_REVISION = 1L
		const val FORMER_FACT_DEPENDENCY_CAP = 2_048L
		const val TEST_MANIFEST_ELAPSED_STRIDE = 10_000_000L
		const val TEST_FACT_ELAPSED_STRIDE = 1L
		const val TEST_MANIFEST_WALL_STRIDE_MS = 1_000L
		const val TERMINAL_FAILURE_DEPENDENCY_CAP = 2_048L
		const val OVERFLOW_OTHER_WRITER_ID = "overflow-other-writer"
		const val OVERFLOW_OTHER_WRITER_VERSION = 1
		const val OVERFLOW_OTHER_BINDING_GENERATION = 101L
		const val LEGACY_OWNER = SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL
		const val CANDIDATE_OWNER = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS
		const val COMPLETE_PROVIDER_COVERAGE = "CALLBACKS_ENTERED_BEFORE_BARRIER"
	}
}
