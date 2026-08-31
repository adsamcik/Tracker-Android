package com.adsamcik.tracker.tracker.source.projection

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneCatalog
import com.adsamcik.tracker.tracker.source.ingress.CorruptSourceEventException
import com.adsamcik.tracker.tracker.source.ingress.DefaultSourcePayloadCodec
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceIngress
import com.adsamcik.tracker.tracker.source.ingress.RoomDurableSourceIngress
import com.adsamcik.tracker.tracker.source.model.AdmittedSourceEvent
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.source.model.StepBoundaryKind
import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import javax.inject.Provider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Host proof for the exact TI-410 recording boundary.
 *
 * This deliberately begins after provider admission: integrity-valid, capture-attributed synthetic
 * raw rows are decoded through [RoomDurableSourceIngress] and then projected by the production Steps
 * writer. Admission qualification, provider callbacks, process death, UI, and platform listener
 * removal remain separate production/device gates. [StepsRecordingBoundary.Recorded] is a test
 * verdict, not a persisted production lifecycle state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StepsRecordingBoundaryIntegrationTest {
	private lateinit var database: AppDatabase
	private lateinit var ingress: DurableSourceIngress

	@Before
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(collectedDataEpoch = COLLECTED_DATA_EPOCH),
		)
		installCanonicalStepsLane()
		ingress = RoomDurableSourceIngress(
			database = database,
			lifecycleStore = mockk<CollectedDataLifecycleStore>(),
			payloadCodec = DefaultSourcePayloadCodec(),
			executableLaneCatalog = ExecutableSourceLaneCatalog(),
			trackingStartupGateProvider = Provider { mockk<TrackingStartupGate>() },
		)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `capture-attributed positive post-baseline WAL precedes canonical materialization`() = runTest {
		evaluateRecordingBoundary(throughOrdinal = 0L) shouldBe StepsRecordingBoundary.NoObservation

		insertCaptureAttributedStepsRow(
			ordinal = 1L,
			boundary = StepBoundaryKind.BASELINE,
			firstCount = 100L,
			lastCount = 100L,
			deltaCount = 0L,
		)
		evaluateRecordingBoundary(throughOrdinal = 1L) shouldBe
			StepsRecordingBoundary.BaselineOnly(baselineOrdinal = 1L)

		insertCaptureAttributedStepsRow(
			ordinal = 2L,
			boundary = StepBoundaryKind.COVERED,
			firstCount = 100L,
			lastCount = 100L,
			deltaCount = 0L,
		)
		evaluateRecordingBoundary(throughOrdinal = 2L) shouldBe
			StepsRecordingBoundary.CoveredWithoutPositiveDelta(throughOrdinal = 2L)

		insertCaptureAttributedStepsRow(
			ordinal = 3L,
			boundary = StepBoundaryKind.COVERED,
			firstCount = 100L,
			lastCount = 105L,
			deltaCount = 5L,
		)
		evaluateRecordingBoundary(throughOrdinal = 3L) shouldBe StepsRecordingBoundary.Recorded(
			eventId = "steps-event-3",
			admissionOrdinal = 3L,
			deltaCount = 5L,
		)

		database.stepFactRevisionDao().writerAdmission(
			StepsSessionFactProjectionLane.WRITER_ID,
			StepsSessionFactProjectionLane.WRITER_VERSION,
			3L,
		) shouldBe null
		activeLane().contiguousAdmissionOrdinal shouldBe 0L

		StepsSessionFactProjectionLane(database, ingress).drainThrough(3L) shouldBe
			StepsSessionFactDrainResult.Complete(
				lastCompletedOrdinal = 3L,
				factsInserted = 3,
				eventsValidated = 3,
			)

		val materialized = requireNotNull(database.stepFactRevisionDao().writerAdmission(
			StepsSessionFactProjectionLane.WRITER_ID,
			StepsSessionFactProjectionLane.WRITER_VERSION,
			3L,
		))
		materialized.sourceEventId shouldBe "steps-event-3"
		materialized.logicalTrackingId shouldBe LOGICAL_TRACKING_ID
		materialized.serviceRunId shouldBe SERVICE_RUN_ID
		materialized.manifestRevision shouldBe MANIFEST_REVISION
		materialized.purpose shouldBe StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE
		materialized.writerBindingGeneration shouldBe StepsSessionFactProjectionLane.BINDING_GENERATION
		materialized.effectiveStepCount shouldBe 5L
		activeLane().contiguousAdmissionOrdinal shouldBe 3L
		database.stepIntervalDao().getBySourceSignalId("source-event:steps-event-3") shouldBe null
	}

	@Test
	fun `corrupt raw Steps evidence after a valid prefix is typed unverifiable`() = runTest {
		insertCaptureAttributedStepsRow(
			ordinal = 1L,
			boundary = StepBoundaryKind.BASELINE,
			firstCount = 100L,
			lastCount = 100L,
			deltaCount = 0L,
		)
		insertCaptureAttributedStepsRow(
			ordinal = 2L,
			boundary = StepBoundaryKind.COVERED,
			firstCount = 100L,
			lastCount = 101L,
			deltaCount = 1L,
			corruptIntegrity = true,
		)

		evaluateRecordingBoundary(throughOrdinal = 2L) shouldBe
			StepsRecordingBoundary.Unverifiable("RAW_PAYLOAD_INTEGRITY")
	}

	private suspend fun evaluateRecordingBoundary(throughOrdinal: Long): StepsRecordingBoundary {
		if (throughOrdinal == 0L) {
			return StepsRecordingBoundary.NoObservation
		}
		return try {
			scanRecordingBoundary(throughOrdinal)
		} catch (corrupt: CorruptSourceEventException) {
			StepsRecordingBoundary.Unverifiable(corrupt.failureCode)
		}
	}

	private suspend fun scanRecordingBoundary(throughOrdinal: Long): StepsRecordingBoundary {
		var cursor = 0L
		val state = RecordingScanState()
		repeat(MAX_RECORDING_PAGES) {
			if (cursor >= throughOrdinal) {
				return state.result(cursor)
			}
			val events = ingress.committedSourceBatch(
				source = SourceKind.STEPS,
				afterOrdinal = cursor,
				throughOrdinal = throughOrdinal,
				limit = RECORDING_PAGE_SIZE,
			)
			if (events.isEmpty()) {
				return state.result(cursor)
			}
			for (event in events) {
				val terminal = state.accept(event)
				if (terminal != null) {
					return terminal
				}
			}
			cursor = events.last().admissionOrdinal
		}
		return if (cursor < throughOrdinal) {
			StepsRecordingBoundary.Unverifiable("STEPS_RECORDING_SCAN_LIMIT")
		} else {
			state.result(cursor)
		}
	}

	private fun AdmittedSourceEvent<out SourcePayload>.recordingAttributionFailure(): String? {
		val evidence = evidence
		return firstMismatch(
			mismatch(evidence.source != SourceKind.STEPS, "SOURCE_NOT_STEPS"),
			mismatch(evidence.logicalTrackingId?.value != LOGICAL_TRACKING_ID, "LOGICAL_SESSION_MISMATCH"),
			mismatch(evidence.serviceRunId?.value != SERVICE_RUN_ID, "SERVICE_RUN_MISMATCH"),
			mismatch(evidence.sourceInstanceId.value != SOURCE_INSTANCE_ID, "SOURCE_INSTANCE_MISMATCH"),
			mismatch(
				evidence.registrationGeneration != REGISTRATION_GENERATION,
				"REGISTRATION_GENERATION_MISMATCH",
			),
			mismatch(
				evidence.physicalConfigurationFingerprint != CONFIGURATION_FINGERPRINT,
				"PHYSICAL_CONFIGURATION_MISMATCH",
			),
			mismatch(evidence.authorizationRevision != AUTHORIZATION_REVISION, "AUTHORIZATION_REVISION_MISMATCH"),
			mismatch(
				evidence.registrationPurposeEligibilityMask != SourceBrokerPurpose.MASK_SESSION_CAPTURE,
				"PURPOSE_NOT_EXACT_SESSION_CAPTURE",
			),
			mismatch(
				evidence.registrationEligibilityFingerprint != AUTHORIZATION_FINGERPRINT,
				"AUTHORIZATION_FINGERPRINT_MISMATCH",
			),
			mismatch(
				evidence.planAttribution != PlanAttribution.CAPTURED_REGISTRATION,
				"PLAN_NOT_CAPTURED_REGISTRATION",
			),
			mismatch(evidence.clockDomainId != BOOT_CLOCK_DOMAIN, "CLOCK_DOMAIN_MISMATCH"),
			mismatch(
				evidence.capturedCollectedDataEpoch != COLLECTED_DATA_EPOCH,
				"COLLECTED_DATA_EPOCH_MISMATCH",
			),
			mismatch(evidence.sourcePolicyRevision != SOURCE_POLICY_REVISION, "SOURCE_POLICY_REVISION_MISMATCH"),
			mismatch(evidence.captureConsentEpoch != CAPTURE_CONSENT_EPOCH, "CAPTURE_CONSENT_EPOCH_MISMATCH"),
			mismatch(
				evidence.sessionManifestRevision != MANIFEST_REVISION,
				"SESSION_MANIFEST_REVISION_MISMATCH",
			),
			mismatch(evidence.lifecycleLeaseGeneration != LEASE_GENERATION, "LEASE_GENERATION_MISMATCH"),
			mismatch(evidence.payloadVersion != PAYLOAD_VERSION, "PAYLOAD_VERSION_MISMATCH"),
			mismatch(evidence.payload !is StepCounterWindowPayload, "PAYLOAD_NOT_STEPS_WINDOW"),
		)
	}

	private fun StepCounterWindowPayload.recordingWindowFailure(
		event: AdmittedSourceEvent<out SourcePayload>,
	): String? = firstMismatch(
		mismatch(bootClockDomainId != event.evidence.clockDomainId, "STEPS_BOOT_CLOCK_DOMAIN_MISMATCH"),
		mismatch(
			windowEndElapsedRealtimeNanos != event.evidence.observedElapsedRealtimeNanos,
			"STEPS_OBSERVED_TIME_MISMATCH",
		),
		mismatch(windowStartElapsedRealtimeNanos > windowEndElapsedRealtimeNanos, "STEPS_WINDOW_REVERSED"),
		mismatch(firstProviderSequence > lastProviderSequence, "STEPS_PROVIDER_SEQUENCE_REVERSED"),
	)

	private fun mismatch(condition: Boolean, reason: String): String? = reason.takeIf { condition }

	private fun firstMismatch(vararg mismatches: String?): String? = mismatches.firstOrNull { it != null }

	private suspend fun insertCaptureAttributedStepsRow(
		ordinal: Long,
		boundary: StepBoundaryKind,
		firstCount: Long,
		lastCount: Long,
		deltaCount: Long,
		corruptIntegrity: Boolean = false,
	) {
		val payload = stepsPayload(ordinal, boundary, firstCount, lastCount, deltaCount)
		val encoded = DefaultSourcePayloadCodec().encode(payload, PAYLOAD_VERSION)
		val raw = SourceEventWalEntity(
			admissionOrdinal = ordinal,
			eventId = "steps-event-$ordinal",
			providerDedupKey = "steps-dedup-$ordinal",
			logicalTrackingId = LOGICAL_TRACKING_ID,
			serviceRunId = SERVICE_RUN_ID,
			sourceKind = SourceKind.STEPS.stableCode,
			sourceInstanceId = SOURCE_INSTANCE_ID,
			registrationGeneration = REGISTRATION_GENERATION,
			physicalConfigurationFingerprint = CONFIGURATION_FINGERPRINT,
			authorizationRevision = AUTHORIZATION_REVISION,
			authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			authorizationFingerprint = AUTHORIZATION_FINGERPRINT,
			sourceSequence = ordinal,
			configRevision = 1L,
			planAttribution = PlanAttribution.CAPTURED_REGISTRATION.ordinal,
			clockDomainId = BOOT_CLOCK_DOMAIN,
			observedElapsedNanos = payload.windowEndElapsedRealtimeNanos,
			observedIntervalStartNanos = payload.windowStartElapsedRealtimeNanos,
			receivedElapsedNanos = payload.windowEndElapsedRealtimeNanos + 1_000L,
			wallTimeMs = 10_000L + ordinal,
			wallTimeUncertaintyMs = 1L,
			capturedCollectedDataEpoch = COLLECTED_DATA_EPOCH,
			sourcePolicyRevision = SOURCE_POLICY_REVISION,
			captureConsentEpoch = CAPTURE_CONSENT_EPOCH,
			sessionManifestRevision = MANIFEST_REVISION,
			lifecycleLeaseGeneration = LEASE_GENERATION,
			acquiredAtMs = 10_000L + ordinal,
			qualityFlags = 0L,
			qualityConfidence = null,
			payloadVersion = PAYLOAD_VERSION,
			payload = encoded.bytes,
			payloadChecksum = encoded.checksum,
			integrityIdentity = "pending",
			createdAtMs = 10_000L + ordinal,
		)
		val integrityValid = raw.copy(integrityIdentity = raw.calculatedIntegrityIdentity())
		val stored = if (corruptIntegrity) {
			integrityValid.copy(payloadChecksum = "corrupt")
		} else {
			integrityValid
		}
		check(database.sourceEventWalDao().insertIgnoringDuplicate(stored) == ordinal)
	}

	private fun stepsPayload(
		ordinal: Long,
		boundary: StepBoundaryKind,
		firstCount: Long,
		lastCount: Long,
		deltaCount: Long,
	): StepCounterWindowPayload {
		val endNanos = ordinal * 2_000_000L
		val isBaseline = boundary == StepBoundaryKind.BASELINE
		val startNanos = if (isBaseline) {
			endNanos
		} else {
			endNanos - 2_000_000L
		}
		val firstProviderSequence = if (isBaseline) {
			ordinal
		} else {
			ordinal - 1L
		}
		return StepCounterWindowPayload(
			bootClockDomainId = BOOT_CLOCK_DOMAIN,
			firstCumulativeCount = firstCount,
			lastCumulativeCount = lastCount,
			deltaCount = deltaCount,
			windowStartElapsedRealtimeNanos = startNanos,
			windowEndElapsedRealtimeNanos = endNanos,
			firstProviderSequence = firstProviderSequence,
			lastProviderSequence = ordinal,
			boundaryKind = boundary,
		)
	}

	private suspend fun installCanonicalStepsLane() {
		val owner = promoteStepsOwner()
		installManifest(owner)
		installProductLane()
	}

	private suspend fun promoteStepsOwner(): SourceDestinationOwnerEntity {
		val legacyOwner = SourceDestinationOwnerEntity(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
			owner = SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL,
			ownerGeneration = SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION,
			updatedAtMs = 0L,
		)
		database.sourceDestinationOwnerDao().insertIfAbsent(legacyOwner)
		check(database.sourceDestinationOwnerDao().compareAndSetOwner(
			sourceKind = legacyOwner.sourceKind,
			destination = legacyOwner.destination,
			expectedOwner = legacyOwner.owner,
			expectedOwnerGeneration = legacyOwner.ownerGeneration,
			newOwner = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS,
			newOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
			updatedAtMs = 1_000L,
		) == 1)
		return requireNotNull(database.sourceDestinationOwnerDao().get(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
		))
	}

	private suspend fun installManifest(owner: SourceDestinationOwnerEntity) {
		val manifestSource = manifestSource(owner)
		val unsignedManifest = unsignedManifest()
		database.sourceSessionDao().insertManifest(unsignedManifest.copy(
			manifestChecksum = SessionManifestIntegrity.compute(unsignedManifest, listOf(manifestSource)),
		))
		database.sourceSessionDao().insertManifestSources(listOf(manifestSource))
	}

	private fun manifestSource(owner: SourceDestinationOwnerEntity) = SessionManifestSourceEntity(
			logicalTrackingId = LOGICAL_TRACKING_ID,
			manifestRevision = MANIFEST_REVISION,
			sourceKind = SourceKind.STEPS.stableCode,
			purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			consentEpoch = CAPTURE_CONSENT_EPOCH,
			persistenceEligible = true,
			qosCode = 0,
			outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
			writerOwner = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS,
			writerOwnerGeneration = owner.ownerGeneration,
			writerProjectionId = StepsSessionFactProjectionLane.WRITER_ID,
			writerProjectionVersion = StepsSessionFactProjectionLane.WRITER_VERSION,
			writerBindingGeneration = StepsSessionFactProjectionLane.BINDING_GENERATION,
		)

	private fun unsignedManifest() = SessionManifestVersionEntity(
			logicalTrackingId = LOGICAL_TRACKING_ID,
			manifestRevision = MANIFEST_REVISION,
			serviceRunId = SERVICE_RUN_ID,
			sessionMode = "MANUAL",
			sourcePolicyRevision = SOURCE_POLICY_REVISION,
			acquisitionPlanRevision = 1L,
			rolloutRevision = 2L,
			startOrigin = "MANUAL_UI",
			effectiveBootId = BOOT_CLOCK_DOMAIN,
			effectiveElapsedRealtimeNanos = 0L,
			effectiveWallTimeMs = 10_000L,
			zoneId = "UTC",
			automationEpoch = null,
			changeReason = "TEST",
			manifestChecksum = "",
		)

	private suspend fun installProductLane() {
		database.sourceProjectionStateDao().installProductLane(
			SourceProductProjectionLaneEntity(
				sourceKind = SourceKind.STEPS.stableCode,
				bindingGeneration = StepsSessionFactProjectionLane.BINDING_GENERATION,
				projectionId = StepsSessionFactProjectionLane.WRITER_ID,
				projectionVersion = StepsSessionFactProjectionLane.WRITER_VERSION,
				captureModeMask = StepsSessionFactProjectionLane.MANUAL_CAPTURE_MODE_MASK,
				productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
				activatedRolloutRevision = 2L,
				activationOrdinal = 1L,
				contiguousAdmissionOrdinal = 0L,
				captureAdmissionCutoffOrdinal = null,
				retentionRequired = true,
				status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
				installedAtMs = 1_000L,
				updatedAtMs = 1_000L,
			),
		)
	}

	private suspend fun activeLane(): SourceProductProjectionLaneEntity = requireNotNull(
		database.sourceProjectionStateDao().activeProductLane(SourceKind.STEPS.stableCode),
	)

	private inner class RecordingScanState {
		private var baselineOrdinal: Long? = null
		private var previousCount: Long? = null
		private var previousProviderSequence: Long? = null
		private var previousWindowEndNanos: Long? = null
		private var sawCovered = false

		fun accept(event: AdmittedSourceEvent<out SourcePayload>): StepsRecordingBoundary? {
			val attributionFailure = event.recordingAttributionFailure()
			if (attributionFailure != null) {
				return StepsRecordingBoundary.Unverifiable(attributionFailure)
			}
			val payload = event.evidence.payload as StepCounterWindowPayload
			val windowFailure = payload.recordingWindowFailure(event)
			if (windowFailure != null) {
				return StepsRecordingBoundary.Unverifiable(windowFailure)
			}
			return when (payload.boundaryKind) {
				StepBoundaryKind.BASELINE -> acceptBaseline(event, payload)
				StepBoundaryKind.COVERED -> acceptCovered(event, payload)
				StepBoundaryKind.COUNTER_RESET,
				StepBoundaryKind.LEGACY_AMBIGUOUS,
				-> StepsRecordingBoundary.Unverifiable("STEPS_BOUNDARY_NOT_RECORDING_PROOF")
			}
		}

		fun result(throughOrdinal: Long): StepsRecordingBoundary = when {
			baselineOrdinal == null -> StepsRecordingBoundary.NoObservation
			sawCovered -> StepsRecordingBoundary.CoveredWithoutPositiveDelta(throughOrdinal)
			else -> StepsRecordingBoundary.BaselineOnly(requireNotNull(baselineOrdinal))
		}

		private fun acceptBaseline(
			event: AdmittedSourceEvent<out SourcePayload>,
			payload: StepCounterWindowPayload,
		): StepsRecordingBoundary? {
			val failure = firstMismatch(
				mismatch(baselineOrdinal != null, "STEPS_BASELINE_REPEATED"),
				mismatch(payload.deltaCount != 0L, "STEPS_BASELINE_DELTA_NONZERO"),
				mismatch(
					payload.firstCumulativeCount != payload.lastCumulativeCount,
					"STEPS_BASELINE_COUNT_MISMATCH",
				),
				mismatch(
					payload.windowStartElapsedRealtimeNanos != payload.windowEndElapsedRealtimeNanos,
					"STEPS_BASELINE_WINDOW_NONEMPTY",
				),
				mismatch(
					payload.firstProviderSequence != payload.lastProviderSequence,
					"STEPS_BASELINE_SEQUENCE_NONEMPTY",
				),
			)
			if (failure != null) {
				return StepsRecordingBoundary.Unverifiable(failure)
			}
			baselineOrdinal = event.admissionOrdinal
			updatePrevious(payload)
			return null
		}

		private fun acceptCovered(
			event: AdmittedSourceEvent<out SourcePayload>,
			payload: StepCounterWindowPayload,
		): StepsRecordingBoundary? {
			val failure = firstMismatch(
				mismatch(baselineOrdinal == null, "STEPS_COVERED_WITHOUT_BASELINE"),
				mismatch(payload.firstCumulativeCount != previousCount, "STEPS_COVERED_COUNT_GAP"),
				mismatch(
					payload.firstProviderSequence != previousProviderSequence,
					"STEPS_COVERED_SEQUENCE_GAP",
				),
				mismatch(
					payload.windowStartElapsedRealtimeNanos != previousWindowEndNanos,
					"STEPS_COVERED_TIME_GAP",
				),
				mismatch(
					payload.windowEndElapsedRealtimeNanos <= payload.windowStartElapsedRealtimeNanos,
					"STEPS_COVERED_WINDOW_EMPTY",
				),
				mismatch(
					payload.lastProviderSequence <= payload.firstProviderSequence,
					"STEPS_COVERED_SEQUENCE_EMPTY",
				),
				mismatch(payload.deltaCount < 0L, "STEPS_COVERED_DELTA_NEGATIVE"),
				mismatch(
					payload.lastCumulativeCount - payload.firstCumulativeCount != payload.deltaCount,
					"STEPS_COVERED_DELTA_MISMATCH",
				),
			)
			if (failure != null) {
				return StepsRecordingBoundary.Unverifiable(failure)
			}
			sawCovered = true
			updatePrevious(payload)
			return if (payload.deltaCount > 0L) {
				StepsRecordingBoundary.Recorded(
					eventId = event.eventId.value,
					admissionOrdinal = event.admissionOrdinal,
					deltaCount = payload.deltaCount,
				)
			} else {
				null
			}
		}

		private fun updatePrevious(payload: StepCounterWindowPayload) {
			previousCount = payload.lastCumulativeCount
			previousProviderSequence = payload.lastProviderSequence
			previousWindowEndNanos = payload.windowEndElapsedRealtimeNanos
		}
	}

	private sealed interface StepsRecordingBoundary {
		data object NoObservation : StepsRecordingBoundary
		data class BaselineOnly(val baselineOrdinal: Long) : StepsRecordingBoundary
		data class CoveredWithoutPositiveDelta(val throughOrdinal: Long) : StepsRecordingBoundary
		data class Recorded(
			val eventId: String,
			val admissionOrdinal: Long,
			val deltaCount: Long,
		) : StepsRecordingBoundary
		data class Unverifiable(val reason: String) : StepsRecordingBoundary
	}

	private companion object {
		const val COLLECTED_DATA_EPOCH = 2L
		const val LOGICAL_TRACKING_ID = "session-1"
		const val SERVICE_RUN_ID = "run-1"
		const val SOURCE_INSTANCE_ID = "steps-provider"
		const val REGISTRATION_GENERATION = 1L
		const val CONFIGURATION_FINGERPRINT = "steps-config"
		const val AUTHORIZATION_REVISION = 1L
		const val AUTHORIZATION_FINGERPRINT = "steps-capture"
		const val SOURCE_POLICY_REVISION = 3L
		const val CAPTURE_CONSENT_EPOCH = 4L
		const val MANIFEST_REVISION = 5L
		const val LEASE_GENERATION = 6L
		const val BOOT_CLOCK_DOMAIN = "boot-1"
		const val PAYLOAD_VERSION = 3
		const val RECORDING_PAGE_SIZE = 64
		const val MAX_RECORDING_PAGES = 4
	}
}
