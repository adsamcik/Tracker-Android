package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * Singleton pointer to the immutable policy revision that all runtime consumers must use.
 *
 * The row starts UNINITIALIZED after the v28 migration. Legacy settings are imported by the
 * policy repository in one Room transaction before this pointer becomes ACTIVE. Runtime readers
 * fail closed while the pointer is absent, uninitialized, or does not resolve to all six sources.
 */
@Entity(tableName = "source_policy_authority")
data class SourcePolicyAuthorityEntity(
	@PrimaryKey val id: Int = SINGLETON_ID,
	@ColumnInfo(name = "bootstrap_state") val bootstrapState: String,
	@ColumnInfo(name = "current_policy_revision") val currentPolicyRevision: Long,
	@ColumnInfo(name = "legacy_settings_fingerprint") val legacySettingsFingerprint: String?,
	@ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
) {
	companion object {
		const val SINGLETON_ID = 1
		const val STATE_UNINITIALIZED = "UNINITIALIZED"
		const val STATE_ACTIVE = "ACTIVE"
	}
}

/** One transactionally observed authority pointer and its complete immutable source revision. */
data class SourcePolicyAuthorityWithPolicies(
	@Embedded val authority: SourcePolicyAuthorityEntity,
	@Relation(
		parentColumn = "current_policy_revision",
		entityColumn = "policy_revision",
	)
	val policies: List<SourcePolicyEntity>,
)

/** Immutable effective policy for one source at one authority revision. */
@Entity(
	tableName = "source_policy",
	primaryKeys = ["policy_revision", "source_kind"],
	indices = [
		Index(value = ["source_kind", "policy_revision"], name = "idx_source_policy_source_revision"),
	],
)
data class SourcePolicyEntity(
	@ColumnInfo(name = "policy_revision") val policyRevision: Long,
	@ColumnInfo(name = "source_kind") val sourceKind: Int,
	@ColumnInfo(name = "enabled") val enabled: Boolean,
	@ColumnInfo(name = "qos_code") val qosCode: Int,
	@ColumnInfo(name = "location_min_time_seconds") val locationMinTimeSeconds: Int?,
	@ColumnInfo(name = "location_min_distance_meters") val locationMinDistanceMeters: Int?,
	@ColumnInfo(name = "location_required_accuracy_meters") val locationRequiredAccuracyMeters: Int?,
	@ColumnInfo(name = "capture_persistence_eligible") val capturePersistenceEligible: Boolean,
	@ColumnInfo(name = "control_persistence_eligible") val controlPersistenceEligible: Boolean,
	@ColumnInfo(name = "ambient_persistence_eligible") val ambientPersistenceEligible: Boolean,
	@ColumnInfo(name = "capture_consent_epoch") val captureConsentEpoch: Long?,
	@ColumnInfo(name = "control_consent_epoch") val controlConsentEpoch: Long?,
	@ColumnInfo(name = "ambient_consent_epoch") val ambientConsentEpoch: Long?,
	@ColumnInfo(name = "effective_boot_id") val effectiveBootId: String,
	@ColumnInfo(name = "effective_elapsed_realtime_nanos") val effectiveElapsedRealtimeNanos: Long,
	@ColumnInfo(name = "effective_wall_time_ms") val effectiveWallTimeMs: Long,
	@ColumnInfo(name = "change_reason") val changeReason: String,
)

/**
 * Append-only purpose-specific consent history.
 *
 * An ineligible row is a fence, not absence of data. Re-enabling a purpose must append a larger
 * epoch; no API updates or deletes these rows, so rollback cannot revive an older eligible epoch.
 */
@Entity(
	tableName = "source_consent_epoch",
	primaryKeys = ["source_kind", "purpose", "epoch"],
	indices = [
		Index(
			value = ["source_kind", "purpose", "policy_revision"],
			name = "idx_source_consent_epoch_policy",
		),
	],
)
data class SourceConsentEpochEntity(
	@ColumnInfo(name = "source_kind") val sourceKind: Int,
	@ColumnInfo(name = "purpose") val purpose: String,
	@ColumnInfo(name = "epoch") val epoch: Long,
	@ColumnInfo(name = "eligible") val eligible: Boolean,
	@ColumnInfo(name = "persistence_eligible") val persistenceEligible: Boolean,
	@ColumnInfo(name = "policy_revision") val policyRevision: Long,
	@ColumnInfo(name = "effective_boot_id") val effectiveBootId: String,
	@ColumnInfo(name = "effective_elapsed_realtime_nanos") val effectiveElapsedRealtimeNanos: Long,
	@ColumnInfo(name = "effective_wall_time_ms") val effectiveWallTimeMs: Long,
	@ColumnInfo(name = "change_reason") val changeReason: String,
)
