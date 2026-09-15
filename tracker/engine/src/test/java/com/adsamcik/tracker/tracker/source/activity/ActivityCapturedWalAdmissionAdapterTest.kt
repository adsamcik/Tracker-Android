package com.adsamcik.tracker.tracker.source.activity

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEvidence
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEvidenceBatch
import com.adsamcik.tracker.activity.api.ingress.ActivityTransitionEvidence
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationIdentity
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityAutomationEpochEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedRegistrationPlanEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionLifecycleIntentVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.ingress.ActivitySourceDeliveryFactory
import com.adsamcik.tracker.tracker.source.ingress.DefaultSourcePayloadCodec
import com.adsamcik.tracker.tracker.source.model.ActivityMode
import com.adsamcik.tracker.tracker.source.model.ActivityPlan
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import com.adsamcik.tracker.tracker.source.model.toStableFlags
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
class ActivityCapturedWalAdmissionAdapterTest {
	private lateinit var database: AppDatabase
	private val payloadCodec = DefaultSourcePayloadCodec()
	private val planCodec = SourcePlanCodec()

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `complete settled capture delivery admits exact transition without writing a fact`() = runTest {
		installFixture()

		val result = subject().admit(EVENT_ID) as ActivityCapturedWalAdmissionResult.Admitted

		result.deliveryUnitIndex shouldBe 0
		result.deliveryUnitCount shouldBe 2
		result.settledWindow.interval shouldBe ActivityProviderTimeInterval(
			AUTHORIZATION_START_NANOS,
			REGISTRATION_END_NANOS,
		)
		(result.observation as ActivityCapturedObservation.Transition).let { observation ->
			observation.change shouldBe ActivityTransitionChange.ENTER
			observation.activity shouldBe CapturedActivityType.WALKING
			observation.reference.sourceEventId shouldBe EVENT_ID
		}
		database.activityCapturedFactDao().revisionCount() shouldBe 0L
	}

	@Test
	fun `canonical sampled unit derives finite coverage only from its bound historical plan`() = runTest {
		installFixture(sampled = true)

		val result = subject().admit(EVENT_ID) as ActivityCapturedWalAdmissionResult.Admitted
		(result.observation as ActivityCapturedObservation.SampledClassification).let { observation ->
			observation.activity shouldBe CapturedActivityType.RUNNING
			observation.confidencePercent shouldBe 90
			observation.coverageEndExclusiveElapsedRealtimeNanos shouldBe REGISTRATION_END_NANOS
		}
	}

	@Test
	fun `control-only retained delivery cannot enter captured admission`() = runTest {
		installFixture(controlOnly = true)

		subject().admit(EVENT_ID) shouldBe ActivityCapturedWalAdmissionResult.Rejected(
			ActivityCapturedWalAdmissionRejection.CONTROL_ONLY,
		)
		database.activityCapturedFactDao().revisionCount() shouldBe 0L
	}

	@Test
	fun `missing immutable registration plan is typed unavailable`() = runTest {
		installFixture(insertRegistrationPlan = false)

		subject().admit(EVENT_ID) shouldBe ActivityCapturedWalAdmissionResult.Unavailable(
			ActivityCapturedWalAdmissionUnavailable.MISSING_IMMUTABLE_REGISTRATION_PLAN,
		)
	}

	@Test
	fun `open provider and session cannot select a stable materialization window`() = runTest {
		installFixture(settled = false)

		(subject().admit(EVENT_ID) is ActivityCapturedWalAdmissionResult.Admitted) shouldBe false
	}

	@Test
	fun `exactly settled STOPPING session admits before final lifecycle commit`() = runTest {
		installFixture()
		installSettledStoppingWindow(sessionState = "STOPPING", desiredState = "FINALIZED")

		(subject().admit(EVENT_ID) is ActivityCapturedWalAdmissionResult.Admitted) shouldBe true
	}

	@Test
	fun `exactly settled suspended run admits while logical session remains active`() = runTest {
		installFixture()
		installSettledStoppingWindow(sessionState = "ACTIVE", desiredState = "ACTIVE")

		(subject().admit(EVENT_ID) is ActivityCapturedWalAdmissionResult.Admitted) shouldBe true
	}

	@Test
	fun `STOPPING without exact completeness remains unavailable`() = runTest {
		installFixture()
		installSettledStoppingWindow(sessionState = "STOPPING", desiredState = "FINALIZED")
		database.sourceSessionDao().deleteAllCompleteness()

		(subject().admit(EVENT_ID) is ActivityCapturedWalAdmissionResult.Admitted) shouldBe false
	}

	@Test
	fun `cleared suspended settlement cannot revive Activity admission`() = runTest {
		installFixture()
		installSettledStoppingWindow(sessionState = "ACTIVE", desiredState = "ACTIVE")
		val session = requireNotNull(database.sourceSessionDao().session(LOGICAL_TRACKING_ID))
		val run = requireNotNull(database.sourceSessionDao().serviceRun(SERVICE_RUN_ID))
		database.sourceSessionDao().updateSession(
			session.copy(
				currentServiceRunId = null,
				cutoffAtMs = null,
				cutoffElapsedNanos = null,
				finalAdmissionOrdinal = null,
			),
		) shouldBe 1
		database.sourceSessionDao().updateServiceRun(
			run.copy(
				state = "FINALIZED",
				completedAtMs = 2_000L,
				completionReason = "ANDROID_RESTART",
			),
		) shouldBe 1

		(subject().admit(EVENT_ID) is ActivityCapturedWalAdmissionResult.Admitted) shouldBe false
	}

	@Test
	fun `reachable reconfiguration and stopping pairs remain typed unsettled`() = runTest {
		val reachablePairs = listOf(
			"RECONFIGURING" to "STARTING",
			"RECONFIGURING" to "ACTIVE",
			"ACTIVE" to "STOPPING",
		)
		for ((sessionState, runState) in reachablePairs) {
			database.clearAllTables()
			installFixture(settled = false)
			val session = requireNotNull(database.sourceSessionDao().session(LOGICAL_TRACKING_ID))
			val run = requireNotNull(database.sourceSessionDao().serviceRun(SERVICE_RUN_ID))
			database.sourceSessionDao().updateSession(session.copy(state = sessionState)) shouldBe 1
			database.sourceSessionDao().updateServiceRun(run.copy(state = runState)) shouldBe 1

			subject().admit(EVENT_ID) shouldBe ActivityCapturedWalAdmissionResult.Unavailable(
				ActivityCapturedWalAdmissionUnavailable.UNSETTLED_FINITE_WINDOW,
			)
		}
	}

	@Test
	fun `terminal logical session can authenticate an older completed replacement run`() = runTest {
		installFixture()
		val completed = requireNotNull(database.sourceSessionDao().serviceRun(SERVICE_RUN_ID))
		database.sourceSessionDao().insertServiceRun(
			completed.copy(
				serviceRunId = "activity-run-replacement",
				startedAtMs = 1_900L,
				startedElapsedNanos = 850L,
				completedAtMs = 1_950L,
				startDeliveryToken = "activity-replacement-token",
				sessionSegmentId = null,
			),
		)

		(subject().admit(EVENT_ID) is ActivityCapturedWalAdmissionResult.Admitted) shouldBe true
	}

	@Test
	fun `oversized selected payload is rejected by payload preflight`() = runTest {
		installFixture()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET payload = ? WHERE event_id = ?",
			arrayOf<Any>(ByteArray(22), EVENT_ID.value),
		)

		subject().admit(EVENT_ID) shouldBe ActivityCapturedWalAdmissionResult.Rejected(
			ActivityCapturedWalAdmissionRejection.DELIVERY_TOO_LARGE,
		)
	}

	@Test
	fun `oversized delivery sibling is rejected before full delivery loading`() = runTest {
		installFixture()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET payload = ? WHERE event_id = 'activity-event-2'",
			arrayOf<Any>(ByteArray(22)),
		)

		subject().admit(EVENT_ID) shouldBe ActivityCapturedWalAdmissionResult.Rejected(
			ActivityCapturedWalAdmissionRejection.DELIVERY_TOO_LARGE,
		)
	}

	@Test
	fun `terminal session retaining an active run pointer fails closed`() = runTest {
		installFixture()
		val session = requireNotNull(database.sourceSessionDao().session(LOGICAL_TRACKING_ID))
		database.sourceSessionDao().updateSession(
			session.copy(currentServiceRunId = SERVICE_RUN_ID),
		) shouldBe 1

		subject().admit(EVENT_ID) shouldBe ActivityCapturedWalAdmissionResult.Rejected(
			ActivityCapturedWalAdmissionRejection.SESSION_MISMATCH,
		)
	}

	@Test
	fun `terminal session with nonterminal physical run fails closed`() = runTest {
		installFixture()
		val run = requireNotNull(database.sourceSessionDao().serviceRun(SERVICE_RUN_ID))
		database.sourceSessionDao().updateServiceRun(
			run.copy(
				state = "ACTIVE",
				completedAtMs = null,
				completionReason = null,
			),
		) shouldBe 1

		subject().admit(EVENT_ID) shouldBe ActivityCapturedWalAdmissionResult.Rejected(
			ActivityCapturedWalAdmissionRejection.SESSION_MISMATCH,
		)
	}

	@Test
	fun `terminal final admission ordinal must cover every delivery member`() = runTest {
		installFixture()
		val session = requireNotNull(database.sourceSessionDao().session(LOGICAL_TRACKING_ID))
		database.sourceSessionDao().updateSession(
			session.copy(finalAdmissionOrdinal = 1L),
		) shouldBe 1

		subject().admit(EVENT_ID) shouldBe ActivityCapturedWalAdmissionResult.Rejected(
			ActivityCapturedWalAdmissionRejection.SESSION_MISMATCH,
		)
	}

	@Test
	fun `missing admitted sibling cannot authenticate declared Activity delivery`() = runTest {
		installFixture()
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM source_event_wal WHERE delivery_unit_index = 1",
		)

		subject().admit(EVENT_ID) shouldBe ActivityCapturedWalAdmissionResult.Rejected(
			ActivityCapturedWalAdmissionRejection.MALFORMED_DELIVERY,
		)
	}

	@Test
	fun `sibling with different capture authority cannot authenticate the delivery`() = runTest {
		installFixture()
		val sibling = requireNotNull(database.sourceEventWalDao().getByEventId("activity-event-2"))
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM source_event_wal WHERE event_id = 'activity-event-2'",
		)
		val changed = sibling.copy(sourcePolicyRevision = POLICY_REVISION + 1L)
		database.sourceEventWalDao().insertDeliveryUnits(
			listOf(changed.copy(integrityIdentity = changed.calculatedIntegrityIdentity())),
		)

		subject().admit(EVENT_ID) shouldBe ActivityCapturedWalAdmissionResult.Rejected(
			ActivityCapturedWalAdmissionRejection.MALFORMED_DELIVERY,
		)
	}

	@Test
	fun `retention floor rejects provider wall uncertainty that can precede it`() = runTest {
		installFixture()
		database.sourceEvidenceStateDao().updateLifecycle(
			epoch = 0L,
			retainedFromMs = FIRST_EVENT_WALL_MS,
			updatedAtMs = 3_000L,
		) shouldBe 1

		subject().admit(EVENT_ID) shouldBe ActivityCapturedWalAdmissionResult.Rejected(
			ActivityCapturedWalAdmissionRejection.RETAINED_EVIDENCE,
		)
	}

	@Test
	fun `deleted event highwater rejects every retained delivery unit atomically`() = runTest {
		installFixture()
		database.sourceEvidenceStateDao().updateAfterFullDeletion(
			epoch = 0L,
			retainedFromMs = null,
			deletedSourceEventHighWaterOrdinal = 1L,
			updatedAtMs = 3_000L,
		) shouldBe 1

		subject().admit(EVENT_ID) shouldBe ActivityCapturedWalAdmissionResult.Rejected(
			ActivityCapturedWalAdmissionRejection.DELETED_EVIDENCE,
		)
	}

	private fun subject() = ActivityCapturedWalAdmissionAdapter(database, payloadCodec, planCodec)

	@Suppress("LongMethod")
	private suspend fun installFixture(
		controlOnly: Boolean = false,
		insertRegistrationPlan: Boolean = true,
		settled: Boolean = true,
		sampled: Boolean = false,
	) {
		val activityPlan = if (sampled) SAMPLED_ACTIVITY_PLAN else ACTIVITY_PLAN
		val physicalFingerprint = activityPlan.physicalConfigurationFingerprint()
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 0L))
		database.sourceDestinationOwnerDao().insertIfAbsent(
			SourceDestinationOwnerEntity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
				destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY,
				owner = SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS,
				ownerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
				updatedAtMs = 1_000L,
			),
		)
		installPolicy(controlOnly)
		val encodedPlan = planCodec.encode(activityPlan)
		database.sourcePlanStateDao().insertRevision(
			AcquisitionPlanRevisionEntity(
				revision = PLAN_REVISION,
				planId = "activity-plan-1",
				createdAtMs = 900L,
				status = "EFFECTIVE",
				sourcePolicyRevision = POLICY_REVISION,
			),
		)
		database.sourcePlanStateDao().insertDesiredPlans(
			listOf(
				SourceDesiredPlanEntity(
					revision = PLAN_REVISION,
					sourceKind = ACTIVITY_SOURCE,
					payloadVersion = 1,
					payload = encodedPlan.bytes,
					payloadChecksum = encodedPlan.checksum,
				),
			),
		)
		if (insertRegistrationPlan) {
			database.activityCapturedFactDao().insertRegistrationPlanBinding(
				ActivityCapturedRegistrationPlanEntity.create(
					sourceInstanceId = SOURCE_INSTANCE_ID,
					registrationGeneration = REGISTRATION_GENERATION,
					configurationRevision = PLAN_REVISION,
					desiredPlanPayloadVersion = 1,
					desiredPlanPayload = encodedPlan.bytes,
					desiredPlanPayloadChecksum = encodedPlan.checksum,
					physicalConfigurationFingerprint = physicalFingerprint,
					appliedAtElapsedRealtimeNanos = REGISTRATION_START_NANOS,
					applyStatus = "APPLIED",
				),
			)
		}

		val segmentId = database.sessionSegmentDao().insert(
			SessionSegment(
				startTimeMs = 1_000L,
				endTimeMs = 2_000L,
				distanceM = 0f,
				steps = null,
				primaryActivity = null,
				activityConfidence = null,
				sampleCount = 0,
				source = SegmentSource.USER_CREATED,
				inferenceVersion = null,
				createdAt = 2_000L,
				logicalTrackingId = LOGICAL_TRACKING_ID,
				serviceRunId = SERVICE_RUN_ID,
			),
		)
		installSession(segmentId, settled, if (sampled) 1L else 2L)
		installManifest()

		val demand = if (controlOnly) controlDemand() else captureDemand()
		database.sourceBrokerDao().insertDemands(listOf(demand))
		database.sourceBrokerDao().insertRegistration(registration(settled, physicalFingerprint))
		val authorizationRows = SourceBrokerAuthorization.rows(
			sourceKind = ACTIVITY_SOURCE,
			registrationGeneration = REGISTRATION_GENERATION,
			authorizationRevision = AUTHORIZATION_REVISION,
			demands = listOf(demand),
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = AUTHORIZATION_START_NANOS,
			effectiveWallTimeMs = 1_100L,
		)
		database.sourceBrokerDao().insertAuthorizations(authorizationRows)

		val identity = ActivityRegistrationIdentity(
			sourceInstanceId = SOURCE_INSTANCE_ID,
			registrationGeneration = REGISTRATION_GENERATION,
			collectedDataEpoch = 0L,
			clockDomainId = BOOT_ID,
			physicalConfigurationFingerprint = physicalFingerprint,
		)
		val delivery = ActivitySourceDeliveryFactory().create(
			batch = ActivityRecognitionEvidenceBatch(
				receivedElapsedRealtimeNanos = RECEIVED_NANOS,
				receivedWallTimeMs = RECEIVED_WALL_MS,
				registrationIdentity = identity,
				recognitions = if (sampled) listOf(
					ActivityRecognitionEvidence(
						DetectedActivityType.RUNNING,
						90,
						FIRST_EVENT_NANOS,
					),
				) else emptyList(),
				transitions = if (sampled) emptyList() else listOf(
					ActivityTransitionEvidence(
						DetectedActivityType.WALKING,
						ActivityTransitionType.ENTER,
						FIRST_EVENT_NANOS,
					),
					ActivityTransitionEvidence(
						DetectedActivityType.WALKING,
						ActivityTransitionType.EXIT,
						SECOND_EVENT_NANOS,
					),
				),
			),
			identity = identity,
			automationAuthority = ActivityAutomationEpochEntity(),
		)
		val purposeMask = authorizationRows.first().purposeEligibilityMask
		val authorizationFingerprint = authorizationRows.first().authorizationFingerprint
		val rows = delivery.candidate.units.mapIndexed { index, unit ->
			val encoded = payloadCodec.encode(unit.evidence.payload, unit.evidence.payloadVersion)
			val unsigned = SourceEventWalEntity(
				admissionOrdinal = index + 1L,
				eventId = if (index == 0) EVENT_ID.value else "activity-event-${index + 1}",
				providerDedupKey = null,
				deliveryIdentity = delivery.candidate.identity.value,
				deliveryUnitIndex = index,
				deliveryUnitCount = delivery.candidate.units.size,
				logicalTrackingId = LOGICAL_TRACKING_ID.takeUnless { controlOnly },
				serviceRunId = SERVICE_RUN_ID.takeUnless { controlOnly },
				sourceKind = ACTIVITY_SOURCE,
				sourceInstanceId = SOURCE_INSTANCE_ID,
				registrationGeneration = REGISTRATION_GENERATION,
				physicalConfigurationFingerprint = physicalFingerprint,
				authorizationRevision = AUTHORIZATION_REVISION,
				authorizationPurposeEligibilityMask = purposeMask,
				authorizationFingerprint = authorizationFingerprint,
				sourceSequence = index + 1L,
				configRevision = PLAN_REVISION,
				planAttribution = if (controlOnly) {
					PlanAttribution.RECEIVE_TIME_ONLY.ordinal
				} else {
					PlanAttribution.CAPTURED_REGISTRATION.ordinal
				},
				clockDomainId = BOOT_ID,
				observedElapsedNanos = unit.evidence.observedElapsedRealtimeNanos,
				observedIntervalStartNanos = unit.observedIntervalStartElapsedRealtimeNanos,
				receivedElapsedNanos = unit.evidence.receivedElapsedRealtimeNanos,
				wallTimeMs = unit.evidence.wallTimeMs,
				wallTimeUncertaintyMs = unit.evidence.wallTimeUncertaintyMs,
				capturedCollectedDataEpoch = 0L,
				activityAutomationEpoch = null,
				sourcePolicyRevision = POLICY_REVISION.takeUnless { controlOnly },
				captureConsentEpoch = CONSENT_EPOCH.takeUnless { controlOnly },
				sessionManifestRevision = MANIFEST_REVISION.takeUnless { controlOnly },
				lifecycleLeaseGeneration = LEASE_GENERATION.takeUnless { controlOnly },
				acquiredAtMs = unit.evidence.acquiredAtMs,
				qualityFlags = unit.evidence.quality.toStableFlags(),
				qualityConfidence = unit.evidence.quality.confidence,
				payloadVersion = unit.evidence.payloadVersion,
				payload = encoded.bytes,
				payloadChecksum = encoded.checksum,
				createdAtMs = 2_000L,
			)
			unsigned.copy(integrityIdentity = unsigned.calculatedIntegrityIdentity())
		}
		database.sourceEventWalDao().insertDeliveryUnits(rows)
	}

	private suspend fun installPolicy(controlOnly: Boolean) {
		database.sourcePolicyDao().ensureAuthority(
			SourcePolicyAuthorityEntity(
				bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
				currentPolicyRevision = POLICY_REVISION,
				legacySettingsFingerprint = null,
				updatedAtMs = 1_000L,
			),
		)
		database.sourcePolicyDao().insertPolicies(
			listOf(
				SourcePolicyEntity(
					policyRevision = POLICY_REVISION,
					sourceKind = ACTIVITY_SOURCE,
					enabled = true,
					qosCode = QOS_CODE,
					locationMinTimeSeconds = null,
					locationMinDistanceMeters = null,
					locationRequiredAccuracyMeters = null,
					capturePersistenceEligible = !controlOnly,
					controlPersistenceEligible = false,
					ambientPersistenceEligible = false,
					captureConsentEpoch = CONSENT_EPOCH.takeUnless { controlOnly },
					controlConsentEpoch = if (controlOnly) CONSENT_EPOCH else null,
					ambientConsentEpoch = null,
					effectiveBootId = BOOT_ID,
					effectiveElapsedRealtimeNanos = RUN_START_NANOS,
					effectiveWallTimeMs = 1_000L,
					changeReason = "TEST",
				),
			),
		)
		if (!controlOnly) {
			database.sourcePolicyDao().insertConsentEpochs(
				listOf(
					SourceConsentEpochEntity(
						sourceKind = ACTIVITY_SOURCE,
						purpose = SourceBrokerPurpose.SESSION_CAPTURE,
						epoch = CONSENT_EPOCH,
						eligible = true,
						persistenceEligible = true,
						policyRevision = POLICY_REVISION,
						effectiveBootId = BOOT_ID,
						effectiveElapsedRealtimeNanos = RUN_START_NANOS,
						effectiveWallTimeMs = 1_000L,
						changeReason = "TEST",
					),
				),
			)
		}
	}

	private suspend fun installSession(
		segmentId: Long,
		settled: Boolean,
		finalAdmissionOrdinal: Long,
	) {
		database.sourceSessionDao().insertSession(
			LogicalTrackingSessionEntity(
				logicalTrackingId = LOGICAL_TRACKING_ID,
				state = if (settled) "FINALIZED" else "ACTIVE",
				lifecycleRevision = if (settled) 2L else 1L,
				desiredPlanRevision = PLAN_REVISION,
				rolloutRevision = 1L,
				startOrigin = "MANUAL_FOREGROUND_START",
				clockDomainId = BOOT_ID,
				startedAtMs = 1_000L,
				startedElapsedNanos = RUN_START_NANOS,
				cutoffAtMs = if (settled) 2_000L else null,
				cutoffElapsedNanos = if (settled) SESSION_END_NANOS else null,
				completedAtMs = if (settled) 2_000L else null,
				finalAdmissionOrdinal = finalAdmissionOrdinal.takeIf { settled },
				failureCode = null,
				sessionMode = "MANUAL",
				currentManifestRevision = MANIFEST_REVISION,
				currentIntentRevision = 1L,
				currentServiceRunId = if (settled) null else SERVICE_RUN_ID,
				lifecycleLeaseGeneration = LEASE_GENERATION,
				lifecycleBootId = BOOT_ID,
				automationEpoch = null,
			),
		)
		database.sourceSessionDao().insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = SERVICE_RUN_ID,
				logicalTrackingId = LOGICAL_TRACKING_ID,
				state = if (settled) "FINALIZED" else "ACTIVE",
				desiredPlanRevision = PLAN_REVISION,
				rolloutRevision = 1L,
				foregroundCapabilityFlags = 0L,
				startedAtMs = 1_000L,
				startedElapsedNanos = RUN_START_NANOS,
				completedAtMs = if (settled) 2_000L else null,
				completionReason = if (settled) "USER_STOP" else null,
				bootId = BOOT_ID,
				leaseGeneration = LEASE_GENERATION,
				startOrigin = "MANUAL_FOREGROUND_START",
				desiredForegroundCapabilityFlags = 0L,
				appliedForegroundCapabilityFlags = 0L,
				runtimeAcknowledgement = if (settled) "STOPPED" else "RUNNING",
				runtimeFailureCode = null,
				runRevision = if (settled) 2L else 1L,
				startDeliveryToken = "activity-start-token",
				startCommandGeneration = 1L,
				preparedManifestRevision = MANIFEST_REVISION,
				preparedIntentRevision = 1L,
				androidDeliveryState = "FOREGROUND_ACCEPTED",
				androidDeliveryUpdatedAtMs = 1_000L,
				startIsUserInitiated = true,
				startIsAmbient = false,
				sessionSegmentId = segmentId,
				presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_PENDING,
				presentationAcknowledgedAtMs = null,
			),
		)
		if (settled) {
			database.sourceSessionDao().saveCompleteness(
				SourceSessionCompletenessEntity(
					logicalTrackingId = LOGICAL_TRACKING_ID,
					serviceRunId = SERVICE_RUN_ID,
					sourceKind = ACTIVITY_SOURCE,
					sourceInstanceId = SOURCE_INSTANCE_ID,
					registrationGeneration = REGISTRATION_GENERATION,
					lastAdmissionOrdinal = finalAdmissionOrdinal,
					lastSourceSequence = finalAdmissionOrdinal,
					appDrainComplete = true,
					providerCoverage = "PROVIDER_COMPLETENESS_UNOBSERVABLE",
					stopStatus = "COMPLETE",
					unresolvedSequenceStart = null,
					unresolvedSequenceEnd = null,
					updatedAtMs = 2_000L,
				),
			)
		}
	}

	private suspend fun installSettledStoppingWindow(
		sessionState: String,
		desiredState: String,
	) {
		val session = requireNotNull(database.sourceSessionDao().session(LOGICAL_TRACKING_ID))
		val run = requireNotNull(database.sourceSessionDao().serviceRun(SERVICE_RUN_ID))
		val intentRevision = 2L
		database.sourceSessionDao().insertLifecycleIntent(
			SessionLifecycleIntentVersionEntity(
				logicalTrackingId = LOGICAL_TRACKING_ID,
				intentRevision = intentRevision,
				manifestRevision = MANIFEST_REVISION,
				desiredState = desiredState,
				startOrigin = if (desiredState == "ACTIVE") "RECOVERY" else "POLICY_RECONCILIATION",
				requestBootId = BOOT_ID,
				requestedElapsedRealtimeNanos = SESSION_END_NANOS,
				requestedWallTimeMs = 2_000L,
				automationEpoch = null,
				triggerId = null,
				triggerKind = null,
				triggerBootId = null,
				triggerObservedElapsedRealtimeNanos = null,
				triggerReceivedElapsedRealtimeNanos = null,
				triggerExpiresElapsedRealtimeNanos = null,
				stopReason = "TEST_SETTLED_STOPPING",
				stopDeadlineBootId = if (desiredState == "FINALIZED") BOOT_ID else null,
				stopDeadlineElapsedRealtimeNanos =
					if (desiredState == "FINALIZED") SESSION_END_NANOS + 1_000L else null,
				intentChecksum = "settled-stopping-intent",
			),
		)
		database.sourceSessionDao().updateSession(
			session.copy(
				state = sessionState,
				completedAtMs = null,
				currentIntentRevision = intentRevision,
				currentServiceRunId = SERVICE_RUN_ID,
			),
		) shouldBe 1
		database.sourceSessionDao().updateServiceRun(
			run.copy(
				state = "STOPPING",
				completedAtMs = null,
				completionReason = null,
				runtimeAcknowledgement = "STOP_ACCEPTED",
				runRevision = run.runRevision + 1L,
			),
		) shouldBe 1
	}

	private suspend fun installManifest() {
		val source = SessionManifestSourceEntity(
			logicalTrackingId = LOGICAL_TRACKING_ID,
			manifestRevision = MANIFEST_REVISION,
			sourceKind = ACTIVITY_SOURCE,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			consentEpoch = CONSENT_EPOCH,
			persistenceEligible = true,
			qosCode = QOS_CODE,
			outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY,
			writerOwner = SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS,
			writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
			writerProjectionId = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID,
			writerProjectionVersion = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION,
			writerBindingGeneration = SourceDestinationOwnerEntity.ACTIVITY_FACT_BINDING_GENERATION,
		)
		val unsigned = SessionManifestVersionEntity(
			logicalTrackingId = LOGICAL_TRACKING_ID,
			manifestRevision = MANIFEST_REVISION,
			serviceRunId = SERVICE_RUN_ID,
			sessionMode = "MANUAL",
			sourcePolicyRevision = POLICY_REVISION,
			acquisitionPlanRevision = PLAN_REVISION,
			rolloutRevision = 1L,
			startOrigin = "MANUAL_FOREGROUND_START",
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = RUN_START_NANOS,
			effectiveWallTimeMs = 1_000L,
			zoneId = "Europe/Prague",
			automationEpoch = null,
			changeReason = "MANUAL_START",
			manifestChecksum = "",
		)
		database.sourceSessionDao().insertManifest(
			unsigned.copy(manifestChecksum = SessionManifestIntegrity.compute(unsigned, listOf(source))),
		)
		database.sourceSessionDao().insertManifestSources(listOf(source))
	}

	private fun registration(
		settled: Boolean,
		physicalFingerprint: String,
	) = ProviderRegistrationGenerationEntity(
		sourceKind = ACTIVITY_SOURCE,
		registrationGeneration = REGISTRATION_GENERATION,
		sourceInstanceId = SOURCE_INSTANCE_ID,
		ownerScope = "activity-provider",
		clockDomainId = BOOT_ID,
		physicalConfigurationFingerprint = physicalFingerprint,
		collectedDataEpoch = 0L,
		providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
		providerProcessIncarnationId = null,
		status = if (settled) {
			ProviderRegistrationGenerationEntity.STATUS_RETIRED
		} else {
			ProviderRegistrationGenerationEntity.STATUS_ACTIVE
		},
		reservedAtMs = 900L,
		reservedElapsedRealtimeNanos = 90L,
		acceptedAtMs = 1_050L,
		acceptedElapsedRealtimeNanos = REGISTRATION_START_NANOS,
		retiredAtMs = if (settled) 1_900L else null,
		retiredElapsedRealtimeNanos = if (settled) REGISTRATION_END_NANOS else null,
		failureCode = null,
		captureCallbackBarrierAuthorizationRevision = if (settled) AUTHORIZATION_REVISION else 0L,
	)

	private fun captureDemand() = SourceDemandEntity(
		demandId = "activity-capture-demand",
		consumerId = "session:$SERVICE_RUN_ID",
		sourceKind = ACTIVITY_SOURCE,
		purpose = SourceBrokerPurpose.SESSION_CAPTURE,
		logicalTrackingId = LOGICAL_TRACKING_ID,
		serviceRunId = SERVICE_RUN_ID,
		manifestRevision = MANIFEST_REVISION,
		lifecycleLeaseGeneration = LEASE_GENERATION,
		sourcePolicyRevision = POLICY_REVISION,
		consentEpoch = CONSENT_EPOCH,
		persistenceEligible = true,
		qosCode = QOS_CODE,
		minimumAcquisitionSpec = "activity:v1:transitions",
		adaptiveReductionAllowed = false,
		maximumAgeMs = 30_000L,
		desiredLatencyMs = 30_000L,
		requestedDeliveryLatencyMs = 0L,
		requestedBootId = BOOT_ID,
		requestedElapsedRealtimeNanos = RUN_START_NANOS,
		requestedAtMs = 1_000L,
		status = SourceDemandEntity.STATUS_RETIRED,
		retireBootId = BOOT_ID,
		retireElapsedRealtimeNanos = SESSION_END_NANOS,
		retiredAtMs = 2_000L,
	)

	private fun controlDemand() = SourceDemandEntity(
		demandId = "activity-control-demand",
		consumerId = "activity-control",
		sourceKind = ACTIVITY_SOURCE,
		purpose = SourceBrokerPurpose.CONTROL_AUTOSTART,
		logicalTrackingId = null,
		serviceRunId = null,
		manifestRevision = null,
		lifecycleLeaseGeneration = null,
		sourcePolicyRevision = POLICY_REVISION,
		consentEpoch = CONSENT_EPOCH,
		persistenceEligible = false,
		qosCode = QOS_CODE,
		minimumAcquisitionSpec = "activity:v1:transitions",
		adaptiveReductionAllowed = false,
		maximumAgeMs = 30_000L,
		desiredLatencyMs = 30_000L,
		requestedDeliveryLatencyMs = 0L,
		requestedBootId = BOOT_ID,
		requestedElapsedRealtimeNanos = RUN_START_NANOS,
		requestedAtMs = 1_000L,
		status = SourceDemandEntity.STATUS_RETIRED,
		retireBootId = BOOT_ID,
		retireElapsedRealtimeNanos = SESSION_END_NANOS,
		retiredAtMs = 2_000L,
	)

	private companion object {
		val EVENT_ID = SourceEventId("activity-event-1")
		const val ACTIVITY_SOURCE = 2
		const val LOGICAL_TRACKING_ID = "logical-activity"
		const val SERVICE_RUN_ID = "run-activity"
		const val SOURCE_INSTANCE_ID = "activity-instance"
		const val BOOT_ID = "boot-activity"
		const val PLAN_REVISION = 1L
		const val POLICY_REVISION = 1L
		const val CONSENT_EPOCH = 1L
		const val MANIFEST_REVISION = 1L
		const val LEASE_GENERATION = 1L
		const val REGISTRATION_GENERATION = 1L
		const val AUTHORIZATION_REVISION = 1L
		const val QOS_CODE = 2
		const val RUN_START_NANOS = 100L
		const val REGISTRATION_START_NANOS = 120L
		const val AUTHORIZATION_START_NANOS = 150L
		const val FIRST_EVENT_NANOS = 200L
		const val SECOND_EVENT_NANOS = 250L
		const val RECEIVED_NANOS = 300L
		const val REGISTRATION_END_NANOS = 800L
		const val SESSION_END_NANOS = 900L
		const val RECEIVED_WALL_MS = 2_000L
		const val FIRST_EVENT_WALL_MS = 2_000L
		val ACTIVITY_PLAN = ActivityPlan(
			revision = PLAN_REVISION,
			mode = ActivityMode.TRANSITIONS_ONLY,
			desiredDetectionLatencyMs = 30_000L,
			confidenceThresholdPercent = 65,
			transitionTypes = setOf(0, 1),
		)
		val SAMPLED_ACTIVITY_PLAN = ACTIVITY_PLAN.copy(
			mode = ActivityMode.CONTINUOUS_RECOGNITION,
			desiredDetectionLatencyMs = 1L,
		)
	}
}
