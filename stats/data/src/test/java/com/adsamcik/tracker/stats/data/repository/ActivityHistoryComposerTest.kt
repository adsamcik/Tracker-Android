package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedEvidenceEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedFragmentEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedRegistrationPlanEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedWindowCursorEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedWindowRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCause
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryFragment
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryProductState
import io.kotest.matchers.collections.shouldHaveSize
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
class ActivityHistoryComposerTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		val application = ApplicationProvider.getApplicationContext<Application>()
		database = AppDatabase.testDatabase(application)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `ordinary discovery finds an Activity-only zero-sample segment from its fact`() = runTest {
		val fixture = fixture(listOf(RunSpec(1L, "run-a", "UTC")))
		val segment = fixture.expansion.segments.single()
		segment.sampleCount shouldBe 0
		database.sessionSegmentDao().insert(segment)
		database.sourceSessionDao().insertServiceRun(fixture.runs.getValue("run-a"))
		database.activityCapturedFactDao().insertRevision(fixture.revisions.single())

		val candidates = database.activityCapturedFactDao().logicalHistoryCandidatePage(10, null, null)

		candidates shouldHaveSize 1
		candidates.single().segment.id shouldBe segment.id
	}

	@Test
	fun `replacement runs compose as one logical entry while preserving bands and stored zones`() {
		val snapshot = fixture(
			listOf(RunSpec(1L, "run-a", "Europe/Prague"), RunSpec(2L, "run-b", "UTC")),
		)

		val entries = ActivityHistoryComposer.composeRecent(snapshot)

		entries shouldHaveSize 1
		val entry = entries.single().entry
		entry.state shouldBe ActivityHistoryProductState.READY
		entry.coverage shouldBe ActivityHistoryCoverage.COMPLETE
		entry.fragments shouldHaveSize 2
		entry.activeTime?.knownActiveDurationNanos shouldBe 200L
		entry.storedZoneIds shouldBe setOf("Europe/Prague", "UTC")
		entry.key.toString() shouldBe "ActivityHistoryEntryKey"
	}

	@Test
	fun `explicit persisted gap remains partial and is not converted into a zero observation`() {
		val snapshot = fixture(listOf(RunSpec(1L, "run-a", "UTC", gap = true)))

		val entry = ActivityHistoryComposer.composeRecent(snapshot).single().entry

		entry.state shouldBe ActivityHistoryProductState.READY
		entry.coverage shouldBe ActivityHistoryCoverage.PARTIAL
		entry.causes shouldBe setOf(ActivityHistoryCause.PROVIDER_GAP)
		entry.fragments.single()::class shouldBe ActivityHistoryFragment.Gap::class
		entry.activeTime?.unobservedDurationNanos shouldBe 100L
	}

	@Test
	fun `missing retained facts is typed unavailable and never fabricates active time`() {
		val snapshot = fixture(listOf(RunSpec(1L, "run-a", "UTC"))).copy(
			revisions = emptyList(),
			cursors = emptyList(),
			fragmentsByRevision = emptyMap(),
			evidenceByRevision = emptyMap(),
			completenessByRun = mapOf(
				"run-a" to listOf(completeness("run-a", generation = 0L, stop = "PROVIDER_FAILED")),
			),
		)

		val entry = ActivityHistoryComposer.composeRecent(snapshot).single().entry

		entry.state shouldBe ActivityHistoryProductState.UNAVAILABLE
		entry.causes shouldBe setOf(ActivityHistoryCause.PROVIDER_UNAVAILABLE)
		entry.activeTime shouldBe null
		entry.fragments shouldBe emptyList()
	}

	@Test
	fun `correction moved outside its immutable physical owner fails the whole logical entry`() {
		val original = fixture(listOf(RunSpec(1L, "run-a", "UTC")))
		val first = original.revisions.single()
		val corrupt = first.copy(
			semanticRevision = 2L,
			supersedesSemanticRevision = 1L,
			mutationId = ActivityCapturedFactIntegrity.mutationId(first.logicalWindowId, 2L),
			serviceRunId = "moved-run",
		)
		val snapshot = original.copy(revisions = listOf(first, corrupt))

		val entry = ActivityHistoryComposer.composeRecent(snapshot).single().entry

		entry.state shouldBe ActivityHistoryProductState.FAILED
		entry.causes shouldBe setOf(ActivityHistoryCause.FACT_INTEGRITY_FAILED)
	}

	@Test
	fun `bounded snapshot overflow is a typed failure with all content hidden`() {
		val snapshot = fixture(listOf(RunSpec(1L, "run-a", "UTC"))).copy(overflow = true)

		val entry = ActivityHistoryComposer.composeRecent(snapshot).single().entry

		entry.state shouldBe ActivityHistoryProductState.FAILED
		entry.causes shouldBe setOf(ActivityHistoryCause.READ_BUDGET_EXCEEDED)
		entry.activeTime shouldBe null
		entry.fragments shouldBe emptyList()
	}

	@Test
	fun `active run with no fact is materializing rather than a fabricated zero`() {
		val initial = fixture(listOf(RunSpec(1L, "run-a", "UTC")))
		val activeRun = initial.runs.getValue("run-a").copy(
			state = "ACTIVE",
			completedAtMs = null,
			completionReason = null,
			presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_PENDING,
			presentationAcknowledgedAtMs = null,
		)
		val snapshot = initial.copy(
			runs = mapOf("run-a" to activeRun),
			revisions = emptyList(),
			cursors = emptyList(),
			fragmentsByRevision = emptyMap(),
			evidenceByRevision = emptyMap(),
			completenessByRun = emptyMap(),
		)

		val entry = ActivityHistoryComposer.composeRecent(snapshot).single().entry

		entry.state shouldBe ActivityHistoryProductState.MATERIALIZING
		entry.activeTime shouldBe null
		entry.fragments shouldBe emptyList()
	}

	private fun fixture(specs: List<RunSpec>): ActivityHistorySnapshot {
		val built = specs.map(::buildRun)
		return ActivityHistorySnapshot(
			expansion = ActivityMembershipExpansion(built.map(BuiltRun::segment), emptyMap(), false),
			runs = built.associate { it.run.serviceRunId to it.run },
			manifestsByRun = built.associate { it.run.serviceRunId to listOf(it.manifest) },
			sourcesByManifest = built.associate {
				ActivityManifestKey(LOGICAL_ID, it.manifest.manifestRevision) to listOf(it.source)
			},
			policies = built.associate { it.policy.policyRevision to it.policy },
			consents = built.associate { it.consent.epoch to it.consent },
			completenessByRun = built.associate { it.run.serviceRunId to listOf(it.completeness) },
			revisions = built.map(BuiltRun::revision),
			cursors = built.map(BuiltRun::cursor),
			fragmentsByRevision = built.associate { revisionKey(it.revision) to listOf(it.fragment) },
			evidenceByRevision = built.associate { revisionKey(it.revision) to it.evidence },
			registrationPlans = built.associate {
				(it.plan.sourceInstanceId to it.plan.registrationGeneration) to it.plan
			},
			providerRegistrations = built.associate {
				it.provider.registrationGeneration to it.provider
			},
			authorizationsByRegistration = built.flatMap(BuiltRun::authorizations)
				.groupBy(SourceAuthorizationEntity::registrationGeneration),
			deletionFenceDigests = emptySet(),
			lanes = listOf(lane(specs.size.toLong())),
			terminalFailures = emptyList(),
			evidenceState = SourceEvidenceState(collectedDataEpoch = 0L),
			overflow = false,
		)
	}

	private fun buildRun(spec: RunSpec): BuiltRun {
		val baseElapsed = spec.revision * 1_000L
		val segment = SessionSegment(
			id = spec.revision,
			startTimeMs = baseElapsed,
			endTimeMs = baseElapsed + 500L,
			distanceM = 0f,
			steps = null,
			primaryActivity = null,
			activityConfidence = null,
			sampleCount = 0,
			source = SegmentSource.INFERRED_HIGH_CONFIDENCE,
			inferenceVersion = null,
			createdAt = baseElapsed,
			logicalTrackingId = LOGICAL_ID,
			serviceRunId = spec.runId,
		)
		val run = SourceServiceRunEntity(
			serviceRunId = spec.runId,
			logicalTrackingId = LOGICAL_ID,
			state = "FINALIZED",
			desiredPlanRevision = spec.revision,
			rolloutRevision = 1L,
			foregroundCapabilityFlags = 0L,
			startedAtMs = baseElapsed,
			startedElapsedNanos = baseElapsed,
			completedAtMs = baseElapsed + 500L,
			completionReason = "STOPPED",
			bootId = "boot-${spec.revision}",
			leaseGeneration = 1L,
			startOrigin = "MANUAL_FOREGROUND_START",
			desiredForegroundCapabilityFlags = 0L,
			appliedForegroundCapabilityFlags = 0L,
			runtimeAcknowledgement = "ACKNOWLEDGED",
			runRevision = 1L,
			startDeliveryToken = "delivery-${spec.revision}",
			startCommandGeneration = 1L,
			preparedManifestRevision = spec.revision,
			preparedIntentRevision = 1L,
			androidDeliveryState = "ACKNOWLEDGED",
			androidDeliveryUpdatedAtMs = baseElapsed,
			startIsUserInitiated = true,
			sessionSegmentId = segment.id,
			presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_QUIESCED,
			presentationAcknowledgedAtMs = baseElapsed + 500L,
		)
		val source = SessionManifestSourceEntity(
			logicalTrackingId = LOGICAL_ID,
			manifestRevision = spec.revision,
			sourceKind = ACTIVITY_SOURCE,
			purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
			consentEpoch = spec.revision,
			persistenceEligible = true,
			qosCode = 2,
			outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY,
			writerOwner = SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS,
			writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
			writerProjectionId = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID,
			writerProjectionVersion = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION,
			writerBindingGeneration = SourceDestinationOwnerEntity.ACTIVITY_FACT_BINDING_GENERATION,
		)
		val unsignedManifest = SessionManifestVersionEntity(
			logicalTrackingId = LOGICAL_ID,
			manifestRevision = spec.revision,
			serviceRunId = spec.runId,
			sessionMode = "MANUAL",
			sourcePolicyRevision = spec.revision,
			acquisitionPlanRevision = spec.revision,
			rolloutRevision = 1L,
			startOrigin = "MANUAL_FOREGROUND_START",
			effectiveBootId = run.bootId,
			effectiveElapsedRealtimeNanos = baseElapsed,
			effectiveWallTimeMs = baseElapsed,
			zoneId = spec.zone,
			automationEpoch = null,
			changeReason = "START",
			manifestChecksum = "pending",
		)
		val manifest = unsignedManifest.copy(
			manifestChecksum = SessionManifestIntegrity.compute(unsignedManifest, listOf(source)),
		)
		val policy = SourcePolicyEntity(
			policyRevision = spec.revision,
			sourceKind = ACTIVITY_SOURCE,
			enabled = true,
			qosCode = 2,
			locationMinTimeSeconds = null,
			locationMinDistanceMeters = null,
			locationRequiredAccuracyMeters = null,
			capturePersistenceEligible = true,
			controlPersistenceEligible = false,
			ambientPersistenceEligible = false,
			captureConsentEpoch = spec.revision,
			controlConsentEpoch = null,
			ambientConsentEpoch = null,
			effectiveBootId = run.bootId,
			effectiveElapsedRealtimeNanos = baseElapsed,
			effectiveWallTimeMs = baseElapsed,
			changeReason = "START",
		)
		val consent = SourceConsentEpochEntity(
			sourceKind = ACTIVITY_SOURCE,
			purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
			epoch = spec.revision,
			eligible = true,
			persistenceEligible = true,
			policyRevision = spec.revision,
			effectiveBootId = run.bootId,
			effectiveElapsedRealtimeNanos = baseElapsed,
			effectiveWallTimeMs = baseElapsed,
			changeReason = "START",
		)
		val plan = ActivityCapturedRegistrationPlanEntity.create(
			sourceInstanceId = "activity-${spec.revision}",
			registrationGeneration = spec.revision,
			configurationRevision = spec.revision,
			desiredPlanPayloadVersion = 1,
			desiredPlanPayloadChecksum = "plan-${spec.revision}",
			physicalConfigurationFingerprint = "config-${spec.revision}",
			appliedAtElapsedRealtimeNanos = baseElapsed,
			applyStatus = "APPLIED",
		)
		val provider = ProviderRegistrationGenerationEntity(
			sourceKind = ACTIVITY_SOURCE,
			registrationGeneration = spec.revision,
			sourceInstanceId = plan.sourceInstanceId,
			ownerScope = "session-activity",
			clockDomainId = run.bootId,
			physicalConfigurationFingerprint = plan.physicalConfigurationFingerprint,
			collectedDataEpoch = 0L,
			providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
			providerProcessIncarnationId = "process-${spec.revision}",
			status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
			reservedAtMs = baseElapsed,
			reservedElapsedRealtimeNanos = baseElapsed,
			acceptedAtMs = baseElapsed,
			acceptedElapsedRealtimeNanos = baseElapsed,
			retiredAtMs = baseElapsed + 200L,
			retiredElapsedRealtimeNanos = baseElapsed + 200L,
			failureCode = null,
			captureCallbackBarrierAuthorizationRevision = 1L,
		)
		val authorizations = authorizations(spec, run, baseElapsed)
		val draft = revision(spec, run, plan, baseElapsed)
		val logicalWindowId = ActivityCapturedFactIntegrity.logicalWindowId(draft)
		val identified = draft.copy(
			logicalWindowId = logicalWindowId,
			mutationId = ActivityCapturedFactIntegrity.mutationId(logicalWindowId, 1L),
		)
		val fragment = fragment(spec, identified, baseElapsed)
		val evidence = if (spec.gap) emptyList() else listOf(evidence(spec, identified, baseElapsed))
		val revision = identified.copy(
			effectChecksum = ActivityCapturedFactIntegrity.effectChecksum(identified, listOf(fragment), evidence),
		)
		return BuiltRun(
			segment, run, manifest, source, policy, consent, plan, provider, authorizations,
			revision, fragment, evidence,
			ActivityCapturedWindowCursorEntity(
				writerProjectionId = revision.writerProjectionId,
				writerProjectionVersion = revision.writerProjectionVersion,
				logicalWindowId = revision.logicalWindowId,
				logicalTrackingId = LOGICAL_ID,
				serviceRunId = spec.runId,
				sessionSegmentId = segment.id,
				writerOwnerGeneration = revision.writerOwnerGeneration,
				latestSemanticRevision = 1L,
				latestMutationId = revision.mutationId,
				latestEffectChecksum = revision.effectChecksum,
				cursorRevision = 1L,
				collectedDataEpoch = 0L,
				updatedAtMs = baseElapsed + 500L,
			),
			completeness(spec.runId, spec.revision, "COMPLETE"),
		)
	}

	@Suppress("LongParameterList")
	private fun revision(
		spec: RunSpec,
		run: SourceServiceRunEntity,
		plan: ActivityCapturedRegistrationPlanEntity,
		base: Long,
	) = ActivityCapturedWindowRevisionEntity(
		writerProjectionId = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID,
		writerProjectionVersion = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION,
		writerBindingGeneration = SourceDestinationOwnerEntity.ACTIVITY_FACT_BINDING_GENERATION,
		writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
		logicalWindowId = "pending",
		semanticRevision = 1L,
		supersedesSemanticRevision = null,
		mutationId = "pending",
		logicalTrackingId = LOGICAL_ID,
		serviceRunId = spec.runId,
		sessionSegmentId = requireNotNull(run.sessionSegmentId),
		purpose = SourceBrokerPurpose.SESSION_CAPTURE,
		sourceInstanceId = plan.sourceInstanceId,
		registrationGeneration = plan.registrationGeneration,
		configurationRevision = plan.configurationRevision,
		physicalConfigurationFingerprint = plan.physicalConfigurationFingerprint,
		authorizationRevision = 1L,
		authorizationFingerprint = "a".repeat(64),
		purposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
		sourcePolicyRevision = spec.revision,
		captureConsentEpoch = spec.revision,
		manifestRevision = spec.revision,
		lifecycleLeaseGeneration = run.leaseGeneration,
		collectedDataEpoch = 0L,
		clockDomainId = run.bootId,
		storedZoneId = spec.zone,
		providerAcceptanceStartNanos = base,
		providerAcceptanceEndNanos = base + 200L,
		authorizationEffectStartNanos = base,
		authorizationEffectEndNanos = base + 200L,
		sessionRunEffectStartNanos = base,
		sessionRunEffectEndNanos = base + 200L,
		windowStartElapsedRealtimeNanos = base + 10L,
		windowEndElapsedRealtimeNanos = base + 110L,
		coverage = if (spec.gap) "NONE" else "COMPLETE",
		knownActiveDurationNanos = if (spec.gap) 0L else 100L,
		knownInactiveDurationNanos = 0L,
		unknownActivityDurationNanos = 0L,
		unobservedDurationNanos = if (spec.gap) 100L else 0L,
		exactDuplicateCount = 0,
		semanticDuplicateCount = 0,
		unchangedEvidenceCount = 0,
		scopeDeletionGeneration = 0L,
		effectChecksum = "pending",
		appliedAtMs = base + 500L,
	)

	private fun fragment(
		spec: RunSpec,
		revision: ActivityCapturedWindowRevisionEntity,
		base: Long,
	): ActivityCapturedFragmentEntity = if (spec.gap) {
		ActivityCapturedFragmentEntity(
			writerProjectionId = revision.writerProjectionId,
			writerProjectionVersion = revision.writerProjectionVersion,
			logicalWindowId = revision.logicalWindowId,
			semanticRevision = 1L,
			fragmentOrdinal = 0,
			fragmentKind = ActivityCapturedFragmentEntity.KIND_GAP,
			bandOrdinal = null,
			intervalStartElapsedRealtimeNanos = base + 10L,
			intervalEndElapsedRealtimeNanos = base + 110L,
			gapReason = "PROVIDER_DISCONTINUITY",
			activity = null,
			mechanism = null,
			refinedTransitionActivity = null,
			confidenceKind = null,
			confidenceMinimumPercent = null,
			confidenceMaximumPercent = null,
			confidenceObservationCount = null,
			startWallTimeMs = null,
			startWallTimeUncertaintyMs = null,
			startBoundaryKind = null,
			startAnchorSourceEventId = null,
			startAnchorProviderElapsedNanos = null,
			endWallTimeMs = null,
			endWallTimeUncertaintyMs = null,
			endBoundaryKind = null,
			endAnchorSourceEventId = null,
			endAnchorProviderElapsedNanos = null,
			wallTimeContinuity = null,
		)
	} else {
		ActivityCapturedFragmentEntity(
			writerProjectionId = revision.writerProjectionId,
			writerProjectionVersion = revision.writerProjectionVersion,
			logicalWindowId = revision.logicalWindowId,
			semanticRevision = 1L,
			fragmentOrdinal = 0,
			fragmentKind = ActivityCapturedFragmentEntity.KIND_BAND,
			bandOrdinal = 0,
			intervalStartElapsedRealtimeNanos = base + 10L,
			intervalEndElapsedRealtimeNanos = base + 110L,
			gapReason = null,
			activity = "WALKING",
			mechanism = "TRANSITION",
			refinedTransitionActivity = null,
			confidenceKind = ActivityCapturedFragmentEntity.CONFIDENCE_TRANSITION,
			confidenceMinimumPercent = null,
			confidenceMaximumPercent = null,
			confidenceObservationCount = null,
			startWallTimeMs = base + 10L,
			startWallTimeUncertaintyMs = 0L,
			startBoundaryKind = "EXACT_PROVIDER_OBSERVATION",
			startAnchorSourceEventId = "event-${spec.revision}",
			startAnchorProviderElapsedNanos = base + 10L,
			endWallTimeMs = base + 110L,
			endWallTimeUncertaintyMs = 0L,
			endBoundaryKind = "SAME_CLOCK_EXTRAPOLATION",
			endAnchorSourceEventId = "event-${spec.revision}",
			endAnchorProviderElapsedNanos = base + 10L,
			wallTimeContinuity = "SAME_ANCHOR",
		)
	}

	private fun evidence(
		spec: RunSpec,
		revision: ActivityCapturedWindowRevisionEntity,
		base: Long,
	) = ActivityCapturedEvidenceEntity(
		writerProjectionId = revision.writerProjectionId,
		writerProjectionVersion = revision.writerProjectionVersion,
		logicalWindowId = revision.logicalWindowId,
		semanticRevision = 1L,
		fragmentOrdinal = 0,
		evidenceOrdinal = 0,
		sourceEventId = "event-${spec.revision}",
		sourceAdmissionOrdinal = spec.revision,
		sourceSequence = 1L,
		providerElapsedRealtimeNanos = base + 10L,
		receivedElapsedRealtimeNanos = base + 11L,
		observationKind = ActivityCapturedEvidenceEntity.KIND_TRANSITION,
		observedActivity = "WALKING",
		transitionChange = "ENTER",
		confidencePercent = null,
		coverageEndExclusiveElapsedRealtimeNanos = null,
	)

	private fun authorizations(
		spec: RunSpec,
		run: SourceServiceRunEntity,
		base: Long,
	): List<SourceAuthorizationEntity> = listOf(
		SourceAuthorizationEntity(
			sourceKind = ACTIVITY_SOURCE,
			registrationGeneration = spec.revision,
			authorizationRevision = 1L,
			memberId = SourceBrokerAuthorization.memberId("demand-${spec.revision}"),
			authorizationFingerprint = "a".repeat(64),
			purposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			demandId = "demand-${spec.revision}",
			consumerId = "session",
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			sourcePolicyRevision = spec.revision,
			consentEpoch = spec.revision,
			persistenceEligible = true,
			effectiveBootId = run.bootId,
			effectiveElapsedRealtimeNanos = base,
			effectiveWallTimeMs = base,
			logicalTrackingId = LOGICAL_ID,
			serviceRunId = spec.runId,
			manifestRevision = spec.revision,
			lifecycleLeaseGeneration = run.leaseGeneration,
		),
		SourceAuthorizationEntity(
			sourceKind = ACTIVITY_SOURCE,
			registrationGeneration = spec.revision,
			authorizationRevision = 2L,
			memberId = SourceBrokerAuthorization.DENY_ALL_MEMBER_ID,
			authorizationFingerprint = "b".repeat(64),
			purposeEligibilityMask = 0L,
			demandId = null,
			consumerId = null,
			purpose = null,
			sourcePolicyRevision = null,
			consentEpoch = null,
			persistenceEligible = false,
			effectiveBootId = run.bootId,
			effectiveElapsedRealtimeNanos = base + 200L,
			effectiveWallTimeMs = base + 200L,
			logicalTrackingId = null,
			serviceRunId = null,
			manifestRevision = null,
			lifecycleLeaseGeneration = null,
		),
	)

	private fun completeness(runId: String, generation: Long, stop: String) =
		SourceSessionCompletenessEntity(
			logicalTrackingId = LOGICAL_ID,
			serviceRunId = runId,
			sourceKind = ACTIVITY_SOURCE,
			sourceInstanceId = if (generation == 0L) "activity-unregistered" else "activity-$generation",
			registrationGeneration = generation,
			lastAdmissionOrdinal = generation.takeIf { it > 0L },
			lastSourceSequence = generation.takeIf { it > 0L },
			appDrainComplete = true,
			providerCoverage = if (generation == 0L) {
				"PROVIDER_COMPLETENESS_UNOBSERVABLE"
			} else {
				"CALLBACKS_ENTERED_BEFORE_BARRIER"
			},
			stopStatus = stop,
			unresolvedSequenceStart = null,
			unresolvedSequenceEnd = null,
			updatedAtMs = 10_000L,
		)

	private fun lane(throughOrdinal: Long) = SourceProductProjectionLaneEntity(
		sourceKind = ACTIVITY_SOURCE,
		bindingGeneration = SourceDestinationOwnerEntity.ACTIVITY_FACT_BINDING_GENERATION,
		projectionId = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID,
		projectionVersion = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION,
		captureModeMask = 1L,
		productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
		activatedRolloutRevision = 1L,
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

	private data class RunSpec(val revision: Long, val runId: String, val zone: String, val gap: Boolean = false)

	@Suppress("LongParameterList")
	private data class BuiltRun(
		val segment: SessionSegment,
		val run: SourceServiceRunEntity,
		val manifest: SessionManifestVersionEntity,
		val source: SessionManifestSourceEntity,
		val policy: SourcePolicyEntity,
		val consent: SourceConsentEpochEntity,
		val plan: ActivityCapturedRegistrationPlanEntity,
		val provider: ProviderRegistrationGenerationEntity,
		val authorizations: List<SourceAuthorizationEntity>,
		val revision: ActivityCapturedWindowRevisionEntity,
		val fragment: ActivityCapturedFragmentEntity,
		val evidence: List<ActivityCapturedEvidenceEntity>,
		val cursor: ActivityCapturedWindowCursorEntity,
		val completeness: SourceSessionCompletenessEntity,
	)

	private companion object {
		const val LOGICAL_ID = "logical-activity"
	}
}
