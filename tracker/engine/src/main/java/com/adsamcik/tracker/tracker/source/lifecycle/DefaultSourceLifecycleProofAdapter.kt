package com.adsamcik.tracker.tracker.source.lifecycle

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.CellPurposeItems
import com.adsamcik.tracker.shared.base.database.PressureFactLineageProof
import com.adsamcik.tracker.shared.base.database.PressureFactProofAuthenticator
import com.adsamcik.tracker.shared.base.database.PressureFactProofExpectation
import com.adsamcik.tracker.shared.base.database.PressureFactProofFailure
import com.adsamcik.tracker.shared.base.database.PressureFactRetentionResolution
import com.adsamcik.tracker.shared.base.database.PressureWalCaptureProof
import com.adsamcik.tracker.shared.base.database.ProviderRegistrationActualObservation
import com.adsamcik.tracker.shared.base.database.ProviderRegistrationFactWindow
import com.adsamcik.tracker.shared.base.database.ProviderRegistrationIntervalAuthentication
import com.adsamcik.tracker.shared.base.database.ProviderRegistrationIntervalFailure
import com.adsamcik.tracker.shared.base.database.StepsCountDomainOwnerRead
import com.adsamcik.tracker.shared.base.database.StepsCountDomainStore
import com.adsamcik.tracker.shared.base.database.authenticateHistoricalProviderFactWindow
import com.adsamcik.tracker.shared.base.database.authenticateHistoricalProviderRegistration
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedEvidenceEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedFragmentEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedWindowCursorEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedWindowRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityWalCarrierAuthenticator
import com.adsamcik.tracker.shared.base.database.data.CELL_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.data.CanonicalRetentionBounds
import com.adsamcik.tracker.shared.base.database.data.CapturedRadioFactRetentionProofEntity
import com.adsamcik.tracker.shared.base.database.data.CellAggregateReuseAuthority
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.CellWalCarrierAuthentication
import com.adsamcik.tracker.shared.base.database.data.CellWalCarrierAuthenticator
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.LocationObservation
import com.adsamcik.tracker.shared.base.database.data.LocationObservationDecision
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.MAX_ACTIVITY_WAL_PAYLOAD_BYTES
import com.adsamcik.tracker.shared.base.database.data.MAX_PRESSURE_WAL_PAYLOAD_BYTES
import com.adsamcik.tracker.shared.base.database.data.MAX_STEPS_WAL_PAYLOAD_BYTES
import com.adsamcik.tracker.shared.base.database.data.MAX_WIFI_WAL_PAYLOAD_BYTES
import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.PressureWalCarrierAuthentication
import com.adsamcik.tracker.shared.base.database.data.PressureWalCarrierAuthenticator
import com.adsamcik.tracker.shared.base.database.data.PressureWalCarrierFailure
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventRetentionProofEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionJoinStateEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceWriterGenerationContract
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.StepsWalBoundaryKind
import com.adsamcik.tracker.shared.base.database.data.StepsWalCarrierAuthentication
import com.adsamcik.tracker.shared.base.database.data.StepsWalCarrierAuthenticator
import com.adsamcik.tracker.shared.base.database.data.StepsWalCarrierFailure
import com.adsamcik.tracker.shared.base.database.data.WIFI_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedChildEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.WifiWalCarrierAuthentication
import com.adsamcik.tracker.shared.base.database.data.WifiWalCarrierAuthenticator
import com.adsamcik.tracker.shared.base.database.data.hasValidCanonicalHistoryShape
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.base.database.loadPressureFactProofSnapshot
import com.adsamcik.tracker.shared.base.database.qualifyCellCapturePurposeItems
import com.adsamcik.tracker.shared.base.database.qualifyWifiPurposeItems
import com.adsamcik.tracker.shared.base.database.resolvePressureRetentionByEvidence
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.stats.api.repository.MaterializedProofState
import com.adsamcik.tracker.stats.api.repository.RecordingProofState
import com.adsamcik.tracker.stats.api.repository.SourceEvidenceIdentity
import com.adsamcik.tracker.stats.api.repository.SourceFactIdentity
import com.adsamcik.tracker.stats.api.repository.SourceProofCompleteness
import com.adsamcik.tracker.stats.api.repository.SourceProofCoverage
import com.adsamcik.tracker.stats.api.repository.SourceProofKey
import com.adsamcik.tracker.stats.api.repository.SourceProofLineage
import com.adsamcik.tracker.stats.api.repository.SourceProofReason
import com.adsamcik.tracker.stats.api.repository.SourceProofResult
import com.adsamcik.tracker.stats.api.repository.SourceProofScope
import com.adsamcik.tracker.stats.api.repository.SourceProofSelectedEvidenceLineage
import com.adsamcik.tracker.stats.api.repository.SourceProofStage
import com.adsamcik.tracker.stats.api.repository.SourceProofWriterIdentity
import com.adsamcik.tracker.tracker.source.activity.ActivityCapturedWalAdmissionAdapter
import com.adsamcik.tracker.tracker.source.activity.ActivityCapturedWalAdmissionRejection
import com.adsamcik.tracker.tracker.source.activity.ActivityCapturedWalAdmissionResult
import com.adsamcik.tracker.tracker.source.cell.CellCaptureAuthority
import com.adsamcik.tracker.tracker.source.cell.CellCapturedFact
import com.adsamcik.tracker.tracker.source.cell.CellCapturedFactClassification
import com.adsamcik.tracker.tracker.source.cell.CellWalAdapterRejection
import com.adsamcik.tracker.tracker.source.cell.CellWalAdapterResult
import com.adsamcik.tracker.tracker.source.cell.CellWalQualificationAdapter
import com.adsamcik.tracker.tracker.source.coordinator.EncodedSourcePlan
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.ingress.SourcePayloadCodec
import com.adsamcik.tracker.tracker.source.location.LocationObservationQualification
import com.adsamcik.tracker.tracker.source.location.LocationStaleReason
import com.adsamcik.tracker.tracker.source.location.LocationWalAdapterRejection
import com.adsamcik.tracker.tracker.source.location.LocationWalAdapterResult
import com.adsamcik.tracker.tracker.source.location.LocationWalQualificationAdapter
import com.adsamcik.tracker.tracker.source.location.ProtectedLocationCanonicalSignalIdentity
import com.adsamcik.tracker.tracker.source.location.authenticatesProtectedLocationCanonicalReceipt
import com.adsamcik.tracker.tracker.source.location.hasAcceptedAmbientSiblingReceipt
import com.adsamcik.tracker.tracker.source.model.DemandReason
import com.adsamcik.tracker.tracker.source.model.EvidenceQuality
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.PressureSensorAccuracy
import com.adsamcik.tracker.tracker.source.model.PressureWindowClosureKind
import com.adsamcik.tracker.tracker.source.model.PressureWindowPayload
import com.adsamcik.tracker.tracker.source.model.PressureWindowQualification
import com.adsamcik.tracker.tracker.source.model.SourceDemand
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import com.adsamcik.tracker.tracker.source.model.SourceQualityFlag
import com.adsamcik.tracker.tracker.source.model.expectedPressureSampleCount
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import com.adsamcik.tracker.tracker.source.model.satisfies
import com.adsamcik.tracker.tracker.source.runtime.toSourceDemandContract
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFact
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactClassification
import com.adsamcik.tracker.tracker.source.wifi.WifiWalAdapterRejection
import com.adsamcik.tracker.tracker.source.wifi.WifiWalAdapterResult
import com.adsamcik.tracker.tracker.source.wifi.WifiWalQualificationAdapter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class DefaultSourceLifecycleProofAdapterFactory @Inject constructor(
	private val database: AppDatabase,
	private val payloadCodec: SourcePayloadCodec,
	private val planCodec: SourcePlanCodec,
	private val locationQualifier: LocationWalQualificationAdapter,
	private val activityQualifier: ActivityCapturedWalAdmissionAdapter,
	private val wifiQualifier: WifiWalQualificationAdapter,
	private val cellQualifier: CellWalQualificationAdapter,
	private val bootClockDomainProvider: BootClockDomainProvider,
) {
	fun create(source: TrackingSource): SourceLifecycleProofAdapter =
		DefaultSourceLifecycleProofAdapter(
			source = source,
			database = database,
			payloadCodec = payloadCodec,
			planCodec = planCodec,
			locationQualifier = locationQualifier,
			activityQualifier = activityQualifier,
			wifiQualifier = wifiQualifier,
			cellQualifier = cellQualifier,
			bootClockDomainProvider = bootClockDomainProvider,
		)
}

@Suppress("LargeClass", "TooManyFunctions")
private class DefaultSourceLifecycleProofAdapter(
	override val source: TrackingSource,
	private val database: AppDatabase,
	private val payloadCodec: SourcePayloadCodec,
	private val planCodec: SourcePlanCodec,
	private val locationQualifier: LocationWalQualificationAdapter,
	private val activityQualifier: ActivityCapturedWalAdmissionAdapter,
	private val wifiQualifier: WifiWalQualificationAdapter,
	private val cellQualifier: CellWalQualificationAdapter,
	private val bootClockDomainProvider: BootClockDomainProvider,
) : SourceLifecycleProofAdapter {
	override suspend fun recording(
		key: SourceProofKey,
	): SourceProofResult<RecordingProofState> {
		preflightKey(key)?.let { return it }
		return when (source) {
			TrackingSource.LOCATION -> recordingLocation(key)
			TrackingSource.ACTIVITY -> recordingActivity(key)
			TrackingSource.STEPS -> recordingSteps(key)
			TrackingSource.PRESSURE -> recordingPressure(key)
			TrackingSource.WIFI -> recordingWifi(key)
			TrackingSource.CELL -> recordingCell(key)
		}
	}

	override suspend fun materialized(
		key: SourceProofKey,
		recording: RecordingProofState,
	): SourceProofResult<MaterializedProofState> {
		if (recording.lineage.evidenceIdentity != key.evidenceIdentity) {
			return failed(SourceProofStage.MATERIALIZED, SourceProofReason.FACT_IDENTITY_MISMATCH)
		}
		return when (source) {
			TrackingSource.LOCATION -> materializedLocation(key, recording)
			TrackingSource.ACTIVITY -> materializedActivity(key, recording)
			TrackingSource.STEPS -> materializedSteps(key, recording)
			TrackingSource.PRESSURE -> materializedPressure(key, recording)
			TrackingSource.WIFI -> materializedWifi(key, recording)
			TrackingSource.CELL -> materializedCell(key, recording)
		}
	}

	private fun preflightKey(key: SourceProofKey): SourceProofResult<RecordingProofState>? = when {
		key.source != source -> failed(SourceProofStage.RECORDING, SourceProofReason.WRONG_SOURCE)
		key.purpose == TrackingPurpose.CONTROL -> unavailable(
			SourceProofStage.RECORDING, SourceProofReason.CONTROL_IS_NOT_CAPTURE_EVIDENCE,
		)
		key.purpose == TrackingPurpose.AMBIENT_PRODUCT -> unavailable(
			SourceProofStage.RECORDING, SourceProofReason.AMBIENT_PROOF_NOT_IMPLEMENTED,
		)
		key.scope !is SourceProofScope.Run -> failed(SourceProofStage.RECORDING, SourceProofReason.SCOPE_MISMATCH)
		else -> null
	}

	private suspend fun recordingLocation(key: SourceProofKey): SourceProofResult<RecordingProofState> =
		when (val qualification = locationQualifier.qualify(SourceEventId(key.evidenceIdentity.value))) {
			is LocationWalAdapterResult.Evaluated -> locationRecordingResult(key, qualification.qualification)
			is LocationWalAdapterResult.Rejected -> if (qualification.reason == LocationWalAdapterRejection.MISSING_EVENT) {
				missingRecordingEvidence(key)
			} else {
				qualification.reason.toRecordingResult()
			}
		}

	private suspend fun locationRecordingResult(
		key: SourceProofKey,
		qualification: LocationObservationQualification,
	): SourceProofResult<RecordingProofState> = when (qualification) {
		is LocationObservationQualification.Qualified -> locationRecordingPoint(
			key, qualification.command.productEffect.durableEvidence.clockAuthority.observedElapsedRealtimeNanos,
		)
		is LocationObservationQualification.Duplicate -> locationRecordingPoint(
			key, qualification.existing.productEffect.durableEvidence.clockAuthority.observedElapsedRealtimeNanos,
		)
		is LocationObservationQualification.Stale -> staleLocationRecording(qualification.reason)
		is LocationObservationQualification.Unavailable ->
			unavailable(SourceProofStage.RECORDING, SourceProofReason.PROVIDER_FAILURE)
		is LocationObservationQualification.Rejected -> failed(
			SourceProofStage.RECORDING,
			when (qualification.reason.name) {
				"INSUFFICIENT_ACCURACY", "INVALID_ACCURACY" -> SourceProofReason.ACCURACY_UNQUALIFIED
				else -> SourceProofReason.MALFORMED_EVIDENCE
			},
		)
	}

	private suspend fun locationRecordingPoint(
		key: SourceProofKey,
		observedAtNanos: Long,
	): SourceProofResult<RecordingProofState> = recordingFromWal(key, SourceKind.LOCATION) { wal, revision ->
		recordingState(key, wal, revision, providerCoverage(observedAtNanos, observedAtNanos),
			SourceProofCompleteness.COMPLETE)
	}

	private fun staleLocationRecording(reason: LocationStaleReason): SourceProofResult<RecordingProofState> =
		when (reason) {
		LocationStaleReason.BEFORE_DELETION_FLOOR -> deleted(SourceProofStage.RECORDING, SourceProofReason.RETENTION_EXPIRED)
		LocationStaleReason.PRE_EFFECTIVE -> unavailable(SourceProofStage.RECORDING, SourceProofReason.PRE_EFFECTIVE)
		else -> unavailable(SourceProofStage.RECORDING, SourceProofReason.STALE)
	}

	private suspend fun recordingActivity(
		key: SourceProofKey,
	): SourceProofResult<RecordingProofState> =
		when (val admission = activityQualifier.admitHistorical(
			SourceEventId(key.evidenceIdentity.value),
		)) {
			is ActivityCapturedWalAdmissionResult.Admitted ->
				recordingFromWal(key, SourceKind.ACTIVITY) { wal, revision ->
					val reference = admission.observation.reference
					val coverageEnd = reference.coverageEndExclusiveElapsedRealtimeNanos
					recordingState(
						key,
						wal,
						revision,
						if (coverageEnd == null) {
							SourceProofCoverage.ProviderPoint(reference.providerElapsedRealtimeNanos)
						} else {
							SourceProofCoverage.ProviderInterval(
								reference.providerElapsedRealtimeNanos,
								coverageEnd,
							)
						},
						SourceProofCompleteness.COMPLETE,
					)
				}
			is ActivityCapturedWalAdmissionResult.ObservationRejected -> unavailable(
				SourceProofStage.RECORDING,
				if (admission.reason.name == "CONTROL_ONLY") {
					SourceProofReason.CONTROL_IS_NOT_CAPTURE_EVIDENCE
				} else {
					SourceProofReason.STALE
				},
			)
			is ActivityCapturedWalAdmissionResult.Unavailable -> pending(
				SourceProofStage.RECORDING,
				SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
			)
			is ActivityCapturedWalAdmissionResult.Rejected ->
				if (admission.reason == ActivityCapturedWalAdmissionRejection.MISSING_EVENT) {
					missingRecordingEvidence(key)
				} else {
					admission.reason.toRecordingResult()
				}
		}

	private suspend fun recordingSteps(
		key: SourceProofKey,
	): SourceProofResult<RecordingProofState> =
		recordingFromWal(key, SourceKind.STEPS, MAX_STEPS_WAL_PAYLOAD_BYTES) { wal, revision ->
			val owner = when (val read = StepsCountDomainStore(database).readOwners(
				listOf(wal.sourceLifecycleStepsWalOwnerKey()),
			)) {
				is StepsCountDomainOwnerRead.Ready -> read.owners[wal.sourceLifecycleStepsWalOwnerKey()]
				StepsCountDomainOwnerRead.SchemaUnavailable -> return@recordingFromWal unavailable(
					SourceProofStage.RECORDING,
					SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
				)
				StepsCountDomainOwnerRead.Overflow,
				StepsCountDomainOwnerRead.Unverifiable,
				-> return@recordingFromWal failed(
					SourceProofStage.RECORDING,
					SourceProofReason.INTEGRITY_FAILURE,
				)
			}
			val authentication = when (
				val result = StepsWalCarrierAuthenticator.authenticate(wal, owner)
			) {
				is StepsWalCarrierAuthentication.Authenticated -> result
				is StepsWalCarrierAuthentication.Rejected ->
					return@recordingFromWal result.toRecordingResult()
			}
			val payload = authentication.carrier.payload
			if (!authentication.carrier.isPositiveCoveredWindow) {
				return@recordingFromWal unavailable(
					SourceProofStage.RECORDING,
					if (payload.deltaCount == 0L &&
						payload.boundaryKind ==
						StepsWalBoundaryKind.COVERED
					) {
						SourceProofReason.ZERO_DELTA
					} else {
						SourceProofReason.BASELINE_ONLY
					},
				)
			}
			historicalSessionAuthority(
				key = key,
				wal = wal,
				expectedSource = SourceKind.STEPS,
				observedStartNanos = payload.windowStartElapsedRealtimeNanos,
				observedEndNanos = payload.windowEndElapsedRealtimeNanos,
			)?.let { return@recordingFromWal it }
			recordingState(
				key,
				wal,
				revision,
				providerCoverage(
					payload.windowStartElapsedRealtimeNanos,
					payload.windowEndElapsedRealtimeNanos,
				),
				SourceProofCompleteness.COMPLETE,
			)
		}

	private suspend fun recordingPressure(
		key: SourceProofKey,
	): SourceProofResult<RecordingProofState> =
		recordingFromWal(key, SourceKind.PRESSURE, MAX_PRESSURE_WAL_PAYLOAD_BYTES) { wal, revision ->
			val authentication = when (
				val result = PressureWalCarrierAuthenticator.authenticate(wal)
			) {
				is PressureWalCarrierAuthentication.Authenticated -> result
				is PressureWalCarrierAuthentication.Rejected ->
					return@recordingFromWal result.toRecordingResult()
			}
			val payload = authentication.carrier.payload
			val proofSnapshot = database.loadPressureFactProofSnapshot(
				facts = emptyList(),
				walByEventId = mapOf(wal.eventId to wal),
				currentClockDomainId = bootClockDomainProvider.current(),
			)
			when (
				val proof = PressureFactProofAuthenticator.authenticateWal(
					wal = wal,
					snapshot = proofSnapshot,
					expectation = key.toPressureFactProofExpectation(),
				)
			) {
				is PressureWalCaptureProof.Authenticated -> Unit
				is PressureWalCaptureProof.Rejected -> return@recordingFromWal if (
					proof.reason == PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING
				) {
					unavailable(
						SourceProofStage.RECORDING,
						SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
					)
				} else {
					failed(SourceProofStage.RECORDING, SourceProofReason.INTEGRITY_FAILURE)
				}
			}
			recordingState(
				key,
				wal,
				revision,
				providerCoverage(
					payload.windowStartElapsedRealtimeNanos,
					payload.windowEndElapsedRealtimeNanos,
				),
				if (
					authentication.carrier.qualification ==
					PressureFactRevisionEntity.QUALIFICATION_COMPLETE
				) {
					SourceProofCompleteness.COMPLETE
				} else {
					SourceProofCompleteness.PARTIAL
				},
			)
		}

	private suspend fun recordingWifi(key: SourceProofKey): SourceProofResult<RecordingProofState> {
		if (isRunDeleted(key)) {
			return deleted(SourceProofStage.RECORDING, SourceProofReason.DELETED)
		}
		val qualified = wifiQualifier.qualify(SourceEventId(key.evidenceIdentity.value))
		val result = qualified.recordingGroup(key) ?: return missingWifiGroupRecording(key)
		return wifiRecordingResult(key, result)
	}

	private fun WifiWalAdapterResult.recordingGroup(key: SourceProofKey): WifiWalAdapterResult? =
		if (this is WifiWalAdapterResult.Groups) {
			results.filterIsInstance<WifiWalAdapterResult.Evaluated>().singleOrNull { evaluated ->
				evaluated.classification.captureFactOrNull()?.authority?.let { authority ->
					radioGroupMatches(key, authority.logicalTrackingId.value, authority.serviceRunId.value,
						authority.sourcePolicyRevision, authority.captureConsentEpoch)
				} == true
			}
		} else {
			this
		}

	private fun WifiCapturedFactClassification.captureFactOrNull():
		WifiCapturedFact? = when (this) {
			is WifiCapturedFactClassification.FreshChanged -> fact
			is WifiCapturedFactClassification.FreshUnchanged -> fact
			else -> null
		}

	private suspend fun wifiRecordingResult(
		key: SourceProofKey,
		result: WifiWalAdapterResult,
	): SourceProofResult<RecordingProofState> = when (result) {
		is WifiWalAdapterResult.Evaluated -> wifiRecordingClassification(key, result.classification)
		is WifiWalAdapterResult.Rejected -> if (result.reason == WifiWalAdapterRejection.MISSING_EVENT) {
			missingRecordingEvidence(key)
		} else {
			result.reason.toRecordingResult()
		}
		is WifiWalAdapterResult.Groups ->
			unavailable(SourceProofStage.RECORDING, SourceProofReason.IMMUTABLE_AUTHORITY_MISSING)
	}

	// Exhaustive Wi-Fi classification-to-proof dispatch; authentication stays in the recording helpers.
	@Suppress("CyclomaticComplexMethod")
	private suspend fun wifiRecordingClassification(
		key: SourceProofKey,
		classification: WifiCapturedFactClassification,
	): SourceProofResult<RecordingProofState> = when (classification) {
		is WifiCapturedFactClassification.FreshChanged ->
			recordingWifiFact(key, classification.fact)
		is WifiCapturedFactClassification.FreshUnchanged ->
			recordingWifiFact(key, classification.fact)
		is WifiCapturedFactClassification.Replay ->
			recordingFromWal(key, SourceKind.WIFI) { wal, revision ->
				recordingState(
					key,
					wal,
					revision,
					wal.providerCoverage(),
					SourceProofCompleteness.UNKNOWN,
				)
			}
		WifiCapturedFactClassification.Absent -> unavailable(
			SourceProofStage.RECORDING,
			SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
		)
		WifiCapturedFactClassification.Stale -> unavailable(
			SourceProofStage.RECORDING,
			SourceProofReason.STALE,
		)
		WifiCapturedFactClassification.Failed -> unavailable(
			SourceProofStage.RECORDING,
			SourceProofReason.PROVIDER_FAILURE,
		)
		WifiCapturedFactClassification.PermissionLimited -> unavailable(
			SourceProofStage.RECORDING,
			SourceProofReason.PERMISSION_UNAVAILABLE,
		)
		WifiCapturedFactClassification.OsThrottled -> unavailable(
			SourceProofStage.RECORDING,
			SourceProofReason.THROTTLED,
		)
		WifiCapturedFactClassification.ClockUnverifiable -> unavailable(
			SourceProofStage.RECORDING,
			SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
		)
		is WifiCapturedFactClassification.Rejected -> failed(
			SourceProofStage.RECORDING,
			SourceProofReason.MALFORMED_EVIDENCE,
		)
	}

	private suspend fun recordingWifiFact(
		key: SourceProofKey,
		fact: WifiCapturedFact,
	): SourceProofResult<RecordingProofState> =
		recordingFromWal(key, SourceKind.WIFI, wifiFact = fact) { wal, revision ->
			recordingState(
				key,
				wal,
				revision,
				providerCoverage(
					fact.coverage.providerIntervalStartElapsedRealtimeNanos,
					fact.coverage.providerIntervalEndElapsedRealtimeNanos,
				),
				if (fact.coverage.completeness.name == "COMPLETE") {
					SourceProofCompleteness.COMPLETE
				} else {
					SourceProofCompleteness.PARTIAL
				},
			)
		}

	private suspend fun missingWifiGroupRecording(key: SourceProofKey): SourceProofResult<RecordingProofState> {
		val scope = key.scope as SourceProofScope.Run
		val retained = database.capturedRadioFactRetentionProofDao().eventCandidatesForRun(
			SourceKind.WIFI.stableCode, key.evidenceIdentity.value,
			scope.logicalTrackingId, scope.serviceRunId, key.sourcePolicyRevision, key.captureConsentEpoch,
		)
		return if (retained.isNotEmpty()) {
			missingRecordingEvidence(key)
		} else
			{
			unavailable(SourceProofStage.RECORDING, SourceProofReason.IMMUTABLE_AUTHORITY_MISSING)
		}
	}

	private suspend fun recordingCell(key: SourceProofKey): SourceProofResult<RecordingProofState> {
		if (isRunDeleted(key)) {
			return deleted(SourceProofStage.RECORDING, SourceProofReason.DELETED)
		}
		val qualified = cellQualifier.qualify(SourceEventId(key.evidenceIdentity.value))
		val result = qualified.recordingGroup(key) ?: return missingCellGroupRecording(key)
		return cellRecordingResult(key, result)
	}

	private fun CellWalAdapterResult.recordingGroup(key: SourceProofKey): CellWalAdapterResult? =
		if (this is CellWalAdapterResult.Groups) {
			results.filterIsInstance<CellWalAdapterResult.Evaluated>().singleOrNull { evaluated ->
				evaluated.classification.captureFactOrNull()?.authority?.let { authority ->
					radioGroupMatches(key, authority.logicalTrackingId.value, authority.serviceRunId.value,
						authority.sourcePolicyRevision, authority.captureConsentEpoch)
				} == true
			}
		} else {
			this
		}

	private fun CellCapturedFactClassification.captureFactOrNull():
		CellCapturedFact? = when (this) {
			is CellCapturedFactClassification.FreshChanged -> fact
			is CellCapturedFactClassification.FreshUnchanged -> fact
			else -> null
		}

	private suspend fun cellRecordingResult(
		key: SourceProofKey,
		result: CellWalAdapterResult,
	): SourceProofResult<RecordingProofState> = when (result) {
		is CellWalAdapterResult.Evaluated -> cellRecordingClassification(key, result.classification)
		is CellWalAdapterResult.Rejected -> if (result.reason == CellWalAdapterRejection.MISSING_EVENT) {
			missingRecordingEvidence(key)
		} else {
			result.reason.toRecordingResult()
		}
		is CellWalAdapterResult.Groups ->
			unavailable(SourceProofStage.RECORDING, SourceProofReason.IMMUTABLE_AUTHORITY_MISSING)
	}

	// Exhaustive Cell classification-to-proof dispatch; authentication stays in the recording helpers.
	@Suppress("CyclomaticComplexMethod")
	private suspend fun cellRecordingClassification(
		key: SourceProofKey,
		classification: CellCapturedFactClassification,
	): SourceProofResult<RecordingProofState> = when (classification) {
		is CellCapturedFactClassification.FreshChanged ->
			recordingCellFact(key, classification.fact)
		is CellCapturedFactClassification.FreshUnchanged ->
			recordingCellFact(key, classification.fact)
		is CellCapturedFactClassification.Replay ->
			recordingFromWal(key, SourceKind.CELL) { wal, revision ->
				recordingState(
					key,
					wal,
					revision,
					wal.providerCoverage(),
					SourceProofCompleteness.UNKNOWN,
				)
			}
		is CellCapturedFactClassification.Stale -> unavailable(
			SourceProofStage.RECORDING,
			SourceProofReason.STALE,
		)
		is CellCapturedFactClassification.Absent -> unavailable(
			SourceProofStage.RECORDING,
			SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
		)
		is CellCapturedFactClassification.Failed -> unavailable(
			SourceProofStage.RECORDING,
			SourceProofReason.PROVIDER_FAILURE,
		)
		CellCapturedFactClassification.PermissionLimited -> unavailable(
			SourceProofStage.RECORDING,
			SourceProofReason.PERMISSION_UNAVAILABLE,
		)
		CellCapturedFactClassification.OsLimited -> unavailable(
			SourceProofStage.RECORDING,
			SourceProofReason.OS_LIMITED,
		)
		is CellCapturedFactClassification.ClockUnverifiable,
		is CellCapturedFactClassification.SourceUnverifiable,
		-> unavailable(
			SourceProofStage.RECORDING,
			SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
		)
		is CellCapturedFactClassification.Rejected -> failed(
			SourceProofStage.RECORDING,
			SourceProofReason.MALFORMED_EVIDENCE,
		)
	}

	private fun radioGroupMatches(
		key: SourceProofKey,
		logicalTrackingId: String,
		serviceRunId: String,
		policyRevision: Long,
		consentEpoch: Long,
	): Boolean {
		val scope = key.scope as SourceProofScope.Run
		val scopeMatches = logicalTrackingId == scope.logicalTrackingId && serviceRunId == scope.serviceRunId
		val selectionMatches = policyRevision == key.sourcePolicyRevision && consentEpoch == key.captureConsentEpoch
		return scopeMatches && selectionMatches
	}

	private suspend fun recordingCellFact(
		key: SourceProofKey,
		fact: CellCapturedFact,
	): SourceProofResult<RecordingProofState> =
		recordingFromWal(key, SourceKind.CELL, cellFact = fact) { wal, revision ->
			recordingState(
				key,
				wal,
				revision,
				providerCoverage(
					fact.coverage.providerIntervalStartElapsedRealtimeNanos,
					fact.coverage.providerIntervalEndElapsedRealtimeNanos,
				),
				if (fact.coverage.childCompleteness.name == "COMPLETE") {
					SourceProofCompleteness.COMPLETE
				} else {
					SourceProofCompleteness.PARTIAL
				},
			)
		}

	private suspend fun missingCellGroupRecording(key: SourceProofKey): SourceProofResult<RecordingProofState> {
		val scope = key.scope as SourceProofScope.Run
		val retained = database.capturedRadioFactRetentionProofDao().eventCandidatesForRun(
			SourceKind.CELL.stableCode, key.evidenceIdentity.value,
			scope.logicalTrackingId, scope.serviceRunId, key.sourcePolicyRevision, key.captureConsentEpoch,
		)
		return if (retained.isNotEmpty()) {
			missingRecordingEvidence(key)
		} else
			{
			unavailable(SourceProofStage.RECORDING, SourceProofReason.IMMUTABLE_AUTHORITY_MISSING)
		}
	}

	private suspend fun materializedLocation(
		key: SourceProofKey,
		recording: RecordingProofState,
	): SourceProofResult<MaterializedProofState> = database.withTransaction {
		materializationDeletion(key, recording)?.let { return@withTransaction it }
		val wal = database.sourceEventWalDao().getByEventId(key.evidenceIdentity.value)
			?: return@withTransaction deletedOrMissing(key, recording, LOCATION_WRITER)
		val observation = database.locationObservationDao().getBySourceEventId(wal.eventId)
			?: return@withTransaction pendingAfterProjectionFailure(
				LOCATION_WRITER,
				wal.admissionOrdinal,
				SourceProofReason.MATERIALIZATION_PENDING,
				recording.lineage,
			)
		val decision = database.locationObservationDecisionDao().getBySourceEventId(wal.eventId)
			?: return@withTransaction pending(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.RECEIPT_MISSING,
				recording.lineage,
			)
		locationReceiptMaterialization(key, recording, wal, observation, decision)
	}

	private suspend fun locationReceiptMaterialization(
		key: SourceProofKey,
		recording: RecordingProofState,
		wal: SourceEventWalEntity,
		observation: LocationObservation,
		decision: LocationObservationDecision,
	): SourceProofResult<MaterializedProofState> {
		val expectation = decision.locationWriterExpectation()
			?: return unavailable(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
				recording.lineage,
			)
		val receipt = database.sourceProjectionStateDao().joinState(
			expectation.projectionId,
			expectation.projectionVersion,
			"canonical-receipt:${wal.eventId}",
		) ?: return pending(
			SourceProofStage.MATERIALIZED,
			SourceProofReason.RECEIPT_MISSING,
			recording.lineage,
		)
		val rows = LocationMaterializationRows(observation, decision, receipt,
			database.locationSampleDao().getBySourceEventIdBounded(wal.eventId, 2))
		return locationDecisionMaterialization(key, recording, wal, expectation, rows)
	}

	@Suppress("ReturnCount") // Keep ordered missing-reference and rejection exits together.
	private suspend fun locationDecisionMaterialization(
		key: SourceProofKey,
		recording: RecordingProofState,
		wal: SourceEventWalEntity,
		expectation: WriterExpectation,
		rows: LocationMaterializationRows,
	): SourceProofResult<MaterializedProofState> {
		val scope = key.scope as SourceProofScope.Run
		if (!rows.matchesLocationReceiptIdentity(wal, scope)) {
			return failed(SourceProofStage.MATERIALIZED, SourceProofReason.INTEGRITY_FAILURE, recording.lineage)
		}
		locationDecisionFailure(recording, wal, rows)?.let { return it }
		materializationRetention(key, recording) { floor ->
			wal.wallTimeMs?.let { wallTimeMs ->
				wal.wallTimeUncertaintyMs?.let { uncertaintyMs ->
					CanonicalRetentionBounds.location(wallTimeMs, uncertaintyMs)
				}
			}?.let { lowerBound -> lowerBound < floor }
		}?.let { return it }
		val authority = materializationAuthority(
			key = key,
			wal = wal,
			expectation = expectation,
			cursorRevision = null,
		)
		if (authority !is MaterializationAuthority.Ready) {
			return authority.result(recording.lineage)
		}
		return provenMaterialized(
			recording,
			authority,
			SourceFactIdentity(wal.eventId),
			rows.decision.decisionVersion.toLong(),
			recording.completeness,
		)
	}

	private fun LocationMaterializationRows.matchesLocationReceiptIdentity(
		wal: SourceEventWalEntity,
		scope: SourceProofScope.Run,
	): Boolean {
		val observationMatches = observation.sourceEventId == wal.eventId && decision.observationSourceEventId == wal.eventId
		val signalMatches = decision.sourceSignalId == ProtectedLocationCanonicalSignalIdentity.canonicalProduct(wal.eventId)
		val decisionMatches = signalMatches &&
			decision.decisionVersion == LocationObservationDecision.CURRENT_DECISION_VERSION &&
			decision.clockDomainId == wal.clockDomainId
		return observationMatches && decisionMatches && receipt.logicalTrackingId == scope.logicalTrackingId
	}

	private suspend fun locationDecisionFailure(
		recording: RecordingProofState,
		wal: SourceEventWalEntity,
		rows: LocationMaterializationRows,
	): SourceProofResult<MaterializedProofState>? = when (rows.decision.decision) {
		LocationObservationDecision.REJECTED -> rejectedLocationReceipt(recording, wal, rows)
		LocationObservationDecision.ACCEPTED -> acceptedLocationReceiptFailure(recording, wal, rows)
		else -> failed(SourceProofStage.MATERIALIZED, SourceProofReason.INTEGRITY_FAILURE, recording.lineage)
	}

	private suspend fun rejectedLocationReceipt(
		recording: RecordingProofState,
		wal: SourceEventWalEntity,
		rows: LocationMaterializationRows,
	): SourceProofResult<MaterializedProofState> {
		if (rows.decision.reason.isNullOrBlank() || rows.decision.acceptedSampleSourceSignalId != null) {
			return failed(SourceProofStage.MATERIALIZED, SourceProofReason.INTEGRITY_FAILURE, recording.lineage)
		}
		val sampleAllowed = rows.samples.isEmpty() ||
			(rows.samples.size == 1 && database.hasAcceptedAmbientSiblingReceipt(wal.eventId))
		return if (!sampleAllowed || !rows.authenticatesLocationReceipt(wal, null)) {
			failed(SourceProofStage.MATERIALIZED, SourceProofReason.INTEGRITY_FAILURE, recording.lineage)
		} else {
			canonicalLocationRejectionResult(rows.decision.reason, recording.lineage)
		}
	}

	private fun acceptedLocationReceiptFailure(
		recording: RecordingProofState,
		wal: SourceEventWalEntity,
		rows: LocationMaterializationRows,
	): SourceProofResult<MaterializedProofState>? {
		val sample = rows.samples.singleOrNull() ?: return if (rows.samples.isEmpty()) {
			pending(SourceProofStage.MATERIALIZED, SourceProofReason.RECEIPT_MISSING, recording.lineage)
		} else {
			failed(SourceProofStage.MATERIALIZED, SourceProofReason.INTEGRITY_FAILURE, recording.lineage)
		}
		val signalId = ProtectedLocationCanonicalSignalIdentity.canonicalProduct(wal.eventId)
		val decisionMatches = rows.decision.reason == null && rows.decision.acceptedSampleSourceSignalId == signalId
		val sampleMatches = sample.sourceSignalId == signalId && sample.sourceEventId == wal.eventId &&
			sample.elapsedRealtimeNanos == (recording.coverage as? SourceProofCoverage.ProviderPoint)?.observedAtNanos
		return if (decisionMatches && sampleMatches && rows.authenticatesLocationReceipt(wal, sample)) {
			null
		} else {
			failed(SourceProofStage.MATERIALIZED, SourceProofReason.INTEGRITY_FAILURE, recording.lineage)
		}
	}

	private fun LocationMaterializationRows.authenticatesLocationReceipt(
		wal: SourceEventWalEntity,
		sample: LocationSample?,
	): Boolean = receipt.authenticatesProtectedLocationCanonicalReceipt(
		wal.eventId, wal.admissionOrdinal, wal.integrityIdentity, observation, decision, sample,
	)

	private suspend fun materializedActivity(
		key: SourceProofKey,
		recording: RecordingProofState,
	): SourceProofResult<MaterializedProofState> = database.withTransaction {
		materializationDeletion(key, recording)?.let { return@withTransaction it }
		val wal = database.sourceEventWalDao().boundedPayloadByEventId(
			key.evidenceIdentity.value,
			MAX_ACTIVITY_WAL_PAYLOAD_BYTES,
		)
			?: return@withTransaction deletedOrMissing(key, recording, ACTIVITY_WRITER)
		val currentHeads = database.activityCapturedFactDao().currentRevisionsForWriterAdmission(
			writerProjectionId = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID,
			writerProjectionVersion = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION,
			sourceAdmissionOrdinal = recording.lineage.sourceAdmissionOrdinal,
			limit = 2,
		)
		if (currentHeads.isEmpty()) {
			return@withTransaction pendingAfterProjectionFailure(
				ACTIVITY_WRITER,
				wal.admissionOrdinal,
				SourceProofReason.MATERIALIZATION_PENDING,
				recording.lineage,
			)
		}
		if (currentHeads.size != 1) {
			return@withTransaction failed(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.FACT_IDENTITY_MISMATCH,
				recording.lineage,
			)
		}
		val fact = currentHeads.single()
		val lineage = database.activityCapturedFactDao().revisionsForWindow(
			writerProjectionId = fact.writerProjectionId,
			writerProjectionVersion = fact.writerProjectionVersion,
			logicalWindowId = fact.logicalWindowId,
			limit = MAX_ACTIVITY_REVISIONS + 1,
		)
		if (lineage.size > MAX_ACTIVITY_REVISIONS) {
			return@withTransaction unavailable(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.PARTIAL_COMPLETENESS,
				recording.lineage,
			)
		}
		if (!ActivityCapturedFactIntegrity.hasCompleteRevisionLineage(lineage) ||
			lineage.lastOrNull() != fact
		) {
			return@withTransaction failed(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.INTEGRITY_FAILURE,
				recording.lineage,
			)
		}
		materializedActivityLineage(key, recording, wal, fact, lineage)
	}

	private suspend fun materializedActivityLineage(
		key: SourceProofKey,
		recording: RecordingProofState,
		wal: SourceEventWalEntity,
		fact: ActivityCapturedWindowRevisionEntity,
		lineage: List<ActivityCapturedWindowRevisionEntity>,
	): SourceProofResult<MaterializedProofState> {
		val cursor = database.activityCapturedFactDao().cursor(
			fact.writerProjectionId,
			fact.writerProjectionVersion,
			fact.logicalWindowId,
		) ?: return pendingAfterProjectionFailure(
			WriterExpectation(
				SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
				fact.writerOwnerGeneration,
				fact.writerProjectionId,
				fact.writerProjectionVersion,
				fact.writerBindingGeneration,
			),
			wal.admissionOrdinal,
			SourceProofReason.CURSOR_BEHIND,
			recording.lineage,
		)
		fact.authorityMismatch(key)?.let { reason ->
			return failed(
				SourceProofStage.MATERIALIZED,
				reason,
				recording.lineage,
			)
		}
		return withActivityRevisionChildren(recording, lineage) { rows ->
			materializedActivityReceipt(key, recording, wal, fact, cursor, rows)
		}
	}

	private suspend fun withActivityRevisionChildren(
		recording: RecordingProofState,
		lineage: List<ActivityCapturedWindowRevisionEntity>,
		block: suspend (ActivityMaterializationRows) -> SourceProofResult<MaterializedProofState>,
	): SourceProofResult<MaterializedProofState> {
		val evidenceByRevision = linkedMapOf<Long, List<
			ActivityCapturedEvidenceEntity>>()
		val fragmentsByRevision = linkedMapOf<Long, List<
			ActivityCapturedFragmentEntity>>()
		for (revision in lineage) {
			val fragments = database.activityCapturedFactDao().maintenanceFragments(
				revision.writerProjectionId,
				revision.writerProjectionVersion,
				revision.logicalWindowId,
				revision.semanticRevision,
				MAX_ACTIVITY_CHILD_ROWS + 1,
			)
			val evidence = database.activityCapturedFactDao().maintenanceEvidence(
				revision.writerProjectionId,
				revision.writerProjectionVersion,
				revision.logicalWindowId,
				revision.semanticRevision,
				MAX_ACTIVITY_CHILD_ROWS + 1,
			)
			if (fragments.size > MAX_ACTIVITY_CHILD_ROWS ||
				evidence.size > MAX_ACTIVITY_CHILD_ROWS
			) {
				return unavailable(
					SourceProofStage.MATERIALIZED,
					SourceProofReason.PARTIAL_COMPLETENESS,
					recording.lineage,
				)
			}
			if (!ActivityCapturedFactIntegrity.verify(revision, fragments, evidence)) {
				return failed(
					SourceProofStage.MATERIALIZED,
					SourceProofReason.INTEGRITY_FAILURE,
					recording.lineage,
				)
			}
			fragmentsByRevision[revision.semanticRevision] = fragments
			evidenceByRevision[revision.semanticRevision] = evidence
		}
		return block(ActivityMaterializationRows(lineage, fragmentsByRevision, evidenceByRevision))
	}

	@Suppress("ReturnCount") // Keep ordered missing-reference and rejection exits together.
	private suspend fun materializedActivityReceipt(
		key: SourceProofKey,
		recording: RecordingProofState,
		wal: SourceEventWalEntity,
		fact: ActivityCapturedWindowRevisionEntity,
		cursor: ActivityCapturedWindowCursorEntity,
		rows: ActivityMaterializationRows,
	): SourceProofResult<MaterializedProofState> {
		if (!cursor.matchesActivityFact(fact)) {
			return failed(SourceProofStage.MATERIALIZED, SourceProofReason.INTEGRITY_FAILURE, recording.lineage)
		}
		val lineageEvidence = rows.lineage.flatMap { revision ->
			rows.evidenceByRevision.getValue(revision.semanticRevision).map { evidence ->
				revision to evidence
			}
		}
		val selectedEvidenceRows = lineageEvidence.filter { (_, evidence) ->
			evidence.sourceEventId == wal.eventId
		}
		val selectedEvidence = selectedEvidenceRows.firstOrNull()?.second
		if (selectedEvidence == null || selectedEvidenceRows.any { (_, evidence) ->
				!ActivityCapturedFactIntegrity.hasSameRecordedObservation(selectedEvidence, evidence)
			}) {
			return failed(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.INTEGRITY_FAILURE,
				recording.lineage,
			)
		}
		val walByEventId = loadActivityWalCarriers(lineageEvidence.map { it.second })
		if (!activityWalCarriersAuthenticate(lineageEvidence, walByEventId)) {
			return failed(SourceProofStage.MATERIALIZED, SourceProofReason.INTEGRITY_FAILURE, recording.lineage)
		}
		if (!authenticatesActivityProvider(fact, lineageEvidence, walByEventId)) {
			return failed(SourceProofStage.MATERIALIZED, SourceProofReason.INTEGRITY_FAILURE, recording.lineage)
		}
		return provenActivityMaterialization(key, recording, wal, fact, cursor,
			ActivityCarrierProof(rows.fragmentsByRevision.values.flatten(), selectedEvidence))
	}

	private fun ActivityCapturedWindowCursorEntity.matchesActivityFact(
		fact: ActivityCapturedWindowRevisionEntity,
	): Boolean {
		val scopeMatches = logicalTrackingId == fact.logicalTrackingId && serviceRunId == fact.serviceRunId &&
			sessionSegmentId == fact.sessionSegmentId
		val revisionMatches = writerOwnerGeneration == fact.writerOwnerGeneration &&
			latestSemanticRevision == fact.semanticRevision && latestMutationId == fact.mutationId
		val effectMatches = latestEffectChecksum == fact.effectChecksum && collectedDataEpoch == fact.collectedDataEpoch &&
			updatedAtMs == fact.appliedAtMs
		return scopeMatches && revisionMatches && effectMatches
	}

	private fun activityWalCarriersAuthenticate(
		lineageEvidence: List<Pair<ActivityCapturedWindowRevisionEntity,
			ActivityCapturedEvidenceEntity>>,
		walByEventId: Map<String, SourceEventWalEntity>,
	): Boolean = walByEventId.size == lineageEvidence.map { it.second.sourceEventId }.distinct().size &&
		lineageEvidence.all { (revision, evidence) ->
			walByEventId[evidence.sourceEventId]?.let { carrier ->
				ActivityWalCarrierAuthenticator.authenticates(revision, evidence, carrier)
			} == true
		}

	private suspend fun authenticatesActivityProvider(
		fact: ActivityCapturedWindowRevisionEntity,
		lineageEvidence: List<Pair<ActivityCapturedWindowRevisionEntity,
			ActivityCapturedEvidenceEntity>>,
		walByEventId: Map<String, SourceEventWalEntity>,
	): Boolean {
		val actualObservations = lineageEvidence.mapNotNull { (_, evidence) ->
			walByEventId[evidence.sourceEventId]?.wallTimeMs?.let { wallTimeMs ->
				ProviderRegistrationActualObservation(
					elapsedRealtimeNanos = evidence.providerElapsedRealtimeNanos,
					wallTimeMs = wallTimeMs,
					coverageEndExclusiveNanos =
						evidence.coverageEndExclusiveElapsedRealtimeNanos,
				)
			}
		}
		val providerAuthentication = database.authenticateHistoricalProviderFactWindow(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
			registrationGeneration = fact.registrationGeneration,
			sourceInstanceId = fact.sourceInstanceId,
			clockDomainId = fact.clockDomainId,
			physicalConfigurationFingerprint = fact.physicalConfigurationFingerprint,
			collectedDataEpoch = fact.collectedDataEpoch,
			expectedProviderResidency =
				ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
			factWindow = ProviderRegistrationFactWindow(
				startInclusiveNanos = fact.windowStartElapsedRealtimeNanos,
				endExclusiveNanos = fact.windowEndElapsedRealtimeNanos,
				actualObservations = actualObservations,
			),
			currentClockDomainId = bootClockDomainProvider.current(),
		) as? ProviderRegistrationIntervalAuthentication.Authenticated
			?: return false
		return providerAuthentication.interval.startInclusiveNanos == fact.providerAcceptanceStartNanos &&
			providerAuthentication.interval.endExclusiveNanos == fact.providerAcceptanceEndNanos
	}

	private suspend fun provenActivityMaterialization(
		key: SourceProofKey,
		recording: RecordingProofState,
		wal: SourceEventWalEntity,
		fact: ActivityCapturedWindowRevisionEntity,
		cursor: ActivityCapturedWindowCursorEntity,
		proof: ActivityCarrierProof,
	): SourceProofResult<MaterializedProofState> {
		materializationRetention(key, recording) { floor ->
			CanonicalRetentionBounds.crossesFloor(
				CanonicalRetentionBounds.activityLineage(
					proof.fragments,
				),
				floor,
			)
		}?.let { return it }
		val authority = materializationAuthority(
			key,
			wal,
			WriterExpectation(
				SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
				fact.writerOwnerGeneration,
				fact.writerProjectionId,
				fact.writerProjectionVersion,
				fact.writerBindingGeneration,
			),
			cursor.cursorRevision,
		)
		if (authority !is MaterializationAuthority.Ready) {
			return authority.result(recording.lineage)
		}
		return provenMaterialized(
			recording,
			authority,
			SourceFactIdentity(fact.logicalWindowId),
			fact.semanticRevision,
			fact.coverage.toCompleteness(),
			proof.selectedEvidence.let { selected ->
				providerCoverage(
					selected.providerElapsedRealtimeNanos,
					selected.coverageEndExclusiveElapsedRealtimeNanos
						?: selected.providerElapsedRealtimeNanos,
				)
			},
		)
	}

	private suspend fun materializedSteps(
		key: SourceProofKey,
		recording: RecordingProofState,
	): SourceProofResult<MaterializedProofState> = database.withTransaction {
		materializationDeletion(key, recording)?.let { return@withTransaction it }
		val wal = database.sourceEventWalDao().boundedPayloadByEventId(
			key.evidenceIdentity.value,
			MAX_STEPS_WAL_PAYLOAD_BYTES,
		)
			?: return@withTransaction deletedOrMissing(key, recording, STEPS_WRITER)
		val fact = database.stepFactRevisionDao().writerAdmission(
			SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
			wal.admissionOrdinal,
		) ?: return@withTransaction pendingAfterProjectionFailure(
			STEPS_WRITER,
			wal.admissionOrdinal,
			SourceProofReason.MATERIALIZATION_PENDING,
			recording.lineage,
		)
		materializedStepsFact(key, recording, wal, fact)
	}

	private suspend fun materializedStepsFact(
		key: SourceProofKey,
		recording: RecordingProofState,
		wal: SourceEventWalEntity,
		fact: StepFactRevisionEntity,
	): SourceProofResult<MaterializedProofState> {
		fact.authorityMismatch(key)?.let { reason ->
			return failed(
				SourceProofStage.MATERIALIZED,
				reason,
				recording.lineage,
			)
		}
		if (!fact.hasPositiveCoveredSteps(wal)) {
			return failed(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.INTEGRITY_FAILURE,
				recording.lineage,
			)
		}
		stepsCarrierFailure(recording, wal, fact)?.let { return it }
		return materializedStepsCurrent(key, recording, wal, fact)
	}

	private fun StepFactRevisionEntity.hasPositiveCoveredSteps(wal: SourceEventWalEntity): Boolean {
		val identityMatches = StepFactRevisionIntegrity.hasValidCanonicalLiveWalFact(this) && sourceEventId == wal.eventId
		val coveredCount = coverageKind == StepFactRevisionEntity.COVERAGE_COVERED && effectiveStepCount != null &&
			effectiveStepCount?.let { it <= 0L } == false
		return identityMatches && coveredCount
	}

	private suspend fun stepsCarrierFailure(
		recording: RecordingProofState,
		wal: SourceEventWalEntity,
		fact: StepFactRevisionEntity,
	): SourceProofResult<MaterializedProofState>? {
		val ownerKeys = listOf(wal.sourceLifecycleStepsWalOwnerKey(), fact.sourceLifecycleStepsFactOwnerKey())
		val owners = when (val read = StepsCountDomainStore(database).readOwners(ownerKeys)) {
			is StepsCountDomainOwnerRead.Ready -> read.owners
			StepsCountDomainOwnerRead.SchemaUnavailable -> return unavailable(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
				recording.lineage,
			)
			StepsCountDomainOwnerRead.Overflow,
			StepsCountDomainOwnerRead.Unverifiable,
			-> return failed(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.INTEGRITY_FAILURE,
				recording.lineage,
			)
		}
		val carrier = StepsWalCarrierAuthenticator.authenticate(
			fact,
			wal,
			owners[ownerKeys[0]],
			owners[ownerKeys[1]],
		) as? StepsWalCarrierAuthentication.Authenticated
		if (carrier == null ||
			!carrier.carrier.countDomainProven ||
			!carrier.carrier.isPositiveCoveredWindow
		) {
			return failed(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.INTEGRITY_FAILURE,
				recording.lineage,
			)
		}
		return null
	}

	private suspend fun materializedStepsCurrent(
		key: SourceProofKey,
		recording: RecordingProofState,
		wal: SourceEventWalEntity,
		fact: StepFactRevisionEntity,
	): SourceProofResult<MaterializedProofState> {
		val binding = database.sourceSessionDao().manifestSources(
			requireNotNull(fact.logicalTrackingId),
			requireNotNull(fact.manifestRevision),
		).singleOrNull { source ->
			source.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
				source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE
		}
		if (binding == null || !binding.matchesFactWriter(fact)) {
			return unavailable(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
				recording.lineage,
			)
		}
		materializationRetention(key, recording) { floor ->
			CanonicalRetentionBounds.stepsWindowCrossesFloor(
				fact.intervalEndTimeMs,
				floor,
			)
		}?.let { return it }
		val authority = materializationAuthority(
			key,
			wal,
			WriterExpectation(
				SourceDestinationOwnerEntity.SOURCE_STEPS,
				requireNotNull(binding.writerOwnerGeneration),
				fact.writerProjectionId,
				fact.writerProjectionVersion,
				fact.writerBindingGeneration,
			),
			null,
		)
		if (authority !is MaterializationAuthority.Ready) {
			return authority.result(recording.lineage)
		}
		return provenMaterialized(
			recording,
			authority,
			SourceFactIdentity(fact.logicalFactId),
			fact.semanticRevision,
			SourceProofCompleteness.COMPLETE,
			providerCoverage(
				requireNotNull(fact.intervalStartElapsedRealtimeNanos),
				requireNotNull(fact.intervalEndElapsedRealtimeNanos),
			),
		)
	}

	private suspend fun materializedPressure(
		key: SourceProofKey,
		recording: RecordingProofState,
	): SourceProofResult<MaterializedProofState> = database.withTransaction {
		materializationDeletion(key, recording)?.let { return@withTransaction it }
		val wal = database.sourceEventWalDao().boundedPayloadByEventId(
			key.evidenceIdentity.value,
			MAX_PRESSURE_WAL_PAYLOAD_BYTES,
		)
			?: return@withTransaction deletedOrMissing(key, recording, PRESSURE_WRITER)
		if (wal.admissionOrdinal != recording.lineage.sourceAdmissionOrdinal) {
			return@withTransaction failed(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.FACT_IDENTITY_MISMATCH,
				recording.lineage,
			)
		}
		val admissionFacts = database.pressureFactRevisionDao().revisionsForWriterAdmission(
			writerProjectionId = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
			writerProjectionVersion = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
			sourceAdmissionOrdinal = recording.lineage.sourceAdmissionOrdinal,
			limit = 2,
		)
		if (admissionFacts.isEmpty()) {
			return@withTransaction pendingAfterProjectionFailure(
				PRESSURE_WRITER,
				wal.admissionOrdinal,
				SourceProofReason.MATERIALIZATION_PENDING,
				recording.lineage,
			)
		}
		if (admissionFacts.size != 1) {
			return@withTransaction failed(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.FACT_IDENTITY_MISMATCH,
				recording.lineage,
			)
		}
		materializedPressureLineage(key, recording, wal, admissionFacts.single())
	}

	private suspend fun materializedPressureLineage(
		key: SourceProofKey,
		recording: RecordingProofState,
		wal: SourceEventWalEntity,
		admissionFact: PressureFactRevisionEntity,
	): SourceProofResult<MaterializedProofState> {
		val lineage = database.pressureFactRevisionDao().revisionsForFact(
			writerProjectionId = admissionFact.writerProjectionId,
			writerProjectionVersion = admissionFact.writerProjectionVersion,
			logicalFactId = admissionFact.logicalFactId,
			limit = MAX_PRESSURE_PROOF_REVISIONS + 1,
		)
		if (lineage.size > MAX_PRESSURE_PROOF_REVISIONS) {
			return failed(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.INTEGRITY_FAILURE,
				recording.lineage,
			)
		}
		val lineageWal = loadPressureWalCarriers(lineage)
		val proofSnapshot = database.loadPressureFactProofSnapshot(
			facts = lineage,
			walByEventId = lineageWal,
			currentClockDomainId = bootClockDomainProvider.current(),
		)
		val proof = PressureFactProofAuthenticator.authenticateLineage(
			revisions = lineage,
			snapshot = proofSnapshot,
			expectation = key.toPressureFactProofExpectation(
				logicalFactId = admissionFact.logicalFactId,
				semanticRevision = admissionFact.semanticRevision,
				sourceAdmissionOrdinal = recording.lineage.sourceAdmissionOrdinal,
			),
		)
		return when (proof) {
			is PressureFactLineageProof.Authenticated ->
				materializedPressureCurrent(key, recording, wal, admissionFact, lineageWal, proof)
			is PressureFactLineageProof.Rejected -> pressureLineageFailure(proof.reason, recording.lineage)
		}
	}

	private fun pressureLineageFailure(
		reason: PressureFactProofFailure,
		lineage: SourceProofLineage,
	): SourceProofResult<MaterializedProofState> = when (reason) {
		PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING ->
			unavailable(SourceProofStage.MATERIALIZED, SourceProofReason.IMMUTABLE_AUTHORITY_MISSING, lineage)
		PressureFactProofFailure.CURRENT_FACT_MISMATCH ->
			failed(SourceProofStage.MATERIALIZED, SourceProofReason.FACT_IDENTITY_MISMATCH, lineage)
		else -> failed(SourceProofStage.MATERIALIZED, SourceProofReason.INTEGRITY_FAILURE, lineage)
	}

	private suspend fun materializedPressureCurrent(
		key: SourceProofKey,
		recording: RecordingProofState,
		wal: SourceEventWalEntity,
		admissionFact: PressureFactRevisionEntity,
		lineageWal: Map<String, SourceEventWalEntity>,
		authenticated: PressureFactLineageProof.Authenticated,
	): SourceProofResult<MaterializedProofState> {
		val fact = authenticated.current
		val selectedFact = authenticated.selected
		if (selectedFact != admissionFact) {
			return failed(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.FACT_IDENTITY_MISMATCH,
				recording.lineage,
			)
		}
		val currentWal = lineageWal[fact.sourceEventId]
			?: return unavailable(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
				recording.lineage,
			)
		val binding = database.sourceSessionDao().manifestSources(
			fact.logicalTrackingId,
			fact.manifestRevision,
		).singleOrNull { source ->
			source.sourceKind == SourceDestinationOwnerEntity.SOURCE_PRESSURE &&
				source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE
		}
		fact.authorityMismatch(key)?.let { reason ->
			return failed(
				SourceProofStage.MATERIALIZED,
				reason,
				recording.lineage,
			)
		}
		return if (binding == null || !binding.matchesFactWriter(fact)) {
			failed(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.INTEGRITY_FAILURE,
				recording.lineage,
			)
		} else {
			provenPressureMaterialization(key, recording, wal, currentWal, authenticated, binding)
		}
	}

	private suspend fun provenPressureMaterialization(
		key: SourceProofKey,
		recording: RecordingProofState,
		wal: SourceEventWalEntity,
		currentWal: SourceEventWalEntity,
		authenticated: PressureFactLineageProof.Authenticated,
		binding: SessionManifestSourceEntity,
	): SourceProofResult<MaterializedProofState> {
		val fact = authenticated.current
		val selectedFact = authenticated.selected
		materializationRetention(key, recording) { floor ->
			CanonicalRetentionBounds.pressureLineage(authenticated.revisions)
				?.let { lowerBound -> lowerBound < floor }
		}?.let { return it }
		val authority = materializationAuthority(
			key,
			currentWal,
			WriterExpectation(
				SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				requireNotNull(binding.writerOwnerGeneration),
				fact.writerProjectionId,
				fact.writerProjectionVersion,
				fact.writerBindingGeneration,
			),
			null,
			additionalAdmissionOrdinals = listOf(wal.admissionOrdinal),
		)
		if (authority !is MaterializationAuthority.Ready) {
			return authority.result(recording.lineage)
		}
		return provenMaterialized(
			recording,
			authority,
			SourceFactIdentity(fact.logicalFactId),
			fact.semanticRevision,
			authenticated.selectedPressureProofCompleteness(),
			authenticated.selectedPressureProofCoverage(),
			selectedEvidenceLineage = SourceProofSelectedEvidenceLineage(
				semanticRevision = selectedFact.semanticRevision,
				supersededByCurrentSemanticRevision = if (
					selectedFact.semanticRevision < fact.semanticRevision
				) {
					fact.semanticRevision
				} else {
					null
				},
			),
		)
	}

	private suspend fun materializedWifi(
		key: SourceProofKey,
		recording: RecordingProofState,
	): SourceProofResult<MaterializedProofState> = database.withTransaction {
		materializationDeletion(key, recording)?.let { return@withTransaction it }
		val wal = database.sourceEventWalDao().boundedPayloadByEventId(
			key.evidenceIdentity.value,
			MAX_WIFI_WAL_PAYLOAD_BYTES,
		)
			?: return@withTransaction deletedOrMissing(key, recording, WIFI_WRITER)
		val resolution = loadWifiMaterializationRevision(key, recording)
		if (resolution is CurrentRevisionResolution.Missing) {
			return@withTransaction pendingAfterProjectionFailure(
				WIFI_WRITER,
				wal.admissionOrdinal,
				SourceProofReason.MATERIALIZATION_PENDING,
				recording.lineage,
			)
		}
		if (resolution !is CurrentRevisionResolution.Ready) {
			return@withTransaction failed(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.INTEGRITY_FAILURE,
				recording.lineage,
			)
		}
		val fact = resolution.current
		val walAuthentication = authenticateWifiFactCarrier(fact, wal)
			?: return@withTransaction failed(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.INTEGRITY_FAILURE,
				recording.lineage,
			)
		withWifiAggregateMaterialization(recording, fact, walAuthentication) { aggregate ->
			materializedWifiCurrent(key, recording, wal, fact, aggregate)
		}
	}

	private suspend fun loadWifiMaterializationRevision(
		key: SourceProofKey,
		recording: RecordingProofState,
	): CurrentRevisionResolution<WifiCapturedFactRevisionEntity> {
		val admissionRevisions = database.wifiCapturedFactDao().revisionsForWriterAdmission(
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION,
			recording.lineage.sourceAdmissionOrdinal,
			MAX_RADIO_REVISION_CHAIN + 1,
			logicalTrackingId = (key.scope as SourceProofScope.Run).logicalTrackingId,
			serviceRunId = (key.scope as SourceProofScope.Run).serviceRunId,
			sourcePolicyRevision = key.sourcePolicyRevision, captureConsentEpoch = key.captureConsentEpoch,
		)
		val current = database.wifiCapturedFactDao().currentRevisionsForWriterAdmission(
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION,
			recording.lineage.sourceAdmissionOrdinal,
			2,
			logicalTrackingId = (key.scope as SourceProofScope.Run).logicalTrackingId,
			serviceRunId = (key.scope as SourceProofScope.Run).serviceRunId,
			sourcePolicyRevision = key.sourcePolicyRevision, captureConsentEpoch = key.captureConsentEpoch,
		)
		val retained = current.singleOrNull()?.let { fact ->
			database.wifiCapturedFactDao().revisionsForFact(
				fact.writerProjectionId,
				fact.writerProjectionVersion,
				fact.logicalFactId,
				MAX_RADIO_REVISION_CHAIN + 1,
			)
		}.orEmpty()
		return resolveCurrentRevision(
			admissionRevisions = admissionRevisions,
			currentRevisions = current,
			retainedLineage = retained,
			maximumRevisionCount = MAX_RADIO_REVISION_CHAIN,
			logicalFactId = WifiCapturedFactRevisionEntity::logicalFactId,
			semanticRevision = WifiCapturedFactRevisionEntity::semanticRevision,
			lineageAuthenticates = WifiCapturedFactRevisionIntegrity::hasValidRetainedLineage,
		)
	}

	private suspend fun withWifiAggregateMaterialization(
		recording: RecordingProofState,
		fact: WifiCapturedFactRevisionEntity,
		dependentCarrier: WifiWalCarrierAuthentication.Authenticated,
		block: suspend (WifiAggregateProof) -> SourceProofResult<MaterializedProofState>,
	): SourceProofResult<MaterializedProofState> = when (fact.factKind) {
		WifiCapturedFactRevisionEntity.FACT_KIND_AGGREGATE ->
			block(WifiAggregateProof(fact, null, dependentCarrier, dependentCarrier))
		WifiCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY ->
			withWifiAggregateOwner(recording, fact, dependentCarrier, block)
		else -> failed(SourceProofStage.MATERIALIZED, SourceProofReason.INTEGRITY_FAILURE, recording.lineage)
	}

	private suspend fun withWifiAggregateOwner(
		recording: RecordingProofState,
		fact: WifiCapturedFactRevisionEntity,
		dependentCarrier: WifiWalCarrierAuthentication.Authenticated,
		block: suspend (WifiAggregateProof) -> SourceProofResult<MaterializedProofState>,
	): SourceProofResult<MaterializedProofState> {
		val ownerId = fact.aggregateOwnerLogicalFactId ?: return failed(
			SourceProofStage.MATERIALIZED, SourceProofReason.INTEGRITY_FAILURE, recording.lineage)
		val ownerRevision = fact.aggregateOwnerSemanticRevision ?: return failed(
			SourceProofStage.MATERIALIZED, SourceProofReason.INTEGRITY_FAILURE, recording.lineage)
		val owner = database.wifiCapturedFactDao().revision(
			fact.writerProjectionId, fact.writerProjectionVersion, ownerId, ownerRevision,
		) ?: return failed(SourceProofStage.MATERIALIZED, SourceProofReason.INTEGRITY_FAILURE, recording.lineage)
		return withWifiAggregateCarrier(recording, fact, owner, dependentCarrier, block)
	}

	private suspend fun withWifiAggregateCarrier(
		recording: RecordingProofState,
		fact: WifiCapturedFactRevisionEntity,
		owner: WifiCapturedFactRevisionEntity,
		dependentCarrier: WifiWalCarrierAuthentication.Authenticated,
		block: suspend (WifiAggregateProof) -> SourceProofResult<MaterializedProofState>,
	): SourceProofResult<MaterializedProofState> {
		val ownerCursor = database.wifiCapturedFactDao().cursor(
			fact.writerProjectionId, fact.writerProjectionVersion, requireNotNull(fact.aggregateOwnerLogicalFactId),
		) ?: return unavailable(
			SourceProofStage.MATERIALIZED, SourceProofReason.IMMUTABLE_AUTHORITY_MISSING, recording.lineage)
		val ownerWal = database.sourceEventWalDao().getByEventId(owner.sourceEventId) ?: return unavailable(
			SourceProofStage.MATERIALIZED, SourceProofReason.IMMUTABLE_AUTHORITY_MISSING, recording.lineage)
		val ownerCarrier = authenticateWifiFactCarrier(owner, ownerWal) ?: return failed(
			SourceProofStage.MATERIALIZED, SourceProofReason.INTEGRITY_FAILURE, recording.lineage)
		return block(WifiAggregateProof(owner, ownerCursor, dependentCarrier, ownerCarrier))
	}

	private fun WifiCapturedFactRevisionEntity.materializationWriter(): WriterExpectation = WriterExpectation(
		SourceDestinationOwnerEntity.SOURCE_WIFI, writerOwnerGeneration, writerProjectionId,
		writerProjectionVersion, writerBindingGeneration,
	)

	private suspend fun materializedWifiCurrent(
		key: SourceProofKey,
		recording: RecordingProofState,
		wal: SourceEventWalEntity,
		fact: WifiCapturedFactRevisionEntity,
		proof: WifiAggregateProof,
	): SourceProofResult<MaterializedProofState> {
		val aggregate = proof.aggregate
		val aggregateOwnerCursor = proof.ownerCursor
		val walAuthentication = proof.dependentCarrier
		val cursor = database.wifiCapturedFactDao().cursor(
			fact.writerProjectionId,
			fact.writerProjectionVersion,
			fact.logicalFactId,
		) ?: return pendingAfterProjectionFailure(
			fact.materializationWriter(),
			wal.admissionOrdinal,
			SourceProofReason.CURSOR_BEHIND,
			recording.lineage,
		)
		fact.authorityMismatch(key)?.let { reason ->
			return failed(
				SourceProofStage.MATERIALIZED,
				reason,
				recording.lineage,
			)
		}
		if (fact.factKind == WifiCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY &&
			aggregateOwnerCursor?.let { ownerCursor ->
				WifiCapturedFactRevisionIntegrity.coverageOnlyOwnerCursorAuthenticates(
					fact,
					aggregate,
					ownerCursor,
				)
			} != true
		) {
			return unavailable(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.STALE,
				recording.lineage,
			)
		}
		return if (!WifiCapturedFactRevisionIntegrity.cursorAuthenticatesCurrent(cursor, fact) ||
			!WifiCapturedFactRevisionIntegrity.hasValidEffectChecksum(aggregate) ||
			!WifiWalCarrierAuthenticator.authenticatesAggregate(
				aggregate,
				walAuthentication.carrier,
				fact,
			) ||
			!WifiWalCarrierAuthenticator.authenticatesAggregate(aggregate, proof.aggregateCarrier.carrier)
		) {
			failed(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.INTEGRITY_FAILURE,
				recording.lineage,
			)
		} else {
			provenWifiMaterialization(key, recording, wal, fact, cursor, proof)
		}
	}

	private suspend fun provenWifiMaterialization(
		key: SourceProofKey,
		recording: RecordingProofState,
		wal: SourceEventWalEntity,
		fact: WifiCapturedFactRevisionEntity,
		cursor: WifiCapturedFactCursorEntity,
		proof: WifiAggregateProof,
	): SourceProofResult<MaterializedProofState> {
		val aggregate = proof.aggregate
		val retentionLowerBounds = listOf(fact, aggregate).map { candidate ->
			CanonicalRetentionBounds.wifiCoverage(
				coverageEndWallTimeMs = candidate.observedWallTimeMs,
				wallTimeUncertaintyMs = candidate.wallTimeUncertaintyMs,
				coverageStartElapsedRealtimeNanos = candidate.coverageIntervalStartNanos,
				coverageEndElapsedRealtimeNanos = candidate.coverageIntervalEndNanos,
			)
		}
		materializationRetention(key, recording) { floor ->
			retentionLowerBounds.takeIf { bounds -> bounds.all { it != null } }
				?.any { lowerBound -> requireNotNull(lowerBound) < floor }
		}?.let { return it }
		val authority = materializationAuthority(
			key,
			wal,
			fact.materializationWriter(),
			cursor.cursorRevision,
		)
		if (authority !is MaterializationAuthority.Ready) {
			return authority.result(recording.lineage)
		}
		return provenMaterialized(
			recording,
			authority,
			SourceFactIdentity(fact.logicalFactId),
			fact.semanticRevision,
			fact.coverageCompleteness.toCompleteness(),
			providerCoverage(
				fact.coverageIntervalStartNanos,
				fact.coverageIntervalEndNanos,
			),
		)
	}

	private suspend fun materializedCell(
		key: SourceProofKey,
		recording: RecordingProofState,
	): SourceProofResult<MaterializedProofState> = database.withTransaction {
		materializationDeletion(key, recording)?.let { return@withTransaction it }
		val wal = database.sourceEventWalDao().getByEventId(key.evidenceIdentity.value)
			?: return@withTransaction deletedOrMissing(key, recording, CELL_WRITER)
		val resolution = loadCellMaterializationRevision(key, recording)
		if (resolution is CurrentRevisionResolution.Missing) {
			return@withTransaction pendingAfterProjectionFailure(
				CELL_WRITER,
				wal.admissionOrdinal,
				SourceProofReason.MATERIALIZATION_PENDING,
				recording.lineage,
			)
		}
		if (resolution !is CurrentRevisionResolution.Ready) {
			return@withTransaction failed(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.INTEGRITY_FAILURE,
				recording.lineage,
			)
		}
		val fact = resolution.current
		val walAuthentication = authenticateCellFactCarrier(fact, wal)
			?: return@withTransaction failed(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.INTEGRITY_FAILURE,
				recording.lineage,
			)
		withCellAggregateMaterialization(key, recording, fact, walAuthentication) { aggregate ->
			materializedCellCurrent(key, recording, wal, fact, aggregate)
		}
	}

	private suspend fun loadCellMaterializationRevision(
		key: SourceProofKey,
		recording: RecordingProofState,
	): CurrentRevisionResolution<CellCapturedFactRevisionEntity> {
		val scope = key.scope as SourceProofScope.Run
		val admissionRevisions = database.cellCapturedFactDao().revisionsForWriterAdmission(
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
			recording.lineage.sourceAdmissionOrdinal,
			MAX_RADIO_REVISION_CHAIN + 1,
			scope.logicalTrackingId, scope.serviceRunId,
			key.sourcePolicyRevision, key.captureConsentEpoch,
		)
		val current = database.cellCapturedFactDao().currentRevisionsForWriterAdmission(
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
			recording.lineage.sourceAdmissionOrdinal,
			2,
			scope.logicalTrackingId, scope.serviceRunId,
			key.sourcePolicyRevision, key.captureConsentEpoch,
		)
		val retained = current.singleOrNull()?.let { fact ->
			database.cellCapturedFactDao().revisionsForFact(
				fact.writerProjectionId,
				fact.writerProjectionVersion,
				fact.logicalFactId,
				MAX_RADIO_REVISION_CHAIN + 1,
			)
		}.orEmpty()
		return resolveCurrentRevision(
			admissionRevisions = admissionRevisions,
			currentRevisions = current,
			retainedLineage = retained,
			maximumRevisionCount = MAX_RADIO_REVISION_CHAIN,
			logicalFactId = CellCapturedFactRevisionEntity::logicalFactId,
			semanticRevision = CellCapturedFactRevisionEntity::semanticRevision,
			lineageAuthenticates = CellCapturedFactRevisionIntegrity::hasValidRetainedLineage,
		)
	}

	private suspend fun withCellAggregateMaterialization(
		key: SourceProofKey,
		recording: RecordingProofState,
		fact: CellCapturedFactRevisionEntity,
		dependentCarrier: CellWalCarrierAuthentication.Authenticated,
		block: suspend (CellAggregateProof) -> SourceProofResult<MaterializedProofState>,
	): SourceProofResult<MaterializedProofState> = when (fact.factKind) {
		CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE ->
			block(CellAggregateProof(fact, null, dependentCarrier, dependentCarrier))
		CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY ->
			withCellAggregateOwner(key, recording, fact, dependentCarrier, block)
		else -> failed(SourceProofStage.MATERIALIZED, SourceProofReason.INTEGRITY_FAILURE, recording.lineage)
	}

	private suspend fun withCellAggregateOwner(
		key: SourceProofKey,
		recording: RecordingProofState,
		fact: CellCapturedFactRevisionEntity,
		dependentCarrier: CellWalCarrierAuthentication.Authenticated,
		block: suspend (CellAggregateProof) -> SourceProofResult<MaterializedProofState>,
	): SourceProofResult<MaterializedProofState> {
		val ownerId = fact.aggregateOwnerLogicalFactId ?: return failed(
			SourceProofStage.MATERIALIZED, SourceProofReason.INTEGRITY_FAILURE, recording.lineage)
		val ownerRevision = fact.aggregateOwnerSemanticRevision ?: return failed(
			SourceProofStage.MATERIALIZED, SourceProofReason.INTEGRITY_FAILURE, recording.lineage)
		val owner = database.cellCapturedFactDao().revision(
			fact.writerProjectionId, fact.writerProjectionVersion, ownerId, ownerRevision,
		) ?: return failed(SourceProofStage.MATERIALIZED, SourceProofReason.INTEGRITY_FAILURE, recording.lineage)
		return withCellAggregateCarrier(key, recording, fact, owner, dependentCarrier, block)
	}

	private suspend fun withCellAggregateCarrier(
		key: SourceProofKey,
		recording: RecordingProofState,
		fact: CellCapturedFactRevisionEntity,
		owner: CellCapturedFactRevisionEntity,
		dependentCarrier: CellWalCarrierAuthentication.Authenticated,
		block: suspend (CellAggregateProof) -> SourceProofResult<MaterializedProofState>,
	): SourceProofResult<MaterializedProofState> {
		val ownerCursor = database.cellCapturedFactDao().cursor(
			fact.writerProjectionId, fact.writerProjectionVersion, requireNotNull(fact.aggregateOwnerLogicalFactId),
		) ?: return unavailable(
			SourceProofStage.MATERIALIZED, SourceProofReason.IMMUTABLE_AUTHORITY_MISSING, recording.lineage)
		if (!CellCapturedFactRevisionIntegrity.coverageOnlyOwnerCursorAuthenticates(fact, owner, ownerCursor)) {
			return unavailable(SourceProofStage.MATERIALIZED, SourceProofReason.STALE, recording.lineage)
		}
		return withCellAggregateWal(key, recording, owner, ownerCursor, dependentCarrier, block)
	}

	private suspend fun withCellAggregateWal(
		key: SourceProofKey,
		recording: RecordingProofState,
		owner: CellCapturedFactRevisionEntity,
		ownerCursor: CellCapturedFactCursorEntity,
		dependentCarrier: CellWalCarrierAuthentication.Authenticated,
		block: suspend (CellAggregateProof) -> SourceProofResult<MaterializedProofState>,
	): SourceProofResult<MaterializedProofState> {
		val ownerWal = database.sourceEventWalDao().getByEventId(owner.sourceEventId) ?: return unavailable(
			SourceProofStage.MATERIALIZED, SourceProofReason.IMMUTABLE_AUTHORITY_MISSING, recording.lineage)
		val ownerCarrier = authenticateCellFactCarrier(owner, ownerWal) ?: return failed(
			SourceProofStage.MATERIALIZED, SourceProofReason.INTEGRITY_FAILURE, recording.lineage)
		cellAggregateOwnerFailure(key, owner, ownerWal)?.let { failure ->
			return failed(SourceProofStage.MATERIALIZED, failure.recordingFailureReason(), recording.lineage)
		}
		return block(CellAggregateProof(owner, ownerCursor, dependentCarrier, ownerCarrier))
	}

	private suspend fun cellAggregateOwnerFailure(
		key: SourceProofKey,
		owner: CellCapturedFactRevisionEntity,
		ownerWal: SourceEventWalEntity,
	): SourceProofResult<RecordingProofState>? {
		val ownerKey = key.copy(
			evidenceIdentity = SourceEvidenceIdentity(owner.sourceEventId),
			sourcePolicyRevision = owner.sourcePolicyRevision, captureConsentEpoch = owner.captureConsentEpoch,
			collectedDataEpoch = owner.collectedDataEpoch,
		)
		return if (ownerWal.payloadVersion == CELL_WAL_PAYLOAD_VERSION) {
			if (hasHistoricalCellFactAuthority(owner, ownerWal)) {
				null
			} else {
				failed(SourceProofStage.RECORDING, SourceProofReason.IMMUTABLE_AUTHORITY_MISSING)
			}
		} else {
			historicalSessionAuthority(ownerKey, ownerWal, SourceKind.CELL,
				owner.coverageIntervalStartNanos, owner.coverageIntervalEndNanos)
		}
	}

	private fun SourceProofResult<RecordingProofState>.recordingFailureReason(): SourceProofReason = when (this) {
		is SourceProofResult.Pending -> reason
		is SourceProofResult.Unavailable -> reason
		is SourceProofResult.Failed -> reason
		is SourceProofResult.Deleted -> reason
		is SourceProofResult.Proven -> SourceProofReason.INTEGRITY_FAILURE
	}

	private fun CellCapturedFactRevisionEntity.materializationWriter(): WriterExpectation = WriterExpectation(
		SourceDestinationOwnerEntity.SOURCE_CELL, writerOwnerGeneration, writerProjectionId,
		writerProjectionVersion, writerBindingGeneration,
	)

	private suspend fun materializedCellCurrent(
		key: SourceProofKey,
		recording: RecordingProofState,
		wal: SourceEventWalEntity,
		fact: CellCapturedFactRevisionEntity,
		proof: CellAggregateProof,
	): SourceProofResult<MaterializedProofState> {
		val cursor = database.cellCapturedFactDao().cursor(
			fact.writerProjectionId,
			fact.writerProjectionVersion,
			fact.logicalFactId,
		) ?: return pendingAfterProjectionFailure(
			fact.materializationWriter(),
			wal.admissionOrdinal,
			SourceProofReason.CURSOR_BEHIND,
			recording.lineage,
		)
		fact.authorityMismatch(key)?.let { reason ->
			return failed(
				SourceProofStage.MATERIALIZED,
				reason,
				recording.lineage,
			)
		}
		if (!cellAggregateAuthenticates(cursor, fact, proof)) {
			return failed(SourceProofStage.MATERIALIZED, SourceProofReason.INTEGRITY_FAILURE, recording.lineage)
		}
		return provenCellMaterialization(key, recording, wal, fact, cursor, proof)
	}

	private suspend fun provenCellMaterialization(
		key: SourceProofKey,
		recording: RecordingProofState,
		wal: SourceEventWalEntity,
		fact: CellCapturedFactRevisionEntity,
		cursor: CellCapturedFactCursorEntity,
		proof: CellAggregateProof,
	): SourceProofResult<MaterializedProofState> {
		val aggregate = proof.aggregate
		val walAuthentication = proof.dependentCarrier
		val aggregateWalAuthentication = proof.aggregateCarrier
		materializationRetention(key, recording) { floor ->
			listOf(
				CellWalCarrierAuthenticator.retentionLowerBound(fact, walAuthentication.carrier),
				CellWalCarrierAuthenticator.retentionLowerBound(
					aggregate,
					aggregateWalAuthentication.carrier,
				),
			).takeIf { bounds -> bounds.all { it != null } }
				?.any { lowerBound -> requireNotNull(lowerBound) < floor }
		}?.let { return it }
		val authority = materializationAuthority(
			key,
			wal,
			fact.materializationWriter(),
			cursor.cursorRevision,
			additionalAdmissionOrdinals = listOf(aggregate.sourceAdmissionOrdinal)
				.filter { it != wal.admissionOrdinal },
		)
		if (authority !is MaterializationAuthority.Ready) {
			return authority.result(recording.lineage)
		}
		return provenMaterialized(
			recording,
			authority,
			SourceFactIdentity(fact.logicalFactId),
			fact.semanticRevision,
			fact.childCompleteness.toCompleteness(),
			providerCoverage(
				fact.coverageIntervalStartNanos,
				fact.coverageIntervalEndNanos,
			),
		)
	}

	private suspend fun cellAggregateAuthenticates(
		cursor: CellCapturedFactCursorEntity,
		fact: CellCapturedFactRevisionEntity,
		proof: CellAggregateProof,
	): Boolean {
		val aggregate = proof.aggregate
		if (!CellCapturedFactRevisionIntegrity.cursorAuthenticatesCurrent(cursor, fact) ||
			!CellCapturedFactRevisionIntegrity.hasValidEffectChecksum(aggregate) ||
			aggregate.writerBindingGeneration != fact.writerBindingGeneration ||
			aggregate.writerOwnerGeneration != fact.writerOwnerGeneration) {
			return false
		}
		if (!fact.authenticatesCellAggregateCarrier(proof)) {
			return false
		}
		if (proof.ownerCursor?.let {
			CellCapturedFactRevisionIntegrity.coverageOnlyOwnerCursorAuthenticates(fact, aggregate, it)
		} == false) {
			return false
		}
		return fact.factKind != CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY ||
			cellAggregateReuseCompatible(fact, aggregate)
	}

	private fun CellCapturedFactRevisionEntity.authenticatesCellAggregateCarrier(proof: CellAggregateProof): Boolean =
		if (factKind == CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY) {
			CellWalCarrierAuthenticator.authenticatesAggregate(proof.aggregate, proof.dependentCarrier.carrier, this)
		} else {
			CellWalCarrierAuthenticator.authenticatesAggregate(proof.aggregate, proof.dependentCarrier.carrier)
		}

	private suspend fun materializationAuthority(
		key: SourceProofKey,
		wal: SourceEventWalEntity,
		expectation: WriterExpectation,
		cursorRevision: Long?,
		additionalAdmissionOrdinals: List<Long> = emptyList(),
	): MaterializationAuthority {
		val lifecycle = database.sourceEvidenceStateDao().get()
			?: return MaterializationAuthority.Blocked(
				unavailable(
					SourceProofStage.MATERIALIZED,
					SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
				),
			)
		materializationLifecycleFailure(key, wal, lifecycle)?.let { return it }
		val lane = database.sourceProjectionStateDao().productLane(
			expectation.sourceKind,
			expectation.bindingGeneration,
			expectation.projectionId,
			expectation.projectionVersion,
		) ?: return MaterializationAuthority.Blocked(
			failed(SourceProofStage.MATERIALIZED, SourceProofReason.WRITER_MISMATCH),
		)
		return materializationLaneAuthority(wal, expectation, cursorRevision, lifecycle, lane, additionalAdmissionOrdinals)
	}

	private suspend fun materializationLifecycleFailure(
		key: SourceProofKey,
		wal: SourceEventWalEntity,
		lifecycle: SourceEvidenceState,
	): MaterializationAuthority.Blocked? {
		if (lifecycle.collectedDataEpoch != key.collectedDataEpoch) {
			return MaterializationAuthority.Blocked(
				if (lifecycle.collectedDataEpoch > key.collectedDataEpoch) {
					deleted(SourceProofStage.MATERIALIZED, SourceProofReason.DELETED)
				} else {
					unavailable(
						SourceProofStage.MATERIALIZED,
						SourceProofReason.DATA_EPOCH_MISMATCH,
					)
				},
			)
		}
		if (wal.admissionOrdinal <= lifecycle.deletedSourceEventHighWaterOrdinal) {
			return MaterializationAuthority.Blocked(
				deleted(SourceProofStage.MATERIALIZED, SourceProofReason.DELETED),
			)
		}
		if (isRunDeleted(key)) {
			return MaterializationAuthority.Blocked(
				deleted(SourceProofStage.MATERIALIZED, SourceProofReason.DELETED),
			)
		}
		return null
	}

	private suspend fun materializationLaneAuthority(
		wal: SourceEventWalEntity,
		expectation: WriterExpectation,
		cursorRevision: Long?,
		lifecycle: SourceEvidenceState,
		lane: SourceProductProjectionLaneEntity,
		additionalAdmissionOrdinals: List<Long>,
	): MaterializationAuthority {
		if (expectation.sourceKind in setOf(
				SourceDestinationOwnerEntity.SOURCE_ACTIVITY, SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				SourceDestinationOwnerEntity.SOURCE_WIFI, SourceDestinationOwnerEntity.SOURCE_CELL,
			) && lane.captureModeMask !=
			SourceWriterGenerationContract.captureModeMaskForKnownBinding(expectation.bindingGeneration)
		) {
			return MaterializationAuthority.Blocked(
			failed(SourceProofStage.MATERIALIZED, SourceProofReason.WRITER_MISMATCH))
		}
		val requiredAdmissionOrdinals =
			listOf(wal.admissionOrdinal) + additionalAdmissionOrdinals
		when (lane.materializationAdmissionFailure(requiredAdmissionOrdinals)) {
			SourceProofReason.MATERIALIZATION_PENDING -> {
				return MaterializationAuthority.Blocked(
					pendingAfterProjectionFailure(
						expectation,
						wal.admissionOrdinal,
						SourceProofReason.MATERIALIZATION_PENDING,
					),
				)
			}
			SourceProofReason.CURSOR_BEHIND -> {
				return MaterializationAuthority.Blocked(
					pendingAfterProjectionFailure(
						expectation,
						wal.admissionOrdinal,
						SourceProofReason.CURSOR_BEHIND,
					),
				)
			}
			null -> Unit
			else -> error("Unexpected materialization admission failure")
		}
		return MaterializationAuthority.Ready(
			evidenceRevision = lifecycle.revision,
			cursorRevision = cursorRevision ?: lane.contiguousAdmissionOrdinal,
			writer = SourceProofWriterIdentity(
				expectation.projectionId,
				expectation.projectionVersion,
				expectation.bindingGeneration,
				expectation.ownerGeneration,
			),
		)
	}

	private suspend fun isRunDeleted(key: SourceProofKey): Boolean {
		val scope = key.scope as? SourceProofScope.Run ?: return false
		return database.sourceDeletionFenceDao().contains(
			sourceKind = source.toSourceKind().stableCode,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			scopeIdentityDigest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
				sourceKind = source.toSourceKind().stableCode,
				purpose = SourceBrokerPurpose.SESSION_CAPTURE,
				logicalTrackingId = scope.logicalTrackingId,
				serviceRunId = scope.serviceRunId,
			),
		)
	}

	private suspend fun pendingAfterProjectionFailure(
		expectation: WriterExpectation,
		admissionOrdinal: Long,
		fallbackReason: SourceProofReason,
		lineage: SourceProofLineage? = null,
	): SourceProofResult<MaterializedProofState> {
		val failure = database.sourceProjectionStateDao().failure(
			expectation.projectionId,
			expectation.projectionVersion,
			admissionOrdinal,
		)
		return failure?.toLifecycleMaterializationResult(lineage)
			?: pending(SourceProofStage.MATERIALIZED, fallbackReason, lineage)
	}

	private suspend fun authenticateWifiFactCarrier(
		fact: WifiCapturedFactRevisionEntity,
		wal: SourceEventWalEntity,
	): WifiWalCarrierAuthentication.Authenticated? {
		val children = database.radioObservationChildDao().wifiSessionChildren(
			fact.writerProjectionId, fact.writerProjectionVersion,
			fact.logicalFactId, fact.semanticRevision, 65,
		)
		val stored = WifiWalCarrierAuthenticator.authenticate(fact, wal, children)
			as? WifiWalCarrierAuthentication.Authenticated ?: return null
		return if (wal.payloadVersion != WIFI_WAL_PAYLOAD_VERSION ||
			wifiFactPurposeMatches(fact, wal, stored, children)) {
			stored
		} else {
			null
		}
	}

	private suspend fun wifiFactPurposeMatches(
		fact: WifiCapturedFactRevisionEntity,
		wal: SourceEventWalEntity,
		stored: WifiWalCarrierAuthentication.Authenticated,
		children: List<WifiCapturedChildEntity>,
	): Boolean {
		val items = database.qualifyWifiPurposeItems(wal, stored.carrier.payload,
			fact.providerAcceptanceStartNanos, fact.providerAcceptanceEndNanos) ?: return false
		val member = items.authorizationTimeline.flatMap { it.authorizedMembers }.singleOrNull {
			it.matchesWifiFactScope(fact) && it.matchesWifiFactAuthorization(fact)
		} ?: return false
		val indexes = items.forDemand(requireNotNull(member.demandId)).toSet()
		return indexes.isNotEmpty() && children.none { it.itemIndex !in indexes }
	}

	private fun SourceAuthorizationEntity.matchesWifiFactScope(
		fact: WifiCapturedFactRevisionEntity,
	): Boolean = logicalTrackingId == fact.logicalTrackingId && serviceRunId == fact.serviceRunId &&
		manifestRevision == fact.manifestRevision && lifecycleLeaseGeneration == fact.lifecycleLeaseGeneration

	private fun SourceAuthorizationEntity.matchesWifiFactAuthorization(
		fact: WifiCapturedFactRevisionEntity,
	): Boolean {
		val authorizationMatches = authorizationRevision == fact.authorizationRevision &&
			authorizationFingerprint == fact.authorizationFingerprint && purposeEligibilityMask == fact.purposeEligibilityMask
		val consentMatches = sourcePolicyRevision == fact.sourcePolicyRevision && consentEpoch == fact.captureConsentEpoch
		return purpose == SourceBrokerPurpose.SESSION_CAPTURE && persistenceEligible && authorizationMatches && consentMatches
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private suspend fun authenticateCellFactCarrier(
		fact: CellCapturedFactRevisionEntity,
		wal: SourceEventWalEntity,
	): CellWalCarrierAuthentication.Authenticated? {
		if (wal.payloadVersion != CELL_WAL_PAYLOAD_VERSION) {
			return CellWalCarrierAuthenticator.authenticate(fact, wal)
				as? CellWalCarrierAuthentication.Authenticated
		}
		val raw = CellWalCarrierAuthenticator.authenticate(wal)
			as? CellWalCarrierAuthentication.Authenticated ?: return null
		val children = database.radioObservationChildDao().cellSessionChildren(
			fact.writerProjectionId, fact.writerProjectionVersion,
			fact.logicalFactId, fact.semanticRevision, raw.carrier.payload.observations.size + 1,
		)
		val stored = CellWalCarrierAuthenticator.authenticate(fact, wal, children)
			as? CellWalCarrierAuthentication.Authenticated ?: return null
		val purpose = database.qualifyCellCapturePurposeItems(wal, raw.carrier.payload, fact)
			as? CellPurposeItems.Qualified ?: return null
		val exact = CellWalCarrierAuthenticator.authenticate(fact, wal, purpose.indexes.toSet())
			as? CellWalCarrierAuthentication.Authenticated ?: return null
		return stored.takeIf { it.carrier.childQualification == exact.carrier.childQualification }
	}

	/** An aggregate owner is requalified in its original session, never the callback's header. */
	private suspend fun hasHistoricalCellFactAuthority(
		owner: CellCapturedFactRevisionEntity,
		wal: SourceEventWalEntity,
	): Boolean {
		val qualified = cellQualifier.qualify(SourceEventId(wal.eventId))
		val groups = if (qualified is CellWalAdapterResult.Groups) {
			qualified.results
		} else {
			listOf(qualified)
		}
		return groups.filterIsInstance<CellWalAdapterResult.Evaluated>().mapNotNull {
			it.classification.captureFactOrNull()
		}.singleOrNull { candidate ->
			candidate.authority.matchesHistoricalCellScope(owner) &&
				candidate.authority.matchesHistoricalCellAuthorization(owner) &&
				candidate.coverage.providerIntervalStartElapsedRealtimeNanos == owner.coverageIntervalStartNanos &&
				candidate.coverage.providerIntervalEndElapsedRealtimeNanos == owner.coverageIntervalEndNanos
		} != null
	}

	private fun CellCaptureAuthority.matchesHistoricalCellScope(
		owner: CellCapturedFactRevisionEntity,
	): Boolean {
		val scopeMatches = logicalTrackingId.value == owner.logicalTrackingId && serviceRunId.value == owner.serviceRunId &&
			sessionSegmentId == owner.sessionSegmentId
		val lifecycleMatches = sessionManifestRevision == owner.manifestRevision &&
			lifecycleLeaseGeneration == owner.lifecycleLeaseGeneration
		return scopeMatches && lifecycleMatches
	}

	private fun CellCaptureAuthority.matchesHistoricalCellAuthorization(
		owner: CellCapturedFactRevisionEntity,
	): Boolean {
		val consentMatches = sourcePolicyRevision == owner.sourcePolicyRevision &&
			captureConsentEpoch == owner.captureConsentEpoch && collectedDataEpoch == owner.collectedDataEpoch
		val authorizationMatches = authorizationRevision == owner.authorizationRevision &&
			authorizationFingerprint == owner.authorizationFingerprint
		return consentMatches && authorizationMatches
	}

	private suspend fun historicalSessionAuthority(
		key: SourceProofKey,
		wal: SourceEventWalEntity,
		expectedSource: SourceKind,
		observedStartNanos: Long,
		observedEndNanos: Long,
	): SourceProofResult<RecordingProofState>? {
		if (!wal.hasHistoricalAuthorityFields()) {
			return historicalAuthorityUnavailable()
		}
		if (!wal.hasHistoricalObservationClock(expectedSource, observedStartNanos, observedEndNanos)) {
			return historicalAuthorityFailure(SourceProofReason.PRE_EFFECTIVE)
		}
		val sequenceIdentity = database.sourceEventWalDao().identityBySourceSequence(
			expectedSource.stableCode, wal.sourceInstanceId, wal.sourceSequence,
		)
		if (sequenceIdentity?.eventId != wal.eventId ||
			sequenceIdentity.admissionOrdinal != wal.admissionOrdinal ||
			sequenceIdentity.integrityIdentity != wal.integrityIdentity
		) {
			return historicalAuthorityFailure()
		}
		return historicalPlanAuthority(key, wal, expectedSource, observedStartNanos..observedEndNanos)
	}

	private fun SourceEventWalEntity.hasHistoricalAuthorityFields(): Boolean = listOf(
		configRevision, physicalConfigurationFingerprint, authorizationRevision, authorizationFingerprint,
		sessionManifestRevision, lifecycleLeaseGeneration, logicalTrackingId, serviceRunId, wallTimeMs, wallTimeUncertaintyMs,
	).none { it == null }

	private fun SourceEventWalEntity.hasHistoricalObservationClock(
		expectedSource: SourceKind,
		startNanos: Long,
		endNanos: Long,
	): Boolean {
		val elapsedMatches = startNanos >= 0L && endNanos >= startNanos &&
			observedElapsedNanos == endNanos && receivedElapsedNanos >= endNanos
		val wallClockValid = requireNotNull(wallTimeMs) >= 0L &&
			requireNotNull(wallTimeUncertaintyMs) >= 0L && acquiredAtMs >= 0L
		val attributionMatches = planAttribution == PlanAttribution.CAPTURED_REGISTRATION.ordinal &&
			(expectedSource != SourceKind.STEPS || wallTimeMs == acquiredAtMs)
		return elapsedMatches && wallClockValid && attributionMatches
	}

	private suspend fun historicalPlanAuthority(
		key: SourceProofKey,
		wal: SourceEventWalEntity,
		expectedSource: SourceKind,
		window: LongRange,
	): SourceProofResult<RecordingProofState>? {
		val revision = requireNotNull(wal.configRevision)
		val planDao = database.sourcePlanStateDao()
		val header = planDao.revision(revision) ?: return historicalAuthorityUnavailable()
		val desired = planDao.desiredPlan(revision, expectedSource.stableCode) ?: return historicalAuthorityUnavailable()
		val plan = runCatching { planCodec.decode(desired.payload) }.getOrNull()
		val encoded = plan?.let { runCatching { planCodec.encode(it) }.getOrNull() }
		if (plan == null || encoded == null) {
			return historicalAuthorityFailure()
		}
		return if (plan.matchesHistoricalPlan(wal, expectedSource) &&
			desired.matchesHistoricalPlanEncoding(encoded, revision, expectedSource) &&
			header.sourcePolicyRevision == key.sourcePolicyRevision) {
			historicalRegistrationAuthority(key, wal, plan, window)
		} else {
			historicalAuthorityFailure(SourceProofReason.POLICY_MISMATCH)
		}
	}

	private suspend fun historicalRegistrationAuthority(
		key: SourceProofKey,
		wal: SourceEventWalEntity,
		plan: SourcePlan,
		window: LongRange,
	): SourceProofResult<RecordingProofState>? = when (val registration =
		database.authenticateHistoricalProviderRegistration(
			sourceKind = plan.source.stableCode,
			registrationGeneration = wal.registrationGeneration,
			sourceInstanceId = wal.sourceInstanceId,
			clockDomainId = wal.clockDomainId,
			physicalConfigurationFingerprint = requireNotNull(wal.physicalConfigurationFingerprint),
			collectedDataEpoch = wal.capturedCollectedDataEpoch,
			expectedProviderResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
			observedStartNanos = window.first,
			observedEndNanos = window.last,
			observedWallTimeMs = requireNotNull(wal.wallTimeMs),
			currentClockDomainId = bootClockDomainProvider.current(),
		)) {
		is ProviderRegistrationIntervalAuthentication.Authenticated ->
			historicalAuthorizationAuthority(key, wal, plan, window, registration)
		is ProviderRegistrationIntervalAuthentication.Rejected ->
			if (registration.reason == ProviderRegistrationIntervalFailure.MISSING_REGISTRATION) {
				historicalAuthorityUnavailable()
			} else {
				historicalAuthorityFailure(SourceProofReason.PRE_EFFECTIVE)
			}
	}

	private suspend fun historicalAuthorizationAuthority(
		key: SourceProofKey,
		wal: SourceEventWalEntity,
		plan: SourcePlan,
		window: LongRange,
		registration: ProviderRegistrationIntervalAuthentication.Authenticated,
	): SourceProofResult<RecordingProofState>? {
		val broker = database.sourceBrokerDao()
		val rows = broker.authorizationRevisionBounded(
			plan.source.stableCode, wal.registrationGeneration,
			requireNotNull(wal.authorizationRevision), MAX_AUTHORIZATION_MEMBERS + 1,
		)
		if (rows.size > MAX_AUTHORIZATION_MEMBERS) {
			return historicalAuthorityFailure()
		}
		val authorization = rows.toAuthorizationSnapshotOrNull() ?: return historicalAuthorityUnavailable()
		historicalAuthorizationDemandFailure(key, wal, plan, window, authorization)?.let { return it }
		return historicalAuthorizationTimeline(key, wal, window, registration, authorization)
	}

	private suspend fun historicalAuthorizationDemandFailure(
		key: SourceProofKey,
		wal: SourceEventWalEntity,
		plan: SourcePlan,
		window: LongRange,
		authorization: SourceAuthorizationSnapshot,
	): SourceProofResult<RecordingProofState>? {
		val broker = database.sourceBrokerDao()
		val startAuthorization = broker.authorizationAtBounded(
			plan.source.stableCode, wal.registrationGeneration, wal.clockDomainId,
			window.first, MAX_AUTHORIZATION_MEMBERS + 1,
		).toAuthorizationSnapshotOrNull()
		val endAuthorization = broker.authorizationAtBounded(
			plan.source.stableCode, wal.registrationGeneration, wal.clockDomainId,
			window.last, MAX_AUTHORIZATION_MEMBERS + 1,
		).toAuthorizationSnapshotOrNull()
		val captureMember = authorization.authorizedMembers.singleOrNull { it.matchesHistoricalCapture(key, wal) }
		val demandIds = authorization.authorizedMembers.mapNotNull { it.demandId }
		val demands = broker.demandsByIds(demandIds)
		val recomputed = runCatching {
			SourceBrokerAuthorization.rows(
				sourceKind = plan.source.stableCode, registrationGeneration = wal.registrationGeneration,
				authorizationRevision = requireNotNull(wal.authorizationRevision), demands = demands,
				effectiveBootId = authorization.effectiveBootId,
				effectiveElapsedRealtimeNanos = authorization.effectiveElapsedRealtimeNanos,
				effectiveWallTimeMs = authorization.members.first().effectiveWallTimeMs,
			)
		}.getOrNull()
		if (authorization.isDenied || captureMember == null ||
			authorization.purposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L
		) {
			return unavailable(SourceProofStage.RECORDING, SourceProofReason.CONTROL_IS_NOT_CAPTURE_EVIDENCE)
		}
		if (!authorization.matchesHistoricalIdentity(wal, startAuthorization, endAuthorization) ||
			!authorization.matchesHistoricalDemandRows(demandIds, demands, recomputed)) {
			return historicalAuthorityFailure()
		}
		return if (demands.all { plan.satisfiesHistoricalDemand(it) }) {
			null
		} else {
			historicalAuthorityFailure(SourceProofReason.POLICY_MISMATCH)
		}
	}

	private fun SourceAuthorizationEntity.matchesHistoricalCapture(
		key: SourceProofKey,
		wal: SourceEventWalEntity,
	): Boolean {
		val scopeMatches = logicalTrackingId == wal.logicalTrackingId && serviceRunId == wal.serviceRunId &&
			manifestRevision == wal.sessionManifestRevision && lifecycleLeaseGeneration == wal.lifecycleLeaseGeneration
		val selectionMatches = sourcePolicyRevision == key.sourcePolicyRevision && consentEpoch == key.captureConsentEpoch
		return purpose == SourceBrokerPurpose.SESSION_CAPTURE && persistenceEligible && scopeMatches && selectionMatches
	}

	private fun SourcePlan.satisfiesHistoricalDemand(
		demand: SourceDemandEntity,
	): Boolean {
		val contract = runCatching { demand.toSourceDemandContract() }.getOrNull() ?: return false
		return satisfies(SourceDemand(
			source = contract.source, maximumAgeMs = contract.maximumProviderItemAgeMs,
			desiredLatencyMs = contract.targetPlanningLatencyMs, quality = EvidenceQuality.ANY,
			reason = DemandReason.SESSION, acquisitionFloor = contract.floor,
			requestedDeliveryLatencyMs = contract.requestedDeliveryLatencyMs,
			adaptiveReductionAllowed = contract.adaptiveReductionAllowed,
		))
	}

	private suspend fun historicalAuthorizationTimeline(
		key: SourceProofKey,
		wal: SourceEventWalEntity,
		window: LongRange,
		registration: ProviderRegistrationIntervalAuthentication.Authenticated,
		authorization: SourceAuthorizationSnapshot,
	): SourceProofResult<RecordingProofState>? {
		val nextRows = database.sourceBrokerDao().nextAuthorizationRevisionBounded(
			key.source.toSourceKind().stableCode, wal.registrationGeneration,
			requireNotNull(wal.authorizationRevision), MAX_AUTHORIZATION_MEMBERS + 1,
		)
		if (nextRows.size > MAX_AUTHORIZATION_MEMBERS) {
			return historicalAuthorityFailure()
		}
		val next = if (nextRows.isEmpty()) {
			null
		} else {
			nextRows.toAuthorizationSnapshotOrNull() ?: return historicalAuthorityFailure()
		}
		if (next != null && (next.effectiveBootId != wal.clockDomainId ||
			next.effectiveElapsedRealtimeNanos <= authorization.effectiveElapsedRealtimeNanos)) {
			return historicalAuthorityFailure()
		}
		return historicalAuthorizationWindow(key, wal, window, registration, authorization, next)
	}

	private suspend fun historicalAuthorizationWindow(
		key: SourceProofKey,
		wal: SourceEventWalEntity,
		window: LongRange,
		registration: ProviderRegistrationIntervalAuthentication.Authenticated,
		authorization: SourceAuthorizationSnapshot,
		next: SourceAuthorizationSnapshot?,
	): SourceProofResult<RecordingProofState>? {
		val end = next?.let { minOf(registration.interval.endExclusiveNanos, it.effectiveElapsedRealtimeNanos) }
			?: registration.interval.endExclusiveNanos
		return if (authorization.effectiveElapsedRealtimeNanos > window.first || window.last >= end) {
			historicalAuthorityFailure(SourceProofReason.PRE_EFFECTIVE)
		} else {
			historicalPolicyAuthority(key, wal, window,
				maxOf(registration.interval.startInclusiveNanos, authorization.effectiveElapsedRealtimeNanos), end)
		}
	}

	private suspend fun historicalPolicyAuthority(
		key: SourceProofKey,
		wal: SourceEventWalEntity,
		window: LongRange,
		providerStartNanos: Long,
		providerEndNanos: Long,
	): SourceProofResult<RecordingProofState>? {
		val dao = database.sourcePolicyDao()
		val authority = dao.authority() ?: return historicalAuthorityUnavailable()
		val policy = dao.policyAtRevision(key.sourcePolicyRevision, key.source.toSourceKind().stableCode)
			?: return historicalAuthorityUnavailable()
		val authorityMatches = authority.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE &&
			authority.currentPolicyRevision >= key.sourcePolicyRevision
		val selectionMatches = policy.enabled && policy.capturePersistenceEligible &&
			policy.captureConsentEpoch == key.captureConsentEpoch
		val clockMatches = policy.effectiveBootId == wal.clockDomainId &&
			policy.effectiveElapsedRealtimeNanos <= window.first
		return if (authorityMatches && selectionMatches && clockMatches) {
			historicalConsentAuthority(key, wal, window, policy, providerStartNanos, providerEndNanos)
		} else {
			historicalAuthorityFailure(SourceProofReason.POLICY_MISMATCH)
		}
	}

	private suspend fun historicalConsentAuthority(
		key: SourceProofKey,
		wal: SourceEventWalEntity,
		window: LongRange,
		policy: SourcePolicyEntity,
		providerStartNanos: Long,
		providerEndNanos: Long,
	): SourceProofResult<RecordingProofState>? {
		val consent = database.sourcePolicyDao().consentEpoch(
			key.source.toSourceKind().stableCode, SourceBrokerPurpose.SESSION_CAPTURE, key.captureConsentEpoch,
		) ?: return historicalAuthorityUnavailable()
		val selectionMatches = consent.eligible && consent.persistenceEligible &&
			consent.policyRevision <= key.sourcePolicyRevision
		val clockMatches = consent.effectiveBootId == wal.clockDomainId &&
			consent.effectiveElapsedRealtimeNanos <= window.first
		return if (selectionMatches && clockMatches) {
			historicalSessionTimeline(key, wal, window, policy,
				maxOf(providerStartNanos, policy.effectiveElapsedRealtimeNanos, consent.effectiveElapsedRealtimeNanos),
				providerEndNanos)
		} else {
			historicalAuthorityFailure(SourceProofReason.CONSENT_MISMATCH)
		}
	}

	private suspend fun historicalSessionTimeline(
		key: SourceProofKey,
		wal: SourceEventWalEntity,
		window: LongRange,
		policy: SourcePolicyEntity,
		appliedBeforeManifestNanos: Long,
		providerEndNanos: Long,
	): SourceProofResult<RecordingProofState>? {
		val dao = database.sourceSessionDao()
		val run = dao.serviceRun(requireNotNull(wal.serviceRunId)) ?: return historicalAuthorityUnavailable()
		val session = dao.session(requireNotNull(wal.logicalTrackingId)) ?: return historicalAuthorityUnavailable()
		val sessionEnd = session.cutoffElapsedNanos ?: Long.MAX_VALUE
		return if (run.matchesHistoricalSessionWindow(wal, session, window, sessionEnd)) {
			historicalManifestAuthority(key, wal, window, policy, run,
				appliedBeforeManifestNanos..minOf(sessionEnd, providerEndNanos))
		} else {
			historicalAuthorityFailure(SourceProofReason.SCOPE_MISMATCH)
		}
	}

	private fun SourceServiceRunEntity.matchesHistoricalSessionWindow(
		wal: SourceEventWalEntity,
		session: com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity,
		window: LongRange,
		sessionEnd: Long,
	): Boolean {
		val runMatches = logicalTrackingId == wal.logicalTrackingId && bootId == wal.clockDomainId &&
			leaseGeneration == wal.lifecycleLeaseGeneration
		val sessionMatches = session.logicalTrackingId == wal.logicalTrackingId &&
			session.lifecycleLeaseGeneration >= requireNotNull(wal.lifecycleLeaseGeneration)
		val clockMatches = startedElapsedNanos <= window.first && window.last < sessionEnd
		return runMatches && sessionMatches && clockMatches
	}

	private suspend fun historicalManifestAuthority(
		key: SourceProofKey,
		wal: SourceEventWalEntity,
		window: LongRange,
		policy: SourcePolicyEntity,
		run: SourceServiceRunEntity,
		sourceBounds: LongRange,
	): SourceProofResult<RecordingProofState>? {
		val manifests = database.sourceSessionDao().manifestsForServiceRun(
			requireNotNull(wal.serviceRunId), MAX_MANIFESTS_PER_RUN + 1,
		)
		if (manifests.size > MAX_MANIFESTS_PER_RUN || !SessionManifestIntegrity.hasValidServiceRunTimeline(run, manifests)) {
			return historicalAuthorityFailure()
		}
		val index = manifests.indexOfFirst { it.manifestRevision == wal.sessionManifestRevision }
		if (index < 0) {
			return historicalAuthorityUnavailable()
		}
		val manifest = manifests[index]
		val sources = historicalManifestSources(requireNotNull(wal.serviceRunId), manifests)
			?: return historicalAuthorityFailure()
		val binding = sources.singleOrNull { it.matchesHistoricalManifestSource(key, wal) }
		val manifestEnd = manifests.getOrNull(index + 1)?.effectiveElapsedRealtimeNanos ?: sourceBounds.last
		return when {
			binding == null -> historicalAuthorityFailure(SourceProofReason.CONTROL_IS_NOT_CAPTURE_EVIDENCE)
			!binding.matchesHistoricalCaptureWindow(key, wal, window, manifestEnd, manifest, policy) ->
				historicalAuthorityFailure(SourceProofReason.PRE_EFFECTIVE)
			else -> historicalStartActionAuthority(key, wal, run, manifest, window.first,
				maxOf(sourceBounds.first, manifest.effectiveElapsedRealtimeNanos))
		}
	}

	private fun SessionManifestSourceEntity.matchesHistoricalCaptureWindow(
		key: SourceProofKey,
		wal: SourceEventWalEntity,
		window: LongRange,
		manifestEnd: Long,
		manifest: SessionManifestVersionEntity,
		policy: SourcePolicyEntity,
	): Boolean = manifest.matchesHistoricalManifest(key, wal, window, manifestEnd) &&
		consentEpoch == key.captureConsentEpoch && qosCode == policy.qosCode

	private suspend fun historicalManifestSources(
		serviceRunId: String,
		manifests: List<SessionManifestVersionEntity>,
	): List<SessionManifestSourceEntity>? {
		val sources = database.trackingHistoryReadDao().manifestSources(listOf(serviceRunId), MAX_MANIFEST_SOURCE_ROWS + 1)
		return sources.takeUnless { rows ->
			rows.size > MAX_MANIFEST_SOURCE_ROWS || manifests.any { manifest ->
				!SessionManifestIntegrity.verify(manifest, rows.filter { source ->
					source.logicalTrackingId == manifest.logicalTrackingId && source.manifestRevision == manifest.manifestRevision
				})
			}
		}
	}

	private fun SessionManifestSourceEntity.matchesHistoricalManifestSource(
		key: SourceProofKey,
		wal: SourceEventWalEntity,
	): Boolean {
		val scopeMatches = logicalTrackingId == wal.logicalTrackingId && manifestRevision == wal.sessionManifestRevision
		val purposeMatches = sourceKind == key.source.toSourceKind().stableCode &&
			purpose == SessionManifestPurposeCode.SESSION_CAPTURE && persistenceEligible
		return scopeMatches && purposeMatches
	}

	private fun SessionManifestVersionEntity.matchesHistoricalManifest(
		key: SourceProofKey,
		wal: SourceEventWalEntity,
		window: LongRange,
		manifestEnd: Long,
	): Boolean {
		val scopeMatches = logicalTrackingId == wal.logicalTrackingId && serviceRunId == wal.serviceRunId
		val planMatches = sourcePolicyRevision == key.sourcePolicyRevision && acquisitionPlanRevision == wal.configRevision
		val clockMatches = effectiveBootId == wal.clockDomainId &&
			effectiveElapsedRealtimeNanos <= window.first && window.last < manifestEnd
		return scopeMatches && planMatches && clockMatches
	}

	private suspend fun historicalStartActionAuthority(
		key: SourceProofKey,
		wal: SourceEventWalEntity,
		run: SourceServiceRunEntity,
		manifest: SessionManifestVersionEntity,
		observedStartNanos: Long,
		appliedAtElapsedNanos: Long,
	): SourceProofResult<RecordingProofState>? {
		val segment = database.sessionSegmentDao().getById(run.sessionSegmentId ?: return historicalAuthorityUnavailable())
			?: return historicalAuthorityUnavailable()
		if (segment.logicalTrackingId != wal.logicalTrackingId || segment.serviceRunId != wal.serviceRunId) {
			return historicalAuthorityFailure(SourceProofReason.SCOPE_MISMATCH)
		}
		val actions = database.sourceSessionDao().sourceStartActionsForManifestBounded(
			requireNotNull(wal.logicalTrackingId), requireNotNull(wal.serviceRunId),
			requireNotNull(wal.sessionManifestRevision), key.source.toSourceKind().stableCode, MAX_PLAN_APPLICATION_ROWS + 1,
		)
		return if (actions.size <= MAX_PLAN_APPLICATION_ROWS && actions.singleOrNull()?.authenticates(
				wal, run, manifest, key.source.toSourceKind(), requireNotNull(wal.configRevision),
				appliedAtElapsedNanos, observedStartNanos,
			) == true) {
			null
		} else if (actions.size > MAX_PLAN_APPLICATION_ROWS) {
			historicalAuthorityFailure()
		} else {
			historicalAuthorityUnavailable()
		}
	}

	private fun historicalAuthorityUnavailable(): SourceProofResult<RecordingProofState> =
		unavailable(SourceProofStage.RECORDING, SourceProofReason.IMMUTABLE_AUTHORITY_MISSING)

	private fun historicalAuthorityFailure(
		reason: SourceProofReason = SourceProofReason.INTEGRITY_FAILURE,
	): SourceProofResult<RecordingProofState> = failed(SourceProofStage.RECORDING, reason)

	private fun SourcePlan.matchesHistoricalPlan(
		wal: SourceEventWalEntity,
		expectedSource: SourceKind,
	): Boolean = source == expectedSource && enabled && revision == wal.configRevision &&
		physicalConfigurationFingerprint() == wal.physicalConfigurationFingerprint

	private fun SourceDesiredPlanEntity.matchesHistoricalPlanEncoding(
		encoded: EncodedSourcePlan,
		configurationRevision: Long,
		expectedSource: SourceKind,
	): Boolean = revision == configurationRevision && sourceKind == expectedSource.stableCode &&
		encoded.bytes.contentEquals(payload) && encoded.checksum == payloadChecksum

	private fun SourceAuthorizationSnapshot.matchesHistoricalIdentity(
		wal: SourceEventWalEntity,
		start: SourceAuthorizationSnapshot?,
		end: SourceAuthorizationSnapshot?,
	): Boolean {
		val endpointsMatch = start == this && end == this
		val identityMatches = authorizationFingerprint == wal.authorizationFingerprint &&
			purposeEligibilityMask == wal.authorizationPurposeEligibilityMask && effectiveBootId == wal.clockDomainId
		return endpointsMatch && identityMatches
	}

	private fun SourceAuthorizationSnapshot.matchesHistoricalDemandRows(
		demandIds: List<String>,
		demands: List<SourceDemandEntity>,
		recomputed: List<SourceAuthorizationEntity>?,
	): Boolean {
		val demandsMatch = demandIds.size == authorizedMembers.size &&
			demandIds.distinct().size == demandIds.size && demands.size == demandIds.size
		val rowsMatch = recomputed?.sortedBy { it.memberId } == members.sortedBy { it.memberId }
		return demandsMatch && rowsMatch
	}

	private suspend fun cellAggregateReuseCompatible(
		dependent: CellCapturedFactRevisionEntity,
		owner: CellCapturedFactRevisionEntity,
	): Boolean {
		val dependentAuthority = cellAggregateReuseAuthority(dependent) ?: return false
		val ownerAuthority = cellAggregateReuseAuthority(owner) ?: return false
		return dependentAuthority.canReuseAggregateFrom(ownerAuthority)
	}

	private suspend fun cellAggregateReuseAuthority(
		fact: CellCapturedFactRevisionEntity,
	): CellAggregateReuseAuthority? {
		val manifest = database.sourceSessionDao().manifest(
			fact.logicalTrackingId,
			fact.manifestRevision,
		) ?: return null
		val sources = database.sourceSessionDao().manifestSources(
			fact.logicalTrackingId,
			fact.manifestRevision,
		)
		if (manifest.serviceRunId != fact.serviceRunId ||
			!SessionManifestIntegrity.verify(manifest, sources)
		) {
			return null
		}
		val capturedSources = sources.filter { source ->
			source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
				source.persistenceEligible
		}.mapTo(linkedSetOf()) { it.sourceKind }
		val controlSources = sources.filter { source ->
			source.purpose == SessionManifestPurposeCode.CONTROL
		}.mapTo(linkedSetOf()) { it.sourceKind }
		return runCatching {
			CellAggregateReuseAuthority(
				logicalTrackingId = fact.logicalTrackingId,
				serviceRunId = fact.serviceRunId,
				sessionSegmentId = fact.sessionSegmentId,
				capturedSources = capturedSources,
				controlSources = controlSources,
				purposeEligibilityMask = fact.purposeEligibilityMask,
				captureConsentEpoch = fact.captureConsentEpoch,
				collectedDataEpoch = fact.collectedDataEpoch,
				scopeDeletionGeneration = fact.scopeDeletionGeneration,
				storedZoneId = fact.storedZoneId,
				structuralEpochDay = fact.structuralEpochDay,
			)
		}.getOrNull()
	}

	private fun LifecycleDesiredActionEntity.authenticates(
		wal: SourceEventWalEntity,
		run: SourceServiceRunEntity,
		manifest: SessionManifestVersionEntity,
		source: SourceKind,
		configurationRevision: Long,
		appliedAtElapsedNanos: Long,
		observedStartNanos: Long,
	): Boolean = hasAcceptedStartStatus(source) &&
		matchesHistoricalStartIntent(wal, run, manifest, configurationRevision) &&
		matchesHistoricalStartRegistration(wal) &&
		acknowledgesHistoricalStartClock(manifest, appliedAtElapsedNanos, observedStartNanos)

	private fun LifecycleDesiredActionEntity.hasAcceptedStartStatus(source: SourceKind): Boolean {
		val actionMatches = actionRevision > 0L && actionFamily == "SOURCE_RUNTIME" &&
			sourceKind == source.stableCode && desiredState == "STARTED"
		val accepted = status == "START_ACCEPTED" && attemptCount > 0 && failureCode == null && retryTrigger == null
		return actionMatches && accepted
	}

	private fun LifecycleDesiredActionEntity.matchesHistoricalStartIntent(
		wal: SourceEventWalEntity,
		run: SourceServiceRunEntity,
		manifest: SessionManifestVersionEntity,
		configurationRevision: Long,
	): Boolean {
		val scopeMatches = logicalTrackingId == wal.logicalTrackingId && serviceRunId == wal.serviceRunId &&
			manifestRevision == wal.sessionManifestRevision
		val planMatches = desiredPlanRevision == configurationRevision &&
			sourcePolicyRevision == wal.sourcePolicyRevision && consentEpoch == wal.captureConsentEpoch
		val originMatches = startOrigin == manifest.startOrigin &&
			(manifest.manifestRevision != run.preparedManifestRevision || startOrigin == run.startOrigin)
		return scopeMatches && planMatches && originMatches
	}

	private fun LifecycleDesiredActionEntity.matchesHistoricalStartRegistration(wal: SourceEventWalEntity): Boolean =
		bootId == wal.clockDomainId && leaseGeneration == wal.lifecycleLeaseGeneration &&
			sourceInstanceId == wal.sourceInstanceId && registrationGeneration == wal.registrationGeneration

	private fun LifecycleDesiredActionEntity.acknowledgesHistoricalStartClock(
		manifest: SessionManifestVersionEntity,
		appliedAtElapsedNanos: Long,
		observedStartNanos: Long,
	): Boolean {
		val acknowledgedAt = acknowledgedAtMs ?: return false
		val acknowledgedElapsed = acknowledgedElapsedRealtimeNanos ?: return false
		val requestedClockMatches = requestedAtMs == manifest.effectiveWallTimeMs && acknowledgedAt >= requestedAtMs &&
			requestedElapsedRealtimeNanos == manifest.effectiveElapsedRealtimeNanos
		val appliedClockMatches = requestedElapsedRealtimeNanos <= appliedAtElapsedNanos &&
			appliedAtElapsedNanos <= acknowledgedElapsed && acknowledgedElapsed <= observedStartNanos
		return requestedClockMatches && appliedClockMatches
	}

	private fun LocationObservationDecision.locationWriterExpectation(): WriterExpectation? {
		val complete = writerProjectionId != null && writerProjectionVersion != null &&
			writerBindingGeneration != null && writerOwnerGeneration != null
		if (!complete) {
			return null
		}
		return if (writerProjectionId == SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID &&
			writerProjectionVersion == SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_VERSION &&
			SourceWriterGenerationContract.captureModeMaskForKnownBinding(requireNotNull(writerBindingGeneration)) != null &&
			writerOwnerGeneration == SourceDestinationOwnerEntity.INITIAL_EXISTING_LOCATION_GENERATION
		) {
			WriterExpectation(SourceDestinationOwnerEntity.SOURCE_LOCATION, requireNotNull(writerOwnerGeneration),
				requireNotNull(writerProjectionId), requireNotNull(writerProjectionVersion),
			requireNotNull(writerBindingGeneration))
		} else {
			null
		}
	}

	private fun SessionManifestSourceEntity
		.matchesFactWriter(fact: StepFactRevisionEntity): Boolean =
		persistenceEligible &&
			consentEpoch == fact.captureConsentEpoch &&
			outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS &&
			writerOwner == SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS &&
			writerOwnerGeneration?.let { it > 0L } == true &&
			writerProjectionId == fact.writerProjectionId &&
			writerProjectionVersion == fact.writerProjectionVersion &&
			writerBindingGeneration == fact.writerBindingGeneration

	private fun SessionManifestSourceEntity
		.matchesFactWriter(fact: PressureFactRevisionEntity): Boolean =
		persistenceEligible &&
			consentEpoch == fact.captureConsentEpoch &&
			outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE &&
			writerOwner == SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS &&
			writerOwnerGeneration?.let { it > 0L } == true &&
			writerProjectionId == fact.writerProjectionId &&
			writerProjectionVersion == fact.writerProjectionVersion &&
			writerBindingGeneration == fact.writerBindingGeneration

	private suspend fun recordingFromWal(
		key: SourceProofKey,
		expectedSource: SourceKind,
		maximumPayloadBytes: Int = Int.MAX_VALUE,
		cellFact: CellCapturedFact? = null,
		wifiFact: WifiCapturedFact? = null,
		block: suspend (
			SourceEventWalEntity,
			Long,
		) -> SourceProofResult<RecordingProofState>,
	): SourceProofResult<RecordingProofState> = database.withTransaction {
		val wal = database.sourceEventWalDao().boundedPayloadByEventId(
			key.evidenceIdentity.value,
			maximumPayloadBytes,
		)
			?: return@withTransaction missingRecordingEvidence(key)
		if (wal.sourceKind != expectedSource.stableCode) {
			return@withTransaction failed(
				SourceProofStage.RECORDING,
				SourceProofReason.WRONG_SOURCE,
			)
		}

		if (!wal.hasQualifiedIntegrity()) {
			return@withTransaction failed(
				SourceProofStage.RECORDING,
				SourceProofReason.INTEGRITY_FAILURE,
			)
		}
		val historicalCell = cellFact?.takeIf {
			expectedSource == SourceKind.CELL && wal.payloadVersion ==
				CELL_WAL_PAYLOAD_VERSION
		}
		val historicalWifi = wifiFact?.takeIf { expectedSource == SourceKind.WIFI && wal.payloadVersion ==
			WIFI_WAL_PAYLOAD_VERSION }
		val lifecycle = database.sourceEvidenceStateDao().get()
			?: return@withTransaction unavailable(
				SourceProofStage.RECORDING,
				SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
			)
		recordingLifecycleFailure(key, wal, lifecycle)?.let { return@withTransaction it }
		if (recordingCrossesRetention(wal, lifecycle.retainedFromMs, historicalCell, historicalWifi)) {
			return@withTransaction deleted(SourceProofStage.RECORDING, SourceProofReason.RETENTION_EXPIRED)
		}
		recordingRadioAuthorityFailure(key, wal, historicalCell, historicalWifi)?.let { return@withTransaction it }
		block(wal, lifecycle.revision)
	}

	private suspend fun recordingLifecycleFailure(
		key: SourceProofKey,
		wal: SourceEventWalEntity,
		lifecycle: SourceEvidenceState,
	): SourceProofResult<RecordingProofState>? {
		if (lifecycle.collectedDataEpoch != key.collectedDataEpoch ||
			wal.capturedCollectedDataEpoch != key.collectedDataEpoch
		) {
			return if (lifecycle.collectedDataEpoch > key.collectedDataEpoch) {
				deleted(SourceProofStage.RECORDING, SourceProofReason.DELETED)
			} else {
				failed(SourceProofStage.RECORDING, SourceProofReason.DATA_EPOCH_MISMATCH)
			}
		}
		if (wal.admissionOrdinal <= lifecycle.deletedSourceEventHighWaterOrdinal || isRunDeleted(key)) {
			return deleted(
				SourceProofStage.RECORDING,
				SourceProofReason.DELETED,
			)
		}
		return pressureRecordingRetentionFailure(key, wal.admissionOrdinal, lifecycle.retainedFromMs)
	}

	private fun recordingCrossesRetention(
		wal: SourceEventWalEntity,
		retainedFromMs: Long?,
		cell: CellCapturedFact?,
		wifi: WifiCapturedFact?,
	): Boolean = retainedFromMs?.let { floor ->
		val earliest = cell?.let { fact ->
			CanonicalRetentionBounds.cellCoverage(requireNotNull(wal.wallTimeMs), requireNotNull(wal.wallTimeUncertaintyMs),
				wal.observedElapsedNanos, fact.coverage.providerIntervalStartElapsedRealtimeNanos)
		} ?: wifi?.let { fact ->
			CanonicalRetentionBounds.wifiCoverage(requireNotNull(wal.wallTimeMs), requireNotNull(wal.wallTimeUncertaintyMs),
				fact.coverage.providerIntervalStartElapsedRealtimeNanos, wal.observedElapsedNanos)
		} ?: wal.earliestPossibleWallTimeMs()
		earliest?.let { it < floor } ?: true
	} == true

	private fun recordingRadioAuthorityFailure(
		key: SourceProofKey,
		wal: SourceEventWalEntity,
		cell: CellCapturedFact?,
		wifi: WifiCapturedFact?,
	): SourceProofResult<RecordingProofState>? {
		val capture = cell?.authority
		val wifiAuthority = wifi?.authority
		return recordingCaptureAuthorityFailure(key,
			originalAuthorityValue(wifiAuthority?.sourcePolicyRevision, capture?.sourcePolicyRevision, wal.sourcePolicyRevision),
			originalAuthorityValue(wifiAuthority?.captureConsentEpoch, capture?.captureConsentEpoch, wal.captureConsentEpoch),
			originalAuthorityValue(wifiAuthority?.logicalTrackingId?.value, capture?.logicalTrackingId?.value,
				wal.logicalTrackingId),
			originalAuthorityValue(wifiAuthority?.serviceRunId?.value, capture?.serviceRunId?.value, wal.serviceRunId),
			requireNotNull(originalAuthorityValue(wifiAuthority?.purposeEligibilityMask, capture?.purposeEligibilityMask,
				wal.authorizationPurposeEligibilityMask)))
	}

	private fun recordingCaptureAuthorityFailure(
		key: SourceProofKey,
		policyRevision: Long?,
		consentEpoch: Long?,
		logicalTrackingId: String?,
		serviceRunId: String?,
		purposeMask: Long,
	): SourceProofResult<RecordingProofState>? {
		val scope = key.scope as SourceProofScope.Run
		return when {
			policyRevision != key.sourcePolicyRevision -> failed(SourceProofStage.RECORDING, SourceProofReason.POLICY_MISMATCH)
			consentEpoch != key.captureConsentEpoch -> failed(SourceProofStage.RECORDING, SourceProofReason.CONSENT_MISMATCH)
			logicalTrackingId != scope.logicalTrackingId || serviceRunId != scope.serviceRunId ->
				failed(SourceProofStage.RECORDING, SourceProofReason.SCOPE_MISMATCH)
			purposeMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L ->
				unavailable(SourceProofStage.RECORDING, SourceProofReason.CONTROL_IS_NOT_CAPTURE_EVIDENCE)
			else -> null
		}
	}

	private suspend fun pressureRecordingRetentionFailure(
		key: SourceProofKey,
		sourceAdmissionOrdinal: Long?,
		retainedFromMs: Long?,
	): SourceProofResult<RecordingProofState>? = when (
		pressureRetentionAuthorityState(key, sourceAdmissionOrdinal, retainedFromMs)) {
		PressureRetentionAuthorityState.EXPIRED -> deleted(SourceProofStage.RECORDING, SourceProofReason.RETENTION_EXPIRED)
		PressureRetentionAuthorityState.INVALID -> failed(SourceProofStage.RECORDING, SourceProofReason.INTEGRITY_FAILURE)
		PressureRetentionAuthorityState.ABSENT -> null
	}

	private fun <T> originalAuthorityValue(wifi: T?, cell: T?, wal: T?): T? = wifi ?: cell ?: wal

	private suspend fun loadActivityWalCarriers(
		evidence: List<
			ActivityCapturedEvidenceEntity>,
	): Map<String, SourceEventWalEntity> {
		val rows = mutableListOf<SourceEventWalEntity>()
		for (eventIds in evidence.map { it.sourceEventId }.distinct()
			.chunked(ACTIVITY_WAL_ID_BATCH_SIZE)
		) {
			rows += database.sourceEventWalDao().boundedPayloadsByEventIds(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
				eventIds = eventIds,
				maximumPayloadBytes = MAX_ACTIVITY_WAL_PAYLOAD_BYTES,
				limit = eventIds.size,
			)
		}
		return rows.associateBy(SourceEventWalEntity::eventId)
	}

	private suspend fun loadPressureWalCarriers(
		revisions: List<PressureFactRevisionEntity>,
	): Map<String, SourceEventWalEntity> {
		val rows = mutableListOf<SourceEventWalEntity>()
		for (eventIds in revisions.map(PressureFactRevisionEntity::sourceEventId).distinct()
			.chunked(PRESSURE_WAL_ID_BATCH_SIZE)
		) {
			rows += database.sourceEventWalDao().boundedPayloadsByEventIds(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				eventIds = eventIds,
				maximumPayloadBytes = MAX_PRESSURE_WAL_PAYLOAD_BYTES,
				limit = eventIds.size,
			)
		}
		return rows.associateBy(SourceEventWalEntity::eventId)
	}

	private fun SourceProofKey.toPressureFactProofExpectation(
		logicalFactId: String? = null,
		semanticRevision: Long? = null,
		sourceAdmissionOrdinal: Long? = null,
	): PressureFactProofExpectation {
		val run = scope as SourceProofScope.Run
		return PressureFactProofExpectation(
			evidenceIdentity = evidenceIdentity.value,
			sourcePolicyRevision = sourcePolicyRevision,
			captureConsentEpoch = captureConsentEpoch,
			collectedDataEpoch = collectedDataEpoch,
			logicalTrackingId = run.logicalTrackingId,
			serviceRunId = run.serviceRunId,
			logicalFactId = logicalFactId,
			semanticRevision = semanticRevision,
			sourceAdmissionOrdinal = sourceAdmissionOrdinal,
		)
	}

	private suspend fun missingRecordingEvidence(
		key: SourceProofKey,
	): SourceProofResult<RecordingProofState> = database.withTransaction {
		val lifecycle = database.sourceEvidenceStateDao().get()
			?: return@withTransaction unavailable(
				SourceProofStage.RECORDING,
				SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
			)
		if (lifecycle.collectedDataEpoch > key.collectedDataEpoch || isRunDeleted(key)) {
			return@withTransaction deleted(
				SourceProofStage.RECORDING,
				SourceProofReason.DELETED,
			)
		}
		pressureRecordingRetentionFailure(key, null, lifecycle.retainedFromMs)?.let { return@withTransaction it }
		missingRadioRecordingResult(key, lifecycle)?.let { return@withTransaction it }
		val retentionProof = database.sourceEventRetentionProofDao().get(
			sourceKind = source.toSourceKind().stableCode,
			eventId = key.evidenceIdentity.value,
		)
		if (retentionProof != null) {
			retainedEventRecordingResult(key, lifecycle, retentionProof)
		} else {
			pending(SourceProofStage.RECORDING, SourceProofReason.EVIDENCE_NOT_FOUND)
		}
	}

	private suspend fun missingRadioRecordingResult(
		key: SourceProofKey,
		lifecycle: SourceEvidenceState,
	): SourceProofResult<RecordingProofState>? {
		if (key.source !in setOf(TrackingSource.WIFI, TrackingSource.CELL)) {
			return null
		}
		val scope = key.scope as SourceProofScope.Run
		val candidates = database.capturedRadioFactRetentionProofDao().eventCandidatesForRun(
			key.source.stableCode, key.evidenceIdentity.value,
			scope.logicalTrackingId, scope.serviceRunId,
			key.sourcePolicyRevision, key.captureConsentEpoch,
		)
		if (candidates.isNotEmpty()) {
			val proof = candidates.singleOrNull()
			val expectation = proof?.originalRadioWriterExpectation(key.source)
			if (proof == null || expectation == null || !proof.authenticatesRequest(
					expectedSourceKind = key.source.stableCode,
					expectedWriterProjectionId = expectation.projectionId,
					expectedWriterProjectionVersion = expectation.projectionVersion,
					expectedWriterBindingGeneration = expectation.bindingGeneration,
					expectedWriterOwnerGeneration = expectation.ownerGeneration,
					expectedLogicalFactId = proof.logicalFactId,
					expectedSemanticRevision = proof.semanticRevision,
					expectedCursorRevision = proof.cursorRevision,
					expectedSourceEventId = key.evidenceIdentity.value,
					expectedSourceAdmissionOrdinal = proof.sourceAdmissionOrdinal,
					expectedSourcePolicyRevision = key.sourcePolicyRevision,
					expectedCaptureConsentEpoch = key.captureConsentEpoch,
					expectedCollectedDataEpoch = key.collectedDataEpoch,
					expectedScopeDeletionGeneration = 0L,
					expectedLogicalRunScopeDigest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
						key.source.stableCode, SourceBrokerPurpose.SESSION_CAPTURE,
						scope.logicalTrackingId, scope.serviceRunId,
					),
					currentRetainedFromMs = lifecycle.retainedFromMs,
				)
			) {
				return failed(SourceProofStage.RECORDING, SourceProofReason.INTEGRITY_FAILURE)
			}
			return deleted(
				SourceProofStage.RECORDING,
				if (proof.sourceAdmissionOrdinal <= lifecycle.deletedSourceEventHighWaterOrdinal) {
					SourceProofReason.DELETED
				} else {
					SourceProofReason.RETENTION_EXPIRED
				},
			)
		}
		return null
	}

	private fun retainedEventRecordingResult(
		key: SourceProofKey,
		lifecycle: SourceEvidenceState,
		retentionProof: SourceEventRetentionProofEntity,
	): SourceProofResult<RecordingProofState> {
		if (!retentionProof.hasValidStoredIntegrity()) {
			return failed(
				SourceProofStage.RECORDING,
				SourceProofReason.INTEGRITY_FAILURE,
			)
		}
		if (retentionProof.sourceAdmissionOrdinal <=
			lifecycle.deletedSourceEventHighWaterOrdinal
		) {
			return deleted(
				SourceProofStage.RECORDING,
				SourceProofReason.DELETED,
			)
		}
		val scope = key.scope as SourceProofScope.Run
		if (retentionProof.authenticates(
				expectedSourceKind = source.toSourceKind().stableCode,
				expectedEventId = key.evidenceIdentity.value,
				expectedPurposeMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
				expectedSourcePolicyRevision = key.sourcePolicyRevision,
				expectedCaptureConsentEpoch = key.captureConsentEpoch,
				expectedCollectedDataEpoch = key.collectedDataEpoch,
				expectedScopeDeletionGeneration = 0L,
				expectedLogicalRunScopeDigest =
					SourceDeletionFenceEntity.logicalServiceRunIdentity(
						sourceKind = source.toSourceKind().stableCode,
						purpose = SourceBrokerPurpose.SESSION_CAPTURE,
						logicalTrackingId = scope.logicalTrackingId,
						serviceRunId = scope.serviceRunId,
					),
			)
		) {
			return deleted(
				SourceProofStage.RECORDING,
				SourceProofReason.RETENTION_EXPIRED,
			)
		}
		return unavailable(
			SourceProofStage.RECORDING,
			SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
		)
	}

	private suspend fun deletedOrMissing(
		key: SourceProofKey,
		recording: RecordingProofState,
		expectation: WriterExpectation,
	): SourceProofResult<MaterializedProofState> {
		return materializationDeletion(key, recording) ?: pendingAfterProjectionFailure(
			expectation,
			recording.lineage.sourceAdmissionOrdinal,
			SourceProofReason.MATERIALIZATION_PENDING,
			recording.lineage,
		)
	}

	private suspend fun materializationDeletion(
		key: SourceProofKey,
		recording: RecordingProofState,
	): SourceProofResult<MaterializedProofState>? {
		if (key.source != source || key.scope !is SourceProofScope.Run) {
			return null
		}
		if (isRunDeleted(key)) {
			return deleted(SourceProofStage.MATERIALIZED, SourceProofReason.DELETED, recording.lineage)
		}
		val lifecycle = database.sourceEvidenceStateDao().get() ?: return null
		return if (lifecycle.collectedDataEpoch > key.collectedDataEpoch ||
			recording.lineage.sourceAdmissionOrdinal <= lifecycle.deletedSourceEventHighWaterOrdinal) {
			deleted(SourceProofStage.MATERIALIZED, SourceProofReason.DELETED, recording.lineage)
		} else {
			retainedMaterializationFailure(key, recording, lifecycle.retainedFromMs)
		}
	}

	private suspend fun retainedMaterializationFailure(
		key: SourceProofKey,
		recording: RecordingProofState,
		retainedFromMs: Long?,
	): SourceProofResult<MaterializedProofState>? {
		locationRetentionFailure(key, recording, retainedFromMs)?.let { return it }
		return when (pressureRetentionAuthorityState(key, recording.lineage.sourceAdmissionOrdinal, retainedFromMs)) {
			PressureRetentionAuthorityState.EXPIRED ->
				deleted(SourceProofStage.MATERIALIZED, SourceProofReason.RETENTION_EXPIRED, recording.lineage)
			PressureRetentionAuthorityState.INVALID ->
				failed(SourceProofStage.MATERIALIZED, SourceProofReason.INTEGRITY_FAILURE, recording.lineage)
			PressureRetentionAuthorityState.ABSENT -> capturedRadioRetentionMaterialization(key, recording, retainedFromMs)
		}
	}

	private suspend fun locationRetentionFailure(
		key: SourceProofKey,
		recording: RecordingProofState,
		retainedFromMs: Long?,
	): SourceProofResult<MaterializedProofState>? {
		if (source != TrackingSource.LOCATION || retainedFromMs == null) {
			return null
		}
		val wal = database.sourceEventWalDao().getByEventId(key.evidenceIdentity.value) ?: return null
		val lowerBound = wal.qualifiedLocationRetentionLowerBound(recording)
		if (lowerBound == null) {
			return failed(SourceProofStage.MATERIALIZED, SourceProofReason.INTEGRITY_FAILURE, recording.lineage)
		}
		return if (lowerBound < retainedFromMs) {
			deleted(SourceProofStage.MATERIALIZED, SourceProofReason.RETENTION_EXPIRED, recording.lineage)
		} else {
			null
		}
	}

	private fun SourceEventWalEntity.qualifiedLocationRetentionLowerBound(
		recording: RecordingProofState,
	): Long? {
		val lowerBound = wallTimeMs?.let { wall ->
			wallTimeUncertaintyMs?.let { uncertainty -> CanonicalRetentionBounds.location(wall, uncertainty) }
		}
		return if (!hasQualifiedIntegrity() || sourceKind != SourceDestinationOwnerEntity.SOURCE_LOCATION ||
			admissionOrdinal != recording.lineage.sourceAdmissionOrdinal || lowerBound == null
		) {
			null
		} else {
			lowerBound
		}
	}

	private fun CapturedRadioFactRetentionProofEntity.originalRadioWriterExpectation(
		source: TrackingSource,
	): WriterExpectation? {
		val original = when (source) {
			TrackingSource.WIFI -> WIFI_WRITER
			TrackingSource.CELL -> CELL_WRITER
			else -> return null
		}
		val headerMatches = sourceKind == original.sourceKind && writerProjectionId == original.projectionId &&
			writerProjectionVersion == original.projectionVersion
		val ownerMatches = SourceWriterGenerationContract.captureModeMaskForKnownBinding(writerBindingGeneration) != null &&
			writerOwnerGeneration == SourceWriterGenerationContract.canonicalOwnerGeneration(writerBindingGeneration)
		if (!headerMatches || !ownerMatches) {
			return null
		}
		return original.copy(bindingGeneration = writerBindingGeneration, ownerGeneration = writerOwnerGeneration)
	}

	@Suppress("ReturnCount") // Keep ordered missing-reference and rejection exits together.
	private suspend fun capturedRadioRetentionMaterialization(
		key: SourceProofKey,
		recording: RecordingProofState,
		currentRetainedFromMs: Long?,
	): SourceProofResult<MaterializedProofState>? {
		if (key.source !in setOf(TrackingSource.WIFI, TrackingSource.CELL)) {
			return null
		}
		val scope = key.scope as SourceProofScope.Run
		val candidates = database.capturedRadioFactRetentionProofDao().eventCandidatesForRun(
			key.source.stableCode,
			key.evidenceIdentity.value,
			scope.logicalTrackingId,
			scope.serviceRunId,
			key.sourcePolicyRevision,
			key.captureConsentEpoch,
		)
		if (candidates.isEmpty()) {
			return null
		}
		val proof = candidates.singleOrNull() ?: return failed(
			SourceProofStage.MATERIALIZED,
			SourceProofReason.INTEGRITY_FAILURE,
			recording.lineage,
		)
		val expectation = proof.originalRadioWriterExpectation(key.source) ?: return failed(
			SourceProofStage.MATERIALIZED, SourceProofReason.INTEGRITY_FAILURE, recording.lineage)
		return capturedRadioRetentionResult(key, recording, currentRetainedFromMs, proof, expectation)
	}

	private fun capturedRadioRetentionResult(
		key: SourceProofKey,
		recording: RecordingProofState,
		currentRetainedFromMs: Long?,
		proof: CapturedRadioFactRetentionProofEntity,
		expectation: WriterExpectation,
	): SourceProofResult<MaterializedProofState> {
		val scope = key.scope as SourceProofScope.Run
		return if (proof.authenticatesRequest(
				expectedSourceKind = key.source.stableCode,
				expectedWriterProjectionId = expectation.projectionId,
				expectedWriterProjectionVersion = expectation.projectionVersion,
				expectedWriterBindingGeneration = expectation.bindingGeneration,
				expectedWriterOwnerGeneration = expectation.ownerGeneration,
				expectedLogicalFactId = proof.logicalFactId,
				expectedSemanticRevision = proof.semanticRevision,
				expectedCursorRevision = proof.cursorRevision,
				expectedSourceEventId = key.evidenceIdentity.value,
				expectedSourceAdmissionOrdinal = recording.lineage.sourceAdmissionOrdinal,
				expectedSourcePolicyRevision = key.sourcePolicyRevision,
				expectedCaptureConsentEpoch = key.captureConsentEpoch,
				expectedCollectedDataEpoch = key.collectedDataEpoch,
				expectedScopeDeletionGeneration = 0L,
				expectedLogicalRunScopeDigest =
					SourceDeletionFenceEntity.logicalServiceRunIdentity(
						key.source.stableCode,
						SourceBrokerPurpose.SESSION_CAPTURE,
						scope.logicalTrackingId,
						scope.serviceRunId,
					),
				currentRetainedFromMs = currentRetainedFromMs,
			)
		) {
			deleted(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.RETENTION_EXPIRED,
				recording.lineage,
			)
		} else {
			failed(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.INTEGRITY_FAILURE,
				recording.lineage,
			)
		}
	}

	private suspend fun materializationRetention(
		key: SourceProofKey,
		recording: RecordingProofState,
		crossesFloor: (Long) -> Boolean?,
	): SourceProofResult<MaterializedProofState>? {
		val lifecycle = database.sourceEvidenceStateDao().get()
			?: return unavailable(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
				recording.lineage,
			)
		if (lifecycle.collectedDataEpoch != key.collectedDataEpoch) {
			return if (lifecycle.collectedDataEpoch > key.collectedDataEpoch) {
				deleted(
					SourceProofStage.MATERIALIZED,
					SourceProofReason.DELETED,
					recording.lineage,
				)
			} else {
				failed(
					SourceProofStage.MATERIALIZED,
					SourceProofReason.DATA_EPOCH_MISMATCH,
					recording.lineage,
				)
			}
		}
		val floor = lifecycle.retainedFromMs ?: return null
		return when (crossesFloor(floor)) {
			true -> deleted(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.RETENTION_EXPIRED,
				recording.lineage,
			)
			false -> null
			null -> failed(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.INTEGRITY_FAILURE,
				recording.lineage,
			)
		}
	}

	private suspend fun pressureRetentionAuthorityState(
		key: SourceProofKey,
		sourceAdmissionOrdinal: Long?,
		retainedFromMs: Long?,
	): PressureRetentionAuthorityState {
		if (key.source != TrackingSource.PRESSURE || key.scope !is SourceProofScope.Run) {
			return PressureRetentionAuthorityState.ABSENT
		}
		val scope = key.scope as SourceProofScope.Run
		return when (database.resolvePressureRetentionByEvidence(
			writerProjectionId = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
			writerProjectionVersion =
				SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
			sourceEventId = key.evidenceIdentity.value,
			sourceAdmissionOrdinal = sourceAdmissionOrdinal,
			sourcePolicyRevision = key.sourcePolicyRevision,
			captureConsentEpoch = key.captureConsentEpoch,
			collectedDataEpoch = key.collectedDataEpoch,
			logicalTrackingId = scope.logicalTrackingId,
			serviceRunId = scope.serviceRunId,
			retainedFromMs = retainedFromMs,
		)) {
			is PressureFactRetentionResolution.Authenticated ->
				PressureRetentionAuthorityState.EXPIRED
			PressureFactRetentionResolution.Invalid -> PressureRetentionAuthorityState.INVALID
			PressureFactRetentionResolution.Absent -> PressureRetentionAuthorityState.ABSENT
		}
	}

	private fun recordingState(
		key: SourceProofKey,
		wal: SourceEventWalEntity,
		evidenceRevision: Long,
		coverage: SourceProofCoverage,
		completeness: SourceProofCompleteness,
	): SourceProofResult.Proven<RecordingProofState> =
		SourceProofResult.Proven(
			RecordingProofState(
				lineage = SourceProofLineage(
					evidenceIdentity = key.evidenceIdentity,
					sourceAdmissionOrdinal = wal.admissionOrdinal,
					sourceEvidenceRevision = evidenceRevision,
				),
				coverage = coverage,
				completeness = completeness,
			),
		)

	private fun provenMaterialized(
		recording: RecordingProofState,
		authority: MaterializationAuthority.Ready,
		factIdentity: SourceFactIdentity,
		semanticRevision: Long,
		completeness: SourceProofCompleteness,
		coverage: SourceProofCoverage = recording.coverage,
		selectedEvidenceLineage: SourceProofSelectedEvidenceLineage? = null,
	): SourceProofResult.Proven<MaterializedProofState> =
		SourceProofResult.Proven(
			MaterializedProofState(
				lineage = recording.lineage.copy(
					sourceEvidenceRevision = authority.evidenceRevision,
					factIdentity = factIdentity,
					semanticRevision = semanticRevision,
					writer = authority.writer,
					cursorRevision = authority.cursorRevision,
					selectedEvidenceLineage = selectedEvidenceLineage,
				),
				coverage = coverage,
				completeness = completeness,
			),
		)

	private inline fun <reified T> decodePayload(
		wal: SourceEventWalEntity,
		source: SourceKind,
	): T? = runCatching {
		payloadCodec.decode(source, wal.payloadVersion, wal.payload) as? T
	}.getOrNull()

	private fun SourceEventWalEntity.providerCoverage(): SourceProofCoverage =
		providerCoverage(
			observedIntervalStartNanos ?: observedElapsedNanos,
			observedElapsedNanos,
		)

	private fun SourceEventWalEntity.earliestPossibleWallTimeMs(): Long? {
		val wall = wallTimeMs ?: return null
		val uncertainty = wallTimeUncertaintyMs ?: return null
		return CanonicalRetentionBounds.walPoint(wall, uncertainty)
	}

	private fun ActivityCapturedWindowRevisionEntity
		.authorityMismatch(key: SourceProofKey): SourceProofReason? = authorityMismatch(
			key,
			sourcePolicyRevision,
			captureConsentEpoch,
			collectedDataEpoch,
			logicalTrackingId,
			serviceRunId,
		)

	private fun StepFactRevisionEntity.authorityMismatch(
		key: SourceProofKey,
	): SourceProofReason? = authorityMismatch(
		key,
		sourcePolicyRevision,
		captureConsentEpoch,
		collectedDataEpoch,
		logicalTrackingId,
		serviceRunId,
	)

	private fun PressureFactRevisionEntity.authorityMismatch(
		key: SourceProofKey,
	): SourceProofReason? = authorityMismatch(
		key,
		sourcePolicyRevision,
		captureConsentEpoch,
		collectedDataEpoch,
		logicalTrackingId,
		serviceRunId,
	)

	private fun WifiCapturedFactRevisionEntity.authorityMismatch(
		key: SourceProofKey,
	): SourceProofReason? =
		if (sourceEventId != key.evidenceIdentity.value) {
			SourceProofReason.FACT_IDENTITY_MISMATCH
		} else {
			authorityMismatch(
				key,
				sourcePolicyRevision,
				captureConsentEpoch,
				collectedDataEpoch,
				logicalTrackingId,
				serviceRunId,
			)
		}

	private fun CellCapturedFactRevisionEntity.authorityMismatch(
		key: SourceProofKey,
	): SourceProofReason? =
		if (sourceEventId != key.evidenceIdentity.value) {
			SourceProofReason.FACT_IDENTITY_MISMATCH
		} else {
			authorityMismatch(
				key,
				sourcePolicyRevision,
				captureConsentEpoch,
				collectedDataEpoch,
				logicalTrackingId,
				serviceRunId,
			)
		}

	private fun authorityMismatch(
		key: SourceProofKey,
		sourcePolicyRevision: Long?,
		captureConsentEpoch: Long?,
		collectedDataEpoch: Long,
		logicalTrackingId: String?,
		serviceRunId: String?,
	): SourceProofReason? = when {
		sourcePolicyRevision != key.sourcePolicyRevision -> SourceProofReason.POLICY_MISMATCH
		captureConsentEpoch != key.captureConsentEpoch -> SourceProofReason.CONSENT_MISMATCH
		collectedDataEpoch != key.collectedDataEpoch -> SourceProofReason.DATA_EPOCH_MISMATCH
		!matchesRunScope(key, logicalTrackingId, serviceRunId) -> SourceProofReason.SCOPE_MISMATCH
		else -> null
	}

	private fun matchesRunScope(
		key: SourceProofKey,
		logicalTrackingId: String?,
		serviceRunId: String?,
	): Boolean {
		val scope = key.scope as? SourceProofScope.Run ?: return false
		return logicalTrackingId == scope.logicalTrackingId && serviceRunId == scope.serviceRunId
	}

	private fun String.toCompleteness(): SourceProofCompleteness = when (this) {
		"COMPLETE" -> SourceProofCompleteness.COMPLETE
		"PARTIAL", "NONE" -> SourceProofCompleteness.PARTIAL
		else -> SourceProofCompleteness.UNKNOWN
	}

	private fun LocationWalAdapterRejection.toRecordingResult():
		SourceProofResult<RecordingProofState> = when (this) {
		LocationWalAdapterRejection.MISSING_EVENT ->
			pending(SourceProofStage.RECORDING, SourceProofReason.EVIDENCE_NOT_FOUND)
		LocationWalAdapterRejection.WRONG_SOURCE ->
			failed(SourceProofStage.RECORDING, SourceProofReason.WRONG_SOURCE)
		LocationWalAdapterRejection.DELETED_EVIDENCE ->
			deleted(SourceProofStage.RECORDING, SourceProofReason.DELETED)
		LocationWalAdapterRejection.RETAINED_EVIDENCE ->
			deleted(SourceProofStage.RECORDING, SourceProofReason.RETENTION_EXPIRED)
		LocationWalAdapterRejection.POLICY_MISMATCH ->
			failed(SourceProofStage.RECORDING, SourceProofReason.POLICY_MISMATCH)
		LocationWalAdapterRejection.CONSENT_MISMATCH ->
			failed(SourceProofStage.RECORDING, SourceProofReason.CONSENT_MISMATCH)
		LocationWalAdapterRejection.SESSION_MISMATCH,
		LocationWalAdapterRejection.SEGMENT_MISMATCH,
		-> failed(SourceProofStage.RECORDING, SourceProofReason.SCOPE_MISMATCH)
		LocationWalAdapterRejection.MISSING_POLICY,
		LocationWalAdapterRejection.MISSING_CONSENT,
		LocationWalAdapterRejection.MISSING_SESSION,
		LocationWalAdapterRejection.MISSING_SEGMENT,
		LocationWalAdapterRejection.MISSING_REGISTRATION,
		LocationWalAdapterRejection.MISSING_AUTHORIZATION,
		LocationWalAdapterRejection.MISSING_HISTORICAL_PLAN,
		-> unavailable(SourceProofStage.RECORDING, SourceProofReason.IMMUTABLE_AUTHORITY_MISSING)
		else -> failed(SourceProofStage.RECORDING, SourceProofReason.MALFORMED_EVIDENCE)
	}

	private fun ActivityCapturedWalAdmissionRejection.toRecordingResult():
		SourceProofResult<RecordingProofState> = when (this) {
		ActivityCapturedWalAdmissionRejection.MISSING_EVENT ->
			pending(SourceProofStage.RECORDING, SourceProofReason.EVIDENCE_NOT_FOUND)
		ActivityCapturedWalAdmissionRejection.WRONG_SOURCE ->
			failed(SourceProofStage.RECORDING, SourceProofReason.WRONG_SOURCE)
		ActivityCapturedWalAdmissionRejection.CONTROL_ONLY ->
			unavailable(
				SourceProofStage.RECORDING,
				SourceProofReason.CONTROL_IS_NOT_CAPTURE_EVIDENCE,
			)
		ActivityCapturedWalAdmissionRejection.DELETED_EVIDENCE ->
			deleted(SourceProofStage.RECORDING, SourceProofReason.DELETED)
		ActivityCapturedWalAdmissionRejection.RETAINED_EVIDENCE ->
			deleted(SourceProofStage.RECORDING, SourceProofReason.RETENTION_EXPIRED)
		ActivityCapturedWalAdmissionRejection.POLICY_MISMATCH ->
			failed(SourceProofStage.RECORDING, SourceProofReason.POLICY_MISMATCH)
		ActivityCapturedWalAdmissionRejection.CONSENT_MISMATCH ->
			failed(SourceProofStage.RECORDING, SourceProofReason.CONSENT_MISMATCH)
		ActivityCapturedWalAdmissionRejection.SESSION_MISMATCH,
		ActivityCapturedWalAdmissionRejection.SEGMENT_MISMATCH,
		-> failed(SourceProofStage.RECORDING, SourceProofReason.SCOPE_MISMATCH)
		else -> failed(SourceProofStage.RECORDING, SourceProofReason.MALFORMED_EVIDENCE)
	}

	private fun WifiWalAdapterRejection.toRecordingResult():
		SourceProofResult<RecordingProofState> = when (this) {
		WifiWalAdapterRejection.MISSING_EVENT ->
			pending(SourceProofStage.RECORDING, SourceProofReason.EVIDENCE_NOT_FOUND)
		WifiWalAdapterRejection.WRONG_SOURCE ->
			failed(SourceProofStage.RECORDING, SourceProofReason.WRONG_SOURCE)
		WifiWalAdapterRejection.DELETED_EVIDENCE,
		WifiWalAdapterRejection.DELETED_SCOPE,
		-> deleted(SourceProofStage.RECORDING, SourceProofReason.DELETED)
		WifiWalAdapterRejection.BEFORE_RETENTION_FLOOR ->
			deleted(SourceProofStage.RECORDING, SourceProofReason.RETENTION_EXPIRED)
		WifiWalAdapterRejection.POLICY_MISMATCH ->
			failed(SourceProofStage.RECORDING, SourceProofReason.POLICY_MISMATCH)
		WifiWalAdapterRejection.CONSENT_MISMATCH ->
			failed(SourceProofStage.RECORDING, SourceProofReason.CONSENT_MISMATCH)
		WifiWalAdapterRejection.SESSION_MISMATCH,
		WifiWalAdapterRejection.SEGMENT_MISMATCH,
		-> failed(SourceProofStage.RECORDING, SourceProofReason.SCOPE_MISMATCH)
		else -> failed(SourceProofStage.RECORDING, SourceProofReason.MALFORMED_EVIDENCE)
	}

	private fun CellWalAdapterRejection.toRecordingResult():
		SourceProofResult<RecordingProofState> = when (this) {
		CellWalAdapterRejection.MISSING_EVENT ->
			pending(SourceProofStage.RECORDING, SourceProofReason.EVIDENCE_NOT_FOUND)
		CellWalAdapterRejection.WRONG_SOURCE ->
			failed(SourceProofStage.RECORDING, SourceProofReason.WRONG_SOURCE)
		CellWalAdapterRejection.DELETED_EVIDENCE,
		CellWalAdapterRejection.DELETED_SCOPE,
		-> deleted(SourceProofStage.RECORDING, SourceProofReason.DELETED)
		CellWalAdapterRejection.BEFORE_RETENTION_FLOOR ->
			deleted(SourceProofStage.RECORDING, SourceProofReason.RETENTION_EXPIRED)
		CellWalAdapterRejection.POLICY_MISMATCH ->
			failed(SourceProofStage.RECORDING, SourceProofReason.POLICY_MISMATCH)
		CellWalAdapterRejection.CONSENT_MISMATCH ->
			failed(SourceProofStage.RECORDING, SourceProofReason.CONSENT_MISMATCH)
		CellWalAdapterRejection.SESSION_MISMATCH,
		CellWalAdapterRejection.SEGMENT_MISMATCH,
		-> failed(SourceProofStage.RECORDING, SourceProofReason.SCOPE_MISMATCH)
		else -> failed(SourceProofStage.RECORDING, SourceProofReason.MALFORMED_EVIDENCE)
	}

	private fun TrackingSource.toSourceKind(): SourceKind = when (this) {
		TrackingSource.LOCATION -> SourceKind.LOCATION
		TrackingSource.ACTIVITY -> SourceKind.ACTIVITY
		TrackingSource.STEPS -> SourceKind.STEPS
		TrackingSource.PRESSURE -> SourceKind.PRESSURE
		TrackingSource.WIFI -> SourceKind.WIFI
		TrackingSource.CELL -> SourceKind.CELL
	}

	private companion object {
		const val MAX_ACTIVITY_REVISIONS = 4_096
		const val MAX_ACTIVITY_CHILD_ROWS = 4_096
		const val ACTIVITY_WAL_ID_BATCH_SIZE = 400
		const val PRESSURE_WAL_ID_BATCH_SIZE = 400
		const val MAX_PRESSURE_PROOF_REVISIONS = 8_192
		const val MAX_AUTHORIZATION_MEMBERS = 64
		const val MAX_MANIFESTS_PER_RUN = 64
		const val MAX_MANIFEST_SOURCE_ROWS = MAX_MANIFESTS_PER_RUN * 12
		const val MAX_PLAN_APPLICATION_ROWS = 1
		const val MAX_RADIO_REVISION_CHAIN = 64

		val LOCATION_WRITER = WriterExpectation(
			SourceDestinationOwnerEntity.SOURCE_LOCATION,
			SourceDestinationOwnerEntity.INITIAL_EXISTING_LOCATION_GENERATION,
			SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID,
			SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_VERSION,
			SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_BINDING_GENERATION,
		)
		val ACTIVITY_WRITER = WriterExpectation(
			SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
			SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
			SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION,
			SourceDestinationOwnerEntity.ACTIVITY_FACT_BINDING_GENERATION,
		)
		val STEPS_WRITER = WriterExpectation(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
			SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
			SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION,
		)
		val PRESSURE_WRITER = WriterExpectation(
			SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
			SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
			SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION,
		)
		val WIFI_WRITER = WriterExpectation(
			SourceDestinationOwnerEntity.SOURCE_WIFI,
			SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION,
			SourceDestinationOwnerEntity.WIFI_FACT_BINDING_GENERATION,
		)
		val CELL_WRITER = WriterExpectation(
			SourceDestinationOwnerEntity.SOURCE_CELL,
			SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
			SourceDestinationOwnerEntity.CELL_FACT_BINDING_GENERATION,
		)
	}
}

private data class LocationMaterializationRows(
	val observation: LocationObservation,
	val decision: LocationObservationDecision,
	val receipt: SourceProjectionJoinStateEntity,
	val samples: List<LocationSample>,
)

private data class WifiAggregateProof(
	val aggregate: WifiCapturedFactRevisionEntity,
	val ownerCursor: WifiCapturedFactCursorEntity?,
	val dependentCarrier: WifiWalCarrierAuthentication.Authenticated,
	val aggregateCarrier: WifiWalCarrierAuthentication.Authenticated,
)

private data class CellAggregateProof(
	val aggregate: CellCapturedFactRevisionEntity,
	val ownerCursor: CellCapturedFactCursorEntity?,
	val dependentCarrier: CellWalCarrierAuthentication.Authenticated,
	val aggregateCarrier: CellWalCarrierAuthentication.Authenticated,
)

private data class ActivityMaterializationRows(
	val lineage: List<ActivityCapturedWindowRevisionEntity>,
	val fragmentsByRevision: Map<Long, List<ActivityCapturedFragmentEntity>>,
	val evidenceByRevision: Map<Long, List<ActivityCapturedEvidenceEntity>>,
)

private data class ActivityCarrierProof(
	val fragments: List<ActivityCapturedFragmentEntity>,
	val selectedEvidence: ActivityCapturedEvidenceEntity,
)

private data class WriterExpectation(
	val sourceKind: Int,
	val ownerGeneration: Long,
	val projectionId: String,
	val projectionVersion: Int,
	val bindingGeneration: Long,
)

private fun StepsWalCarrierAuthentication.Rejected.toRecordingResult():
	SourceProofResult<RecordingProofState> = when (reason) {
		StepsWalCarrierFailure.CONTROL_ONLY -> unavailable(
			SourceProofStage.RECORDING,
			SourceProofReason.CONTROL_IS_NOT_CAPTURE_EVIDENCE,
		)
		StepsWalCarrierFailure.COUNT_DOMAIN_UNPROVEN -> unavailable(
			SourceProofStage.RECORDING,
			SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
		)
		StepsWalCarrierFailure.UNSUPPORTED_PAYLOAD_VERSION -> failed(
			SourceProofStage.RECORDING,
			SourceProofReason.UNSUPPORTED_PAYLOAD,
		)
		else -> failed(SourceProofStage.RECORDING, SourceProofReason.INTEGRITY_FAILURE)
	}

private fun PressureWalCarrierAuthentication.Rejected.toRecordingResult():
	SourceProofResult<RecordingProofState> = when (reason) {
		PressureWalCarrierFailure.UNSUPPORTED_PAYLOAD_VERSION -> failed(
			SourceProofStage.RECORDING,
			SourceProofReason.UNSUPPORTED_PAYLOAD,
		)
		PressureWalCarrierFailure.QUALIFICATION_MISMATCH -> failed(
			SourceProofStage.RECORDING,
			SourceProofReason.WINDOW_INCOMPLETE,
		)
		else -> failed(SourceProofStage.RECORDING, SourceProofReason.INTEGRITY_FAILURE)
	}

internal fun PressureWindowPayload.recordingCompletenessOrNull(
	wal: SourceEventWalEntity,
): SourceProofCompleteness? = runCatching {
	requirePressureTimingAndSequence(wal)
	requirePressureSummary()
	requirePressureAcquisition()
	requirePressureWindowShape()
	val qualification = PressureWindowQualification.classify(this)
	val incomplete = wal.qualityFlags and SourceQualityFlag.INCOMPLETE_WINDOW.bit != 0L
	require(incomplete == (qualification == PressureWindowQualification.PARTIAL))
	if (qualification == PressureWindowQualification.COMPLETE) {
		SourceProofCompleteness.COMPLETE
	} else {
		SourceProofCompleteness.PARTIAL
	}
}.getOrNull()

private fun PressureWindowPayload.requirePressureTimingAndSequence(wal: SourceEventWalEntity) {
	require(wal.payloadVersion == PressureFactRevisionEntity.QUALIFIED_PRESSURE_PAYLOAD_VERSION)
	require(windowStartElapsedRealtimeNanos >= 0L)
	require(windowEndElapsedRealtimeNanos >= windowStartElapsedRealtimeNanos)
	require(windowEndElapsedRealtimeNanos == wal.observedElapsedNanos)
	require(sampleCount > 0)
	require(firstProviderSequence > 0L)
	require(lastProviderSequence - firstProviderSequence == sampleCount.toLong() - 1L)
	require(lastProviderSequence == wal.sourceSequence)
}

private fun PressureWindowPayload.requirePressureSummary() {
	require(meanHectopascals.isFinite() && meanHectopascals > 0.0)
	require(sumSquaredDeviations.isFinite() && sumSquaredDeviations >= 0.0)
	require(minimumHectopascals.isFinite() && minimumHectopascals > 0f)
	require(maximumHectopascals.isFinite() && maximumHectopascals >= minimumHectopascals)
	require(meanHectopascals in minimumHectopascals.toDouble()..maximumHectopascals.toDouble())
	require(firstHectopascals?.isFinite() == true)
	require(lastHectopascals?.isFinite() == true)
	require(requireNotNull(firstHectopascals) in minimumHectopascals..maximumHectopascals)
	require(requireNotNull(lastHectopascals) in minimumHectopascals..maximumHectopascals)
}

private fun PressureWindowPayload.requirePressureAcquisition() {
	require(effectiveSamplePeriodMicros != null && effectiveSamplePeriodMicros > 0)
	require(effectiveMaximumReportLatencyMicros != null &&
		effectiveMaximumReportLatencyMicros >= 0)
	require(targetWindowDurationNanos != null && targetWindowDurationNanos > 0L)
	require(expectedSampleCount != null && expectedSampleCount > 0)
	require(
		expectedSampleCount == expectedPressureSampleCount(
			requireNotNull(targetWindowDurationNanos),
			requireNotNull(effectiveSamplePeriodMicros),
		),
	)
	require(maximumInterSampleGapNanos != null && maximumInterSampleGapNanos >= 0L)
	require(sensorAccuracy != PressureSensorAccuracy.LEGACY_UNAVAILABLE)
	require(closureKind != PressureWindowClosureKind.LEGACY_UNAVAILABLE)
}

private fun PressureWindowPayload.requirePressureWindowShape() {
	if (sampleCount == 1) {
		require(firstHectopascals == lastHectopascals)
		require(firstHectopascals == minimumHectopascals)
		require(lastHectopascals == maximumHectopascals)
		require(meanHectopascals == firstHectopascals?.toDouble())
		require(sumSquaredDeviations == 0.0)
		require(windowEndElapsedRealtimeNanos == windowStartElapsedRealtimeNanos)
		require(maximumInterSampleGapNanos == 0L)
		require(slopeHectopascalsPerSecond == null && rSquared == null)
	} else {
		require(windowEndElapsedRealtimeNanos > windowStartElapsedRealtimeNanos)
		require(requireNotNull(maximumInterSampleGapNanos) > 0L)
		require(slopeHectopascalsPerSecond?.isFinite() == true)
		require(rSquared == null || rSquared?.let { it.isFinite() && it in 0.0..1.0 } == true)
	}
}

internal sealed interface CurrentRevisionResolution<out T> {
	data object Missing : CurrentRevisionResolution<Nothing>
	data object Invalid : CurrentRevisionResolution<Nothing>
	data class Ready<T>(
		val current: T,
		val retainedLineage: List<T>,
	) : CurrentRevisionResolution<T>
}

internal fun <T> resolveCurrentRevision(
	admissionRevisions: List<T>,
	currentRevisions: List<T>,
	retainedLineage: List<T>,
	maximumRevisionCount: Int,
	logicalFactId: (T) -> String,
	semanticRevision: (T) -> Long,
	lineageAuthenticates: (List<T>) -> Boolean,
): CurrentRevisionResolution<T> {
	require(maximumRevisionCount > 0)
	if (admissionRevisions.isEmpty()) {
		return CurrentRevisionResolution.Missing
	}
	if (admissionRevisions.size > maximumRevisionCount || currentRevisions.size != 1) {
		return CurrentRevisionResolution.Invalid
	}
	val current = currentRevisions.single()
	if (admissionRevisions.any { logicalFactId(it) != logicalFactId(current) }) {
		return CurrentRevisionResolution.Invalid
	}
	val orderedAdmission = admissionRevisions.sortedBy(semanticRevision)
	val orderedLineage = retainedLineage.sortedBy(semanticRevision)
	return if (orderedLineage.size > maximumRevisionCount ||
		orderedLineage != orderedAdmission ||
		!lineageAuthenticates(orderedLineage) ||
		orderedLineage.lastOrNull() != current
	) {
		CurrentRevisionResolution.Invalid
	} else {
		CurrentRevisionResolution.Ready(current, orderedLineage)
	}
}

private enum class PressureRetentionAuthorityState {
	ABSENT,
	EXPIRED,
	INVALID,
}

private sealed interface MaterializationAuthority {
	data class Ready(
		val evidenceRevision: Long,
		val cursorRevision: Long,
		val writer: SourceProofWriterIdentity,
	) : MaterializationAuthority

	data class Blocked(
		val proofResult: SourceProofResult<MaterializedProofState>,
	) : MaterializationAuthority

	fun result(lineage: SourceProofLineage): SourceProofResult<MaterializedProofState> =
		when (val blocked = (this as Blocked).proofResult) {
			is SourceProofResult.Pending -> blocked.copy(lineage = lineage)
			is SourceProofResult.Unavailable -> blocked.copy(lineage = lineage)
			is SourceProofResult.Failed -> blocked.copy(lineage = lineage)
			is SourceProofResult.Deleted -> blocked.copy(lineage = lineage)
			is SourceProofResult.Proven -> error("Blocked materialization cannot be proven")
		}
}

internal fun canonicalLocationRejectionResult(
		storedReason: String?,
		lineage: SourceProofLineage,
): SourceProofResult<MaterializedProofState> {
		val reason = when {
			storedReason.isNullOrBlank() -> SourceProofReason.INTEGRITY_FAILURE
			storedReason.contains("ACCURACY", ignoreCase = true) ->
				SourceProofReason.ACCURACY_UNQUALIFIED
			storedReason.contains("STALE", ignoreCase = true) ->
				SourceProofReason.STALE
			storedReason.contains("PERMISSION", ignoreCase = true) ->
				SourceProofReason.PERMISSION_UNAVAILABLE
			storedReason.contains("PROVIDER", ignoreCase = true) ->
				SourceProofReason.PROVIDER_FAILURE
			else -> SourceProofReason.MALFORMED_EVIDENCE
		}
		return if (reason in setOf(
				SourceProofReason.ACCURACY_UNQUALIFIED,
				SourceProofReason.STALE,
				SourceProofReason.PERMISSION_UNAVAILABLE,
				SourceProofReason.PROVIDER_FAILURE,
			)
		) {
			unavailable(SourceProofStage.MATERIALIZED, reason, lineage)
		} else {
			failed(SourceProofStage.MATERIALIZED, reason, lineage)
		}
}

internal fun SourceProjectionFailureEntity.toLifecycleMaterializationResult(
		lineage: SourceProofLineage?,
): SourceProofResult<MaterializedProofState> = if (terminal) {
		failed(
			SourceProofStage.MATERIALIZED,
			SourceProofReason.PROJECTION_TERMINAL_FAILURE,
			lineage,
		)
} else {
		pending(
			SourceProofStage.MATERIALIZED,
			SourceProofReason.PROJECTION_RETRYABLE_FAILURE,
			lineage,
		)
}

internal fun SourceProductProjectionLaneEntity.materializationAdmissionFailure(
	requiredAdmissionOrdinals: List<Long>,
): SourceProofReason? {
	if (requiredAdmissionOrdinals.isEmpty() ||
		!hasValidCanonicalHistoryShape() ||
		requiredAdmissionOrdinals.any { admissionOrdinal ->
			admissionOrdinal < activationOrdinal ||
				captureAdmissionCutoffOrdinal?.let { admissionOrdinal > it } == true
		}
	) {
		return SourceProofReason.MATERIALIZATION_PENDING
	}
	if (requiredAdmissionOrdinals.any { it > contiguousAdmissionOrdinal }) {
		return SourceProofReason.CURSOR_BEHIND
	}
	return null
}

internal fun PressureFactLineageProof.Authenticated.selectedPressureProofCoverage():
	SourceProofCoverage = providerCoverage(
	selected.windowStartElapsedRealtimeNanos,
	selected.windowEndElapsedRealtimeNanos,
)

internal fun PressureFactLineageProof.Authenticated.selectedPressureProofCompleteness():
	SourceProofCompleteness = when (selected.qualification) {
		PressureFactRevisionEntity.QUALIFICATION_COMPLETE -> SourceProofCompleteness.COMPLETE
		PressureFactRevisionEntity.QUALIFICATION_PARTIAL -> SourceProofCompleteness.PARTIAL
		else -> SourceProofCompleteness.UNKNOWN
	}

private fun providerCoverage(startNanos: Long, endNanos: Long): SourceProofCoverage =
	if (endNanos > startNanos) {
		SourceProofCoverage.ProviderInterval(startNanos, endNanos)
	} else {
		SourceProofCoverage.ProviderPoint(startNanos)
	}

private fun pending(
	stage: SourceProofStage,
	reason: SourceProofReason,
	lineage: SourceProofLineage? = null,
) = SourceProofResult.Pending(stage, reason, lineage)

private fun unavailable(
	stage: SourceProofStage,
	reason: SourceProofReason,
	lineage: SourceProofLineage? = null,
) = SourceProofResult.Unavailable(stage, reason, lineage)

private fun failed(
	stage: SourceProofStage,
	reason: SourceProofReason,
	lineage: SourceProofLineage? = null,
) = SourceProofResult.Failed(stage, reason, lineage)

private fun deleted(
	stage: SourceProofStage,
	reason: SourceProofReason,
	lineage: SourceProofLineage? = null,
) = SourceProofResult.Deleted(stage, reason, lineage)
