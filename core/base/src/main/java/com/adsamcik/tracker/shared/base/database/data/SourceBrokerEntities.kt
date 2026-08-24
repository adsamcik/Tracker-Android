package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import java.security.MessageDigest

/** Stable persisted purpose codes and callback eligibility bits. */
object SourceBrokerPurpose {
	const val CONTROL_AUTOSTART = "CONTROL_AUTOSTART"
	const val CONTROL_CONTINUATION = "CONTROL_CONTINUATION"
	const val SESSION_CAPTURE = "SESSION_CAPTURE"
	const val AMBIENT_PRODUCT = "AMBIENT_PRODUCT"

	const val MASK_CONTROL_AUTOSTART = 1L shl 0
	const val MASK_CONTROL_CONTINUATION = 1L shl 1
	const val MASK_SESSION_CAPTURE = 1L shl 2
	const val MASK_AMBIENT_PRODUCT = 1L shl 3
	const val ALL_MASK = MASK_CONTROL_AUTOSTART or MASK_CONTROL_CONTINUATION or
		MASK_SESSION_CAPTURE or MASK_AMBIENT_PRODUCT

	fun mask(purpose: String): Long = when (purpose) {
		CONTROL_AUTOSTART -> MASK_CONTROL_AUTOSTART
		CONTROL_CONTINUATION -> MASK_CONTROL_CONTINUATION
		SESSION_CAPTURE -> MASK_SESSION_CAPTURE
		AMBIENT_PRODUCT -> MASK_AMBIENT_PRODUCT
		else -> 0L
	}
}

object SourceBrokerAuthorization {
	fun purposeMask(demands: Collection<SourceDemandEntity>): Long =
		demands.fold(0L) { mask, demand -> mask or SourceBrokerPurpose.mask(demand.purpose) }

	fun fingerprint(demands: Collection<SourceDemandEntity>): String {
		val canonical = demands.sortedWith(
			compareBy<SourceDemandEntity>(SourceDemandEntity::purpose)
				.thenBy(SourceDemandEntity::consumerId)
				.thenBy(SourceDemandEntity::demandId),
		).joinToString("\u001f") { demand ->
			listOf(
				demand.demandId,
				demand.consumerId,
				demand.purpose,
				demand.sourcePolicyRevision,
				demand.consentEpoch,
				demand.persistenceEligible,
				demand.logicalTrackingId.orEmpty(),
				demand.manifestRevision ?: 0L,
				demand.qosCode,
				demand.minimumAcquisitionSpec,
				demand.adaptiveReductionAllowed,
				demand.maximumAgeMs,
				demand.desiredLatencyMs,
				demand.requestedDeliveryLatencyMs,
				demand.requestedBootId,
				demand.requestedElapsedRealtimeNanos,
			).joinToString("\u001e")
		}
		return MessageDigest.getInstance("SHA-256")
			.digest(canonical.toByteArray(Charsets.UTF_8))
			.joinToString("") { byte -> "%02x".format(byte) }
	}

	fun memberId(demandId: String): String = "demand:$demandId"

	fun rows(
		sourceKind: Int,
		registrationGeneration: Long,
		authorizationRevision: Long,
		demands: Collection<SourceDemandEntity>,
		effectiveBootId: String,
		effectiveElapsedRealtimeNanos: Long,
		effectiveWallTimeMs: Long,
	): List<SourceAuthorizationEntity> {
		val matching = demands.filter { it.sourceKind == sourceKind }
		val purposeMask = purposeMask(matching)
		val fingerprint = fingerprint(matching)
		if (matching.isEmpty()) {
			return listOf(
				SourceAuthorizationEntity(
					sourceKind = sourceKind,
					registrationGeneration = registrationGeneration,
					authorizationRevision = authorizationRevision,
					memberId = DENY_ALL_MEMBER_ID,
					authorizationFingerprint = fingerprint,
					purposeEligibilityMask = 0L,
					demandId = null,
					consumerId = null,
					purpose = null,
					sourcePolicyRevision = null,
					consentEpoch = null,
					persistenceEligible = false,
					effectiveBootId = effectiveBootId,
					effectiveElapsedRealtimeNanos = effectiveElapsedRealtimeNanos,
					effectiveWallTimeMs = effectiveWallTimeMs,
					logicalTrackingId = null,
					serviceRunId = null,
					manifestRevision = null,
					lifecycleLeaseGeneration = null,
				),
			)
		}
		return matching.sortedWith(
			compareBy<SourceDemandEntity>(SourceDemandEntity::purpose)
				.thenBy(SourceDemandEntity::consumerId)
				.thenBy(SourceDemandEntity::demandId),
		).map { demand ->
			SourceAuthorizationEntity(
				sourceKind = sourceKind,
				registrationGeneration = registrationGeneration,
				authorizationRevision = authorizationRevision,
				memberId = memberId(demand.demandId),
				authorizationFingerprint = fingerprint,
				purposeEligibilityMask = purposeMask,
				demandId = demand.demandId,
				consumerId = demand.consumerId,
				purpose = demand.purpose,
				sourcePolicyRevision = demand.sourcePolicyRevision,
				consentEpoch = demand.consentEpoch,
				persistenceEligible = demand.persistenceEligible,
				effectiveBootId = effectiveBootId,
				effectiveElapsedRealtimeNanos = effectiveElapsedRealtimeNanos,
				effectiveWallTimeMs = effectiveWallTimeMs,
				logicalTrackingId = demand.logicalTrackingId,
				serviceRunId = demand.serviceRunId,
				manifestRevision = demand.manifestRevision,
				lifecycleLeaseGeneration = demand.lifecycleLeaseGeneration,
			)
		}
	}

	const val DENY_ALL_MEMBER_ID = "__DENY_ALL__"
}

@Deprecated("Use SourceBrokerAuthorization; eligibility belongs to an observed-time authorization revision")
typealias SourceBrokerEligibility = SourceBrokerAuthorization

/**
 * One durable request for a physical source provider.
 *
 * Demand rows are append-only in identity. Removal changes only the reconciliation status and
 * records an effective retirement boundary, so a provider generation can always be audited back to
 * the exact consumers and privacy epochs that authorized it.
 */
@Entity(
	tableName = "source_demand",
	indices = [
		Index(
			value = ["consumer_id", "source_kind", "purpose", "status"],
			name = "idx_source_demand_consumer",
		),
		Index(
			value = ["source_kind", "status", "requested_elapsed_realtime_nanos"],
			name = "idx_source_demand_active",
		),
		Index(
			value = ["logical_tracking_id", "manifest_revision"],
			name = "idx_source_demand_manifest",
		),
	],
)
data class SourceDemandEntity(
	@androidx.room.PrimaryKey
	@ColumnInfo(name = "demand_id") val demandId: String,
	@ColumnInfo(name = "consumer_id") val consumerId: String,
	@ColumnInfo(name = "source_kind") val sourceKind: Int,
	@ColumnInfo(name = "purpose") val purpose: String,
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String?,
	@ColumnInfo(name = "service_run_id") val serviceRunId: String?,
	@ColumnInfo(name = "manifest_revision") val manifestRevision: Long?,
	@ColumnInfo(name = "lifecycle_lease_generation") val lifecycleLeaseGeneration: Long?,
	@ColumnInfo(name = "source_policy_revision") val sourcePolicyRevision: Long,
	@ColumnInfo(name = "consent_epoch") val consentEpoch: Long,
	@ColumnInfo(name = "persistence_eligible") val persistenceEligible: Boolean,
	@ColumnInfo(name = "qos_code") val qosCode: Int,
	/** Opaque engine-owned floor specification; core persists and fingerprints but never interprets it. */
	@ColumnInfo(name = "minimum_acquisition_spec")
	val minimumAcquisitionSpec: String = "unspecified:v1:source=$sourceKind",
	@ColumnInfo(name = "adaptive_reduction_allowed")
	val adaptiveReductionAllowed: Boolean = false,
	@ColumnInfo(name = "maximum_age_ms") val maximumAgeMs: Long,
	@ColumnInfo(name = "desired_latency_ms") val desiredLatencyMs: Long,
	@ColumnInfo(name = "requested_delivery_latency_ms")
	val requestedDeliveryLatencyMs: Long? = null,
	@ColumnInfo(name = "requested_boot_id") val requestedBootId: String,
	@ColumnInfo(name = "requested_elapsed_realtime_nanos") val requestedElapsedRealtimeNanos: Long,
	@ColumnInfo(name = "requested_at_ms") val requestedAtMs: Long,
	@ColumnInfo(name = "status") val status: String,
	@ColumnInfo(name = "retire_boot_id") val retireBootId: String?,
	@ColumnInfo(name = "retire_elapsed_realtime_nanos") val retireElapsedRealtimeNanos: Long?,
	@ColumnInfo(name = "retired_at_ms") val retiredAtMs: Long?,
) {
	init {
		require(demandId.isNotBlank())
		require(consumerId.isNotBlank())
		require(sourceKind >= 0)
		require(SourceBrokerPurpose.mask(purpose) != 0L) { "Unknown broker purpose $purpose" }
		require(sourcePolicyRevision > 0L)
		require(consentEpoch >= 0L)
		require(minimumAcquisitionSpec.isNotBlank())
		require(maximumAgeMs >= 0L)
		require(desiredLatencyMs >= 0L)
		require(requestedDeliveryLatencyMs == null || requestedDeliveryLatencyMs >= 0L)
		require(requestedBootId.isNotBlank())
		require(requestedElapsedRealtimeNanos >= 0L)
		require(requestedAtMs >= 0L)
		require(
			listOf(logicalTrackingId, serviceRunId, manifestRevision, lifecycleLeaseGeneration)
				.all { it == null } ||
				listOf(logicalTrackingId, serviceRunId, manifestRevision, lifecycleLeaseGeneration)
					.all { it != null },
		) {
			"Session demand identity must include logical tracking, service run, manifest, and lease"
		}
		require(
			(purpose in setOf(SourceBrokerPurpose.SESSION_CAPTURE, SourceBrokerPurpose.CONTROL_CONTINUATION)) ==
				(logicalTrackingId != null),
		) { "Session purposes require a manifest; app purposes must not claim one" }
		require(purpose != SourceBrokerPurpose.SESSION_CAPTURE || persistenceEligible) {
			"Session capture demand must be persistence eligible"
		}
		require(manifestRevision == null || manifestRevision > 0L)
		require(lifecycleLeaseGeneration == null || lifecycleLeaseGeneration > 0L)
		require((retireBootId == null) == (retireElapsedRealtimeNanos == null))
	}

	companion object {
		const val STATUS_ACTIVE = "ACTIVE"
		const val STATUS_RETIRING = "RETIRING"
		const val STATUS_RETIRED = "RETIRED"
		const val STATUS_BLOCKED = "BLOCKED"
	}
}

/** One immutable physical registration generation reserved before the provider side effect. */
@Entity(
	tableName = "provider_registration_generation",
	primaryKeys = ["source_kind", "registration_generation"],
	indices = [
		Index(value = ["source_instance_id"], name = "idx_provider_registration_instance"),
		Index(value = ["source_kind", "status"], name = "idx_provider_registration_status"),
	],
)
data class ProviderRegistrationGenerationEntity(
	@ColumnInfo(name = "source_kind") val sourceKind: Int,
	@ColumnInfo(name = "registration_generation") val registrationGeneration: Long,
	@ColumnInfo(name = "source_instance_id") val sourceInstanceId: String,
	@ColumnInfo(name = "owner_scope") val ownerScope: String,
	@ColumnInfo(name = "clock_domain_id") val clockDomainId: String,
	@ColumnInfo(name = "physical_configuration_fingerprint") val physicalConfigurationFingerprint: String,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "status") val status: String,
	@ColumnInfo(name = "reserved_at_ms") val reservedAtMs: Long,
	@ColumnInfo(name = "reserved_elapsed_realtime_nanos") val reservedElapsedRealtimeNanos: Long,
	@ColumnInfo(name = "accepted_at_ms") val acceptedAtMs: Long?,
	@ColumnInfo(name = "accepted_elapsed_realtime_nanos") val acceptedElapsedRealtimeNanos: Long?,
	@ColumnInfo(name = "retired_at_ms") val retiredAtMs: Long?,
	@ColumnInfo(name = "retired_elapsed_realtime_nanos") val retiredElapsedRealtimeNanos: Long?,
	@ColumnInfo(name = "failure_code") val failureCode: String?,
) {
	init {
		require(sourceKind >= 0)
		require(registrationGeneration > 0L)
		require(sourceInstanceId.isNotBlank())
		require(ownerScope.isNotBlank())
		require(clockDomainId.isNotBlank())
		require(physicalConfigurationFingerprint.isNotBlank())
		require(collectedDataEpoch >= 0L)
		require(reservedAtMs >= 0L)
		require(reservedElapsedRealtimeNanos >= 0L)
		require((acceptedAtMs == null) == (acceptedElapsedRealtimeNanos == null))
		require((retiredAtMs == null) == (retiredElapsedRealtimeNanos == null))
	}

	companion object {
		const val STATUS_RESERVED = "RESERVED"
		const val STATUS_ACTIVE = "ACTIVE"
		const val STATUS_RETIRING = "RETIRING"
		const val STATUS_RETIRED = "RETIRED"
		const val STATUS_FAILED = "FAILED"
	}
}

/**
 * One member of an immutable observed-time authorization revision.
 *
 * The latest revision whose effective boundary is at or before an observation owns that
 * observation. A deny-all revision contains exactly one [SourceBrokerAuthorization.DENY_ALL_MEMBER_ID]
 * row, allowing revocation to fence use immediately without rewriting an older revision or waiting
 * for physical provider cleanup.
 */
@Entity(
	tableName = "source_authorization",
	primaryKeys = ["source_kind", "registration_generation", "authorization_revision", "member_id"],
	indices = [
		Index(
			value = ["logical_tracking_id", "manifest_revision", "purpose"],
			name = "idx_source_authorization_manifest",
		),
		Index(
			value = [
				"source_kind",
				"registration_generation",
				"effective_boot_id",
				"effective_elapsed_realtime_nanos",
				"authorization_revision",
			],
			name = "idx_source_authorization_observed_time",
		),
	],
)
data class SourceAuthorizationEntity(
	@ColumnInfo(name = "source_kind") val sourceKind: Int,
	@ColumnInfo(name = "registration_generation") val registrationGeneration: Long,
	@ColumnInfo(name = "authorization_revision") val authorizationRevision: Long,
	@ColumnInfo(name = "member_id") val memberId: String,
	@ColumnInfo(name = "authorization_fingerprint") val authorizationFingerprint: String,
	@ColumnInfo(name = "purpose_eligibility_mask") val purposeEligibilityMask: Long,
	@ColumnInfo(name = "demand_id") val demandId: String?,
	@ColumnInfo(name = "consumer_id") val consumerId: String?,
	@ColumnInfo(name = "purpose") val purpose: String?,
	@ColumnInfo(name = "source_policy_revision") val sourcePolicyRevision: Long?,
	@ColumnInfo(name = "consent_epoch") val consentEpoch: Long?,
	@ColumnInfo(name = "persistence_eligible") val persistenceEligible: Boolean,
	@ColumnInfo(name = "effective_boot_id") val effectiveBootId: String,
	@ColumnInfo(name = "effective_elapsed_realtime_nanos") val effectiveElapsedRealtimeNanos: Long,
	@ColumnInfo(name = "effective_wall_time_ms") val effectiveWallTimeMs: Long,
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String?,
	@ColumnInfo(name = "service_run_id") val serviceRunId: String?,
	@ColumnInfo(name = "manifest_revision") val manifestRevision: Long?,
	@ColumnInfo(name = "lifecycle_lease_generation") val lifecycleLeaseGeneration: Long?,
) {
	val isDenyAll: Boolean get() = memberId == SourceBrokerAuthorization.DENY_ALL_MEMBER_ID

	init {
		require(sourceKind >= 0)
		require(registrationGeneration > 0L)
		require(authorizationRevision > 0L)
		require(memberId.isNotBlank())
		require(authorizationFingerprint.isNotBlank())
		require(purposeEligibilityMask >= 0L)
		require(purposeEligibilityMask and SourceBrokerPurpose.ALL_MASK == purposeEligibilityMask)
		require(effectiveBootId.isNotBlank())
		require(effectiveElapsedRealtimeNanos >= 0L)
		require(effectiveWallTimeMs >= 0L)
		if (isDenyAll) {
			require(purposeEligibilityMask == 0L)
			require(
				listOf(
					demandId,
					consumerId,
					purpose,
					sourcePolicyRevision,
					consentEpoch,
					logicalTrackingId,
					serviceRunId,
					manifestRevision,
					lifecycleLeaseGeneration,
				).all { it == null },
			)
			require(!persistenceEligible)
		} else {
			require(demandId?.isNotBlank() == true)
			require(consumerId?.isNotBlank() == true)
			require(purpose != null && SourceBrokerPurpose.mask(purpose) != 0L)
			require(sourcePolicyRevision != null && sourcePolicyRevision > 0L)
			require(consentEpoch != null && consentEpoch >= 0L)
			require(SourceBrokerPurpose.mask(purpose) and purposeEligibilityMask != 0L)
			require(
				listOf(logicalTrackingId, serviceRunId, manifestRevision, lifecycleLeaseGeneration)
					.all { it == null } ||
				listOf(logicalTrackingId, serviceRunId, manifestRevision, lifecycleLeaseGeneration)
					.all { it != null },
			)
			require(
				(purpose in setOf(SourceBrokerPurpose.SESSION_CAPTURE, SourceBrokerPurpose.CONTROL_CONTINUATION)) ==
					(logicalTrackingId != null),
			)
			require(manifestRevision == null || manifestRevision > 0L)
			require(lifecycleLeaseGeneration == null || lifecycleLeaseGeneration > 0L)
		}
	}
}

data class SourceAuthorizationSnapshot(
	val authorizationRevision: Long,
	val authorizationFingerprint: String,
	val purposeEligibilityMask: Long,
	val effectiveBootId: String,
	val effectiveElapsedRealtimeNanos: Long,
	val members: List<SourceAuthorizationEntity>,
) {
	val authorizedMembers: List<SourceAuthorizationEntity> = members.filterNot(SourceAuthorizationEntity::isDenyAll)
	val isDenied: Boolean get() = authorizedMembers.isEmpty()

	init {
		require(authorizationRevision > 0L)
		require(authorizationFingerprint.isNotBlank())
		require(purposeEligibilityMask >= 0L)
		require(effectiveBootId.isNotBlank())
		require(effectiveElapsedRealtimeNanos >= 0L)
		require(members.isNotEmpty())
		val first = members.first()
		require(members.all { row ->
			row.sourceKind == first.sourceKind &&
				row.registrationGeneration == first.registrationGeneration &&
				row.authorizationRevision == authorizationRevision &&
				row.authorizationFingerprint == authorizationFingerprint &&
				row.purposeEligibilityMask == purposeEligibilityMask &&
				row.effectiveBootId == effectiveBootId &&
				row.effectiveElapsedRealtimeNanos == effectiveElapsedRealtimeNanos
		})
	}
}

fun List<SourceAuthorizationEntity>.toAuthorizationSnapshotOrNull(): SourceAuthorizationSnapshot? {
	if (isEmpty()) return null
	val first = first()
	return SourceAuthorizationSnapshot(
		authorizationRevision = first.authorizationRevision,
		authorizationFingerprint = first.authorizationFingerprint,
		purposeEligibilityMask = first.purposeEligibilityMask,
		effectiveBootId = first.effectiveBootId,
		effectiveElapsedRealtimeNanos = first.effectiveElapsedRealtimeNanos,
		members = this,
	)
}
