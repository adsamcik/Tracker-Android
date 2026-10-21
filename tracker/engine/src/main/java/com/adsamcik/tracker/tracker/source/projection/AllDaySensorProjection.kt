package com.adsamcik.tracker.tracker.source.projection

import android.hardware.SensorManager
import com.adsamcik.tracker.shared.base.data.DetectedActivity
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ProviderRegistrationIntervalAuthentication
import com.adsamcik.tracker.shared.base.database.authenticateHistoricalProviderRegistration
import com.adsamcik.tracker.shared.base.database.isAmbientSensorConsentErased
import com.adsamcik.tracker.shared.base.database.data.ActivitySnapshot
import com.adsamcik.tracker.shared.base.database.data.ActivityWalPayload
import com.adsamcik.tracker.shared.base.database.data.ActivityWalPayloadCodec
import com.adsamcik.tracker.shared.base.database.data.AllDaySensorObservationIdentity
import com.adsamcik.tracker.shared.base.database.data.ObservationStampColumns
import com.adsamcik.tracker.shared.base.database.data.PressureSample
import com.adsamcik.tracker.shared.base.database.data.PressureWalPayloadCodec
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.base.database.data.hasExactEligibleAmbientConsentReference
import com.adsamcik.tracker.tracker.source.model.AdmittedSourceEvent
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.source.model.StableActivityTypeCode
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import com.adsamcik.tracker.tracker.source.ingress.continuousAmbientSensorMembers
import kotlin.math.sqrt

/** Materializes independently chosen observations in existing typed tables, without a session. */
internal class AllDaySensorProjection(
	private val source: SourceKind,
	private val database: AppDatabase,
	private val clock: BootClockDomainProvider,
) : Projection {
	init { require(source in SOURCES) }
	override val id = writerId(source)
	override val version = VERSION

	override suspend fun apply(event: AdmittedSourceEvent<out SourcePayload>, context: ProjectionContext) {
		if (event.evidence.source != source) {
			return
		}
		val wal = database.sourceEventWalDao().getByAdmissionOrdinal(event.admissionOrdinal) ?: return
		if (wal.eventId != event.eventId.value || !qualifies(wal)) {
			return
		}
		when (source) {
			SourceKind.ACTIVITY -> saveActivity(wal)
			SourceKind.PRESSURE -> savePressure(wal)
			else -> error("Not an all-day sensor")
		}
	}

	private suspend fun qualifies(wal: SourceEventWalEntity): Boolean {
		if (wal.sourceKind != source.stableCode || !wal.hasQualifiedIntegrity()) {
			return false
		}
		val wall = wal.wallTimeMs ?: return false
		val start = wal.observedIntervalStartNanos ?: wal.observedElapsedNanos
		if (!lifecycleAllows(wal, start, wall) || !historicalConsentAllows(wal, start, wall)) {
			return false
		}
		val fingerprint = wal.physicalConfigurationFingerprint
		return fingerprint != null && database.authenticateHistoricalProviderRegistration(source.stableCode,
			wal.registrationGeneration, wal.sourceInstanceId, wal.clockDomainId,
			fingerprint, wal.capturedCollectedDataEpoch,
			if (source == SourceKind.ACTIVITY) {
				ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE
			} else {
				ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND
			}, start, wal.observedElapsedNanos, wall, clock.current()) !is
			ProviderRegistrationIntervalAuthentication.Rejected
	}

	private suspend fun lifecycleAllows(wal: SourceEventWalEntity, start: Long, wall: Long): Boolean {
		val lifecycle = database.sourceEvidenceStateDao().get() ?: return false
		if (lifecycle.collectedDataEpoch != wal.capturedCollectedDataEpoch ||
			wal.admissionOrdinal <= lifecycle.deletedSourceEventHighWaterOrdinal) {
			return false
		}
		check(database.collectedDataDeletionOperationDao().activeRetentionFloorSettlement() == null) {
			"All-day sensor projection waits for retention settlement"
		}
		if (start < 0L || wal.observedElapsedNanos < start || wal.receivedElapsedNanos < wal.observedElapsedNanos ||
			wall < 0L) {
			return false
		}
		val durationMs = (wal.observedElapsedNanos - start) / NANOS_PER_MILLISECOND
		return lifecycle.retainedFromMs?.let { wall - durationMs < it } != true
	}

	// Keep historical consent, its next boundary and explicit erasure checks in observation order.
	@Suppress("ReturnCount")
	private suspend fun historicalConsentAllows(wal: SourceEventWalEntity, start: Long, wall: Long): Boolean {
		val durationMs = (wal.observedElapsedNanos - start) / NANOS_PER_MILLISECOND
		val demand = qualifiedDemand(wal, start) ?: return false
		if (database.isAmbientSensorConsentErased(source.stableCode, demand.consentEpoch,
			wal.capturedCollectedDataEpoch)) {
			return false
		}
		if (!consentHasStarted(wal, demand, start, wall - durationMs)) {
			return false
		}
		val policyDao = database.sourcePolicyDao()
		val nextConsent = policyDao.nextConsentEpoch(source.stableCode,
			com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose.AMBIENT_PRODUCT, demand.consentEpoch)
		if (nextConsent?.let {
			if (it.effectiveBootId == wal.clockDomainId) {
				it.effectiveElapsedRealtimeNanos <= wal.observedElapsedNanos
			} else {
				it.effectiveWallTimeMs <= wall
			}
		} == true) {
			return false
		}
		// The immutable demand was accepted with retention authority. Later settings changes never hide
		// saved data; physical deletion/retention fences are checked before replay instead.
		return demand.liveAmbientRetentionPolicyId != null && demand.liveAmbientRetentionApprovalRevision != null
	}

	private suspend fun consentHasStarted(
		wal: SourceEventWalEntity,
		demand: SourceDemandEntity,
		start: Long,
		windowStartWall: Long,
	): Boolean {
		val policyDao = database.sourcePolicyDao()
		val policy = policyDao.policyAtRevision(demand.sourcePolicyRevision, source.stableCode) ?: return false
		val consent = policyDao.consentEpoch(source.stableCode,
			com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose.AMBIENT_PRODUCT, demand.consentEpoch)
		return policy.hasExactEligibleAmbientConsentReference(consent) &&
			boundaryReached(wal, start, windowStartWall, policy.effectiveBootId,
				policy.effectiveElapsedRealtimeNanos, policy.effectiveWallTimeMs) &&
			consent != null && boundaryReached(wal, start, windowStartWall, consent.effectiveBootId,
				consent.effectiveElapsedRealtimeNanos, consent.effectiveWallTimeMs)
	}

	private fun boundaryReached(wal: SourceEventWalEntity, elapsed: Long, wall: Long,
		boot: String, effectiveElapsed: Long, effectiveWall: Long): Boolean =
		if (boot == wal.clockDomainId) {
			effectiveElapsed <= elapsed
		} else {
			effectiveWall <= wall
		}

	// Missing or inconsistent original members are separate ordered rejection points.
	@Suppress("ReturnCount")
	private suspend fun qualifiedDemand(wal: SourceEventWalEntity, start: Long): SourceDemandEntity? {
		val broker = database.sourceBrokerDao()
		val revision = wal.authorizationRevision ?: return null
		val rows = broker.authorizationRevisionBounded(source.stableCode, wal.registrationGeneration, revision,
			MAX_AUTHORIZATION_MEMBERS + 1)
		if (rows.size > MAX_AUTHORIZATION_MEMBERS) {
			return null
		}
		val authorization = rows.toAuthorizationSnapshotOrNull() ?: return null
		if (authorization.authorizationFingerprint != wal.authorizationFingerprint ||
			authorization.purposeEligibilityMask != wal.authorizationPurposeEligibilityMask ||
			authorization.effectiveBootId != wal.clockDomainId) {
			return null
		}
		val demands = broker.demandsByIds(authorization.authorizedMembers.mapNotNull { it.demandId })
		val recomputed = SourceBrokerAuthorization.rows(source.stableCode, wal.registrationGeneration, revision,
			demands, authorization.effectiveBootId, authorization.effectiveElapsedRealtimeNanos,
			authorization.members.first().effectiveWallTimeMs)
		if (recomputed.sortedBy { it.memberId } != rows.sortedBy { it.memberId }) {
			return null
		}
		val ambient = broker.continuousAmbientSensorMembers(authorization, start, wal.observedElapsedNanos)
			.singleOrNull() ?: return null
		return demands.singleOrNull { it.demandId == ambient.demandId }
	}

	private suspend fun saveActivity(wal: SourceEventWalEntity) {
		val payload = ActivityWalPayloadCodec.decodeCanonical(wal.payloadVersion, wal.payload) ?: return
		if (payload.providerElapsedRealtimeNanos != wal.observedElapsedNanos) {
			return
		}
		database.activitySnapshotDao().insert(ActivitySnapshot(timeMs = requireNotNull(wal.wallTimeMs),
			activityType = platformActivityType(payload.activityType),
			// The native table predates nullable confidence; transitions expose no confidence value.
			confidence = (payload as? ActivityWalPayload.Recognition)?.confidencePercent ?: 0,
			isTransition = payload is ActivityWalPayload.Transition, createdAt = wal.createdAtMs,
			sourceSignalId = AllDaySensorObservationIdentity.activity(wal.eventId), observationStamp = stamp(wal).let {
				if (payload is ActivityWalPayload.Transition) {
					it.copy(capabilityFlags = "${it.capabilityFlags},ACTIVITY_TRANSITION:${payload.transitionType}")
				} else {
					it
				}
			}))
	}

	private suspend fun savePressure(wal: SourceEventWalEntity) {
		val payload = PressureWalPayloadCodec.decodeCanonical(wal.payloadVersion, wal.payload) ?: return
		if (payload.windowStartElapsedRealtimeNanos != wal.observedIntervalStartNanos ||
			payload.windowEndElapsedRealtimeNanos != wal.observedElapsedNanos) {
			return
		}
		val mean = payload.meanHectopascals.toFloat()
		database.pressureSampleDao().insert(PressureSample(timeMs = requireNotNull(wal.wallTimeMs),
			elapsedRealtimeNanos = wal.observedElapsedNanos, pressureHpa = mean,
			altitudeM = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, mean),
			bucketId = null, createdAt = wal.createdAtMs,
			sourceSignalId = AllDaySensorObservationIdentity.pressure(wal.eventId), sampleCount = payload.sampleCount,
			minPressureHpa = payload.minimumHectopascals, maxPressureHpa = payload.maximumHectopascals,
			standardDeviationHpa = sqrt(payload.sumSquaredDeviations / payload.sampleCount).toFloat(),
			windowStartElapsedRealtimeNanos = payload.windowStartElapsedRealtimeNanos,
			windowEndElapsedRealtimeNanos = payload.windowEndElapsedRealtimeNanos,
			observationStamp = stamp(wal).copy(sourceFirstSequence = payload.firstProviderSequence,
				capabilityFlags = "PRESSURE_QUALIFICATION:${payload.qualification}," +
					"PRESSURE_CLOSURE:${payload.closureKind.name},SOURCE_QUALITY_FLAGS:${wal.qualityFlags}",
				sourceFirstElapsedRealtimeNanos = payload.windowStartElapsedRealtimeNanos)))
	}

	private fun stamp(wal: SourceEventWalEntity) = ObservationStampColumns(sourceTimeMs = wal.wallTimeMs,
		sourceElapsedRealtimeNanos = wal.observedElapsedNanos, receivedTimeMs = wal.receivedWallTimeMs,
		receivedElapsedRealtimeNanos = wal.receivedElapsedNanos, sourceSequence = wal.sourceSequence,
		clockDomainId = wal.clockDomainId, bootClockDomainId = wal.clockDomainId,
		sourceAgeMs = (wal.receivedElapsedNanos - wal.observedElapsedNanos) / NANOS_PER_MILLISECOND,
		timeUncertaintyMs = wal.wallTimeUncertaintyMs, capabilityFlags = "SOURCE_QUALITY_FLAGS:${wal.qualityFlags}")

	private fun platformActivityType(type: Int): Int = when (type) {
		StableActivityTypeCode.STILL -> DetectedActivity.STILL
		StableActivityTypeCode.WALKING -> DetectedActivity.WALKING
		StableActivityTypeCode.RUNNING -> DetectedActivity.RUNNING
		StableActivityTypeCode.ON_BICYCLE -> DetectedActivity.ON_BICYCLE
		StableActivityTypeCode.IN_VEHICLE -> DetectedActivity.IN_VEHICLE
		StableActivityTypeCode.ON_FOOT -> DetectedActivity.ON_FOOT
		StableActivityTypeCode.TILTING -> DetectedActivity.TILTING
		else -> DetectedActivity.UNKNOWN
	}.value

	companion object {
		const val VERSION = AllDaySensorObservationIdentity.PROJECTION_VERSION
		const val WRITER_OWNER_GENERATION = 1L
		val SOURCES = setOf(SourceKind.ACTIVITY, SourceKind.PRESSURE)
		fun writerId(source: SourceKind): String = AllDaySensorObservationIdentity.projectionId(source.stableCode)
		private const val NANOS_PER_MILLISECOND = 1_000_000L
		private const val MAX_AUTHORIZATION_MEMBERS = 64
	}
}
