package com.adsamcik.tracker.tracker.source.location

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.data.LocationAcquisitionMode
import com.adsamcik.tracker.shared.base.data.LocationRequestPriority
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.SourceEventWalDao
import com.adsamcik.tracker.shared.base.database.dao.SourceEventWalPayloadPreflightRow
import com.adsamcik.tracker.shared.base.database.dao.SourceEventWalDeliveryPayloadPreflightRow
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.LocationRegistrationPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.preferences.tracking.SourceQos
import com.adsamcik.tracker.tracker.source.coordinator.EncodedSourcePlan
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.ingress.SourcePayloadCodec
import com.adsamcik.tracker.tracker.source.model.ADAPTIVE_PROVIDER_ITEM_MAXIMUM_AGE_MS
import com.adsamcik.tracker.tracker.source.model.LocationFixPayload
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION
import com.adsamcik.tracker.tracker.source.model.LOCATION_OPTIONAL_ACCURACY_PAYLOAD_VERSION
import com.adsamcik.tracker.tracker.source.model.LocationMode
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryIdentity
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import com.adsamcik.tracker.tracker.source.model.sourceDeliveryIdentity
import com.adsamcik.tracker.stats.api.PolicyTier
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import javax.inject.Inject

internal enum class LocationWalAdapterRejection {
	MISSING_EVENT,
	WRONG_SOURCE,
	INCOMPLETE_CAPTURE_BINDING,
	WAL_INTEGRITY_MISMATCH,
	SOURCE_SEQUENCE_MISMATCH,
	DELIVERY_IDENTITY_MISMATCH,
	DELIVERY_TOO_LARGE,
	MALFORMED_DELIVERY,
	UNSUPPORTED_PAYLOAD,
	MISSING_HISTORICAL_PLAN,
	HISTORICAL_PLAN_MISMATCH,
	MISSING_REGISTRATION,
	REGISTRATION_MISMATCH,
	MISSING_AUTHORIZATION,
	AUTHORIZATION_MISMATCH,
	MISSING_POLICY,
	POLICY_MISMATCH,
	MISSING_CONSENT,
	CONSENT_MISMATCH,
	MISSING_SESSION,
	SESSION_MISMATCH,
	AFTER_SESSION_CUTOFF,
	AFTER_AUTHORIZATION_DENIAL,
	MANIFEST_TIMELINE_UNVERIFIABLE,
	MANIFEST_MISMATCH,
	MISSING_SEGMENT,
	SEGMENT_MISMATCH,
	INVALID_STORED_ZONE,
	DELETED_EVIDENCE,
	RETAINED_EVIDENCE,
	TEMPORAL_AUTHORITY_UNVERIFIABLE,
	MOCK_PROVENANCE_UNVERIFIABLE,
}

internal sealed interface LocationWalAdapterResult {
	data class Evaluated(
		val qualification: LocationObservationQualification,
		val acquisitionMetadata: LocationWalAcquisitionMetadata =
			LocationWalAcquisitionMetadata.UNKNOWN,
	) : LocationWalAdapterResult

	data class Rejected(val reason: LocationWalAdapterRejection) : LocationWalAdapterResult
}

internal data class LocationWalAcquisitionMetadata(
	val acquisitionMode: LocationAcquisitionMode,
	val requestPriority: LocationRequestPriority,
	val requiredAccuracyMeters: Float,
	val policyTier: PolicyTier,
	val policyName: String,
	val curationVersion: Int,
	val altitudeModelVersion: Int,
	val altitudeEstimatorVersion: Int,
	val altitudeCalibrationVersion: Int,
) {
	val isQualified: Boolean
		get() = requestPriority != LocationRequestPriority.UNKNOWN &&
			requiredAccuracyMeters.isFinite() &&
			requiredAccuracyMeters >= 0f &&
			policyTier != PolicyTier.OFF &&
			policyName.isNotBlank() &&
			policyName != "UNKNOWN" &&
			curationVersion > 0 &&
			altitudeModelVersion >= 0 &&
			altitudeEstimatorVersion >= 0 &&
			altitudeCalibrationVersion >= 0

	companion object {
		val UNKNOWN = LocationWalAcquisitionMetadata(
			LocationAcquisitionMode.UNKNOWN,
			LocationRequestPriority.UNKNOWN,
			Float.NaN,
			PolicyTier.OFF,
			"UNKNOWN",
			0,
			0,
			0,
			0,
		)
	}
}

internal const val PROTECTED_LOCATION_CANONICAL_CURATION_VERSION = 1

internal fun interface ProtectedLocationWalQualifier {
	suspend fun qualify(eventId: SourceEventId): LocationWalAdapterResult
	suspend fun qualifyAmbient(eventId: SourceEventId): LocationAmbientQualification =
		LocationAmbientQualification.NotEligible
	suspend fun qualifyBatch(eventIds: List<SourceEventId>): Map<SourceEventId, LocationWalAdapterResult> =
		eventIds.distinct().associateWith { qualify(it) }
	suspend fun qualifyForPurposes(eventId: SourceEventId): LocationPurposeQualification {
		val ambient = qualifyAmbient(eventId)
		return LocationPurposeQualification(ambient, if (ambient.isAmbientOnly()) {
			null
		} else {
			qualify(eventId)
		})
	}
}

internal data class LocationPurposeQualification(
	val ambient: LocationAmbientQualification,
	val capture: LocationWalAdapterResult?,
)

private fun LocationAmbientQualification.isAmbientOnly(): Boolean = this is LocationAmbientQualification.Qualified &&
	command.wal.authorizationPurposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L

/**
 * Dormant read-only bridge from the canonical source WAL to the pure Location qualifier.
 *
 * The adapter accepts only an event identity. Every provider value and every authority field is
 * recovered and authenticated in one Room snapshot; no caller can supply a fix or a mutable
 * latest-plan pointer. Retained v1 rows terminate in a typed mock-provenance rejection because
 * their payload did not store that immutable provider flag. Canonical v2 rows can return a command,
 * but this adapter never persists it, so the established Location writer remains the sole writer.
 */
internal class LocationWalQualificationAdapter @Inject constructor(
	private val database: AppDatabase,
	private val payloadCodec: SourcePayloadCodec,
	private val planCodec: SourcePlanCodec,
	private val bootClockDomainProvider: BootClockDomainProvider,
) : ProtectedLocationWalQualifier {
	private val physicalReader = LocationWalPhysicalEvidenceReader()

	/** Each operation owns its snapshot; no authority survives a writer transaction. */
	override suspend fun qualify(eventId: SourceEventId): LocationWalAdapterResult = database.withTransaction {
		QualificationSnapshot().qualify(eventId)
	}

	override suspend fun qualifyBatch(eventIds: List<SourceEventId>): Map<SourceEventId, LocationWalAdapterResult> =
		database.withTransaction {
			val snapshot = QualificationSnapshot()
			eventIds.distinct().associateWith { snapshot.qualify(it) }
		}

	override suspend fun qualifyAmbient(eventId: SourceEventId): LocationAmbientQualification = database.withTransaction {
		QualificationSnapshot().qualifyAmbient(eventId)
	}

	override suspend fun qualifyForPurposes(eventId: SourceEventId): LocationPurposeQualification =
		database.withTransaction {
			val snapshot = QualificationSnapshot()
			val ambient = snapshot.qualifyAmbient(eventId)
			LocationPurposeQualification(ambient, if (ambient.isAmbientOnly()) {
				null
			} else {
				snapshot.qualify(eventId)
			})
		}

	/** Bounded memo for the current delivery/run only, inside the surrounding Room transaction. */
	private inner class QualificationSnapshot {
		private var lastPhysical: LocationPhysicalEvidence? = null
		private val delivery = LocationDeliverySnapshot()
		private var lastPlanKey: Triple<Long, Long, Long>? = null
		private var lastPlan: LocationPlan? = null
		private var lastTimeline: Pair<SourceServiceRunEntity, LocationManifestTimeline>? = null

		suspend fun physical(eventId: SourceEventId): LocationPhysicalEvidence {
			lastPhysical?.takeIf { it.wal.eventId == eventId.value }?.let { return it }
			return physicalReader.authenticate(database, payloadCodec, eventId, delivery)
				.also { lastPhysical = it }
		}

		suspend fun registrationPlan(wal: SourceEventWalEntity): LocationPlan? {
			val revision = wal.configRevision ?: return null
			val authorization = wal.authorizationRevision ?: return null
			val key = Triple(wal.registrationGeneration, revision, authorization)
			if (lastPlanKey != key) {
				lastPlan = actualRegistrationPlan(wal)
				lastPlanKey = key
			}
			return lastPlan
		}

		suspend fun qualify(eventId: SourceEventId): LocationWalAdapterResult = try {
			val physical = physical(eventId)
			LocationCaptureQualification(database, planCodec, bootClockDomainProvider, ::hasExactQualifiedMembership)
				.qualify(physical, ::registrationPlan, ::manifestTimeline)
		} catch (failure: LocationPhysicalEvidenceRejected) {
			rejected(failure.reason)
		}

		suspend fun qualifyAmbient(eventId: SourceEventId): LocationAmbientQualification = try {
			database.qualifyAmbientLocationPhysical(
				physical(eventId), ::registrationPlan, ::hasExactQualifiedMembership, bootClockDomainProvider,
			)
		} catch (failure: LocationPhysicalEvidenceRejected) {
			LocationAmbientQualification.Rejected(failure.reason)
		}

		suspend fun manifestTimeline(run: SourceServiceRunEntity): LocationManifestTimeline {
			lastTimeline?.takeIf { it.first == run }?.let { return it.second }
			val manifests = database.sourceSessionDao().manifestsForServiceRun(run.serviceRunId, MAX_MANIFESTS_PER_RUN + 1)
			if (manifests.size > MAX_MANIFESTS_PER_RUN || !SessionManifestIntegrity.hasValidServiceRunTimeline(run, manifests)) {
				rejectLocation(LocationWalAdapterRejection.MANIFEST_TIMELINE_UNVERIFIABLE)
			}
			val sources = database.trackingHistoryReadDao().manifestSources(
				listOf(run.serviceRunId), MAX_MANIFEST_SOURCE_ROWS + 1,
			)
			val groupedSources = sources.groupBy { it.logicalTrackingId to it.manifestRevision }
			if (sources.size > MAX_MANIFEST_SOURCE_ROWS || manifests.any {
				!SessionManifestIntegrity.verify(it, groupedSources[it.logicalTrackingId to it.manifestRevision].orEmpty())
			}) {
				rejectLocation(LocationWalAdapterRejection.MANIFEST_TIMELINE_UNVERIFIABLE)
			}
			return LocationManifestTimeline(manifests, sources).also { lastTimeline = run to it }
		}
	}


	/** Ingress narrows eligibility independently for each fix in a batched physical delivery. */
	private fun hasExactQualifiedMembership(wal: SourceEventWalEntity, authorization: SourceAuthorizationSnapshot,
		demands: List<SourceDemandEntity>): Boolean {
		val age = wal.receivedElapsedNanos - wal.observedElapsedNanos
		if (age < 0) {
			return false
		}
		val eligible = authorization.authorizedMembers.filter { member ->
			val demand = demands.singleOrNull { it.demandId == member.demandId } ?: return@filter false
			age <= runCatching {
				Math.multiplyExact(demand.maximumAgeMs, LOCATION_WAL_NANOS_PER_MILLISECOND)
			}.getOrDefault(Long.MAX_VALUE)
		}
		val mask = eligible.fold(0L) { bits, member ->
            bits or SourceBrokerPurpose.mask(requireNotNull(member.purpose)) }
		if (mask == 0L || mask != wal.authorizationPurposeEligibilityMask) {
			return false
		}
		val captures = eligible.filter { it.purpose == SourceBrokerPurpose.SESSION_CAPTURE && it.persistenceEligible }
		if (captures.size > 1) {
			return false
		}
		val capture = captures.singleOrNull()
		return wal.matchesQualifiedCapture(capture)
	}

	private fun SourceEventWalEntity.matchesQualifiedCapture(capture: SourceAuthorizationEntity?): Boolean =
		logicalTrackingId == capture?.logicalTrackingId && serviceRunId == capture?.serviceRunId &&
			sourcePolicyRevision == capture?.sourcePolicyRevision &&
			captureConsentEpoch == capture?.consentEpoch &&
			sessionManifestRevision == capture?.manifestRevision &&
			lifecycleLeaseGeneration == capture?.lifecycleLeaseGeneration &&
			planAttribution == (if (capture == null) {
				PlanAttribution.RECEIVE_TIME_ONLY
			} else {
				PlanAttribution.CAPTURED_REGISTRATION
			}).ordinal

	private suspend fun actualRegistrationPlan(wal: SourceEventWalEntity): LocationPlan? {
		val revision = wal.configRevision ?: return null
		val authorizationRevision = wal.authorizationRevision ?: return null
		val stored = database.locationPurposeDao().registrationPlan(wal.registrationGeneration, revision,
            authorizationRevision)
			?: return null
		val plan = runCatching { planCodec.decode(stored.payload) as? LocationPlan }.getOrNull()
		val encoded = plan?.let { runCatching { planCodec.encode(it) }.getOrNull() }
		if (!stored.hasActualLocationPlanIdentity(wal, plan, PLAN_PAYLOAD_VERSION) || plan == null) {
			throw LocationPhysicalEvidenceRejected(LocationWalAdapterRejection.HISTORICAL_PLAN_MISMATCH)
		}
		if (!plan.hasCanonicalRegistrationPayload(stored, encoded, revision)) {
			throw LocationPhysicalEvidenceRejected(LocationWalAdapterRejection.HISTORICAL_PLAN_MISMATCH)
		}
		return plan
	}


	companion object {
		private const val LOCATION_SOURCE = 1
		private const val PLAN_PAYLOAD_VERSION = 1
		// DataOutputStream.writeUTF permits 65,535 provider bytes; nullable accuracy adds one flag.
		internal const val MAX_LOCATION_PAYLOAD_BYTES = 65_535 + 52
		private const val MAX_MANIFESTS_PER_RUN = 256
		private const val MAX_MANIFEST_SOURCE_ROWS = 4_096
	}
}

private data class LocationDeliveryKey(val epoch: Long, val clock: String, val identity: String)
private class LocationDeliverySnapshot {
	var key: LocationDeliveryKey? = null
	var units: List<LocationPhysicalEvidence> = emptyList()
}

internal fun rejectLocation(reason: LocationWalAdapterRejection): Nothing =
	throw LocationPhysicalEvidenceRejected(reason)

internal data class LocationPhysicalEvidence(
	val wal: SourceEventWalEntity,
	val payload: LocationFixPayload,
	val deliveryIdentity: SourceDeliveryIdentity,
)

internal class LocationPhysicalEvidenceRejected(val reason: LocationWalAdapterRejection) : IllegalStateException()

/** One bounded physical-delivery check shared by session and all-day Location qualification. */
// One canonical Location delivery reader owns its bounded queries, protocol checks and codec phases.
@Suppress("TooManyFunctions")
private class LocationWalPhysicalEvidenceReader {
	private val decodedPayloads = LocationDeliveryPayloadMemo()

	suspend fun authenticate(
		database: AppDatabase,
		payloadCodec: SourcePayloadCodec,
		eventId: SourceEventId,
		snapshot: LocationDeliverySnapshot,
	): LocationPhysicalEvidence {
		val wal = loadSelected(database, eventId)
		val deliveryIdentity = authenticatedDeliveryIdentity(wal)
		val deliveryUnitIndex = wal.deliveryUnitIndex
		val deliveryUnitCount = wal.deliveryUnitCount
		if (deliveryUnitIndex == null || deliveryUnitCount == null ||
			deliveryUnitIndex !in 0 until deliveryUnitCount
		) {
			reject(LocationWalAdapterRejection.INCOMPLETE_CAPTURE_BINDING)
		}
		if (deliveryUnitCount > MAX_LOCATION_DELIVERY_UNITS) {
			reject(LocationWalAdapterRejection.DELIVERY_TOO_LARGE)
		}
		val key = LocationDeliveryKey(wal.capturedCollectedDataEpoch, wal.clockDomainId, deliveryIdentity.value)
		if (snapshot.key != key) {
			val delivery = loadDelivery(database, wal, deliveryIdentity, deliveryUnitIndex, deliveryUnitCount)
			val decodedDelivery = decodeAndAuthenticateDelivery(payloadCodec, delivery, wal)
				?: reject(LocationWalAdapterRejection.MALFORMED_DELIVERY)
			authenticateCompleteDeliveryIdentity(decodedDelivery, deliveryUnitCount, deliveryIdentity)
			snapshot.units = decodedDelivery.map { LocationPhysicalEvidence(it.wal, it.payload, deliveryIdentity) }
			snapshot.key = key
		}
		val selected = snapshot.units.singleOrNull { it.wal.deliveryUnitIndex == deliveryUnitIndex }
			?: reject(LocationWalAdapterRejection.MALFORMED_DELIVERY)
		if (!selected.wal.hasSamePersistedValueAs(wal)) {
			reject(LocationWalAdapterRejection.MALFORMED_DELIVERY)
		}
		return selected
	}

	private fun authenticatedDeliveryIdentity(wal: SourceEventWalEntity): SourceDeliveryIdentity {
		val deliveryIdentity = wal.deliveryIdentity?.let { value ->
			runCatching { SourceDeliveryIdentity(value) }.getOrNull()
		} ?: reject(LocationWalAdapterRejection.INCOMPLETE_CAPTURE_BINDING)
		return deliveryIdentity
	}

	private fun authenticateCompleteDeliveryIdentity(
		decodedDelivery: List<DecodedLocationDeliveryUnit>,
		deliveryUnitCount: Int,
		deliveryIdentity: SourceDeliveryIdentity,
	) {
		// Admission and retention may leave a sparse batch; every retained point keeps its own proof.
		if (decodedDelivery.size == deliveryUnitCount &&
			sourceDeliveryIdentity(canonicalDeliveryBytes(decodedDelivery)).value != deliveryIdentity.value
		) {
			reject(LocationWalAdapterRejection.DELIVERY_IDENTITY_MISMATCH)
		}
	}

	private suspend fun loadSelected(database: AppDatabase, eventId: SourceEventId): SourceEventWalEntity {
		val walDao = database.sourceEventWalDao()
		val selectedPreflight = walDao.payloadPreflightByEventId(eventId.value)
			?: reject(LocationWalAdapterRejection.MISSING_EVENT)
		if (selectedPreflight.sourceKind != LOCATION_SOURCE) {
			reject(LocationWalAdapterRejection.WRONG_SOURCE)
		}
		if (selectedPreflight.payloadBytes > MAX_LOCATION_PAYLOAD_BYTES) {
			reject(LocationWalAdapterRejection.DELIVERY_TOO_LARGE)
		}
		if (selectedPreflight.payloadBytes <= 0L) {
			reject(LocationWalAdapterRejection.MALFORMED_DELIVERY)
		}
		val wal = walDao.boundedPayloadByEventId(eventId.value, MAX_LOCATION_PAYLOAD_BYTES)
			?: reject(LocationWalAdapterRejection.MALFORMED_DELIVERY)
		if (!wal.matchesSelectedPreflight(selectedPreflight)) {
			reject(LocationWalAdapterRejection.MALFORMED_DELIVERY)
		}
		if (!wal.hasQualifiedIntegrity()) {
			reject(LocationWalAdapterRejection.WAL_INTEGRITY_MISMATCH)
		}
		if (wal.payloadVersion !in SUPPORTED_LOCATION_PAYLOAD_VERSIONS) {
			reject(LocationWalAdapterRejection.UNSUPPORTED_PAYLOAD)
		}
		authenticateSelectedSequence(walDao, wal)

		return wal
	}

	// The loaded row must match the complete payload-free preflight before it is authenticated.
	@Suppress("ComplexCondition")
	private fun SourceEventWalEntity.matchesSelectedPreflight(
		selectedPreflight: SourceEventWalPayloadPreflightRow,
	): Boolean = !(
		eventId != selectedPreflight.eventId || sourceKind != selectedPreflight.sourceKind ||
		capturedCollectedDataEpoch != selectedPreflight.capturedCollectedDataEpoch ||
		clockDomainId != selectedPreflight.clockDomainId ||
		deliveryIdentity != selectedPreflight.deliveryIdentity ||
		deliveryUnitIndex != selectedPreflight.deliveryUnitIndex ||
		deliveryUnitCount != selectedPreflight.deliveryUnitCount ||
		payload.size.toLong() != selectedPreflight.payloadBytes
	)

	private suspend fun authenticateSelectedSequence(
		walDao: SourceEventWalDao,
		wal: SourceEventWalEntity,
	) {
		if (wal.sourceSequence < 0L || wal.sourceSequence > MAX_DURABLE_SOURCE_SEQUENCE) {
			reject(LocationWalAdapterRejection.SOURCE_SEQUENCE_MISMATCH)
		}
		val sequenceRow = walDao.identityBySourceSequence(
			LOCATION_SOURCE,
			wal.sourceInstanceId,
			wal.sourceSequence,
		)
		if (sequenceRow?.eventId != wal.eventId || sequenceRow.admissionOrdinal != wal.admissionOrdinal ||
			sequenceRow.integrityIdentity != wal.integrityIdentity
		) {
			reject(LocationWalAdapterRejection.SOURCE_SEQUENCE_MISMATCH)
		}
	}

	private suspend fun loadDelivery(
		database: AppDatabase,
		wal: SourceEventWalEntity,
		deliveryIdentity: SourceDeliveryIdentity,
		deliveryUnitIndex: Int,
		deliveryUnitCount: Int,
	): List<SourceEventWalEntity> {
		val walDao = database.sourceEventWalDao()
		val deliveryPreflight = loadDeliveryPreflight(
			walDao, wal, deliveryIdentity, deliveryUnitIndex, deliveryUnitCount,
		)
		val delivery = walDao.deliveryEventsWithBoundedPayload(
			sourceKind = LOCATION_SOURCE,
			collectedDataEpoch = wal.capturedCollectedDataEpoch,
			clockDomainId = wal.clockDomainId,
			deliveryIdentity = deliveryIdentity.value,
			maximumPayloadBytes = MAX_LOCATION_PAYLOAD_BYTES,
			limit = MAX_LOCATION_DELIVERY_UNITS + 1,
		)
		if (delivery.size != deliveryPreflight.size) {
			reject(LocationWalAdapterRejection.MALFORMED_DELIVERY)
		}
		if (delivery.indices.any { index ->
			val row = delivery[index]
			val preflight = deliveryPreflight[index]
			row.eventId != preflight.eventId || row.deliveryUnitIndex != preflight.deliveryUnitIndex ||
				row.deliveryUnitCount != preflight.deliveryUnitCount ||
				row.payload.size.toLong() != preflight.payloadBytes
		}) {
			reject(LocationWalAdapterRejection.MALFORMED_DELIVERY)
		}
		return delivery
	}

	private suspend fun loadDeliveryPreflight(
		walDao: SourceEventWalDao,
		wal: SourceEventWalEntity,
		deliveryIdentity: SourceDeliveryIdentity,
		deliveryUnitIndex: Int,
		deliveryUnitCount: Int,
	): List<SourceEventWalDeliveryPayloadPreflightRow> {
		val deliveryPreflight = walDao.deliveryPayloadPreflight(
			sourceKind = LOCATION_SOURCE,
			collectedDataEpoch = wal.capturedCollectedDataEpoch,
			clockDomainId = wal.clockDomainId,
			deliveryIdentity = deliveryIdentity.value,
			limit = MAX_LOCATION_DELIVERY_UNITS + 1,
		)
		if (deliveryPreflight.size > MAX_LOCATION_DELIVERY_UNITS ||
			deliveryPreflight.any { it.payloadBytes > MAX_LOCATION_PAYLOAD_BYTES }
		) {
			reject(LocationWalAdapterRejection.DELIVERY_TOO_LARGE)
		}
		if (deliveryPreflight.size !in 1..deliveryUnitCount ||
			deliveryPreflight.any { it.payloadBytes <= 0L || it.deliveryUnitCount != deliveryUnitCount }
		) {
			reject(LocationWalAdapterRejection.MALFORMED_DELIVERY)
		}
		if (!hasOrderedDeliveryIndexes(deliveryPreflight.map { it.deliveryUnitIndex }, deliveryUnitCount) ||
			deliveryPreflight.singleOrNull { it.deliveryUnitIndex == deliveryUnitIndex }?.eventId != wal.eventId
		) {
			reject(LocationWalAdapterRejection.MALFORMED_DELIVERY)
		}
		return deliveryPreflight
	}

	private fun decodeAndAuthenticateDelivery(
		payloadCodec: SourcePayloadCodec,
		delivery: List<SourceEventWalEntity>,
		target: SourceEventWalEntity,
	): List<DecodedLocationDeliveryUnit>? {
		if (!hasValidDeliveryStructure(delivery, target)) {
			return null
		}
		val declaredCount = requireNotNull(target.deliveryUnitCount)
		val targetIndex = requireNotNull(target.deliveryUnitIndex)
		val decoded = delivery.map { row ->
			if (!row.matchesDeliveryBinding(target, declaredCount)) {
				return null
			}
			decodeDeliveryUnit(payloadCodec, row) ?: return null
		}
		return decoded.takeIf { units ->
			units.singleOrNull { it.wal.deliveryUnitIndex == targetIndex }?.wal?.hasSamePersistedValueAs(target) == true
		}
	}

	// Ordered metadata rejection keeps index, cardinality and checked sequence bounds together.
	@Suppress("ReturnCount")
	private fun hasValidDeliveryStructure(
		delivery: List<SourceEventWalEntity>,
		target: SourceEventWalEntity,
	): Boolean {
		val declaredCount = target.deliveryUnitCount ?: return false
		val targetIndex = target.deliveryUnitIndex ?: return false
		if (declaredCount !in 1..MAX_LOCATION_DELIVERY_UNITS || targetIndex !in 0 until declaredCount ||
			delivery.size !in 1..declaredCount ||
			!hasOrderedDeliveryIndexes(delivery.map { it.deliveryUnitIndex }, declaredCount)
		) {
			return false
		}
		return hasOrderedDeliverySequences(delivery, declaredCount)
	}

	private fun hasOrderedDeliveryIndexes(indexes: List<Int?>, declaredCount: Int): Boolean =
		indexes.all { it != null && it in 0 until declaredCount } &&
			indexes.filterNotNull().let { it == it.distinct().sorted() }

	private fun hasOrderedDeliverySequences(delivery: List<SourceEventWalEntity>, declaredCount: Int): Boolean {
		val firstSequence = delivery.first().sourceSequence
		if (firstSequence < 0L || delivery.last().sourceSequence > MAX_DURABLE_SOURCE_SEQUENCE ||
			delivery.zipWithNext().any { (prior, next) -> prior.sourceSequence >= next.sourceSequence }
		) {
			return false
		}
		if (delivery.size < declaredCount) {
			return true
		}
		val lastSequence = runCatching {
			Math.addExact(firstSequence, declaredCount.toLong() - 1L)
		}.getOrNull() ?: return false
		return delivery.last().sourceSequence == lastSequence
	}

	// One saved delivery-binding tuple; checks stay ordered and short-circuit before decoding.
	@Suppress("CyclomaticComplexMethod", "ComplexCondition")
	private fun SourceEventWalEntity.matchesDeliveryBinding(
		target: SourceEventWalEntity,
		declaredCount: Int,
	): Boolean = !(
		payload.size !in 1..MAX_LOCATION_PAYLOAD_BYTES ||
		!hasQualifiedIntegrity() || deliveryUnitCount != declaredCount ||
		deliveryIdentity != target.deliveryIdentity || sourceKind != LOCATION_SOURCE ||
		payloadVersion != target.payloadVersion ||
		providerDedupKey != null || admissionOrdinal <= 0L ||
		sourceInstanceId != target.sourceInstanceId ||
		registrationGeneration != target.registrationGeneration ||
		physicalConfigurationFingerprint != target.physicalConfigurationFingerprint ||
		configRevision != target.configRevision ||
		capturedCollectedDataEpoch != target.capturedCollectedDataEpoch ||
		clockDomainId != target.clockDomainId || activityAutomationEpoch != null ||
		observedIntervalStartNanos != observedElapsedNanos ||
		receivedElapsedNanos < observedElapsedNanos ||
		(payloadVersion >= LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION &&
			receivedWallTimeMs == null) ||
		receivedWallTimeMs != target.receivedWallTimeMs ||
		wallTimeMs == null || wallTimeUncertaintyMs == null ||
		wallTimeUncertaintyMs !in 0L..1L
	)

	private fun decodeDeliveryUnit(
		payloadCodec: SourcePayloadCodec,
		row: SourceEventWalEntity,
	): DecodedLocationDeliveryUnit? {
		val payload = decodedPayloads.decode(payloadCodec, row) ?: return null
		val canonicalPayload = runCatching { payloadCodec.encode(payload, row.payloadVersion) }.getOrNull()
			?: return null
		if (!canonicalPayload.bytes.contentEquals(row.payload) ||
			canonicalPayload.checksum != row.payloadChecksum
		) {
			return null
		}
		return DecodedLocationDeliveryUnit(row, payload)
	}

	private fun SourceEventWalEntity.hasSamePersistedValueAs(other: SourceEventWalEntity): Boolean =
		qualityConfidence == other.qualityConfidence &&
			this == other.copy(payload = payload, qualityConfidence = qualityConfidence) &&
			payload.contentEquals(other.payload)

	private fun canonicalDeliveryBytes(units: List<DecodedLocationDeliveryUnit>): ByteArray =
		ByteArrayOutputStream().use { bytes ->
			DataOutputStream(bytes).use { output ->
				val payloadVersion = units.first().wal.payloadVersion
				output.writeInt(LOCATION_DELIVERY_MAGIC)
				output.writeInt(
                    if (payloadVersion >= LOCATION_OPTIONAL_ACCURACY_PAYLOAD_VERSION) {
                        LOCATION_OPTIONAL_ACCURACY_DELIVERY_VERSION
                    } else if (payloadVersion >= LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION) {
						LOCATION_MOCK_PROVENANCE_DELIVERY_VERSION
					} else {
						LOCATION_LEGACY_DELIVERY_VERSION
					},
				)
				output.writeInt(units.size)
				units.forEach { unit ->
					val row = unit.wal
					val payload = unit.payload
					val providerBytes = payload.provider.encodeToByteArray()
					output.writeInt(providerBytes.size)
					output.write(providerBytes)
					output.writeLong(row.observedElapsedNanos)
					output.writeLong(if (row.wallTimeUncertaintyMs == DERIVED_WALL_UNCERTAINTY_MS) {
						0L
					} else {
						requireNotNull(row.wallTimeMs)
					})
					output.writeLong(java.lang.Double.doubleToRawLongBits(payload.latitudeDegrees))
					output.writeLong(java.lang.Double.doubleToRawLongBits(payload.longitudeDegrees))
					if (payloadVersion >= LOCATION_OPTIONAL_ACCURACY_PAYLOAD_VERSION) {
						output.writeOptionalFloat(payload.horizontalAccuracyMeters)
					} else {
						output.writeInt(java.lang.Float.floatToRawIntBits(requireNotNull(payload.horizontalAccuracyMeters)))
					}
					output.writeOptionalDouble(payload.altitudeMeters)
					output.writeOptionalFloat(payload.verticalAccuracyMeters)
					output.writeOptionalFloat(payload.speedMetersPerSecond)
					output.writeOptionalFloat(payload.bearingDegrees)
					if (payloadVersion >= LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION) {
						output.writeBoolean(requireNotNull(payload.isMock))
					}
				}
			}
			bytes.toByteArray()
		}

	private fun DataOutputStream.writeOptionalDouble(value: Double?) {
		writeBoolean(value != null)
		if (value != null) {
			writeLong(java.lang.Double.doubleToRawLongBits(value))
		}
	}

	private fun DataOutputStream.writeOptionalFloat(value: Float?) {
		writeBoolean(value != null)
		if (value != null) {
			writeInt(java.lang.Float.floatToRawIntBits(value))
		}
	}

	private data class DecodedLocationDeliveryUnit(
		val wal: SourceEventWalEntity,
		val payload: LocationFixPayload,
	)

	private companion object {
		private const val LOCATION_SOURCE = 1
		private const val MAX_LOCATION_PAYLOAD_BYTES = LocationWalQualificationAdapter.MAX_LOCATION_PAYLOAD_BYTES
		private const val MAX_LOCATION_DELIVERY_UNITS = 256
		private const val MAX_DURABLE_SOURCE_SEQUENCE = Long.MAX_VALUE - 1L
		private const val LOCATION_DELIVERY_MAGIC = 0x4c4f4342
		private const val LOCATION_LEGACY_DELIVERY_VERSION = 1
		private const val LOCATION_MOCK_PROVENANCE_DELIVERY_VERSION = 2
		private const val LOCATION_OPTIONAL_ACCURACY_DELIVERY_VERSION = 3
		private const val DERIVED_WALL_UNCERTAINTY_MS = 1L
		private val SUPPORTED_LOCATION_PAYLOAD_VERSIONS =
			setOf(LOCATION_LEGACY_DELIVERY_VERSION, LOCATION_MOCK_PROVENANCE_PAYLOAD_VERSION,
				LOCATION_OPTIONAL_ACCURACY_PAYLOAD_VERSION)
	}

	private fun reject(reason: LocationWalAdapterRejection): Nothing =
		throw LocationPhysicalEvidenceRejected(reason)

}

/**
 * One adapter-local pure decode hint; no qualification or WAL row is cached. The byte budget limits
 * reuse only: larger valid deliveries keep using the original decoder.
 */
private class LocationDeliveryPayloadMemo {
	private var delivery: LocationDeliveryKey? = null
	private var retainedBytes = 0
	private val payloads = mutableMapOf<Int, DecodedPayload>()

	@Synchronized
	fun decode(codec: SourcePayloadCodec, row: SourceEventWalEntity): LocationFixPayload? {
		val identity = row.deliveryIdentity
		val unit = row.deliveryUnitIndex
		if (identity == null || unit == null) {
			return decodePayload(codec, row)
		}
		val key = LocationDeliveryKey(row.capturedCollectedDataEpoch, row.clockDomainId, identity)
		if (delivery != key) {
			payloads.clear()
			retainedBytes = 0
			delivery = key
		}
		val existing = payloads[unit]
		if (existing != null && existing.matches(row)) {
			return existing.payload
		}
		val payload = decodePayload(codec, row) ?: return null
		retain(unit, row, payload)
		return payload
	}

	private fun retain(unit: Int, row: SourceEventWalEntity, payload: LocationFixPayload) {
		val previousBytes = payloads[unit]?.bytes?.size ?: 0
		if (unit !in 0 until MAX_CACHED_UNITS || row.payload.size - previousBytes > MAX_CACHED_BYTES - retainedBytes) {
			return
		}
		payloads[unit] = DecodedPayload(row.payloadVersion, row.deliveryUnitCount, row.payload.copyOf(), payload)
		retainedBytes += row.payload.size - previousBytes
	}

	private fun decodePayload(codec: SourcePayloadCodec, row: SourceEventWalEntity): LocationFixPayload? = runCatching {
		codec.decode(SourceKind.LOCATION, row.payloadVersion, row.payload) as? LocationFixPayload
	}.getOrNull()

	private class DecodedPayload(
		val version: Int,
		val unitCount: Int?,
		val bytes: ByteArray,
		val payload: LocationFixPayload,
	) {
		fun matches(row: SourceEventWalEntity): Boolean = version == row.payloadVersion &&
			unitCount == row.deliveryUnitCount && bytes.contentEquals(row.payload)
	}

	private companion object {
		const val MAX_CACHED_UNITS = 256
		const val MAX_CACHED_BYTES = 1_048_576
	}
}

private fun LocationRegistrationPlanEntity.hasActualLocationPlanIdentity(
	wal: SourceEventWalEntity,
	plan: LocationPlan?,
	basePayloadVersion: Int,
): Boolean = sourceInstanceId == wal.sourceInstanceId && payloadVersion == when {
	plan?.passiveSystemDelivery == true -> SourcePlanCodec.PASSIVE_LOCATION_FORMAT_VERSION
	plan?.adaptiveProfileVersion != null -> SourcePlanCodec.ADAPTIVE_LOCATION_FORMAT_VERSION
	else -> basePayloadVersion
} && physicalConfigurationFingerprint == wal.physicalConfigurationFingerprint

private fun LocationPlan.hasCanonicalRegistrationPayload(
	stored: LocationRegistrationPlanEntity,
	encoded: EncodedSourcePlan?,
	expectedRevision: Long,
): Boolean = enabled && hasSupportedHistoricalShape() && revision == expectedRevision && encoded != null &&
	encoded.bytes.contentEquals(stored.payload) && encoded.checksum == stored.payloadChecksum &&
	physicalConfigurationFingerprint() == stored.physicalConfigurationFingerprint

internal fun LocationPlan.maximumLocationEvidenceAgeNanos(): Long {
	val maximumAgeMs = if (adaptiveProfileVersion == 1) {
		ADAPTIVE_PROVIDER_ITEM_MAXIMUM_AGE_MS
	} else {
		maxOf(requestedIntervalMs, maximumBatchDelayMs).coerceAtLeast(1L)
	}
	return if (maximumAgeMs > Long.MAX_VALUE / LOCATION_WAL_NANOS_PER_MILLISECOND) {
		Long.MAX_VALUE
	} else {
		maximumAgeMs * LOCATION_WAL_NANOS_PER_MILLISECOND
	}
}

internal fun LocationPlan.hasSupportedHistoricalShape(): Boolean =
	requestedIntervalMs > 0L && minimumUpdateIntervalMs >= 0L &&
		minimumUpdateIntervalMs <= requestedIntervalMs &&
		minimumDisplacementMeters.isFinite() && minimumDisplacementMeters >= 0f &&
		maximumBatchDelayMs >= 0L && (probeDurationMs == null || probeDurationMs > 0L)

internal fun LocationPlan.canonicalAcquisitionMode(): LocationAcquisitionMode = when (backend) {
	LocationBackend.FUSED -> LocationAcquisitionMode.FUSED
	LocationBackend.FRAMEWORK -> when (mode) {
		LocationMode.PASSIVE -> LocationAcquisitionMode.PLATFORM_PASSIVE
		LocationMode.HIGH_ACCURACY, LocationMode.PROBE -> LocationAcquisitionMode.PLATFORM_GPS
		else -> LocationAcquisitionMode.UNKNOWN // Existing wire enum has no network-provider label.
	}
}

internal fun LocationPlan.canonicalRequestPriority(): LocationRequestPriority = when (mode) {
	LocationMode.DISABLED -> LocationRequestPriority.UNKNOWN
	LocationMode.PASSIVE -> LocationRequestPriority.PASSIVE
	LocationMode.LOW_POWER -> LocationRequestPriority.LOW_POWER
	LocationMode.BALANCED -> LocationRequestPriority.BALANCED
	LocationMode.HIGH_ACCURACY,
	LocationMode.PROBE,
	-> if (preciseLocationAvailable) {
		LocationRequestPriority.HIGH_ACCURACY
	} else {
		LocationRequestPriority.BALANCED
	}
}

internal fun Int.toLocationPolicyTier(): PolicyTier = when (this) {
	SourceQos.BATTERY_SAVER.stableCode, SourceQos.BALANCED.stableCode -> PolicyTier.ACTIVE
	SourceQos.RESPONSIVE.stableCode -> PolicyTier.PRECISION
	else -> error("Unsupported captured Location QoS $this")
}

internal fun Int.toLocationPolicyName(): String = when (this) {
	SourceQos.BATTERY_SAVER.stableCode -> "SOURCE_QOS_BATTERY_SAVER"
	SourceQos.BALANCED.stableCode -> "SOURCE_QOS_BALANCED"
	SourceQos.RESPONSIVE.stableCode -> "SOURCE_QOS_RESPONSIVE"
	else -> error("Unsupported captured Location QoS $this")
}

private fun rejected(reason: LocationWalAdapterRejection) = LocationWalAdapterResult.Rejected(reason)

internal const val LOCATION_WAL_AUTHORIZATION_MEMBER_LIMIT = 16
internal const val LOCATION_WAL_NANOS_PER_MILLISECOND = 1_000_000L
