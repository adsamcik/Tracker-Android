package com.adsamcik.tracker.tracker.source.lifecycle

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.PressureFactLineageProof
import com.adsamcik.tracker.shared.base.database.PressureFactProofAuthenticator
import com.adsamcik.tracker.shared.base.database.PressureFactProofExpectation
import com.adsamcik.tracker.shared.base.database.PressureFactProofFailure
import com.adsamcik.tracker.shared.base.database.PressureFactRetentionResolution
import com.adsamcik.tracker.shared.base.database.PressureWalCaptureProof
import com.adsamcik.tracker.shared.base.database.ProviderRegistrationIntervalAuthentication
import com.adsamcik.tracker.shared.base.database.ProviderRegistrationIntervalFailure
import com.adsamcik.tracker.shared.base.database.ProviderRegistrationActualObservation
import com.adsamcik.tracker.shared.base.database.ProviderRegistrationFactWindow
import com.adsamcik.tracker.shared.base.database.authenticateHistoricalProviderFactWindow
import com.adsamcik.tracker.shared.base.database.authenticateHistoricalProviderRegistration
import com.adsamcik.tracker.shared.base.database.loadPressureFactProofSnapshot
import com.adsamcik.tracker.shared.base.database.resolvePressureRetentionByEvidence
import com.adsamcik.tracker.shared.base.database.StepsCountDomainOwnerLookupKey
import com.adsamcik.tracker.shared.base.database.StepsCountDomainOwnerRead
import com.adsamcik.tracker.shared.base.database.StepsCountDomainStore
import com.adsamcik.tracker.shared.base.database.data.CanonicalRetentionBounds
import com.adsamcik.tracker.shared.base.database.data.ActivityWalCarrierAuthenticator
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.CellAggregateReuseAuthority
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.CellWalCarrierAuthenticator
import com.adsamcik.tracker.shared.base.database.data.CellWalCarrierAuthentication
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.LocationObservationDecision
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
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptIntegrity
import com.adsamcik.tracker.shared.base.database.data.StepsWalCarrierAuthentication
import com.adsamcik.tracker.shared.base.database.data.StepsWalCarrierAuthenticator
import com.adsamcik.tracker.shared.base.database.data.StepsWalCarrierFailure
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.WifiWalCarrierAuthentication
import com.adsamcik.tracker.shared.base.database.data.WifiWalCarrierAuthenticator
import com.adsamcik.tracker.shared.base.database.data.hasValidCanonicalHistoryShape
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.stats.api.repository.MaterializedProofState
import com.adsamcik.tracker.stats.api.repository.RecordingProofState
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
import com.adsamcik.tracker.tracker.source.cell.CellCapturedFactClassification
import com.adsamcik.tracker.tracker.source.cell.CellWalAdapterRejection
import com.adsamcik.tracker.tracker.source.cell.CellWalAdapterResult
import com.adsamcik.tracker.tracker.source.cell.CellWalQualificationAdapter
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.ingress.SourcePayloadCodec
import com.adsamcik.tracker.tracker.source.location.LocationObservationQualification
import com.adsamcik.tracker.tracker.source.location.LocationStaleReason
import com.adsamcik.tracker.tracker.source.location.LocationWalAdapterRejection
import com.adsamcik.tracker.tracker.source.location.LocationWalAdapterResult
import com.adsamcik.tracker.tracker.source.location.LocationWalQualificationAdapter
import com.adsamcik.tracker.tracker.source.location.ProtectedLocationCanonicalSignalIdentity
import com.adsamcik.tracker.tracker.source.location.authenticatesProtectedLocationCanonicalReceipt
import com.adsamcik.tracker.tracker.source.model.PressureSensorAccuracy
import com.adsamcik.tracker.tracker.source.model.PressureWindowClosureKind
import com.adsamcik.tracker.tracker.source.model.PressureWindowPayload
import com.adsamcik.tracker.tracker.source.model.PressureWindowQualification
import com.adsamcik.tracker.tracker.source.model.DemandReason
import com.adsamcik.tracker.tracker.source.model.EvidenceQuality
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.SourceDemand
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQualityFlag
import com.adsamcik.tracker.tracker.source.model.StepBoundaryKind
import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload
import com.adsamcik.tracker.tracker.source.model.expectedPressureSampleCount
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import com.adsamcik.tracker.tracker.source.model.satisfies
import com.adsamcik.tracker.tracker.source.runtime.toSourceDemandContract
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

	private fun preflightKey(
		key: SourceProofKey,
	): SourceProofResult<RecordingProofState>? {
		if (key.source != source) {
			return failed(SourceProofStage.RECORDING, SourceProofReason.WRONG_SOURCE)
		}
		if (key.purpose == TrackingPurpose.CONTROL) {
			return unavailable(
				SourceProofStage.RECORDING,
				SourceProofReason.CONTROL_IS_NOT_CAPTURE_EVIDENCE,
			)
		}
		if (key.purpose == TrackingPurpose.AMBIENT_PRODUCT) {
			return unavailable(
				SourceProofStage.RECORDING,
				SourceProofReason.AMBIENT_PROOF_NOT_IMPLEMENTED,
			)
		}
		if (key.scope !is SourceProofScope.Run) {
			return failed(SourceProofStage.RECORDING, SourceProofReason.SCOPE_MISMATCH)
		}
		return null
	}

	private suspend fun recordingLocation(
		key: SourceProofKey,
	): SourceProofResult<RecordingProofState> {
		val qualification = locationQualifier.qualify(SourceEventId(key.evidenceIdentity.value))
		return when (qualification) {
			is LocationWalAdapterResult.Evaluated -> when (val result = qualification.qualification) {
				is LocationObservationQualification.Qualified ->
					recordingFromWal(key, SourceKind.LOCATION) { wal, revision ->
						val clock = result.command.productEffect.durableEvidence.clockAuthority
						recordingState(
							key,
							wal,
							revision,
							providerCoverage(
								clock.observedElapsedRealtimeNanos,
								clock.observedElapsedRealtimeNanos,
							),
							SourceProofCompleteness.COMPLETE,
						)
					}
				is LocationObservationQualification.Duplicate ->
					recordingFromWal(key, SourceKind.LOCATION) { wal, revision ->
						val clock = result.existing.productEffect.durableEvidence.clockAuthority
						recordingState(
							key,
							wal,
							revision,
							providerCoverage(
								clock.observedElapsedRealtimeNanos,
								clock.observedElapsedRealtimeNanos,
							),
							SourceProofCompleteness.COMPLETE,
						)
					}
				is LocationObservationQualification.Stale ->
					if (result.reason == LocationStaleReason.BEFORE_DELETION_FLOOR) {
						deleted(
							SourceProofStage.RECORDING,
							SourceProofReason.RETENTION_EXPIRED,
						)
					} else {
						unavailable(
							SourceProofStage.RECORDING,
							if (result.reason == LocationStaleReason.PRE_EFFECTIVE) {
								SourceProofReason.PRE_EFFECTIVE
							} else {
								SourceProofReason.STALE
							},
						)
					}
				is LocationObservationQualification.Unavailable -> unavailable(
					SourceProofStage.RECORDING,
					SourceProofReason.PROVIDER_FAILURE,
				)
				is LocationObservationQualification.Rejected -> failed(
					SourceProofStage.RECORDING,
					when (result.reason.name) {
						"INSUFFICIENT_ACCURACY", "INVALID_ACCURACY" ->
							SourceProofReason.ACCURACY_UNQUALIFIED
						else -> SourceProofReason.MALFORMED_EVIDENCE
					},
				)
			}
			is LocationWalAdapterResult.Rejected ->
				if (qualification.reason == LocationWalAdapterRejection.MISSING_EVENT) {
					missingRecordingEvidence(key)
				} else {
					qualification.reason.toRecordingResult()
				}
		}
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
				listOf(wal.stepsWalOwnerKey()),
			)) {
				is StepsCountDomainOwnerRead.Ready -> read.owners[wal.stepsWalOwnerKey()]
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
						com.adsamcik.tracker.shared.base.database.data.StepsWalBoundaryKind.COVERED
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

	private suspend fun recordingWifi(
		key: SourceProofKey,
	): SourceProofResult<RecordingProofState> =
		when (val result = wifiQualifier.qualify(SourceEventId(key.evidenceIdentity.value))) {
			is WifiWalAdapterResult.Evaluated -> when (val classification = result.classification) {
				is WifiCapturedFactClassification.FreshChanged ->
					recordingWifiFact(key, classification.fact.coverage)
				is WifiCapturedFactClassification.FreshUnchanged ->
					recordingWifiFact(key, classification.fact.coverage)
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
			is WifiWalAdapterResult.Rejected ->
				if (result.reason == WifiWalAdapterRejection.MISSING_EVENT) {
					missingRecordingEvidence(key)
				} else {
					result.reason.toRecordingResult()
				}
		}

	private suspend fun recordingWifiFact(
		key: SourceProofKey,
		coverage: com.adsamcik.tracker.tracker.source.wifi.WifiCoverageEvidence,
	): SourceProofResult<RecordingProofState> =
		recordingFromWal(key, SourceKind.WIFI) { wal, revision ->
			recordingState(
				key,
				wal,
				revision,
				providerCoverage(
					coverage.providerIntervalStartElapsedRealtimeNanos,
					coverage.providerIntervalEndElapsedRealtimeNanos,
				),
				if (coverage.completeness.name == "COMPLETE") {
					SourceProofCompleteness.COMPLETE
				} else {
					SourceProofCompleteness.PARTIAL
				},
			)
		}

	private suspend fun recordingCell(
		key: SourceProofKey,
	): SourceProofResult<RecordingProofState> {
		if (isRunDeleted(key)) {
			return deleted(SourceProofStage.RECORDING, SourceProofReason.DELETED)
		}
		return when (val result = cellQualifier.qualify(SourceEventId(key.evidenceIdentity.value))) {
			is CellWalAdapterResult.Evaluated -> when (val classification = result.classification) {
				is CellCapturedFactClassification.FreshChanged ->
					recordingCellFact(key, classification.fact.coverage)
				is CellCapturedFactClassification.FreshUnchanged ->
					recordingCellFact(key, classification.fact.coverage)
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
			is CellWalAdapterResult.Rejected ->
				if (result.reason == CellWalAdapterRejection.MISSING_EVENT) {
					missingRecordingEvidence(key)
				} else {
					result.reason.toRecordingResult()
				}
		}
	}

	private suspend fun recordingCellFact(
		key: SourceProofKey,
		coverage: com.adsamcik.tracker.tracker.source.cell.CellCoverageEvidence,
	): SourceProofResult<RecordingProofState> =
		recordingFromWal(key, SourceKind.CELL) { wal, revision ->
			recordingState(
				key,
				wal,
				revision,
				providerCoverage(
					coverage.providerIntervalStartElapsedRealtimeNanos,
					coverage.providerIntervalEndElapsedRealtimeNanos,
				),
				if (coverage.childCompleteness.name == "COMPLETE") {
					SourceProofCompleteness.COMPLETE
				} else {
					SourceProofCompleteness.PARTIAL
				},
			)
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
		val expectation = decision.locationWriterExpectation()
			?: return@withTransaction unavailable(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
				recording.lineage,
			)
		val receipt = database.sourceProjectionStateDao().joinState(
			expectation.projectionId,
			expectation.projectionVersion,
			"canonical-receipt:${wal.eventId}",
		) ?: return@withTransaction pending(
			SourceProofStage.MATERIALIZED,
			SourceProofReason.RECEIPT_MISSING,
			recording.lineage,
		)
		val samples = database.locationSampleDao().getBySourceEventIdBounded(wal.eventId, 2)
		val canonicalSignalId =
			ProtectedLocationCanonicalSignalIdentity.canonicalProduct(wal.eventId)
		val runScope = key.scope as SourceProofScope.Run
		if (observation.sourceEventId != wal.eventId ||
			decision.observationSourceEventId != wal.eventId ||
			decision.sourceSignalId != canonicalSignalId ||
			decision.decisionVersion != LocationObservationDecision.CURRENT_DECISION_VERSION ||
			decision.clockDomainId != wal.clockDomainId ||
			receipt.logicalTrackingId != runScope.logicalTrackingId
		) {
			return@withTransaction failed(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.INTEGRITY_FAILURE,
				recording.lineage,
			)
		}
		when (decision.decision) {
			LocationObservationDecision.REJECTED -> {
				if (decision.reason.isNullOrBlank() ||
					decision.acceptedSampleSourceSignalId != null || samples.isNotEmpty() ||
					!receipt.authenticatesProtectedLocationCanonicalReceipt(
						wal.eventId,
						wal.admissionOrdinal,
						wal.integrityIdentity,
						observation,
						decision,
						null,
					)
				) {
					return@withTransaction failed(
						SourceProofStage.MATERIALIZED,
						SourceProofReason.INTEGRITY_FAILURE,
						recording.lineage,
					)
				}
				return@withTransaction canonicalLocationRejectionResult(
					decision.reason,
					recording.lineage,
				)
			}
			LocationObservationDecision.ACCEPTED -> Unit
			else -> return@withTransaction failed(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.INTEGRITY_FAILURE,
				recording.lineage,
			)
		}
		val sample = samples.singleOrNull()
			?: return@withTransaction if (samples.isEmpty()) {
				pending(
					SourceProofStage.MATERIALIZED,
					SourceProofReason.RECEIPT_MISSING,
					recording.lineage,
				)
			} else {
				failed(
					SourceProofStage.MATERIALIZED,
					SourceProofReason.INTEGRITY_FAILURE,
					recording.lineage,
				)
			}
		if (decision.reason != null ||
			decision.acceptedSampleSourceSignalId != canonicalSignalId ||
			sample.sourceSignalId != canonicalSignalId ||
			sample.sourceEventId != wal.eventId ||
			sample.elapsedRealtimeNanos !=
			(recording.coverage as? SourceProofCoverage.ProviderPoint)?.observedAtNanos ||
			!receipt.authenticatesProtectedLocationCanonicalReceipt(
				wal.eventId,
				wal.admissionOrdinal,
				wal.integrityIdentity,
				observation,
				decision,
				sample,
			)
		) {
			return@withTransaction failed(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.INTEGRITY_FAILURE,
				recording.lineage,
			)
		}
		materializationRetention(key, recording) { floor ->
			wal.wallTimeMs?.let { wallTimeMs ->
				wal.wallTimeUncertaintyMs?.let { uncertaintyMs ->
					CanonicalRetentionBounds.location(wallTimeMs, uncertaintyMs)
				}
			}?.let { lowerBound -> lowerBound < floor }
		}?.let { return@withTransaction it }
		val authority = materializationAuthority(
			key = key,
			wal = wal,
			expectation = expectation,
			cursorRevision = null,
		)
		if (authority !is MaterializationAuthority.Ready) {
			return@withTransaction authority.result(recording.lineage)
		}
		provenMaterialized(
			recording,
			authority,
			SourceFactIdentity(wal.eventId),
			decision.decisionVersion.toLong(),
			recording.completeness,
		)
	}

	@Suppress("ComplexCondition", "LongMethod")
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
		val cursor = database.activityCapturedFactDao().cursor(
			fact.writerProjectionId,
			fact.writerProjectionVersion,
			fact.logicalWindowId,
		) ?: return@withTransaction pendingAfterProjectionFailure(
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
			return@withTransaction failed(
				SourceProofStage.MATERIALIZED,
				reason,
				recording.lineage,
			)
		}
		val evidenceByRevision = linkedMapOf<Long, List<
			com.adsamcik.tracker.shared.base.database.data.ActivityCapturedEvidenceEntity>>()
		val fragmentsByRevision = linkedMapOf<Long, List<
			com.adsamcik.tracker.shared.base.database.data.ActivityCapturedFragmentEntity>>()
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
				return@withTransaction unavailable(
					SourceProofStage.MATERIALIZED,
					SourceProofReason.PARTIAL_COMPLETENESS,
					recording.lineage,
				)
			}
			if (!ActivityCapturedFactIntegrity.verify(revision, fragments, evidence)) {
				return@withTransaction failed(
					SourceProofStage.MATERIALIZED,
					SourceProofReason.INTEGRITY_FAILURE,
					recording.lineage,
				)
			}
			fragmentsByRevision[revision.semanticRevision] = fragments
			evidenceByRevision[revision.semanticRevision] = evidence
		}
		if (cursor.logicalTrackingId != fact.logicalTrackingId ||
			cursor.serviceRunId != fact.serviceRunId ||
			cursor.sessionSegmentId != fact.sessionSegmentId ||
			cursor.writerOwnerGeneration != fact.writerOwnerGeneration ||
			cursor.latestSemanticRevision != fact.semanticRevision ||
			cursor.latestMutationId != fact.mutationId ||
			cursor.latestEffectChecksum != fact.effectChecksum ||
			cursor.collectedDataEpoch != fact.collectedDataEpoch ||
			cursor.updatedAtMs != fact.appliedAtMs
		) {
			return@withTransaction failed(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.INTEGRITY_FAILURE,
				recording.lineage,
			)
		}
		val lineageEvidence = lineage.flatMap { revision ->
			evidenceByRevision.getValue(revision.semanticRevision).map { evidence ->
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
			return@withTransaction failed(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.INTEGRITY_FAILURE,
				recording.lineage,
			)
		}
		val walByEventId = loadActivityWalCarriers(lineageEvidence.map { it.second })
		if (walByEventId.size != lineageEvidence.map { it.second.sourceEventId }.distinct().size ||
			lineageEvidence.any { (revision, row) ->
				walByEventId[row.sourceEventId]?.let { carrier ->
					ActivityWalCarrierAuthenticator.authenticates(revision, row, carrier)
				} != true
			}
		) {
			return@withTransaction failed(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.INTEGRITY_FAILURE,
				recording.lineage,
			)
		}
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
			?: return@withTransaction failed(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.INTEGRITY_FAILURE,
				recording.lineage,
			)
		if (providerAuthentication.interval.startInclusiveNanos !=
			fact.providerAcceptanceStartNanos ||
			providerAuthentication.interval.endExclusiveNanos !=
			fact.providerAcceptanceEndNanos
		) {
			return@withTransaction failed(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.INTEGRITY_FAILURE,
				recording.lineage,
			)
		}
		materializationRetention(key, recording) { floor ->
			CanonicalRetentionBounds.crossesFloor(
				CanonicalRetentionBounds.activityLineage(
					fragmentsByRevision.values.flatten(),
				),
				floor,
			)
		}?.let { return@withTransaction it }
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
			return@withTransaction authority.result(recording.lineage)
		}
		provenMaterialized(
			recording,
			authority,
			SourceFactIdentity(fact.logicalWindowId),
			fact.semanticRevision,
			fact.coverage.toCompleteness(),
			selectedEvidence.let { selected ->
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
		fact.authorityMismatch(key)?.let { reason ->
			return@withTransaction failed(
				SourceProofStage.MATERIALIZED,
				reason,
				recording.lineage,
			)
		}
		if (!StepFactRevisionIntegrity.hasValidCanonicalLiveWalFact(fact) ||
			fact.sourceEventId != wal.eventId ||
			fact.coverageKind != StepFactRevisionEntity.COVERAGE_COVERED ||
			fact.effectiveStepCount == null ||
			fact.effectiveStepCount?.let { it <= 0L } != false
		) {
			return@withTransaction failed(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.INTEGRITY_FAILURE,
				recording.lineage,
			)
		}
		val ownerKeys = listOf(wal.stepsWalOwnerKey(), fact.stepsFactOwnerKey())
		val owners = when (val read = StepsCountDomainStore(database).readOwners(ownerKeys)) {
			is StepsCountDomainOwnerRead.Ready -> read.owners
			StepsCountDomainOwnerRead.SchemaUnavailable -> return@withTransaction unavailable(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
				recording.lineage,
			)
			StepsCountDomainOwnerRead.Overflow,
			StepsCountDomainOwnerRead.Unverifiable,
			-> return@withTransaction failed(
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
			return@withTransaction failed(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.INTEGRITY_FAILURE,
				recording.lineage,
			)
		}
		val binding = database.sourceSessionDao().manifestSources(
			requireNotNull(fact.logicalTrackingId),
			requireNotNull(fact.manifestRevision),
		).singleOrNull { source ->
			source.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
				source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE
		}
		if (binding == null || !binding.matchesFactWriter(fact)) {
			return@withTransaction unavailable(
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
		}?.let { return@withTransaction it }
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
			return@withTransaction authority.result(recording.lineage)
		}
		provenMaterialized(
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
		val admissionFact = admissionFacts.single()
		val lineage = database.pressureFactRevisionDao().revisionsForFact(
			writerProjectionId = admissionFact.writerProjectionId,
			writerProjectionVersion = admissionFact.writerProjectionVersion,
			logicalFactId = admissionFact.logicalFactId,
			limit = MAX_PRESSURE_PROOF_REVISIONS + 1,
		)
		if (lineage.size > MAX_PRESSURE_PROOF_REVISIONS) {
			return@withTransaction failed(
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
		val authenticated = when (proof) {
			is PressureFactLineageProof.Authenticated -> proof
			is PressureFactLineageProof.Rejected -> return@withTransaction if (
				proof.reason == PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING
			) {
				unavailable(
					SourceProofStage.MATERIALIZED,
					SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
					recording.lineage,
				)
			} else {
				failed(
					SourceProofStage.MATERIALIZED,
					if (proof.reason == PressureFactProofFailure.CURRENT_FACT_MISMATCH) {
						SourceProofReason.FACT_IDENTITY_MISMATCH
					} else {
						SourceProofReason.INTEGRITY_FAILURE
					},
					recording.lineage,
				)
			}
		}
		val fact = authenticated.current
		val selectedFact = authenticated.selected
		if (selectedFact != admissionFact) {
			return@withTransaction failed(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.FACT_IDENTITY_MISMATCH,
				recording.lineage,
			)
		}
		val currentWal = lineageWal[fact.sourceEventId]
			?: return@withTransaction unavailable(
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
			return@withTransaction failed(
				SourceProofStage.MATERIALIZED,
				reason,
				recording.lineage,
			)
		}
		if (binding == null || !binding.matchesFactWriter(fact)) {
			return@withTransaction failed(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.INTEGRITY_FAILURE,
				recording.lineage,
			)
		}
		materializationRetention(key, recording) { floor ->
			CanonicalRetentionBounds.pressureLineage(authenticated.revisions)
				?.let { lowerBound -> lowerBound < floor }
		}?.let { return@withTransaction it }
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
			return@withTransaction authority.result(recording.lineage)
		}
		provenMaterialized(
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
		val admissionRevisions = database.wifiCapturedFactDao().revisionsForWriterAdmission(
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION,
			recording.lineage.sourceAdmissionOrdinal,
			MAX_RADIO_REVISION_CHAIN + 1,
		)
		val current = database.wifiCapturedFactDao().currentRevisionsForWriterAdmission(
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION,
			recording.lineage.sourceAdmissionOrdinal,
			2,
		)
		val retained = current.singleOrNull()?.let { fact ->
			database.wifiCapturedFactDao().revisionsForFact(
				fact.writerProjectionId,
				fact.writerProjectionVersion,
				fact.logicalFactId,
				MAX_RADIO_REVISION_CHAIN + 1,
			)
		}.orEmpty()
		val resolution = resolveCurrentRevision(
			admissionRevisions = admissionRevisions,
			currentRevisions = current,
			retainedLineage = retained,
			maximumRevisionCount = MAX_RADIO_REVISION_CHAIN,
			logicalFactId = WifiCapturedFactRevisionEntity::logicalFactId,
			semanticRevision = WifiCapturedFactRevisionEntity::semanticRevision,
			lineageAuthenticates = WifiCapturedFactRevisionIntegrity::hasValidRetainedLineage,
		)
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
		val walAuthentication = WifiWalCarrierAuthenticator.authenticate(fact, wal)
			as? WifiWalCarrierAuthentication.Authenticated
			?: return@withTransaction failed(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.INTEGRITY_FAILURE,
				recording.lineage,
			)
		var aggregateOwnerCursor: WifiCapturedFactCursorEntity? = null
		val aggregate = when (fact.factKind) {
			WifiCapturedFactRevisionEntity.FACT_KIND_AGGREGATE -> fact
			WifiCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY -> {
				val ownerId = fact.aggregateOwnerLogicalFactId
					?: return@withTransaction failed(
						SourceProofStage.MATERIALIZED,
						SourceProofReason.INTEGRITY_FAILURE,
						recording.lineage,
					)
				val ownerRevision = fact.aggregateOwnerSemanticRevision
					?: return@withTransaction failed(
						SourceProofStage.MATERIALIZED,
						SourceProofReason.INTEGRITY_FAILURE,
						recording.lineage,
					)
				val owner = database.wifiCapturedFactDao().revision(
					fact.writerProjectionId,
					fact.writerProjectionVersion,
					ownerId,
					ownerRevision,
				) ?: return@withTransaction failed(
					SourceProofStage.MATERIALIZED,
					SourceProofReason.INTEGRITY_FAILURE,
					recording.lineage,
				)
				aggregateOwnerCursor = database.wifiCapturedFactDao().cursor(
					fact.writerProjectionId,
					fact.writerProjectionVersion,
					ownerId,
				) ?: return@withTransaction unavailable(
					SourceProofStage.MATERIALIZED,
					SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
					recording.lineage,
				)
				owner
			}
			else -> return@withTransaction failed(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.INTEGRITY_FAILURE,
				recording.lineage,
			)
		}
		val cursor = database.wifiCapturedFactDao().cursor(
			fact.writerProjectionId,
			fact.writerProjectionVersion,
			fact.logicalFactId,
		) ?: return@withTransaction pendingAfterProjectionFailure(
			WriterExpectation(
				SourceDestinationOwnerEntity.SOURCE_WIFI,
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
			return@withTransaction failed(
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
			return@withTransaction unavailable(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.STALE,
				recording.lineage,
			)
		}
		if (!WifiCapturedFactRevisionIntegrity.cursorAuthenticatesCurrent(cursor, fact) ||
			!WifiCapturedFactRevisionIntegrity.hasValidEffectChecksum(aggregate) ||
			!WifiWalCarrierAuthenticator.authenticatesAggregate(
				aggregate,
				walAuthentication.carrier,
				fact,
			)
		) {
			return@withTransaction failed(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.INTEGRITY_FAILURE,
				recording.lineage,
			)
		}
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
		}?.let { return@withTransaction it }
		val authority = materializationAuthority(
			key,
			wal,
			WriterExpectation(
				SourceDestinationOwnerEntity.SOURCE_WIFI,
				fact.writerOwnerGeneration,
				fact.writerProjectionId,
				fact.writerProjectionVersion,
				fact.writerBindingGeneration,
			),
			cursor.cursorRevision,
		)
		if (authority !is MaterializationAuthority.Ready) {
			return@withTransaction authority.result(recording.lineage)
		}
		provenMaterialized(
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
		val admissionRevisions = database.cellCapturedFactDao().revisionsForWriterAdmission(
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
			recording.lineage.sourceAdmissionOrdinal,
			MAX_RADIO_REVISION_CHAIN + 1,
		)
		val current = database.cellCapturedFactDao().currentRevisionsForWriterAdmission(
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
			recording.lineage.sourceAdmissionOrdinal,
			2,
		)
		val retained = current.singleOrNull()?.let { fact ->
			database.cellCapturedFactDao().revisionsForFact(
				fact.writerProjectionId,
				fact.writerProjectionVersion,
				fact.logicalFactId,
				MAX_RADIO_REVISION_CHAIN + 1,
			)
		}.orEmpty()
		val resolution = resolveCurrentRevision(
			admissionRevisions = admissionRevisions,
			currentRevisions = current,
			retainedLineage = retained,
			maximumRevisionCount = MAX_RADIO_REVISION_CHAIN,
			logicalFactId = CellCapturedFactRevisionEntity::logicalFactId,
			semanticRevision = CellCapturedFactRevisionEntity::semanticRevision,
			lineageAuthenticates = CellCapturedFactRevisionIntegrity::hasValidRetainedLineage,
		)
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
		val walAuthentication = CellWalCarrierAuthenticator.authenticate(fact, wal)
			as? CellWalCarrierAuthentication.Authenticated
			?: return@withTransaction failed(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.INTEGRITY_FAILURE,
				recording.lineage,
			)
		var aggregateOwnerCursor: CellCapturedFactCursorEntity? = null
		var aggregateWalAuthentication = walAuthentication
		val aggregate = when (fact.factKind) {
			CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE -> fact
			CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY -> {
				val ownerId = fact.aggregateOwnerLogicalFactId
					?: return@withTransaction failed(
						SourceProofStage.MATERIALIZED,
						SourceProofReason.INTEGRITY_FAILURE,
						recording.lineage,
					)
				val ownerRevision = fact.aggregateOwnerSemanticRevision
					?: return@withTransaction failed(
						SourceProofStage.MATERIALIZED,
						SourceProofReason.INTEGRITY_FAILURE,
						recording.lineage,
					)
				val owner = database.cellCapturedFactDao().revision(
					fact.writerProjectionId,
					fact.writerProjectionVersion,
					ownerId,
					ownerRevision,
				) ?: return@withTransaction failed(
					SourceProofStage.MATERIALIZED,
					SourceProofReason.INTEGRITY_FAILURE,
					recording.lineage,
				)
				val ownerCursor = database.cellCapturedFactDao().cursor(
					fact.writerProjectionId,
					fact.writerProjectionVersion,
					ownerId,
				) ?: return@withTransaction unavailable(
					SourceProofStage.MATERIALIZED,
					SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
					recording.lineage,
				)
				if (!CellCapturedFactRevisionIntegrity.coverageOnlyOwnerCursorAuthenticates(
						fact,
						owner,
						ownerCursor,
					)
				) {
					return@withTransaction unavailable(
						SourceProofStage.MATERIALIZED,
						SourceProofReason.STALE,
						recording.lineage,
					)
				}
				val ownerWal = database.sourceEventWalDao().getByEventId(owner.sourceEventId)
					?: return@withTransaction unavailable(
						SourceProofStage.MATERIALIZED,
						SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
						recording.lineage,
					)
				aggregateWalAuthentication =
					CellWalCarrierAuthenticator.authenticate(owner, ownerWal)
						as? CellWalCarrierAuthentication.Authenticated
						?: return@withTransaction failed(
							SourceProofStage.MATERIALIZED,
							SourceProofReason.INTEGRITY_FAILURE,
							recording.lineage,
						)
				val ownerKey = key.copy(
					evidenceIdentity = com.adsamcik.tracker.stats.api.repository.SourceEvidenceIdentity(
						owner.sourceEventId,
					),
					sourcePolicyRevision = owner.sourcePolicyRevision,
					captureConsentEpoch = owner.captureConsentEpoch,
					collectedDataEpoch = owner.collectedDataEpoch,
				)
				historicalSessionAuthority(
					key = ownerKey,
					wal = ownerWal,
					expectedSource = SourceKind.CELL,
					observedStartNanos = owner.coverageIntervalStartNanos,
					observedEndNanos = owner.coverageIntervalEndNanos,
				)?.let { failure ->
					return@withTransaction failed(
						SourceProofStage.MATERIALIZED,
						when (failure) {
							is SourceProofResult.Pending -> failure.reason
							is SourceProofResult.Unavailable -> failure.reason
							is SourceProofResult.Failed -> failure.reason
							is SourceProofResult.Deleted -> failure.reason
							is SourceProofResult.Proven -> SourceProofReason.INTEGRITY_FAILURE
						},
						recording.lineage,
					)
				}
				aggregateOwnerCursor = ownerCursor
				owner
			}
			else -> return@withTransaction failed(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.INTEGRITY_FAILURE,
				recording.lineage,
			)
		}
		val cursor = database.cellCapturedFactDao().cursor(
			fact.writerProjectionId,
			fact.writerProjectionVersion,
			fact.logicalFactId,
		) ?: return@withTransaction pendingAfterProjectionFailure(
			WriterExpectation(
				SourceDestinationOwnerEntity.SOURCE_CELL,
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
			return@withTransaction failed(
				SourceProofStage.MATERIALIZED,
				reason,
				recording.lineage,
			)
		}
		if (!CellCapturedFactRevisionIntegrity.cursorAuthenticatesCurrent(cursor, fact) ||
			!CellCapturedFactRevisionIntegrity.hasValidEffectChecksum(aggregate) ||
			aggregate.writerBindingGeneration != CELL_WRITER.bindingGeneration ||
			aggregate.writerOwnerGeneration != CELL_WRITER.ownerGeneration ||
			!(if (fact.factKind == CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY) {
				CellWalCarrierAuthenticator.authenticatesAggregate(
					aggregate,
					walAuthentication.carrier,
					fact,
				)
			} else {
				CellWalCarrierAuthenticator.authenticatesAggregate(
					aggregate,
					walAuthentication.carrier,
				)
			}) ||
			aggregateOwnerCursor?.let { ownerCursor ->
				CellCapturedFactRevisionIntegrity.coverageOnlyOwnerCursorAuthenticates(
					fact,
					aggregate,
					ownerCursor,
				)
			} == false ||
			fact.factKind == CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY &&
			!cellAggregateReuseCompatible(fact, aggregate)
		) {
			return@withTransaction failed(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.INTEGRITY_FAILURE,
				recording.lineage,
			)
		}
		materializationRetention(key, recording) { floor ->
			listOf(
				CellWalCarrierAuthenticator.retentionLowerBound(fact, walAuthentication.carrier),
				CellWalCarrierAuthenticator.retentionLowerBound(
					aggregate,
					aggregateWalAuthentication.carrier,
				),
			).takeIf { bounds -> bounds.all { it != null } }
				?.any { lowerBound -> requireNotNull(lowerBound) < floor }
		}?.let { return@withTransaction it }
		val authority = materializationAuthority(
			key,
			wal,
			WriterExpectation(
				SourceDestinationOwnerEntity.SOURCE_CELL,
				fact.writerOwnerGeneration,
				fact.writerProjectionId,
				fact.writerProjectionVersion,
				fact.writerBindingGeneration,
			),
			cursor.cursorRevision,
			additionalAdmissionOrdinals = listOf(aggregate.sourceAdmissionOrdinal)
				.filter { it != wal.admissionOrdinal },
		)
		if (authority !is MaterializationAuthority.Ready) {
			return@withTransaction authority.result(recording.lineage)
		}
		provenMaterialized(
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
		val lane = database.sourceProjectionStateDao().productLane(
			expectation.sourceKind,
			expectation.bindingGeneration,
			expectation.projectionId,
			expectation.projectionVersion,
		) ?: return MaterializationAuthority.Blocked(
			failed(SourceProofStage.MATERIALIZED, SourceProofReason.WRITER_MISMATCH),
		)
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

	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private suspend fun historicalSessionAuthority(
		key: SourceProofKey,
		wal: SourceEventWalEntity,
		expectedSource: SourceKind,
		observedStartNanos: Long,
		observedEndNanos: Long,
	): SourceProofResult<RecordingProofState>? {
		fun unavailableAuthority() = unavailable(
			SourceProofStage.RECORDING,
			SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
		)
		fun integrityFailure(reason: SourceProofReason = SourceProofReason.INTEGRITY_FAILURE) =
			failed(SourceProofStage.RECORDING, reason)

		val configurationRevision = wal.configRevision ?: return unavailableAuthority()
		val physicalFingerprint = wal.physicalConfigurationFingerprint
			?: return unavailableAuthority()
		val authorizationRevision = wal.authorizationRevision ?: return unavailableAuthority()
		val authorizationFingerprint = wal.authorizationFingerprint ?: return unavailableAuthority()
		val manifestRevision = wal.sessionManifestRevision ?: return unavailableAuthority()
		val leaseGeneration = wal.lifecycleLeaseGeneration ?: return unavailableAuthority()
		val logicalTrackingId = wal.logicalTrackingId ?: return unavailableAuthority()
		val serviceRunId = wal.serviceRunId ?: return unavailableAuthority()
		val wallTimeMs = wal.wallTimeMs ?: return unavailableAuthority()
		val wallTimeUncertaintyMs = wal.wallTimeUncertaintyMs ?: return unavailableAuthority()
		if (wal.planAttribution != PlanAttribution.CAPTURED_REGISTRATION.ordinal ||
			observedStartNanos < 0L ||
			observedEndNanos < observedStartNanos ||
			wal.observedElapsedNanos != observedEndNanos ||
			wal.receivedElapsedNanos < observedEndNanos ||
			wallTimeMs < 0L ||
			wallTimeUncertaintyMs < 0L ||
			wal.acquiredAtMs < 0L ||
			(expectedSource == SourceKind.STEPS && wallTimeMs != wal.acquiredAtMs)
		) {
			return integrityFailure(SourceProofReason.PRE_EFFECTIVE)
		}
		val sequenceIdentity = database.sourceEventWalDao().identityBySourceSequence(
			expectedSource.stableCode,
			wal.sourceInstanceId,
			wal.sourceSequence,
		)
		if (sequenceIdentity?.eventId != wal.eventId ||
			sequenceIdentity.admissionOrdinal != wal.admissionOrdinal ||
			sequenceIdentity.integrityIdentity != wal.integrityIdentity
		) return integrityFailure()

		val planDao = database.sourcePlanStateDao()
		val planHeader = planDao.revision(configurationRevision) ?: return unavailableAuthority()
		val desiredPlan = planDao.desiredPlan(configurationRevision, expectedSource.stableCode)
			?: return unavailableAuthority()
		val decodedPlan = runCatching {
			planCodec.decode(desiredPlan.payload)
		}.getOrNull() ?: return integrityFailure()
		val reencodedPlan = runCatching { planCodec.encode(decodedPlan) }.getOrNull()
			?: return integrityFailure()
		if (decodedPlan.source != expectedSource || !decodedPlan.enabled ||
			decodedPlan.revision != configurationRevision ||
			desiredPlan.revision != configurationRevision ||
			desiredPlan.sourceKind != expectedSource.stableCode ||
			!reencodedPlan.bytes.contentEquals(desiredPlan.payload) ||
			reencodedPlan.checksum != desiredPlan.payloadChecksum ||
			decodedPlan.physicalConfigurationFingerprint() != physicalFingerprint ||
			planHeader.sourcePolicyRevision != key.sourcePolicyRevision
		) {
			return integrityFailure(SourceProofReason.POLICY_MISMATCH)
		}

		val brokerDao = database.sourceBrokerDao()
		val registrationAuthentication = database.authenticateHistoricalProviderRegistration(
			sourceKind = expectedSource.stableCode,
			registrationGeneration = wal.registrationGeneration,
			sourceInstanceId = wal.sourceInstanceId,
			clockDomainId = wal.clockDomainId,
			physicalConfigurationFingerprint = physicalFingerprint,
			collectedDataEpoch = wal.capturedCollectedDataEpoch,
			expectedProviderResidency =
				ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
			observedStartNanos = observedStartNanos,
			observedEndNanos = observedEndNanos,
			observedWallTimeMs = wallTimeMs,
			currentClockDomainId = bootClockDomainProvider.current(),
		)
		val authenticatedRegistration = when (registrationAuthentication) {
			is ProviderRegistrationIntervalAuthentication.Authenticated ->
				registrationAuthentication
			is ProviderRegistrationIntervalAuthentication.Rejected ->
				return if (registrationAuthentication.reason ==
					ProviderRegistrationIntervalFailure.MISSING_REGISTRATION
				) {
					unavailableAuthority()
				} else {
					integrityFailure(SourceProofReason.PRE_EFFECTIVE)
				}
		}
		val registrationStart = authenticatedRegistration.interval.startInclusiveNanos
		val registrationEnd = authenticatedRegistration.interval.endExclusiveNanos

		val authorizationRows = brokerDao.authorizationRevisionBounded(
			expectedSource.stableCode,
			wal.registrationGeneration,
			authorizationRevision,
			MAX_AUTHORIZATION_MEMBERS + 1,
		)
		if (authorizationRows.size > MAX_AUTHORIZATION_MEMBERS) return integrityFailure()
		val authorization = authorizationRows.toAuthorizationSnapshotOrNull()
			?: return unavailableAuthority()
		val startAuthorization = brokerDao.authorizationAtBounded(
			expectedSource.stableCode,
			wal.registrationGeneration,
			wal.clockDomainId,
			observedStartNanos,
			MAX_AUTHORIZATION_MEMBERS + 1,
		).toAuthorizationSnapshotOrNull()
		val endAuthorization = brokerDao.authorizationAtBounded(
			expectedSource.stableCode,
			wal.registrationGeneration,
			wal.clockDomainId,
			observedEndNanos,
			MAX_AUTHORIZATION_MEMBERS + 1,
		).toAuthorizationSnapshotOrNull()
		val captureMember = authorization.authorizedMembers.singleOrNull { member ->
			member.purpose == SourceBrokerPurpose.SESSION_CAPTURE &&
				member.persistenceEligible &&
				member.logicalTrackingId == logicalTrackingId &&
				member.serviceRunId == serviceRunId &&
				member.manifestRevision == manifestRevision &&
				member.lifecycleLeaseGeneration == leaseGeneration &&
				member.sourcePolicyRevision == key.sourcePolicyRevision &&
				member.consentEpoch == key.captureConsentEpoch
		}
		val demandIds = authorization.authorizedMembers.mapNotNull { it.demandId }
		val demands = brokerDao.demandsByIds(demandIds)
		val recomputedAuthorization = runCatching {
			SourceBrokerAuthorization.rows(
				sourceKind = expectedSource.stableCode,
				registrationGeneration = wal.registrationGeneration,
				authorizationRevision = authorizationRevision,
				demands = demands,
				effectiveBootId = authorization.effectiveBootId,
				effectiveElapsedRealtimeNanos = authorization.effectiveElapsedRealtimeNanos,
				effectiveWallTimeMs = authorization.members.first().effectiveWallTimeMs,
			)
		}.getOrNull()
		if (authorization.isDenied || captureMember == null ||
			authorization.purposeEligibilityMask and
			SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L
		) {
			return unavailable(
				SourceProofStage.RECORDING,
				SourceProofReason.CONTROL_IS_NOT_CAPTURE_EVIDENCE,
			)
		}
		if (startAuthorization != authorization ||
			endAuthorization != authorization ||
			authorization.authorizationFingerprint != authorizationFingerprint ||
			authorization.purposeEligibilityMask != wal.authorizationPurposeEligibilityMask ||
			authorization.effectiveBootId != wal.clockDomainId ||
			demandIds.size != authorization.authorizedMembers.size ||
			demandIds.distinct().size != demandIds.size ||
			demands.size != demandIds.size ||
			recomputedAuthorization?.sortedBy { it.memberId } !=
			authorization.members.sortedBy { it.memberId }
		) return integrityFailure()
		val demandsFitPlan = demands.all { demand ->
			val contract = runCatching { demand.toSourceDemandContract() }.getOrNull()
				?: return@all false
			decodedPlan.satisfies(
				SourceDemand(
					source = contract.source,
					maximumAgeMs = contract.maximumProviderItemAgeMs,
					desiredLatencyMs = contract.targetPlanningLatencyMs,
					quality = EvidenceQuality.ANY,
					reason = DemandReason.SESSION,
					acquisitionFloor = contract.floor,
					requestedDeliveryLatencyMs = contract.requestedDeliveryLatencyMs,
					adaptiveReductionAllowed = contract.adaptiveReductionAllowed,
				),
			)
		}
		if (!demandsFitPlan) return integrityFailure(SourceProofReason.POLICY_MISMATCH)
		val nextAuthorizationRows = brokerDao.nextAuthorizationRevisionBounded(
			expectedSource.stableCode,
			wal.registrationGeneration,
			authorizationRevision,
			MAX_AUTHORIZATION_MEMBERS + 1,
		)
		if (nextAuthorizationRows.size > MAX_AUTHORIZATION_MEMBERS) return integrityFailure()
		val nextAuthorization = if (nextAuthorizationRows.isEmpty()) {
			null
		} else {
			nextAuthorizationRows.toAuthorizationSnapshotOrNull() ?: return integrityFailure()
		}
		val authorizationEnd = when {
			nextAuthorization == null -> registrationEnd
			nextAuthorization.effectiveBootId != wal.clockDomainId ||
				nextAuthorization.effectiveElapsedRealtimeNanos <=
				authorization.effectiveElapsedRealtimeNanos -> return integrityFailure()
			else -> minOf(registrationEnd, nextAuthorization.effectiveElapsedRealtimeNanos)
		}
		if (authorization.effectiveElapsedRealtimeNanos > observedStartNanos ||
			observedEndNanos >= authorizationEnd
		) return integrityFailure(SourceProofReason.PRE_EFFECTIVE)

		val policyDao = database.sourcePolicyDao()
		val policyAuthority = policyDao.authority() ?: return unavailableAuthority()
		val policy = policyDao.policyAtRevision(
			key.sourcePolicyRevision,
			expectedSource.stableCode,
		) ?: return unavailableAuthority()
		if (policyAuthority.bootstrapState != SourcePolicyAuthorityEntity.STATE_ACTIVE ||
			policyAuthority.currentPolicyRevision < key.sourcePolicyRevision ||
			!policy.enabled || !policy.capturePersistenceEligible ||
			policy.captureConsentEpoch != key.captureConsentEpoch ||
			policy.effectiveBootId != wal.clockDomainId ||
			policy.effectiveElapsedRealtimeNanos > observedStartNanos
		) return integrityFailure(SourceProofReason.POLICY_MISMATCH)
		val consent = policyDao.consentEpoch(
			expectedSource.stableCode,
			SourceBrokerPurpose.SESSION_CAPTURE,
			key.captureConsentEpoch,
		) ?: return unavailableAuthority()
		if (!consent.eligible || !consent.persistenceEligible ||
			consent.policyRevision > key.sourcePolicyRevision ||
			consent.effectiveBootId != wal.clockDomainId ||
			consent.effectiveElapsedRealtimeNanos > observedStartNanos
		) return integrityFailure(SourceProofReason.CONSENT_MISMATCH)

		val sessionDao = database.sourceSessionDao()
		val run = sessionDao.serviceRun(serviceRunId) ?: return unavailableAuthority()
		val session = sessionDao.session(logicalTrackingId) ?: return unavailableAuthority()
		val sessionEnd = session.cutoffElapsedNanos ?: Long.MAX_VALUE
		if (run.logicalTrackingId != logicalTrackingId ||
			run.bootId != wal.clockDomainId ||
			run.leaseGeneration != leaseGeneration ||
			session.logicalTrackingId != logicalTrackingId ||
			session.lifecycleLeaseGeneration < leaseGeneration ||
			run.startedElapsedNanos > observedStartNanos ||
			observedEndNanos >= sessionEnd
		) return integrityFailure(SourceProofReason.SCOPE_MISMATCH)
		val manifests = sessionDao.manifestsForServiceRun(
			serviceRunId,
			MAX_MANIFESTS_PER_RUN + 1,
		)
		if (manifests.size > MAX_MANIFESTS_PER_RUN ||
			!SessionManifestIntegrity.hasValidServiceRunTimeline(run, manifests)
		) return integrityFailure()
		val manifestIndex = manifests.indexOfFirst { it.manifestRevision == manifestRevision }
		if (manifestIndex < 0) return unavailableAuthority()
		val manifest = manifests[manifestIndex]
		val manifestSources = database.trackingHistoryReadDao().manifestSources(
			listOf(serviceRunId),
			MAX_MANIFEST_SOURCE_ROWS + 1,
		)
		if (manifestSources.size > MAX_MANIFEST_SOURCE_ROWS || manifests.any { candidate ->
			!SessionManifestIntegrity.verify(
				candidate,
				manifestSources.filter { source ->
					source.logicalTrackingId == candidate.logicalTrackingId &&
						source.manifestRevision == candidate.manifestRevision
				},
			)
		}
		) return integrityFailure()
		val sourceBinding = manifestSources.singleOrNull { source ->
			source.logicalTrackingId == logicalTrackingId &&
				source.manifestRevision == manifestRevision &&
				source.sourceKind == expectedSource.stableCode &&
				source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
				source.persistenceEligible
		} ?: return integrityFailure(SourceProofReason.CONTROL_IS_NOT_CAPTURE_EVIDENCE)
		val manifestEnd = manifests.getOrNull(manifestIndex + 1)?.effectiveElapsedRealtimeNanos
			?: minOf(sessionEnd, registrationEnd, authorizationEnd)
		if (manifest.logicalTrackingId != logicalTrackingId ||
			manifest.serviceRunId != serviceRunId ||
			manifest.sourcePolicyRevision != key.sourcePolicyRevision ||
			manifest.acquisitionPlanRevision != configurationRevision ||
			manifest.effectiveBootId != wal.clockDomainId ||
			manifest.effectiveElapsedRealtimeNanos > observedStartNanos ||
			observedEndNanos >= manifestEnd ||
			sourceBinding.consentEpoch != key.captureConsentEpoch ||
			sourceBinding.qosCode != policy.qosCode
		) return integrityFailure(SourceProofReason.PRE_EFFECTIVE)
		val segmentId = run.sessionSegmentId ?: return unavailableAuthority()
		val segment = database.sessionSegmentDao().getById(segmentId) ?: return unavailableAuthority()
		if (segment.logicalTrackingId != logicalTrackingId ||
			segment.serviceRunId != serviceRunId
		) return integrityFailure(SourceProofReason.SCOPE_MISMATCH)

		val startActions = sessionDao.sourceStartActionsForManifestBounded(
			logicalTrackingId,
			serviceRunId,
			manifestRevision,
			expectedSource.stableCode,
			MAX_PLAN_APPLICATION_ROWS + 1,
		)
		if (startActions.size > MAX_PLAN_APPLICATION_ROWS) return integrityFailure()
		val appliedAtElapsedNanos = maxOf(
			registrationStart,
			authorization.effectiveElapsedRealtimeNanos,
			policy.effectiveElapsedRealtimeNanos,
			consent.effectiveElapsedRealtimeNanos,
			manifest.effectiveElapsedRealtimeNanos,
		)
		if (startActions.singleOrNull()?.authenticates(
				wal = wal,
				run = run,
				manifest = manifest,
				source = expectedSource,
				configurationRevision = configurationRevision,
				appliedAtElapsedNanos = appliedAtElapsedNanos,
				observedStartNanos = observedStartNanos,
			) != true
		) return unavailableAuthority()
		return null
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
		) return null
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
		run: com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity,
		manifest: SessionManifestVersionEntity,
		source: SourceKind,
		configurationRevision: Long,
		appliedAtElapsedNanos: Long,
		observedStartNanos: Long,
	): Boolean {
		val acknowledgedAt = acknowledgedAtMs ?: return false
		val acknowledgedElapsed = acknowledgedElapsedRealtimeNanos ?: return false
		return actionRevision > 0L &&
			actionFamily == "SOURCE_RUNTIME" &&
			sourceKind == source.stableCode &&
			desiredState == "STARTED" &&
			status == "START_ACCEPTED" &&
			attemptCount > 0 &&
			failureCode == null &&
			retryTrigger == null &&
			logicalTrackingId == wal.logicalTrackingId &&
			serviceRunId == wal.serviceRunId &&
			manifestRevision == wal.sessionManifestRevision &&
			desiredPlanRevision == configurationRevision &&
			sourcePolicyRevision == wal.sourcePolicyRevision &&
			consentEpoch == wal.captureConsentEpoch &&
			startOrigin == manifest.startOrigin &&
			startOrigin == run.startOrigin &&
			bootId == wal.clockDomainId &&
			leaseGeneration == wal.lifecycleLeaseGeneration &&
			sourceInstanceId == wal.sourceInstanceId &&
			registrationGeneration == wal.registrationGeneration &&
			requestedAtMs == manifest.effectiveWallTimeMs &&
			acknowledgedAt >= requestedAtMs &&
			requestedElapsedRealtimeNanos == manifest.effectiveElapsedRealtimeNanos &&
			requestedElapsedRealtimeNanos <= appliedAtElapsedNanos &&
			appliedAtElapsedNanos <= acknowledgedElapsed &&
			acknowledgedElapsed <= observedStartNanos
	}

	private fun LocationObservationDecision.locationWriterExpectation(): WriterExpectation? {
		val projectionId = writerProjectionId ?: return null
		val projectionVersion = writerProjectionVersion ?: return null
		val bindingGeneration = writerBindingGeneration ?: return null
		val ownerGeneration = writerOwnerGeneration ?: return null
		if (projectionId != SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID ||
			projectionVersion !=
			SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_VERSION ||
			bindingGeneration !=
			SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_BINDING_GENERATION
		) return null
		return WriterExpectation(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_LOCATION,
			ownerGeneration = ownerGeneration,
			projectionId = projectionId,
			projectionVersion = projectionVersion,
			bindingGeneration = bindingGeneration,
		)
	}

	private fun com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
		.matchesFactWriter(fact: StepFactRevisionEntity): Boolean =
		persistenceEligible &&
			consentEpoch == fact.captureConsentEpoch &&
			outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS &&
			writerOwner == SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS &&
			writerOwnerGeneration?.let { it > 0L } == true &&
			writerProjectionId == fact.writerProjectionId &&
			writerProjectionVersion == fact.writerProjectionVersion &&
			writerBindingGeneration == fact.writerBindingGeneration

	private fun com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
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
		val lifecycle = database.sourceEvidenceStateDao().get()
			?: return@withTransaction unavailable(
				SourceProofStage.RECORDING,
				SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
			)
		if (lifecycle.collectedDataEpoch != key.collectedDataEpoch ||
			wal.capturedCollectedDataEpoch != key.collectedDataEpoch
		) {
			return@withTransaction if (lifecycle.collectedDataEpoch > key.collectedDataEpoch) {
				deleted(SourceProofStage.RECORDING, SourceProofReason.DELETED)
			} else {
				failed(SourceProofStage.RECORDING, SourceProofReason.DATA_EPOCH_MISMATCH)
			}
		}
		if (wal.admissionOrdinal <= lifecycle.deletedSourceEventHighWaterOrdinal || isRunDeleted(key)) {
			return@withTransaction deleted(
				SourceProofStage.RECORDING,
				SourceProofReason.DELETED,
			)
		}
		when (pressureRetentionAuthorityState(
			key = key,
			sourceAdmissionOrdinal = wal.admissionOrdinal,
			retainedFromMs = lifecycle.retainedFromMs,
		)) {
			PressureRetentionAuthorityState.EXPIRED -> return@withTransaction deleted(
				SourceProofStage.RECORDING,
				SourceProofReason.RETENTION_EXPIRED,
			)
			PressureRetentionAuthorityState.INVALID -> return@withTransaction failed(
				SourceProofStage.RECORDING,
				SourceProofReason.INTEGRITY_FAILURE,
			)
			PressureRetentionAuthorityState.ABSENT -> Unit
		}
		if (lifecycle.retainedFromMs?.let { floor ->
			wal.earliestPossibleWallTimeMs()?.let { it < floor } ?: true
		} == true) {
			return@withTransaction deleted(
				SourceProofStage.RECORDING,
				SourceProofReason.RETENTION_EXPIRED,
			)
		}
		if (wal.sourcePolicyRevision != key.sourcePolicyRevision) {
			return@withTransaction failed(
				SourceProofStage.RECORDING,
				SourceProofReason.POLICY_MISMATCH,
			)
		}
		if (wal.captureConsentEpoch != key.captureConsentEpoch) {
			return@withTransaction failed(
				SourceProofStage.RECORDING,
				SourceProofReason.CONSENT_MISMATCH,
			)
		}
		val scope = key.scope as SourceProofScope.Run
		if (wal.logicalTrackingId != scope.logicalTrackingId ||
			wal.serviceRunId != scope.serviceRunId
		) {
			return@withTransaction failed(
				SourceProofStage.RECORDING,
				SourceProofReason.SCOPE_MISMATCH,
			)
		}
		if (wal.authorizationPurposeEligibilityMask and
			SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L
		) {
			return@withTransaction unavailable(
				SourceProofStage.RECORDING,
				SourceProofReason.CONTROL_IS_NOT_CAPTURE_EVIDENCE,
			)
		}
		block(wal, lifecycle.revision)
	}

	private suspend fun loadActivityWalCarriers(
		evidence: List<
			com.adsamcik.tracker.shared.base.database.data.ActivityCapturedEvidenceEntity>,
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
		when (pressureRetentionAuthorityState(
			key = key,
			sourceAdmissionOrdinal = null,
			retainedFromMs = lifecycle.retainedFromMs,
		)) {
			PressureRetentionAuthorityState.EXPIRED -> return@withTransaction deleted(
				SourceProofStage.RECORDING,
				SourceProofReason.RETENTION_EXPIRED,
			)
			PressureRetentionAuthorityState.INVALID -> return@withTransaction failed(
				SourceProofStage.RECORDING,
				SourceProofReason.INTEGRITY_FAILURE,
			)
			PressureRetentionAuthorityState.ABSENT -> Unit
		}
		val retentionProof = database.sourceEventRetentionProofDao().get(
			sourceKind = source.toSourceKind().stableCode,
			eventId = key.evidenceIdentity.value,
		)
		if (retentionProof != null) {
			if (!retentionProof.hasValidStoredIntegrity()) {
				return@withTransaction failed(
					SourceProofStage.RECORDING,
					SourceProofReason.INTEGRITY_FAILURE,
				)
			}
			if (retentionProof.sourceAdmissionOrdinal <=
				lifecycle.deletedSourceEventHighWaterOrdinal
			) {
				return@withTransaction deleted(
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
				return@withTransaction deleted(
					SourceProofStage.RECORDING,
					SourceProofReason.RETENTION_EXPIRED,
				)
			}
			return@withTransaction unavailable(
				SourceProofStage.RECORDING,
				SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
			)
		}
		pending(SourceProofStage.RECORDING, SourceProofReason.EVIDENCE_NOT_FOUND)
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
		if (key.source != source || key.scope !is SourceProofScope.Run) return null
		if (isRunDeleted(key)) {
			return deleted(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.DELETED,
				recording.lineage,
			)
		}
		val lifecycle = database.sourceEvidenceStateDao().get() ?: return null
		return if (lifecycle.collectedDataEpoch > key.collectedDataEpoch ||
			recording.lineage.sourceAdmissionOrdinal <=
			lifecycle.deletedSourceEventHighWaterOrdinal
		) {
			deleted(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.DELETED,
				recording.lineage,
			)
		} else {
			if (source == TrackingSource.LOCATION && lifecycle.retainedFromMs != null) {
				val wal = database.sourceEventWalDao().getByEventId(key.evidenceIdentity.value)
				if (wal != null) {
					val lowerBound = wal.wallTimeMs?.let { wallTimeMs ->
						wal.wallTimeUncertaintyMs?.let { uncertaintyMs ->
							CanonicalRetentionBounds.location(wallTimeMs, uncertaintyMs)
						}
					}
					if (!wal.hasQualifiedIntegrity() ||
						wal.sourceKind != SourceDestinationOwnerEntity.SOURCE_LOCATION ||
						wal.admissionOrdinal != recording.lineage.sourceAdmissionOrdinal ||
						lowerBound == null
					) {
						return failed(
							SourceProofStage.MATERIALIZED,
							SourceProofReason.INTEGRITY_FAILURE,
							recording.lineage,
						)
					}
					if (lowerBound < requireNotNull(lifecycle.retainedFromMs)) {
						return deleted(
							SourceProofStage.MATERIALIZED,
							SourceProofReason.RETENTION_EXPIRED,
							recording.lineage,
						)
					}
				}
			}
			when (pressureRetentionAuthorityState(
				key = key,
				sourceAdmissionOrdinal = recording.lineage.sourceAdmissionOrdinal,
				retainedFromMs = lifecycle.retainedFromMs,
			)) {
				PressureRetentionAuthorityState.EXPIRED -> deleted(
					SourceProofStage.MATERIALIZED,
					SourceProofReason.RETENTION_EXPIRED,
					recording.lineage,
				)
				PressureRetentionAuthorityState.INVALID -> failed(
					SourceProofStage.MATERIALIZED,
					SourceProofReason.INTEGRITY_FAILURE,
					recording.lineage,
				)
				PressureRetentionAuthorityState.ABSENT ->
					capturedRadioRetentionMaterialization(key, recording, lifecycle.retainedFromMs)
			}
		}
	}

	private suspend fun capturedRadioRetentionMaterialization(
		key: SourceProofKey,
		recording: RecordingProofState,
		currentRetainedFromMs: Long?,
	): SourceProofResult<MaterializedProofState>? {
		val expectation = when (key.source) {
			TrackingSource.WIFI -> WIFI_WRITER
			TrackingSource.CELL -> CELL_WRITER
			else -> return null
		}
		val proof = database.capturedRadioFactRetentionProofDao().byEvent(
			key.source.stableCode,
			key.evidenceIdentity.value,
		) ?: return null
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

	private fun com.adsamcik.tracker.shared.base.database.data.ActivityCapturedWindowRevisionEntity
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

private data class WriterExpectation(
	val sourceKind: Int,
	val ownerGeneration: Long,
	val projectionId: String,
	val projectionVersion: Int,
	val bindingGeneration: Long,
)

private fun SourceEventWalEntity.stepsWalOwnerKey() = StepsCountDomainOwnerLookupKey(
	ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL,
	ownerIdentity = StepsCountDomainReceiptIntegrity.sessionWalOwnerIdentity(
		admissionOrdinal,
		eventId,
	),
	ownerRevision = 1L,
)

private fun StepFactRevisionEntity.stepsFactOwnerKey() = StepsCountDomainOwnerLookupKey(
	ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT,
	ownerIdentity = StepsCountDomainReceiptIntegrity.sessionFactOwnerIdentity(
		writerProjectionId,
		writerProjectionVersion,
		logicalFactId,
	),
	ownerRevision = semanticRevision,
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

internal fun StepCounterWindowPayload.recordingDisqualification(
	wal: SourceEventWalEntity,
): SourceProofReason? {
	if (boundaryKind == StepBoundaryKind.BASELINE ||
		boundaryKind == StepBoundaryKind.COUNTER_RESET ||
		boundaryKind == StepBoundaryKind.LEGACY_AMBIGUOUS
	) {
		return SourceProofReason.BASELINE_ONLY
	}
	if (deltaCount == 0L) return SourceProofReason.ZERO_DELTA
	return if (
		deltaCount > 0L &&
		firstCumulativeCount >= 0L &&
		lastCumulativeCount >= firstCumulativeCount &&
		deltaCount == lastCumulativeCount - firstCumulativeCount &&
		windowStartElapsedRealtimeNanos >= 0L &&
		windowEndElapsedRealtimeNanos >= windowStartElapsedRealtimeNanos &&
		windowEndElapsedRealtimeNanos == wal.observedElapsedNanos &&
		bootClockDomainId == wal.clockDomainId &&
		firstProviderSequence >= 0L &&
		lastProviderSequence >= firstProviderSequence &&
		lastProviderSequence == wal.sourceSequence
	) {
		null
	} else {
		SourceProofReason.MALFORMED_EVIDENCE
	}
}

internal fun PressureWindowPayload.recordingCompletenessOrNull(
	wal: SourceEventWalEntity,
): SourceProofCompleteness? = runCatching {
	require(wal.payloadVersion == PressureFactRevisionEntity.QUALIFIED_PRESSURE_PAYLOAD_VERSION)
	require(windowStartElapsedRealtimeNanos >= 0L)
	require(windowEndElapsedRealtimeNanos >= windowStartElapsedRealtimeNanos)
	require(windowEndElapsedRealtimeNanos == wal.observedElapsedNanos)
	require(sampleCount > 0)
	require(firstProviderSequence > 0L)
	require(lastProviderSequence - firstProviderSequence == sampleCount.toLong() - 1L)
	require(lastProviderSequence == wal.sourceSequence)
	require(meanHectopascals.isFinite() && meanHectopascals > 0.0)
	require(sumSquaredDeviations.isFinite() && sumSquaredDeviations >= 0.0)
	require(minimumHectopascals.isFinite() && minimumHectopascals > 0f)
	require(maximumHectopascals.isFinite() && maximumHectopascals >= minimumHectopascals)
	require(meanHectopascals in minimumHectopascals.toDouble()..maximumHectopascals.toDouble())
	require(firstHectopascals?.isFinite() == true)
	require(lastHectopascals?.isFinite() == true)
	require(requireNotNull(firstHectopascals) in minimumHectopascals..maximumHectopascals)
	require(requireNotNull(lastHectopascals) in minimumHectopascals..maximumHectopascals)
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
	val qualification = PressureWindowQualification.classify(this)
	val incomplete = wal.qualityFlags and SourceQualityFlag.INCOMPLETE_WINDOW.bit != 0L
	require(incomplete == (qualification == PressureWindowQualification.PARTIAL))
	if (qualification == PressureWindowQualification.COMPLETE) {
		SourceProofCompleteness.COMPLETE
	} else {
		SourceProofCompleteness.PARTIAL
	}
}.getOrNull()

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
	if (admissionRevisions.isEmpty()) return CurrentRevisionResolution.Missing
	if (admissionRevisions.size > maximumRevisionCount || currentRevisions.size != 1) {
		return CurrentRevisionResolution.Invalid
	}
	val current = currentRevisions.single()
	if (admissionRevisions.any { logicalFactId(it) != logicalFactId(current) }) {
		return CurrentRevisionResolution.Invalid
	}
	val orderedAdmission = admissionRevisions.sortedBy(semanticRevision)
	val orderedLineage = retainedLineage.sortedBy(semanticRevision)
	if (orderedLineage.size > maximumRevisionCount ||
		orderedLineage != orderedAdmission ||
		!lineageAuthenticates(orderedLineage) ||
		orderedLineage.lastOrNull() != current
	) {
		return CurrentRevisionResolution.Invalid
	}
	return CurrentRevisionResolution.Ready(current, orderedLineage)
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
