package com.adsamcik.tracker.tracker.source.lifecycle

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.StepsCountDomainSchema
import com.adsamcik.tracker.shared.base.database.StepsCountDomainSchemaState
import com.adsamcik.tracker.shared.base.database.StepsCountDomainStore
import com.adsamcik.tracker.shared.base.database.StepsCountDomainWriteResult
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
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProviderPurposeScope
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceWriterGenerationContract
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.model.steps.StepsCounterDomainToken
import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.stats.api.repository.RecordingProofState
import com.adsamcik.tracker.stats.api.repository.SourceEvidenceIdentity
import com.adsamcik.tracker.stats.api.repository.SourceProofCompleteness
import com.adsamcik.tracker.stats.api.repository.SourceProofKey
import com.adsamcik.tracker.stats.api.repository.SourceProofReason
import com.adsamcik.tracker.stats.api.repository.SourceProofResult
import com.adsamcik.tracker.stats.api.repository.SourceProofScope
import com.adsamcik.tracker.tracker.source.activity.ActivityCapturedWalAdmissionAdapter
import com.adsamcik.tracker.tracker.source.cell.CellWalQualificationAdapter
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.ingress.DefaultSourcePayloadCodec
import com.adsamcik.tracker.tracker.source.location.LocationWalQualificationAdapter
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.PressureAcquisitionFloor
import com.adsamcik.tracker.tracker.source.model.PressurePlan
import com.adsamcik.tracker.tracker.source.model.PressureSensorAccuracy
import com.adsamcik.tracker.tracker.source.model.PressureWindowClosureKind
import com.adsamcik.tracker.tracker.source.model.PressureWindowPayload
import com.adsamcik.tracker.tracker.source.model.SourceAcquisitionFloorCodec
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import com.adsamcik.tracker.tracker.source.model.StepBoundaryKind
import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload
import com.adsamcik.tracker.tracker.source.model.StepsAcquisitionFloor
import com.adsamcik.tracker.tracker.source.model.StepsAcquisitionMechanism
import com.adsamcik.tracker.tracker.source.model.StepsPlan
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import com.adsamcik.tracker.tracker.source.runtime.pressureProviderDeliveryIdentity
import com.adsamcik.tracker.tracker.source.wifi.WifiWalQualificationAdapter
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Room authority, codecs and recording ports; no provider activation or private predicate mocks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReconfiguredSessionSourceProofRoomTest {
	private lateinit var database: AppDatabase
	private val planCodec = SourcePlanCodec()
	private val payloadCodec = DefaultSourcePayloadCodec()

	@Before
	fun setUp() {
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
		StepsCountDomainSchema.installIfAbsent(database.openHelper.writableDatabase) shouldBe
			StepsCountDomainSchemaState.ValidV2
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `initial manual Steps action matches original run`() = runTest {
		assertProven(seed(SourceKind.STEPS, manifestRevision = 1L))
	}

	@Test
	fun `initial manual Pressure action matches original run`() = runTest {
		assertProven(seed(SourceKind.PRESSURE, manifestRevision = 1L))
	}

	@Test
	fun `initial automatic Steps action matches original run`() = runTest {
		assertProven(seed(SourceKind.STEPS, manifestRevision = 1L, originalOrigin = AUTOMATIC))
	}

	@Test
	fun `initial automatic Pressure action matches original run`() = runTest {
		assertProven(seed(SourceKind.PRESSURE, manifestRevision = 1L, originalOrigin = AUTOMATIC))
	}

	@Test
	fun `manual Steps remains proven after same-run policy reconciliation`() = runTest {
		assertProven(seed(SourceKind.STEPS))
	}

	@Test
	fun `manual Pressure remains proven after same-run policy reconciliation`() = runTest {
		assertProven(seed(SourceKind.PRESSURE))
	}

	@Test
	fun `automatic Steps remains proven after same-run policy reconciliation`() = runTest {
		assertProven(seed(SourceKind.STEPS, originalOrigin = AUTOMATIC))
	}

	@Test
	fun `automatic Pressure remains proven after same-run policy reconciliation`() = runTest {
		assertProven(seed(SourceKind.PRESSURE, originalOrigin = AUTOMATIC))
	}

	@Test
	fun `initial Steps action cannot claim reconciliation instead of original start`() = runTest {
		assertActionRejected(seed(SourceKind.STEPS, manifestRevision = 1L, actionOrigin = RECONCILIATION))
	}

	@Test
	fun `initial Pressure action cannot claim reconciliation instead of original start`() = runTest {
		assertActionRejected(seed(SourceKind.PRESSURE, manifestRevision = 1L, actionOrigin = RECONCILIATION))
	}

	@Test
	fun `later Steps action cannot reuse original manual start origin`() = runTest {
		assertActionRejected(seed(SourceKind.STEPS, actionOrigin = MANUAL))
	}

	@Test
	fun `later Pressure action cannot reuse original manual start origin`() = runTest {
		assertActionRejected(seed(SourceKind.PRESSURE, actionOrigin = MANUAL))
	}

	@Test
	fun `Steps later action cannot prove a timeline missing its prepared manifest`() = runTest {
		val wal = seed(SourceKind.STEPS)
		removePreparedManifest()
		assertTimelineRejected(wal)
	}

	@Test
	fun `Pressure later action cannot prove a timeline missing its prepared manifest`() = runTest {
		val wal = seed(SourceKind.PRESSURE)
		removePreparedManifest()
		assertTimelineRejected(wal)
	}

	@Test
	fun `Steps later manual manifest fails even with matching action and valid checksum`() = runTest {
		assertTimelineRejected(seed(SourceKind.STEPS, laterOrigin = MANUAL))
	}

	@Test
	fun `Pressure later manual manifest fails even with matching action and valid checksum`() = runTest {
		assertTimelineRejected(seed(SourceKind.PRESSURE, laterOrigin = MANUAL))
	}

	private suspend fun assertProven(wal: SourceEventWalEntity) {
		val result = recording(wal)
		(result is SourceProofResult.Proven) shouldBe true
		val state = (result as SourceProofResult.Proven).state
		state.lineage.evidenceIdentity shouldBe SourceEvidenceIdentity(wal.eventId)
		state.lineage.sourceAdmissionOrdinal shouldBe wal.admissionOrdinal
		state.completeness shouldBe SourceProofCompleteness.COMPLETE
		val run = requireNotNull(database.sourceSessionDao().serviceRun(RUN))
		val manifests = database.sourceSessionDao().manifestsForServiceRun(RUN, 3)
		SessionManifestIntegrity.hasValidServiceRunTimeline(run, manifests) shouldBe true
		manifests.last().startOrigin shouldBe RECONCILIATION
		manifests.first().startOrigin shouldBe run.startOrigin
	}

	private suspend fun assertActionRejected(wal: SourceEventWalEntity) {
		val result = recording(wal)
		when (SourceKind.entries.single { it.stableCode == wal.sourceKind }) {
			SourceKind.STEPS -> (result as SourceProofResult.Unavailable).reason shouldBe
				SourceProofReason.IMMUTABLE_AUTHORITY_MISSING
			SourceKind.PRESSURE -> (result as SourceProofResult.Failed).reason shouldBe
				SourceProofReason.INTEGRITY_FAILURE
			else -> error("Unexpected source")
		}
	}

	private suspend fun assertTimelineRejected(wal: SourceEventWalEntity) {
		(recording(wal) as SourceProofResult.Failed).reason shouldBe SourceProofReason.INTEGRITY_FAILURE
	}

	private suspend fun recording(wal: SourceEventWalEntity): SourceProofResult<RecordingProofState> {
		val source = if (wal.sourceKind == SourceKind.STEPS.stableCode) {
			TrackingSource.STEPS
		} else {
			TrackingSource.PRESSURE
		}
		return DefaultSourceLifecycleProofAdapterFactory(
			database = database,
			payloadCodec = payloadCodec,
			planCodec = planCodec,
			locationQualifier = mockk<LocationWalQualificationAdapter>(),
			activityQualifier = mockk<ActivityCapturedWalAdmissionAdapter>(),
			wifiQualifier = mockk<WifiWalQualificationAdapter>(),
			cellQualifier = mockk<CellWalQualificationAdapter>(),
			bootClockDomainProvider = BootClockDomainProvider { BOOT },
		).create(source).recording(
			SourceProofKey(
				source = source,
				purpose = TrackingPurpose.SESSION_CAPTURE,
				evidenceIdentity = SourceEvidenceIdentity(wal.eventId),
				sourcePolicyRevision = POLICY,
				captureConsentEpoch = CONSENT,
				collectedDataEpoch = EPOCH,
				scope = SourceProofScope.Run(LOGICAL, RUN),
			),
		)
	}

	private fun removePreparedManifest() {
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM session_manifest_version WHERE logical_tracking_id = ? AND manifest_revision = 1",
			arrayOf(LOGICAL),
		)
	}

	/** Both revisions share the original run and physical registration, as a compatible reconfiguration does. */
	// Keep the fixed two-manifest Steps/Pressure reconfiguration fixture variants together.
	@Suppress("LongMethod", "CyclomaticComplexMethod")
	private suspend fun seed(
		source: SourceKind,
		manifestRevision: Long = 2L,
		originalOrigin: String = MANUAL,
		actionOrigin: String? = null,
		laterOrigin: String = RECONCILIATION,
	): SourceEventWalEntity {
		database.sourceEvidenceStateDao().ensure()
		database.sourceEvidenceStateDao().updateLifecycle(EPOCH, null, 1L)
		val mode = if (originalOrigin == AUTOMATIC) {
			"AUTOMATIC"
		} else {
			"MANUAL"
		}
		val segmentId = database.sessionSegmentDao().insert(
			SessionSegment(
				startTimeMs = START_MS, endTimeMs = 5_000L, distanceM = 0f, steps = null,
				primaryActivity = null, activityConfidence = null, sampleCount = 0,
				source = SegmentSource.USER_CREATED, inferenceVersion = null, createdAt = START_MS,
				logicalTrackingId = LOGICAL, serviceRunId = RUN,
			),
		)
		database.sourceSessionDao().insertSession(
			LogicalTrackingSessionEntity(
				logicalTrackingId = LOGICAL, state = "ACTIVE", lifecycleRevision = 2L,
				desiredPlanRevision = 2L, rolloutRevision = 1L, startOrigin = originalOrigin,
				clockDomainId = BOOT, startedAtMs = START_MS, startedElapsedNanos = elapsed(START_MS),
				cutoffAtMs = null, cutoffElapsedNanos = null, completedAtMs = null,
				finalAdmissionOrdinal = null, failureCode = null, sessionMode = mode,
				currentManifestRevision = 2L, currentIntentRevision = 1L, currentServiceRunId = RUN,
				lifecycleLeaseGeneration = 1L, lifecycleBootId = BOOT,
				automationEpoch = 1L.takeIf { mode == "AUTOMATIC" },
			),
		)
		database.sourceSessionDao().insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = RUN, logicalTrackingId = LOGICAL, state = "ACTIVE", desiredPlanRevision = 2L,
				rolloutRevision = 1L, foregroundCapabilityFlags = 0L, startedAtMs = START_MS,
				startedElapsedNanos = elapsed(START_MS), completedAtMs = null, completionReason = null,
				bootId = BOOT, leaseGeneration = 1L, startOrigin = originalOrigin,
				runtimeAcknowledgement = "START_ACCEPTED", runRevision = 2L,
				startDeliveryToken = "start-delivery", startCommandGeneration = 1L,
				preparedManifestRevision = 1L, preparedIntentRevision = 1L,
				androidDeliveryState = "FOREGROUND_ACCEPTED", androidDeliveryUpdatedAtMs = START_MS,
				startIsUserInitiated = mode == "MANUAL", sessionSegmentId = segmentId,
			),
		)
		insertPolicy(source)
		val demands = mutableListOf<SourceDemandEntity>()
		for (revision in 1L..2L) {
			val effectiveMs = if (revision == 1L) {
				START_MS
			} else {
				RECONFIGURED_MS
			}
			val origin = if (revision == 1L) {
				originalOrigin
			} else {
				laterOrigin
			}
			val plan = plan(source, revision)
			val encoded = planCodec.encode(plan)
			database.sourcePlanStateDao().insertRevision(
				AcquisitionPlanRevisionEntity(revision, "plan-$revision", effectiveMs, "APPLIED", POLICY),
			)
			database.sourcePlanStateDao().insertDesiredPlans(
				listOf(SourceDesiredPlanEntity(revision, source.stableCode, encoded.formatVersion, encoded.bytes, encoded.checksum)),
			)
			val binding = binding(source, revision, mode)
			val unsignedManifest = SessionManifestVersionEntity(
				logicalTrackingId = LOGICAL, manifestRevision = revision, serviceRunId = RUN,
				sessionMode = mode, sourcePolicyRevision = POLICY, acquisitionPlanRevision = revision,
				rolloutRevision = 1L, startOrigin = origin, effectiveBootId = BOOT,
				effectiveElapsedRealtimeNanos = elapsed(effectiveMs), effectiveWallTimeMs = effectiveMs,
				zoneId = "UTC", automationEpoch = 1L.takeIf { mode == "AUTOMATIC" },
				changeReason = if (revision == 1L) {
					"START"
				} else {
					origin
				}, manifestChecksum = "pending",
			)
			database.sourceSessionDao().insertManifest(
				unsignedManifest.copy(manifestChecksum = SessionManifestIntegrity.compute(unsignedManifest, listOf(binding))),
			)
			database.sourceSessionDao().insertManifestSources(listOf(binding))
			val demand = demand(source, revision, effectiveMs)
			demands += demand
			database.sourceBrokerDao().insertDemands(listOf(demand))
			database.sourceBrokerDao().insertAuthorizations(
				SourceBrokerAuthorization.rows(source.stableCode, 1L, revision, listOf(demand), BOOT, elapsed(effectiveMs), effectiveMs),
			)
			database.sourceSessionDao().insertLifecycleActions(
				listOf(
					LifecycleDesiredActionEntity(
						actionId = "start-action-$revision", logicalTrackingId = LOGICAL, serviceRunId = RUN,
						manifestRevision = revision, actionRevision = revision, actionFamily = "SOURCE_RUNTIME",
						sourceKind = source.stableCode, desiredState = "STARTED", desiredPlanRevision = revision,
						sourcePolicyRevision = POLICY, consentEpoch = CONSENT,
						startOrigin = if (revision == manifestRevision) {
							actionOrigin ?: origin
						} else {
							origin
						},
						bootId = BOOT, leaseGeneration = 1L, requestedAtMs = effectiveMs,
						requestedElapsedRealtimeNanos = elapsed(effectiveMs), status = "START_ACCEPTED",
						attemptCount = 1, acknowledgedAtMs = effectiveMs,
						acknowledgedElapsedRealtimeNanos = elapsed(effectiveMs) + 1L,
						failureCode = null, retryTrigger = null, sourceInstanceId = PROVIDER, registrationGeneration = 1L,
					),
				),
			)
		}
		val fingerprint = plan(source, manifestRevision).physicalConfigurationFingerprint()
		database.sourceBrokerDao().insertRegistration(
			ProviderRegistrationGenerationEntity(
				sourceKind = source.stableCode, registrationGeneration = 1L, sourceInstanceId = PROVIDER,
				ownerScope = SourceProviderPurposeScope.sharedOwnerScope(source.stableCode), clockDomainId = BOOT,
				physicalConfigurationFingerprint = fingerprint, collectedDataEpoch = EPOCH,
				providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
				providerProcessIncarnationId = "current-process", status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
				reservedAtMs = START_MS, reservedElapsedRealtimeNanos = elapsed(START_MS),
				acceptedAtMs = START_MS, acceptedElapsedRealtimeNanos = elapsed(START_MS),
				retiredAtMs = null, retiredElapsedRealtimeNanos = null, failureCode = null,
			),
		)
		val selectedDemand = demands.single { it.manifestRevision == manifestRevision }
		val effectiveMs = if (manifestRevision == 1L) {
			START_MS
		} else {
			RECONFIGURED_MS
		}
		val windowStart = elapsed(effectiveMs + 200L)
		val windowEnd = windowStart + 150_000_000L
		val payload = payload(source, windowStart, windowEnd)
		val payloadVersion = if (source == SourceKind.STEPS) {
			7
		} else {
			4
		}
		val encoded = payloadCodec.encode(payload, payloadVersion)
		val wallMs = windowEnd / NANOS_PER_MS
		val unsigned = SourceEventWalEntity(
			eventId = "reconfigured-event", providerDedupKey = "step-dedup".takeIf { source == SourceKind.STEPS },
			deliveryIdentity = (payload as? PressureWindowPayload)?.let { pressureProviderDeliveryIdentity(BOOT, it).value },
			deliveryUnitIndex = 0.takeIf { source == SourceKind.PRESSURE },
			deliveryUnitCount = 1.takeIf { source == SourceKind.PRESSURE },
			logicalTrackingId = LOGICAL, serviceRunId = RUN, sourceKind = source.stableCode,
			sourceInstanceId = PROVIDER, registrationGeneration = 1L, physicalConfigurationFingerprint = fingerprint,
			authorizationRevision = manifestRevision, authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			authorizationFingerprint = SourceBrokerAuthorization.fingerprint(listOf(selectedDemand)),
			sourceSequence = 2L, configRevision = manifestRevision, planAttribution = PlanAttribution.CAPTURED_REGISTRATION.ordinal,
			clockDomainId = BOOT, observedElapsedNanos = windowEnd, observedIntervalStartNanos = windowStart,
			receivedElapsedNanos = windowEnd + 1L, receivedWallTimeMs = wallMs + 1L,
			wallTimeMs = wallMs, wallTimeUncertaintyMs = 1L, capturedCollectedDataEpoch = EPOCH,
			sourcePolicyRevision = POLICY, captureConsentEpoch = CONSENT, sessionManifestRevision = manifestRevision,
			lifecycleLeaseGeneration = 1L, acquiredAtMs = wallMs, qualityFlags = 0L, qualityConfidence = null,
			payloadVersion = payloadVersion, payload = encoded.bytes, payloadChecksum = encoded.checksum,
			createdAtMs = wallMs,
		)
		val signed = unsigned.copy(integrityIdentity = unsigned.calculatedIntegrityIdentity())
		val ordinal = database.sourceEventWalDao().insertAbortingOnUnexpectedConflict(signed)
		val wal = signed.copy(admissionOrdinal = ordinal)
		if (source == SourceKind.STEPS) {
			StepsCountDomainStore(database).recordSessionWal(wal, COUNTER_TOKEN) shouldBe StepsCountDomainWriteResult.INSERTED
		}
		return wal
	}

	private suspend fun insertPolicy(source: SourceKind) {
		database.sourcePolicyDao().ensureAuthority(
			SourcePolicyAuthorityEntity(
				bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE, currentPolicyRevision = POLICY,
				legacySettingsFingerprint = null, updatedAtMs = START_MS,
			),
		)
		database.sourcePolicyDao().insertPolicies(
			listOf(
				SourcePolicyEntity(
					policyRevision = POLICY, sourceKind = source.stableCode, enabled = true, qosCode = 1,
					locationMinTimeSeconds = null, locationMinDistanceMeters = null, locationRequiredAccuracyMeters = null,
					capturePersistenceEligible = true, controlPersistenceEligible = false, ambientPersistenceEligible = false,
					captureConsentEpoch = CONSENT, controlConsentEpoch = null, ambientConsentEpoch = null,
					effectiveBootId = BOOT, effectiveElapsedRealtimeNanos = elapsed(START_MS), effectiveWallTimeMs = START_MS,
					changeReason = "START",
				),
			),
		)
		database.sourcePolicyDao().insertConsentEpochs(
			listOf(
				SourceConsentEpochEntity(
					sourceKind = source.stableCode, purpose = SourceBrokerPurpose.SESSION_CAPTURE, epoch = CONSENT,
					eligible = true, persistenceEligible = true, policyRevision = POLICY, effectiveBootId = BOOT,
					effectiveElapsedRealtimeNanos = elapsed(START_MS), effectiveWallTimeMs = START_MS, changeReason = "START",
				),
			),
		)
	}

	private fun binding(source: SourceKind, revision: Long, sessionMode: String): SessionManifestSourceEntity {
		val steps = source == SourceKind.STEPS
		val bindingGeneration = when {
			sessionMode == "AUTOMATIC" && steps -> SourceDestinationOwnerEntity.STEPS_FACT_AUTOMATIC_BINDING_GENERATION
			sessionMode == "AUTOMATIC" -> SourceDestinationOwnerEntity.PRESSURE_FACT_AUTOMATIC_BINDING_GENERATION
			steps -> SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION
			else -> SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION
		}
		return SessionManifestSourceEntity(
			logicalTrackingId = LOGICAL, manifestRevision = revision, sourceKind = source.stableCode,
			purpose = SessionManifestPurposeCode.SESSION_CAPTURE, consentEpoch = CONSENT,
			persistenceEligible = true, qosCode = 1,
			outputDestination = if (steps) {
				SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS
			} else {
				SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE
			},
			writerOwner = if (steps) {
				SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS
			} else {
				SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS
			},
			writerOwnerGeneration = SourceWriterGenerationContract.canonicalOwnerGeneration(bindingGeneration),
			writerProjectionId = if (steps) {
				SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID
			} else {
				SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID
			},
			writerProjectionVersion = if (steps) {
				SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION
			} else {
				SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION
			},
			writerBindingGeneration = bindingGeneration,
		)
	}

	private fun demand(source: SourceKind, revision: Long, effectiveMs: Long): SourceDemandEntity {
		val floor = when (source) {
			SourceKind.STEPS -> StepsAcquisitionFloor(StepsAcquisitionMechanism.DIRECT_COUNTER, 0L, false)
			SourceKind.PRESSURE -> PressureAcquisitionFloor(50_000, 0, 200L)
			else -> error("Unexpected source")
		}
		return SourceDemandEntity(
			demandId = "demand-$revision", consumerId = "session:$LOGICAL", sourceKind = source.stableCode,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE, logicalTrackingId = LOGICAL, serviceRunId = RUN,
			manifestRevision = revision, lifecycleLeaseGeneration = 1L, sourcePolicyRevision = POLICY,
			consentEpoch = CONSENT, persistenceEligible = true, qosCode = 1,
			minimumAcquisitionSpec = SourceAcquisitionFloorCodec.encode(floor), adaptiveReductionAllowed = true,
			maximumAgeMs = 200L, desiredLatencyMs = 200L, requestedDeliveryLatencyMs = 0L,
			requestedBootId = BOOT, requestedElapsedRealtimeNanos = elapsed(effectiveMs), requestedAtMs = effectiveMs,
			status = if (revision == 1L) {
				SourceDemandEntity.STATUS_RETIRED
			} else {
				SourceDemandEntity.STATUS_ACTIVE
			},
			retireBootId = BOOT.takeIf { revision == 1L },
			retireElapsedRealtimeNanos = elapsed(RECONFIGURED_MS).takeIf { revision == 1L },
			retiredAtMs = RECONFIGURED_MS.takeIf { revision == 1L },
		)
	}

	private fun plan(source: SourceKind, revision: Long): SourcePlan = when (source) {
		SourceKind.STEPS -> StepsPlan(revision, true, 0L, 1_000L, false)
		SourceKind.PRESSURE -> PressurePlan(revision, true, 50_000, 0, 200L)
		else -> error("Unexpected source")
	}

	private fun payload(source: SourceKind, start: Long, end: Long): SourcePayload = when (source) {
		SourceKind.STEPS -> StepCounterWindowPayload(
			bootClockDomainId = BOOT, firstCumulativeCount = 100L, lastCumulativeCount = 105L, deltaCount = 5L,
			windowStartElapsedRealtimeNanos = start, windowEndElapsedRealtimeNanos = end,
			firstProviderSequence = 1L, lastProviderSequence = 2L, boundaryKind = StepBoundaryKind.COVERED,
			counterDomainToken = COUNTER_TOKEN, counterEpochGeneration = 1L,
		)
		SourceKind.PRESSURE -> PressureWindowPayload(
			sampleCount = 4, meanHectopascals = 1_001.5, sumSquaredDeviations = 5.0,
			minimumHectopascals = 1_000f, maximumHectopascals = 1_003f,
			windowStartElapsedRealtimeNanos = start, windowEndElapsedRealtimeNanos = end,
			firstProviderSequence = 1L, lastProviderSequence = 4L, firstHectopascals = 1_000f, lastHectopascals = 1_003f,
			slopeHectopascalsPerSecond = 20.0, rSquared = 1.0, sensorAccuracy = PressureSensorAccuracy.HIGH,
			effectiveSamplePeriodMicros = 50_000, effectiveMaximumReportLatencyMicros = 0,
			targetWindowDurationNanos = 200_000_000L, expectedSampleCount = 4, maximumInterSampleGapNanos = 50_000_000L,
			closureKind = PressureWindowClosureKind.TARGET_ELAPSED,
		)
		else -> error("Unexpected source")
	}

	private fun elapsed(wallMs: Long): Long = wallMs * NANOS_PER_MS

	private companion object {
		const val LOGICAL = "reconfigured-logical"
		const val RUN = "reconfigured-run"
		const val PROVIDER = "reconfigured-provider"
		const val BOOT = "reconfigured-boot"
		const val MANUAL = "MANUAL_FOREGROUND_START"
		const val AUTOMATIC = "AUTOMATIC_BACKGROUND_START"
		const val RECONCILIATION = "POLICY_RECONCILIATION"
		const val START_MS = 1_000L
		const val RECONFIGURED_MS = 2_000L
		const val NANOS_PER_MS = 1_000_000L
		const val POLICY = 1L
		const val CONSENT = 1L
		const val EPOCH = 0L
		val COUNTER_TOKEN = StepsCounterDomainToken.opaque("sha256:" + "a".repeat(64))
	}
}
