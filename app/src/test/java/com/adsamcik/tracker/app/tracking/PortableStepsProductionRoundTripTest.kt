package com.adsamcik.tracker.app.tracking

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
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
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.time.FixedClock
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsResult
import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.HistoryCapture
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.ImportPortableStepsResult
import com.adsamcik.tracker.stats.api.repository.PortableStepsEntryV1
import com.adsamcik.tracker.stats.api.repository.SessionHistoryQuery
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCoverage
import com.adsamcik.tracker.stats.data.metric.DefaultMetricDirtyTracker
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Cross-module host contract over the production Room exporter, importer, and history facade. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PortableStepsProductionRoundTripTest {
	private lateinit var source: AppDatabase
	private lateinit var destination: AppDatabase
	private val laneAuthority = SourceProductLaneExecutionAuthority { lane ->
		lane.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
			lane.projectionId == WRITER_ID && lane.projectionVersion == WRITER_VERSION &&
			lane.bindingGeneration == BINDING_GENERATION
	}

	@Before
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		source = AppDatabase.testDatabase(context)
		destination = AppDatabase.testDatabase(context)
		source.sourceEvidenceStateDao().ensure(SourceEvidenceState())
		source.sourceProjectionStateDao().installProductLane(productLane())
		destination.sourceDestinationOwnerDao().insertIfAbsent(
			SourceDestinationOwnerEntity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
				owner = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS,
				ownerGeneration = 2L,
				updatedAtMs = 1L,
			),
		)
	}

	@After
	fun tearDown() {
		source.close()
		destination.close()
	}

	@Test
	fun `local source exports then imports into fresh Room history without fabricating authority`() = runTest {
		seedLocalStepsOnlySession()
		val sourceExporter = PortableStepsRoundTripInternals.exporter(
			source, laneAuthority, Dispatchers.Unconfined,
		)
		val portable = mutableListOf<PortableStepsEntryV1>()

		sourceExporter.export(ExportPortableStepsRequest(0L, Long.MAX_VALUE)) { portable += it } shouldBe
			ExportPortableStepsResult.Exported(1)
		portable.single().runs.single().also { run ->
			run.facts.map { it.stepCount } shouldContainExactly listOf(12L)
			run.manifests.map { it.source.name } shouldContainExactly listOf("STEPS")
			run.manifests.map { it.purpose.name } shouldContainExactly listOf("SESSION_CAPTURE")
		}

		val destinationExporter = PortableStepsRoundTripInternals.exporter(
			destination, SourceProductLaneExecutionAuthority { false }, Dispatchers.Unconfined,
		)
		val importer = PortableStepsRoundTripInternals.importer(
			destination,
			FixedLifecycleStore(),
			ReadyStartupGate,
			FixedClock(10_000L),
			Dispatchers.Unconfined,
			DefaultMetricDirtyTracker(),
			destinationExporter,
		)

		importer.importEntry(portable.single()) shouldBe ImportPortableStepsResult.Applied(1, 1)
		val importedRun = requireNotNull(
			destination.importedStepsDao().run(portable.single().runs.single().identity.value),
		)
		val segmentId = requireNotNull(importedRun.sessionSegmentId)
		val history = PortableStepsRoundTripInternals.history(
			destination, SourceProductLaneExecutionAuthority { false }, Dispatchers.Unconfined,
		)
		val selected = (history.observeSession(segmentId).first() as SessionHistoryQuery.Found).history

		(selected.capture is HistoryCapture.ImportedSteps) shouldBe true
		selected.capturesOnlySteps shouldBe false
		selected.qualifiedSources shouldBe setOf(HistorySource.STEPS)
		selected.steps.count shouldBe 12L
		selected.steps.availability shouldBe HistoryAvailability.RETAINED_IMPORTED
		selected.steps.productState shouldBe HistoryProductState.READY
		selected.steps.coverage shouldBe StepsHistoryCoverage.COMPLETE
		destination.trackingHistoryReadDao().serviceRuns(
			listOf(portable.single().runs.single().identity.value),
		) shouldBe emptyList()
		val importedFact = requireNotNull(
			destination.stepFactRevisionDao().latest(
				WRITER_ID,
				WRITER_VERSION,
				portable.single().runs.single().facts.single().identity.value,
			),
		)
		importedFact.originKind shouldBe StepFactRevisionEntity.ORIGIN_PORTABLE_IMPORT
		importedFact.sourceEventId shouldBe null
		importedFact.sourceAdmissionOrdinal shouldBe null

		val reExported = mutableListOf<PortableStepsEntryV1>()
		destinationExporter.export(ExportPortableStepsRequest(0L, Long.MAX_VALUE)) { reExported += it } shouldBe
			ExportPortableStepsResult.Exported(1)
		reExported shouldBe portable
	}

	@Suppress("LongMethod")
	private suspend fun seedLocalStepsOnlySession() {
		source.sourcePolicyDao().insertPolicies(listOf(policy()))
		source.sourcePolicyDao().insertConsentEpochs(listOf(consent()))
		source.sourceSessionDao().insertSession(
			LogicalTrackingSessionEntity(
				logicalTrackingId = LOGICAL_ID,
				state = "FINALIZED",
				lifecycleRevision = 1L,
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				startOrigin = "MANUAL_FOREGROUND_START",
				clockDomainId = "boot-1",
				startedAtMs = START_MS,
				startedElapsedNanos = START_MS,
				cutoffAtMs = END_MS,
				cutoffElapsedNanos = END_MS,
				completedAtMs = END_MS,
				finalAdmissionOrdinal = 1L,
				failureCode = null,
				sessionMode = "MANUAL",
				currentManifestRevision = null,
				currentIntentRevision = null,
				currentServiceRunId = null,
				automationEpoch = null,
			),
		)
		source.sourceSessionDao().insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = RUN_ID,
				logicalTrackingId = LOGICAL_ID,
				state = "FINALIZED",
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				foregroundCapabilityFlags = 0L,
				startedAtMs = START_MS,
				startedElapsedNanos = START_MS,
				completedAtMs = END_MS,
				completionReason = "STOPPED",
				bootId = "boot-1",
				leaseGeneration = 1L,
				startOrigin = "MANUAL_FOREGROUND_START",
				runRevision = 1L,
				startDeliveryToken = "delivery-1",
				startCommandGeneration = 1L,
				preparedManifestRevision = 1L,
				preparedIntentRevision = 1L,
				sessionSegmentId = SEGMENT_ID,
				presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_QUIESCED,
				presentationAcknowledgedAtMs = END_MS,
			),
		)
		val sources = listOf(
			SessionManifestSourceEntity(
				logicalTrackingId = LOGICAL_ID,
				manifestRevision = 1L,
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				consentEpoch = 1L,
				persistenceEligible = true,
				qosCode = 1,
				outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
				writerOwner = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS,
				writerOwnerGeneration = 2L,
				writerProjectionId = WRITER_ID,
				writerProjectionVersion = WRITER_VERSION,
				writerBindingGeneration = BINDING_GENERATION,
			),
			SessionManifestSourceEntity(
				logicalTrackingId = LOGICAL_ID,
				manifestRevision = 1L,
				sourceKind = TrackingSourceComponent.ACTIVITY.stableCode,
				purpose = SessionManifestPurposeCode.CONTROL,
				consentEpoch = 1L,
				persistenceEligible = false,
				qosCode = 1,
			),
		)
		val unsignedManifest = SessionManifestVersionEntity(
			logicalTrackingId = LOGICAL_ID,
			manifestRevision = 1L,
			serviceRunId = RUN_ID,
			sessionMode = "MANUAL",
			sourcePolicyRevision = 1L,
			acquisitionPlanRevision = 1L,
			rolloutRevision = 1L,
			startOrigin = "MANUAL_FOREGROUND_START",
			effectiveBootId = "boot-1",
			effectiveElapsedRealtimeNanos = START_MS,
			effectiveWallTimeMs = START_MS,
			zoneId = "Europe/Prague",
			automationEpoch = null,
			changeReason = "TEST",
			manifestChecksum = "",
		)
		source.sourceSessionDao().insertManifest(
			unsignedManifest.copy(
				manifestChecksum = SessionManifestIntegrity.compute(unsignedManifest, sources),
			),
		)
		source.sourceSessionDao().insertManifestSources(sources)
		source.sessionSegmentDao().insert(
			SessionSegment(
				id = SEGMENT_ID,
				startTimeMs = START_MS,
				endTimeMs = END_MS,
				distanceM = 0f,
				steps = null,
				primaryActivity = null,
				activityConfidence = null,
				sampleCount = 0,
				source = SegmentSource.USER_CREATED,
				inferenceVersion = "round-trip-test",
				createdAt = END_MS,
				logicalTrackingId = LOGICAL_ID,
				serviceRunId = RUN_ID,
			),
		)
		val unsignedFact = StepFactRevisionEntity(
			logicalFactId = "$WRITER_ID:event-1",
			semanticRevision = 1L,
			mutationId = "$WRITER_ID:event-1:1:UPSERT",
			stepIntervalId = null,
			sourceEventId = "event-1",
			sourceAdmissionOrdinal = 1L,
			originKind = StepFactRevisionEntity.ORIGIN_LIVE_WAL,
			originIdentity = "event-1",
			writerProjectionId = WRITER_ID,
			writerProjectionVersion = WRITER_VERSION,
			writerBindingGeneration = BINDING_GENERATION,
			operation = StepFactRevisionEntity.OPERATION_UPSERT,
			intervalStartTimeMs = START_MS + 10L,
			intervalEndTimeMs = END_MS - 10L,
			intervalStartElapsedRealtimeNanos = START_MS + 10L,
			intervalEndElapsedRealtimeNanos = END_MS - 10L,
			clockDomainId = "boot-1",
			bootClockDomainId = "boot-1",
			cumulativeStepCountStart = 100L,
			cumulativeStepCountEnd = 112L,
			wallTimeUncertaintyMs = 0L,
			coverageKind = StepFactRevisionEntity.COVERAGE_COVERED,
			effectiveStepCount = 12L,
			logicalTrackingId = LOGICAL_ID,
			serviceRunId = RUN_ID,
			purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			manifestRevision = 1L,
			sourcePolicyRevision = 1L,
			captureConsentEpoch = 1L,
			collectedDataEpoch = 0L,
			scopeDeletionGeneration = 0L,
			effectChecksum = "pending",
			appliedAtMs = END_MS,
		)
		source.stepFactRevisionDao().insert(
			unsignedFact.copy(effectChecksum = StepFactRevisionIntegrity.liveWalEffectChecksum(unsignedFact)),
		)
		source.sourceSessionDao().saveCompleteness(
			SourceSessionCompletenessEntity(
				logicalTrackingId = LOGICAL_ID,
				serviceRunId = RUN_ID,
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				sourceInstanceId = "steps-1",
				registrationGeneration = 1L,
				lastAdmissionOrdinal = 1L,
				lastSourceSequence = 1L,
				appDrainComplete = true,
				providerCoverage = "COMPLETE",
				stopStatus = "COMPLETE",
				unresolvedSequenceStart = null,
				unresolvedSequenceEnd = null,
				updatedAtMs = END_MS,
			),
		)
	}

	private fun policy() = SourcePolicyEntity(
		policyRevision = 1L,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
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
		effectiveBootId = "boot-1",
		effectiveElapsedRealtimeNanos = 1L,
		effectiveWallTimeMs = 1L,
		changeReason = "TEST",
	)

	private fun consent() = SourceConsentEpochEntity(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
		epoch = 1L,
		eligible = true,
		persistenceEligible = true,
		policyRevision = 1L,
		effectiveBootId = "boot-1",
		effectiveElapsedRealtimeNanos = 1L,
		effectiveWallTimeMs = 1L,
		changeReason = "TEST",
	)

	private fun productLane() = SourceProductProjectionLaneEntity(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		bindingGeneration = BINDING_GENERATION,
		projectionId = WRITER_ID,
		projectionVersion = WRITER_VERSION,
		captureModeMask = 1L,
		productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
		activatedRolloutRevision = 1L,
		activationOrdinal = 1L,
		contiguousAdmissionOrdinal = 1L,
		retentionRequired = true,
		status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
		installedAtMs = 1L,
		updatedAtMs = 1L,
	)

	private class FixedLifecycleStore : CollectedDataLifecycleStore {
		private val state = MutableStateFlow(CollectedDataLifecycleSnapshot(epoch = 3L, retainedFromMs = null))
		override val snapshots: Flow<CollectedDataLifecycleSnapshot> = state
		override suspend fun snapshot(): CollectedDataLifecycleSnapshot = state.value
		override suspend fun beginFullDeletion(deletedAtMs: Long) = error("Not used")
		override suspend fun advanceRetainedFrom(retainedFromMs: Long) = error("Not used")
	}

	private data object ReadyStartupGate : TrackingStartupGate {
		override val isReady: Boolean = true
		override val currentGeneration: Long = 1L
		override suspend fun reconcile(retryFailedStorage: Boolean) =
			TrackingStartupResult.Ready(legacyRecoveryPartial = false, liveCompletedThroughOrdinal = 0L)
	}

	private companion object {
		const val LOGICAL_ID = "round-trip-logical"
		const val RUN_ID = "round-trip-run"
		const val SEGMENT_ID = 41L
		const val START_MS = 1_000L
		const val END_MS = 2_000L
		const val WRITER_ID = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID
		const val WRITER_VERSION = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION
		const val BINDING_GENERATION = SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION
	}
}
