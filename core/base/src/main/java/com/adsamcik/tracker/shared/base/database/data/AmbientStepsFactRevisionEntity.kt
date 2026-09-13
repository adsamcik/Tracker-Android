package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import java.time.LocalDate
import java.time.ZoneId

/**
 * Append-only, sessionless Ambient Steps aggregate history.
 *
 * Provider-account and Health Connect origin identifiers are deliberately absent. An UPSERT keeps
 * only the selected provider class, exact accepted registration/authorization lineage, immutable
 * civil-day authority, and one aggregate window. A RETRACT keeps only opaque identity and deletion
 * fencing, so a deleted interval cannot be reconstructed from the retained correction.
 */
@Entity(
	tableName = "ambient_steps_fact_revision",
	primaryKeys = [
		"writer_id",
		"writer_version",
		"logical_fact_id",
		"semantic_revision",
	],
	indices = [
		Index(
			value = ["writer_id", "writer_version", "mutation_id"],
			unique = true,
			name = "idx_ambient_steps_fact_mutation",
		),
		Index(
			value = ["structural_epoch_day", "stored_zone_id", "window_start_time_ms"],
			name = "idx_ambient_steps_fact_day_window",
		),
		Index(
			value = [
				"provider",
				"registration_generation",
				"continuity_segment_generation",
				"window_start_time_ms",
			],
			name = "idx_ambient_steps_fact_registration_window",
		),
	],
)
data class AmbientStepsFactRevisionEntity(
	@ColumnInfo(name = "logical_fact_id") val logicalFactId: String,
	@ColumnInfo(name = "semantic_revision") val semanticRevision: Long,
	@ColumnInfo(name = "mutation_id") val mutationId: String,
	@ColumnInfo(name = "writer_id") val writerId: String,
	@ColumnInfo(name = "writer_version") val writerVersion: Int,
	@ColumnInfo(name = "writer_owner_generation") val writerOwnerGeneration: Long,
	@ColumnInfo(name = "operation") val operation: String,
	@ColumnInfo(name = "origin_kind") val originKind: String,
	@ColumnInfo(name = "provider") val provider: String?,
	@ColumnInfo(name = "registration_generation") val registrationGeneration: Long?,
	@ColumnInfo(name = "continuity_segment_generation") val continuitySegmentGeneration: Long?,
	@ColumnInfo(name = "source_instance_id") val sourceInstanceId: String?,
	@ColumnInfo(name = "authorization_revision") val authorizationRevision: Long?,
	@ColumnInfo(name = "authorization_fingerprint") val authorizationFingerprint: String?,
	@ColumnInfo(name = "window_start_time_ms") val windowStartTimeMs: Long?,
	@ColumnInfo(name = "window_end_time_ms") val windowEndTimeMs: Long?,
	@ColumnInfo(name = "observed_at_ms") val observedAtMs: Long?,
	@ColumnInfo(name = "structural_epoch_day") val structuralEpochDay: Long?,
	@ColumnInfo(name = "stored_zone_id") val storedZoneId: String?,
	@ColumnInfo(name = "structural_day_start_time_ms") val structuralDayStartTimeMs: Long?,
	@ColumnInfo(name = "structural_day_end_time_ms") val structuralDayEndTimeMs: Long?,
	/** Explicit provider aggregate; zero is valid only because the exact window is present. */
	@ColumnInfo(name = "step_count") val stepCount: Long?,
	@ColumnInfo(name = "purpose") val purpose: String,
	@ColumnInfo(name = "source_policy_revision") val sourcePolicyRevision: Long?,
	@ColumnInfo(name = "ambient_consent_epoch") val ambientConsentEpoch: Long?,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "scope_deletion_generation") val scopeDeletionGeneration: Long,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
	@ColumnInfo(name = "applied_at_ms") val appliedAtMs: Long,
) {
	init {
		require(AmbientStepsFactIntegrity.isOpaque(logicalFactId))
		require(semanticRevision > 0L)
		require(AmbientStepsFactIntegrity.isOpaque(mutationId))
		require(writerId == WRITER_ID)
		require(writerVersion == WRITER_VERSION)
		require(writerOwnerGeneration > 0L)
		require(operation in OPERATIONS)
		require(originKind in ORIGIN_KINDS)
		require(purpose == PURPOSE_AMBIENT_PRODUCT)
		require(collectedDataEpoch >= 0L)
		require(scopeDeletionGeneration >= 0L)
		require(AmbientStepsFactIntegrity.isDigest(effectChecksum))
		require(appliedAtMs >= 0L)
		when (operation) {
			OPERATION_UPSERT -> requireProviderAggregateShape()
			OPERATION_RETRACT -> requireRedactedRetractionShape()
		}
	}

	private fun requireProviderAggregateShape() {
		require(originKind == ORIGIN_PROVIDER_AGGREGATE)
		require(scopeDeletionGeneration == 0L)
		require(provider in PROVIDERS)
		require(registrationGeneration != null && registrationGeneration > 0L)
		require(continuitySegmentGeneration != null && continuitySegmentGeneration > 0L)
		require(sourceInstanceId?.isNotBlank() == true)
		require(authorizationRevision != null && authorizationRevision > 0L)
		require(authorizationFingerprint?.isNotBlank() == true)
		require(windowStartTimeMs != null && windowStartTimeMs >= 0L)
		require(windowEndTimeMs != null && windowEndTimeMs > windowStartTimeMs)
		require(windowStartTimeMs % MILLIS_PER_SECOND == 0L)
		require(windowEndTimeMs % MILLIS_PER_SECOND == 0L)
		require(windowEndTimeMs - windowStartTimeMs <= MAX_PROVIDER_WINDOW_MILLIS)
		require(observedAtMs != null && observedAtMs >= windowEndTimeMs)
		require(structuralEpochDay != null)
		require(!storedZoneId.isNullOrBlank() && storedZoneId.length <= MAX_ZONE_ID_LENGTH)
		require(structuralDayStartTimeMs != null && structuralDayStartTimeMs >= 0L)
		require(structuralDayEndTimeMs != null && structuralDayEndTimeMs > structuralDayStartTimeMs)
		require(windowStartTimeMs >= structuralDayStartTimeMs)
		require(windowEndTimeMs <= structuralDayEndTimeMs)
		require(stepCount != null && stepCount >= 0L)
		require(sourcePolicyRevision != null && sourcePolicyRevision > 0L)
		require(ambientConsentEpoch != null && ambientConsentEpoch >= 0L)
		require(appliedAtMs >= observedAtMs)

		val zone = ZoneId.of(storedZoneId)
		val date = LocalDate.ofEpochDay(structuralEpochDay)
		require(date.atStartOfDay(zone).toInstant().toEpochMilli() == structuralDayStartTimeMs)
		require(date.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli() == structuralDayEndTimeMs)
		require(structuralDayEndTimeMs - structuralDayStartTimeMs <= MAX_PROVIDER_WINDOW_MILLIS)
	}

	private fun requireRedactedRetractionShape() {
		require(originKind == ORIGIN_LOCAL_DELETE)
		require(scopeDeletionGeneration > 0L)
		require(provider == null)
		require(registrationGeneration == null)
		require(continuitySegmentGeneration == null)
		require(sourceInstanceId == null)
		require(authorizationRevision == null)
		require(authorizationFingerprint == null)
		require(windowStartTimeMs == null && windowEndTimeMs == null)
		require(observedAtMs == null)
		require(structuralEpochDay == null)
		require(storedZoneId == null)
		require(structuralDayStartTimeMs == null && structuralDayEndTimeMs == null)
		require(stepCount == null)
		require(sourcePolicyRevision == null)
		require(ambientConsentEpoch == null)
	}

	companion object {
		const val WRITER_ID = "ambient-steps-facts"
		const val WRITER_VERSION = 1
		const val OPERATION_UPSERT = "UPSERT"
		const val OPERATION_RETRACT = "RETRACT"
		const val ORIGIN_PROVIDER_AGGREGATE = "PROVIDER_AGGREGATE"
		const val ORIGIN_LOCAL_DELETE = "LOCAL_DELETE"
		const val PROVIDER_HEALTH_CONNECT_MOBILE_STEPS = "HEALTH_CONNECT_MOBILE_STEPS"
		const val PROVIDER_LOCAL_RECORDING_STEPS = "LOCAL_RECORDING_STEPS"
		const val PURPOSE_AMBIENT_PRODUCT = "AMBIENT_PRODUCT"

		private val OPERATIONS = setOf(OPERATION_UPSERT, OPERATION_RETRACT)
		private val ORIGIN_KINDS = setOf(ORIGIN_PROVIDER_AGGREGATE, ORIGIN_LOCAL_DELETE)
		private val PROVIDERS = setOf(
			PROVIDER_HEALTH_CONNECT_MOBILE_STEPS,
			PROVIDER_LOCAL_RECORDING_STEPS,
		)
		private const val MAX_ZONE_ID_LENGTH = 128
		private const val MILLIS_PER_SECOND = 1_000L
		private const val MAX_PROVIDER_WINDOW_MILLIS = 25L * 60L * 60L * MILLIS_PER_SECOND
	}
}
