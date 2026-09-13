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
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.model.SegmentSource
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
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
	private suspend fun insertReplacementFixture(): PressureFixture {
		val segment = segment(
			id = REPLACEMENT_SEGMENT_ID,
			runId = REPLACEMENT_RUN_ID,
			startTimeMs = REPLACEMENT_RUN_START_MS,
			endTimeMs = REPLACEMENT_RUN_END_MS,
		)
		database.sessionSegmentDao().insert(segment)
		database.sourceSessionDao().insertServiceRun(
			serviceRun(
				runId = REPLACEMENT_RUN_ID,
				segmentId = REPLACEMENT_SEGMENT_ID,
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
				sourceInstanceId = "pressure-provider-2",
				registrationGeneration = 2L,
				lastAdmissionOrdinal = 2L,
				lastSourceSequence = 2L,
				appDrainComplete = true,
				providerCoverage = COMPLETE_PROVIDER_COVERAGE,
				stopStatus = "COMPLETE",
				unresolvedSequenceStart = null,
				unresolvedSequenceEnd = null,
				updatedAtMs = REPLACEMENT_RUN_END_MS,
			),
		)
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
		return PressureFixture(segment.id, LOGICAL_ID, REPLACEMENT_RUN_ID)
	}

	private fun segment(
		id: Long = SEGMENT_ID,
		runId: String = RUN_ID,
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
		logicalTrackingId = LOGICAL_ID,
		serviceRunId = runId,
	)

	private fun serviceRun(
		runId: String = RUN_ID,
		segmentId: Long = SEGMENT_ID,
		startTimeMs: Long = RUN_START_MS,
		endTimeMs: Long = RUN_END_MS,
		startElapsedNanos: Long = RUN_START_ELAPSED_NANOS,
		manifestRevision: Long = 1L,
	) = SourceServiceRunEntity(
		serviceRunId = runId,
		logicalTrackingId = LOGICAL_ID,
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

	private fun pressureBinding(manifestRevision: Long = 1L) = SessionManifestSourceEntity(
		logicalTrackingId = LOGICAL_ID,
		manifestRevision = manifestRevision,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
		consentEpoch = 1L,
		persistenceEligible = true,
		qosCode = 1,
		outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
		writerOwner = SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS,
		writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
		writerProjectionId = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
		writerProjectionVersion = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
		writerBindingGeneration = SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION,
	)

	private fun pressurePolicy() = SourcePolicyEntity(
		policyRevision = 1L,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		enabled = true,
		qosCode = 1,
		locationMinTimeSeconds = null,
		locationMinDistanceMeters = null,
		locationRequiredAccuracyMeters = null,
		capturePersistenceEligible = true,
		controlPersistenceEligible = false,
		ambientPersistenceEligible = false,
		captureConsentEpoch = 1L,
		controlConsentEpoch = null,
		ambientConsentEpoch = null,
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = RUN_START_ELAPSED_NANOS,
		effectiveWallTimeMs = RUN_START_MS,
		changeReason = "TEST",
	)

	private fun pressureConsent() = SourceConsentEpochEntity(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
		epoch = 1L,
		eligible = true,
		persistenceEligible = true,
		policyRevision = 1L,
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = RUN_START_ELAPSED_NANOS,
		effectiveWallTimeMs = RUN_START_MS,
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
		serviceRunId: String = RUN_ID,
		manifestRevision: Long = 1L,
		intervalStartTimeMs: Long = PRESSURE_START_MS,
		intervalEndTimeMs: Long = PRESSURE_END_MS,
		windowStartElapsedRealtimeNanos: Long = PRESSURE_START_ELAPSED_NANOS,
		windowEndElapsedRealtimeNanos: Long = PRESSURE_END_ELAPSED_NANOS,
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
			logicalTrackingId = LOGICAL_ID,
			serviceRunId = serviceRunId,
			purpose = PressureFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			manifestRevision = manifestRevision,
			sourcePolicyRevision = 1L,
			captureConsentEpoch = 1L,
			collectedDataEpoch = 0L,
			effectChecksum = "pending",
			appliedAtMs = intervalEndTimeMs,
		)
		return unsigned.copy(
			effectChecksum = PressureFactRevisionIntegrity.effectChecksum(unsigned, binding),
		)
	}

	private fun executableLaneAuthority() = SourceProductLaneExecutionAuthority { lane ->
		lane.sourceKind == SourceDestinationOwnerEntity.SOURCE_PRESSURE &&
			lane.bindingGeneration == SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION &&
			lane.projectionId == SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID &&
			lane.projectionVersion == SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION &&
			lane.captureModeMask == MANUAL_CAPTURE_MASK
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
		const val MANUAL_CAPTURE_MASK = 1L
		const val ROLLOUT_REVISION = 2L
		const val RUN_START_MS = 1_000L
		const val RUN_END_MS = 2_000L
		const val RUN_START_ELAPSED_NANOS = 10_000_000L
		const val PRESSURE_START_MS = 1_100L
		const val PRESSURE_END_MS = 1_250L
		const val PRESSURE_START_ELAPSED_NANOS = 110_000_000L
		const val PRESSURE_END_ELAPSED_NANOS = 260_000_000L
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
