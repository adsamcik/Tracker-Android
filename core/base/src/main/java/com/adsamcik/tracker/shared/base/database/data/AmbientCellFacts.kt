package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Identity-free, sessionless Cell aggregate or compact fresh-unchanged coverage fact. */
@Entity(
	tableName = "ambient_cell_fact_revision",
	primaryKeys = ["writer_id", "writer_version", "logical_fact_id", "semantic_revision"],
	indices = [
		Index(
			value = ["writer_id", "writer_version", "mutation_id"],
			unique = true,
			name = "idx_ambient_cell_fact_mutation",
		),
		Index(
			value = ["source_admission_ordinal"],
			name = "idx_ambient_cell_fact_admission",
		),
		Index(
			value = ["structural_epoch_day", "stored_zone_id", "observed_wall_time_ms"],
			name = "idx_ambient_cell_fact_day",
		),
		Index(
			value = ["observed_wall_time_ms", "logical_fact_id"],
			name = "idx_ambient_cell_fact_window",
		),
		Index(
			value = ["aggregate_owner_logical_fact_id", "aggregate_owner_semantic_revision"],
			name = "idx_ambient_cell_fact_aggregate_owner",
		),
	],
)
@Suppress("LongParameterList")
data class AmbientCellFactRevisionEntity(
	@ColumnInfo(name = "writer_id") val writerId: String,
	@ColumnInfo(name = "writer_version") val writerVersion: Int,
	@ColumnInfo(name = "writer_owner_generation") val writerOwnerGeneration: Long,
	@ColumnInfo(name = "logical_fact_id") val logicalFactId: String,
	@ColumnInfo(name = "semantic_revision") val semanticRevision: Long,
	@ColumnInfo(name = "supersedes_semantic_revision") val supersedesSemanticRevision: Long?,
	@ColumnInfo(name = "mutation_id") val mutationId: String,
	@ColumnInfo(name = "fact_kind") val factKind: String,
	@ColumnInfo(name = "aggregate_owner_logical_fact_id") val aggregateOwnerLogicalFactId: String?,
	@ColumnInfo(name = "aggregate_owner_semantic_revision") val aggregateOwnerSemanticRevision: Long?,
	@ColumnInfo(name = "source_event_id") val sourceEventId: String,
	@ColumnInfo(name = "source_admission_ordinal") val sourceAdmissionOrdinal: Long,
	@ColumnInfo(name = "wal_integrity_identity") val walIntegrityIdentity: String,
	@ColumnInfo(name = "payload_checksum") val payloadChecksum: String,
	@ColumnInfo(name = "source_delivery_identity") val sourceDeliveryIdentity: String,
	@ColumnInfo(name = "source_instance_id") val sourceInstanceId: String,
	@ColumnInfo(name = "registration_generation") val registrationGeneration: Long,
	@ColumnInfo(name = "configuration_revision") val configurationRevision: Long?,
	@ColumnInfo(name = "physical_configuration_fingerprint")
	val physicalConfigurationFingerprint: String,
	@ColumnInfo(name = "authorization_revision") val authorizationRevision: Long,
	@ColumnInfo(name = "authorization_fingerprint") val authorizationFingerprint: String,
	@ColumnInfo(name = "purpose_eligibility_mask") val purposeEligibilityMask: Long,
	@ColumnInfo(name = "source_policy_revision") val sourcePolicyRevision: Long,
	@ColumnInfo(name = "ambient_consent_epoch") val ambientConsentEpoch: Long,
	@ColumnInfo(name = "retention_policy_id") val retentionPolicyId: String,
	@ColumnInfo(name = "retention_approval_revision") val retentionApprovalRevision: Long,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "scope_deletion_generation") val scopeDeletionGeneration: Long,
	@ColumnInfo(name = "clock_domain_id") val clockDomainId: String,
	@ColumnInfo(name = "stored_zone_id") val storedZoneId: String,
	@ColumnInfo(name = "structural_epoch_day") val structuralEpochDay: Long,
	@ColumnInfo(name = "structural_day_start_time_ms") val structuralDayStartTimeMs: Long,
	@ColumnInfo(name = "structural_day_end_time_ms") val structuralDayEndTimeMs: Long,
	@ColumnInfo(name = "observed_interval_start_nanos") val observedIntervalStartNanos: Long,
	@ColumnInfo(name = "observed_elapsed_nanos") val observedElapsedNanos: Long,
	@ColumnInfo(name = "received_elapsed_nanos") val receivedElapsedNanos: Long,
	@ColumnInfo(name = "coverage_start_time_ms") val coverageStartTimeMs: Long,
	@ColumnInfo(name = "observed_wall_time_ms") val observedWallTimeMs: Long,
	@ColumnInfo(name = "wall_time_uncertainty_ms") val wallTimeUncertaintyMs: Long,
	@ColumnInfo(name = "coverage_completeness") val coverageCompleteness: String,
	@ColumnInfo(name = "subscription_completeness") val subscriptionCompleteness: String,
	@ColumnInfo(name = "observation_count") val observationCount: Int?,
	@ColumnInfo(name = "registered_observation_count") val registeredObservationCount: Int?,
	@ColumnInfo(name = "gsm_count") val gsmCount: Int?,
	@ColumnInfo(name = "cdma_count") val cdmaCount: Int?,
	@ColumnInfo(name = "wcdma_count") val wcdmaCount: Int?,
	@ColumnInfo(name = "tdscdma_count") val tdscdmaCount: Int?,
	@ColumnInfo(name = "lte_count") val lteCount: Int?,
	@ColumnInfo(name = "nr_count") val nrCount: Int?,
	@ColumnInfo(name = "quality_unknown_count") val qualityUnknownCount: Int?,
	@ColumnInfo(name = "quality_none_or_unknown_count") val qualityNoneOrUnknownCount: Int?,
	@ColumnInfo(name = "quality_poor_count") val qualityPoorCount: Int?,
	@ColumnInfo(name = "quality_moderate_count") val qualityModerateCount: Int?,
	@ColumnInfo(name = "quality_good_count") val qualityGoodCount: Int?,
	@ColumnInfo(name = "quality_great_count") val qualityGreatCount: Int?,
	@ColumnInfo(name = "quality_flags") val qualityFlags: Long,
	@ColumnInfo(name = "quality_confidence") val qualityConfidence: Float?,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
	@ColumnInfo(name = "applied_at_ms") val appliedAtMs: Long,
) {
	init {
		require(writerId == WRITER_ID && writerVersion == WRITER_VERSION)
		require(writerOwnerGeneration > 0L)
		require(AmbientCellAuthorityIntegrity.isDigest(logicalFactId))
		require(semanticRevision in 1L..MAX_AMBIENT_CELL_SEMANTIC_REVISIONS)
		require(supersedesSemanticRevision == semanticRevision.takeIf { it > 1L }?.minus(1L))
		require(mutationId == AmbientCellFactIntegrity.mutationId(logicalFactId, semanticRevision))
		require(factKind in FACT_KINDS)
		require((aggregateOwnerLogicalFactId == null) == (aggregateOwnerSemanticRevision == null))
		aggregateOwnerLogicalFactId?.let { require(AmbientCellAuthorityIntegrity.isDigest(it)) }
		require(aggregateOwnerSemanticRevision?.let { it > 0L } != false)
		require(sourceEventId.isNotBlank() && sourceAdmissionOrdinal > 0L)
		require(AmbientCellAuthorityIntegrity.isDigest(walIntegrityIdentity))
		require(AmbientCellAuthorityIntegrity.isDigest(payloadChecksum))
		require(AmbientCellAuthorityIntegrity.isDigest(sourceDeliveryIdentity))
		require(sourceInstanceId.isNotBlank() && registrationGeneration > 0L)
		require(physicalConfigurationFingerprint.isNotBlank())
		require(authorizationRevision > 0L)
		require(AmbientCellAuthorityIntegrity.isDigest(authorizationFingerprint))
		require(purposeEligibilityMask and SourceBrokerPurpose.MASK_AMBIENT_PRODUCT != 0L)
		require(sourcePolicyRevision > 0L && ambientConsentEpoch >= 0L)
		require(retentionPolicyId.isNotBlank() && retentionApprovalRevision > 0L)
		require(collectedDataEpoch >= 0L && scopeDeletionGeneration >= 0L)
		require(clockDomainId.isNotBlank())
		requireValidStructuralDay()
		require(observedIntervalStartNanos > 0L)
		require(observedElapsedNanos >= observedIntervalStartNanos)
		require(receivedElapsedNanos >= observedElapsedNanos)
		require(coverageStartTimeMs in structuralDayStartTimeMs until structuralDayEndTimeMs)
		require(observedWallTimeMs in coverageStartTimeMs until structuralDayEndTimeMs)
		require(wallTimeUncertaintyMs >= 0L)
		require(coverageCompleteness in COMPLETENESS)
		require(subscriptionCompleteness in SUBSCRIPTION_COMPLETENESS)
		require(qualityFlags >= 0L && qualityConfidence?.let { it in 0f..1f } != false)
		requireFactShape()
		require(
			logicalFactId == AmbientCellFactIntegrity.logicalFactId(
				sourceDeliveryIdentity,
				ambientConsentEpoch,
				collectedDataEpoch,
				scopeDeletionGeneration,
			),
		)
		require(AmbientCellAuthorityIntegrity.isDigest(effectChecksum))
		require(appliedAtMs >= observedWallTimeMs)
	}

	private fun requireValidStructuralDay() {
		val zone = ZoneId.of(storedZoneId)
		val date = LocalDate.ofEpochDay(structuralEpochDay)
		require(date.atStartOfDay(zone).toInstant().toEpochMilli() == structuralDayStartTimeMs)
		require(date.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli() ==
			structuralDayEndTimeMs)
	}

	private fun requireFactShape() {
		val aggregate = listOf(
			observationCount,
			registeredObservationCount,
			gsmCount,
			cdmaCount,
			wcdmaCount,
			tdscdmaCount,
			lteCount,
			nrCount,
			qualityUnknownCount,
			qualityNoneOrUnknownCount,
			qualityPoorCount,
			qualityModerateCount,
			qualityGoodCount,
			qualityGreatCount,
		)
		if (factKind == FACT_KIND_AGGREGATE) {
			require(aggregateOwnerLogicalFactId == null && aggregate.all { it != null })
			val count = requireNotNull(observationCount)
			require(count > 0 && requireNotNull(registeredObservationCount) in 0..count)
			require(
				listOf(gsmCount, cdmaCount, wcdmaCount, tdscdmaCount, lteCount, nrCount)
					.filterNotNull().sum() == count,
			)
			require(
				listOf(
					qualityUnknownCount,
					qualityNoneOrUnknownCount,
					qualityPoorCount,
					qualityModerateCount,
					qualityGoodCount,
					qualityGreatCount,
				).filterNotNull().sum() == count,
			)
		} else {
			require(aggregateOwnerLogicalFactId != null && aggregate.all { it == null })
		}
	}

	companion object {
		const val WRITER_ID = "ambient-cell-facts"
		const val WRITER_VERSION = 1
		const val FACT_KIND_AGGREGATE = "AGGREGATE"
		const val FACT_KIND_COVERAGE_ONLY = "COVERAGE_ONLY"
		const val COMPLETENESS_COMPLETE = "COMPLETE"
		const val COMPLETENESS_UNVERIFIABLE = "UNVERIFIABLE"
		const val SUBSCRIPTION_COMPLETE = "COMPLETE"
		const val SUBSCRIPTION_PARTIAL = "PARTIAL"
		const val SUBSCRIPTION_UNKNOWN = "UNKNOWN"
		private val FACT_KINDS = setOf(FACT_KIND_AGGREGATE, FACT_KIND_COVERAGE_ONLY)
		private val COMPLETENESS = setOf(COMPLETENESS_COMPLETE, COMPLETENESS_UNVERIFIABLE)
		private val SUBSCRIPTION_COMPLETENESS =
			setOf(SUBSCRIPTION_COMPLETE, SUBSCRIPTION_PARTIAL, SUBSCRIPTION_UNKNOWN)
	}
}

@Entity(
	tableName = "ambient_cell_fact_cursor",
	primaryKeys = ["writer_id", "writer_version", "logical_fact_id"],
)
data class AmbientCellFactCursorEntity(
	@ColumnInfo(name = "writer_id") val writerId: String,
	@ColumnInfo(name = "writer_version") val writerVersion: Int,
	@ColumnInfo(name = "logical_fact_id") val logicalFactId: String,
	@ColumnInfo(name = "latest_semantic_revision") val latestSemanticRevision: Long,
	@ColumnInfo(name = "latest_mutation_id") val latestMutationId: String,
	@ColumnInfo(name = "latest_effect_checksum") val latestEffectChecksum: String,
	@ColumnInfo(name = "latest_source_admission_ordinal") val latestSourceAdmissionOrdinal: Long,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "scope_deletion_generation") val scopeDeletionGeneration: Long,
	@ColumnInfo(name = "cursor_revision") val cursorRevision: Long,
	@ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
) {
	init {
		require(writerId == AmbientCellFactRevisionEntity.WRITER_ID)
		require(writerVersion == AmbientCellFactRevisionEntity.WRITER_VERSION)
		require(AmbientCellAuthorityIntegrity.isDigest(logicalFactId))
		require(latestSemanticRevision in 1L..MAX_AMBIENT_CELL_SEMANTIC_REVISIONS)
		require(AmbientCellAuthorityIntegrity.isDigest(latestMutationId))
		require(AmbientCellAuthorityIntegrity.isDigest(latestEffectChecksum))
		require(latestSourceAdmissionOrdinal > 0L)
		require(collectedDataEpoch >= 0L && scopeDeletionGeneration >= 0L)
		require(cursorRevision > 0L && updatedAtMs >= 0L)
	}
}

@Entity(
	tableName = "ambient_cell_gap",
	primaryKeys = ["gap_id"],
	indices = [
		Index(
			value = ["structural_epoch_day", "stored_zone_id", "gap_start_time_ms"],
			name = "idx_ambient_cell_gap_day",
		),
		Index(
			value = ["gap_start_time_ms", "gap_end_time_ms"],
			name = "idx_ambient_cell_gap_window",
		),
	],
)
data class AmbientCellGapEntity(
	@ColumnInfo(name = "gap_id") val gapId: String,
	@ColumnInfo(name = "reason") val reason: String,
	@ColumnInfo(name = "gap_start_time_ms") val gapStartTimeMs: Long,
	@ColumnInfo(name = "gap_end_time_ms") val gapEndTimeMs: Long,
	@ColumnInfo(name = "stored_zone_id") val storedZoneId: String,
	@ColumnInfo(name = "structural_epoch_day") val structuralEpochDay: Long,
	@ColumnInfo(name = "source_policy_revision") val sourcePolicyRevision: Long,
	@ColumnInfo(name = "ambient_consent_epoch") val ambientConsentEpoch: Long,
	@ColumnInfo(name = "retention_policy_id") val retentionPolicyId: String,
	@ColumnInfo(name = "retention_approval_revision") val retentionApprovalRevision: Long,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "scope_deletion_generation") val scopeDeletionGeneration: Long,
	@ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
) {
	init {
		require(AmbientCellAuthorityIntegrity.isDigest(gapId))
		require(reason in REASONS)
		require(gapStartTimeMs >= 0L && gapEndTimeMs > gapStartTimeMs)
		val zone = ZoneId.of(storedZoneId)
		require(
			Instant.ofEpochMilli(gapStartTimeMs).atZone(zone).toLocalDate().toEpochDay() ==
				structuralEpochDay,
		)
		require(sourcePolicyRevision > 0L && ambientConsentEpoch >= 0L)
		require(retentionPolicyId.isNotBlank() && retentionApprovalRevision > 0L)
		require(collectedDataEpoch >= 0L && scopeDeletionGeneration >= 0L)
		require(createdAtMs >= gapEndTimeMs)
		require(effectChecksum == AmbientCellFactIntegrity.gapChecksum(this))
	}

	companion object {
		const val REASON_CLOCK_UNVERIFIABLE = "CLOCK_UNVERIFIABLE"
		const val REASON_SUBSCRIPTION_PARTIAL = "SUBSCRIPTION_PARTIAL"
		const val REASON_PROVIDER_COMPLETENESS_UNVERIFIABLE =
			"PROVIDER_COMPLETENESS_UNVERIFIABLE"
		const val REASON_STORAGE_DISCONTINUITY = "STORAGE_DISCONTINUITY"
		private val REASONS = setOf(
			REASON_CLOCK_UNVERIFIABLE,
			REASON_SUBSCRIPTION_PARTIAL,
			REASON_PROVIDER_COMPLETENESS_UNVERIFIABLE,
			REASON_STORAGE_DISCONTINUITY,
		)
	}
}

@Entity(
	tableName = "ambient_cell_deletion_marker",
	primaryKeys = ["collected_data_epoch", "deletion_generation"],
)
data class AmbientCellDeletionMarkerEntity(
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "deletion_generation") val deletionGeneration: Long,
	@ColumnInfo(name = "through_consent_epoch") val throughConsentEpoch: Long,
	@ColumnInfo(name = "reason") val reason: String,
	@ColumnInfo(name = "deleted_at_ms") val deletedAtMs: Long,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
) {
	init {
		require(collectedDataEpoch >= 0L && deletionGeneration > 0L)
		require(throughConsentEpoch >= 0L && reason.isNotBlank() && deletedAtMs >= 0L)
		require(effectChecksum == AmbientCellFactIntegrity.deletionChecksum(this))
	}
}

/** Imported ambient Cell facts remain portable product evidence, never local runtime authority. */
@Entity(
	tableName = "imported_ambient_cell_fact",
	primaryKeys = ["archive_id", "fact_id", "semantic_revision"],
	indices = [
		Index(
			value = ["structural_epoch_day", "stored_zone_id", "observed_time_ms"],
			name = "idx_imported_ambient_cell_day",
		),
		Index(
			value = ["fact_id", "semantic_revision"],
			unique = true,
			name = "idx_imported_ambient_cell_fact_revision",
		),
	],
)
@Suppress("LongParameterList")
data class ImportedAmbientCellFactEntity(
	@ColumnInfo(name = "archive_id") val archiveId: String,
	@ColumnInfo(name = "fact_id") val factId: String,
	@ColumnInfo(name = "semantic_revision") val semanticRevision: Long,
	@ColumnInfo(name = "supersedes_semantic_revision") val supersedesSemanticRevision: Long?,
	@ColumnInfo(name = "content_checksum") val contentChecksum: String,
	@ColumnInfo(name = "portable_effect_checksum") val portableEffectChecksum: String,
	@ColumnInfo(name = "portable_origin") val portableOrigin: String,
	@ColumnInfo(name = "coverage_start_time_ms") val coverageStartTimeMs: Long,
	@ColumnInfo(name = "observed_time_ms") val observedTimeMs: Long,
	@ColumnInfo(name = "latest_possible_time_ms") val latestPossibleTimeMs: Long,
	@ColumnInfo(name = "stored_zone_id") val storedZoneId: String,
	@ColumnInfo(name = "structural_epoch_day") val structuralEpochDay: Long,
	@ColumnInfo(name = "coverage_completeness") val coverageCompleteness: String,
	@ColumnInfo(name = "subscription_completeness") val subscriptionCompleteness: String,
	@ColumnInfo(name = "observation_count") val observationCount: Int,
	@ColumnInfo(name = "registered_observation_count") val registeredObservationCount: Int,
	@ColumnInfo(name = "gsm_count") val gsmCount: Int,
	@ColumnInfo(name = "cdma_count") val cdmaCount: Int,
	@ColumnInfo(name = "wcdma_count") val wcdmaCount: Int,
	@ColumnInfo(name = "tdscdma_count") val tdscdmaCount: Int,
	@ColumnInfo(name = "lte_count") val lteCount: Int,
	@ColumnInfo(name = "nr_count") val nrCount: Int,
	@ColumnInfo(name = "quality_unknown_count") val qualityUnknownCount: Int,
	@ColumnInfo(name = "quality_none_or_unknown_count") val qualityNoneOrUnknownCount: Int,
	@ColumnInfo(name = "quality_poor_count") val qualityPoorCount: Int,
	@ColumnInfo(name = "quality_moderate_count") val qualityModerateCount: Int,
	@ColumnInfo(name = "quality_good_count") val qualityGoodCount: Int,
	@ColumnInfo(name = "quality_great_count") val qualityGreatCount: Int,
	@ColumnInfo(name = "retention_policy_id") val retentionPolicyId: String,
	@ColumnInfo(name = "retention_approval_revision") val retentionApprovalRevision: Long,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "import_deletion_generation") val importDeletionGeneration: Long,
	@ColumnInfo(name = "received_at_ms") val receivedAtMs: Long,
) {
	init {
		require(AmbientCellAuthorityIntegrity.isDigest(archiveId))
		require(AmbientCellAuthorityIntegrity.isDigest(factId))
		require(semanticRevision in 1L..MAX_AMBIENT_CELL_SEMANTIC_REVISIONS)
		require(supersedesSemanticRevision == semanticRevision.takeIf { it > 1L }?.minus(1L))
		require(AmbientCellAuthorityIntegrity.isDigest(contentChecksum))
		require(AmbientCellAuthorityIntegrity.isDigest(portableEffectChecksum))
		require(portableOrigin in setOf("LOCAL_DEVICE", "PORTABLE_IMPORT"))
		require(coverageStartTimeMs >= 0L && observedTimeMs >= coverageStartTimeMs)
		require(latestPossibleTimeMs >= observedTimeMs)
		val zone = ZoneId.of(storedZoneId)
		require(
			Instant.ofEpochMilli(observedTimeMs).atZone(zone).toLocalDate().toEpochDay() ==
				structuralEpochDay,
		)
		require(coverageCompleteness in setOf(
			AmbientCellFactRevisionEntity.COMPLETENESS_COMPLETE,
			AmbientCellFactRevisionEntity.COMPLETENESS_UNVERIFIABLE,
		))
		require(subscriptionCompleteness in setOf(
			AmbientCellFactRevisionEntity.SUBSCRIPTION_COMPLETE,
			AmbientCellFactRevisionEntity.SUBSCRIPTION_PARTIAL,
			AmbientCellFactRevisionEntity.SUBSCRIPTION_UNKNOWN,
		))
		require(observationCount > 0 && registeredObservationCount in 0..observationCount)
		require(gsmCount + cdmaCount + wcdmaCount + tdscdmaCount + lteCount + nrCount ==
			observationCount)
		require(
			qualityUnknownCount + qualityNoneOrUnknownCount + qualityPoorCount +
				qualityModerateCount + qualityGoodCount + qualityGreatCount == observationCount,
		)
		require(retentionPolicyId.isNotBlank() && retentionApprovalRevision > 0L)
		require(collectedDataEpoch >= 0L && importDeletionGeneration >= 0L)
		require(receivedAtMs >= latestPossibleTimeMs)
	}
}

@Entity(
	tableName = "imported_ambient_cell_gap",
	primaryKeys = ["archive_id", "gap_id"],
	indices = [
		Index(
			value = ["start_time_ms", "end_time_ms"],
			name = "idx_imported_ambient_cell_gap_window",
		),
		Index(
			value = ["gap_id"],
			unique = true,
			name = "idx_imported_ambient_cell_gap_identity",
		),
	],
)
data class ImportedAmbientCellGapEntity(
	@ColumnInfo(name = "archive_id") val archiveId: String,
	@ColumnInfo(name = "gap_id") val gapId: String,
	@ColumnInfo(name = "content_checksum") val contentChecksum: String,
	@ColumnInfo(name = "portable_effect_checksum") val portableEffectChecksum: String,
	@ColumnInfo(name = "portable_origin") val portableOrigin: String,
	@ColumnInfo(name = "start_time_ms") val startTimeMs: Long,
	@ColumnInfo(name = "end_time_ms") val endTimeMs: Long,
	@ColumnInfo(name = "stored_zone_id") val storedZoneId: String,
	@ColumnInfo(name = "structural_epoch_day") val structuralEpochDay: Long,
	@ColumnInfo(name = "reason") val reason: String,
	@ColumnInfo(name = "retention_policy_id") val retentionPolicyId: String,
	@ColumnInfo(name = "retention_approval_revision") val retentionApprovalRevision: Long,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "received_at_ms") val receivedAtMs: Long,
) {
	init {
		require(AmbientCellAuthorityIntegrity.isDigest(archiveId))
		require(AmbientCellAuthorityIntegrity.isDigest(gapId))
		require(AmbientCellAuthorityIntegrity.isDigest(contentChecksum))
		require(AmbientCellAuthorityIntegrity.isDigest(portableEffectChecksum))
		require(portableOrigin in setOf("LOCAL_DEVICE", "PORTABLE_IMPORT"))
		require(startTimeMs >= 0L && endTimeMs > startTimeMs)
		val zone = ZoneId.of(storedZoneId)
		require(
			Instant.ofEpochMilli(startTimeMs).atZone(zone).toLocalDate().toEpochDay() ==
				structuralEpochDay,
		)
		require(
			reason.isNotBlank() &&
				retentionPolicyId.isNotBlank() &&
				retentionApprovalRevision > 0L,
		)
		require(collectedDataEpoch >= 0L && receivedAtMs >= endTimeMs)
	}
}

@Entity(
	tableName = "imported_ambient_cell_receipt",
	primaryKeys = ["import_job_id", "import_entry_key"],
	indices = [
		Index(value = ["archive_id"], name = "idx_imported_ambient_cell_receipt_archive"),
	],
)
data class ImportedAmbientCellReceiptEntity(
	@ColumnInfo(name = "import_job_id") val importJobId: String,
	@ColumnInfo(name = "import_entry_key") val importEntryKey: String,
	@ColumnInfo(name = "source_name") val sourceName: String,
	@ColumnInfo(name = "archive_id") val archiveId: String,
	@ColumnInfo(name = "archive_checksum") val archiveChecksum: String,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "received_at_ms") val receivedAtMs: Long,
) {
	init {
		require(importJobId.isNotBlank() && importEntryKey.isNotBlank() && sourceName.isNotBlank())
		require(AmbientCellAuthorityIntegrity.isDigest(archiveId))
		require(AmbientCellAuthorityIntegrity.isDigest(archiveChecksum))
		require(collectedDataEpoch >= 0L && receivedAtMs >= 0L)
	}
}

@Entity(
	tableName = "imported_ambient_cell_tombstone",
	primaryKeys = ["archive_id"],
)
data class ImportedAmbientCellTombstoneEntity(
	@ColumnInfo(name = "archive_id") val archiveId: String,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "deletion_generation") val deletionGeneration: Long,
	@ColumnInfo(name = "deleted_at_ms") val deletedAtMs: Long,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
) {
	init {
		require(AmbientCellAuthorityIntegrity.isDigest(archiveId))
		require(collectedDataEpoch >= 0L && deletionGeneration > 0L && deletedAtMs >= 0L)
		require(effectChecksum == AmbientCellFactIntegrity.importTombstoneChecksum(this))
	}
}

@Entity(
	tableName = "ambient_cell_replay_footprint",
	primaryKeys = ["footprint_kind", "identity_digest", "semantic_revision"],
)
data class AmbientCellReplayFootprintEntity(
	@ColumnInfo(name = "footprint_kind") val footprintKind: String,
	@ColumnInfo(name = "identity_digest") val identityDigest: String,
	@ColumnInfo(name = "semantic_revision") val semanticRevision: Long,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "deletion_generation") val deletionGeneration: Long,
	@ColumnInfo(name = "recorded_at_ms") val recordedAtMs: Long,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
) {
	init {
		require(footprintKind in KINDS)
		require(AmbientCellAuthorityIntegrity.isDigest(identityDigest))
		require(semanticRevision >= 0L)
		require(
			(footprintKind == KIND_FACT_IDENTITY || footprintKind == KIND_LOCAL_FACT) ==
				(semanticRevision > 0L),
		)
		require(collectedDataEpoch >= 0L && deletionGeneration > 0L && recordedAtMs >= 0L)
		require(effectChecksum == AmbientCellFactIntegrity.replayFootprintChecksum(this))
	}

	companion object {
		const val KIND_LOCAL_FACT = "LOCAL_FACT"
		const val KIND_LOCAL_GAP = "LOCAL_GAP"
		const val KIND_FACT_IDENTITY = "PORTABLE_FACT_IDENTITY"
		const val KIND_FACT_EFFECT = "PORTABLE_FACT_EFFECT"
		const val KIND_GAP_IDENTITY = "PORTABLE_GAP_IDENTITY"
		const val KIND_GAP_EFFECT = "PORTABLE_GAP_EFFECT"
		const val KIND_ARCHIVE_SCOPE = "ARCHIVE_SCOPE"
		private val KINDS = setOf(
			KIND_LOCAL_FACT,
			KIND_LOCAL_GAP,
			KIND_FACT_IDENTITY,
			KIND_FACT_EFFECT,
			KIND_GAP_IDENTITY,
			KIND_GAP_EFFECT,
			KIND_ARCHIVE_SCOPE,
		)
	}
}

object AmbientCellFactIntegrity {
	fun createReplayFootprint(
		footprintKind: String,
		identityDigest: String,
		semanticRevision: Long,
		collectedDataEpoch: Long,
		deletionGeneration: Long,
		recordedAtMs: Long,
	): AmbientCellReplayFootprintEntity =
		AmbientCellReplayFootprintEntity(
			footprintKind,
			identityDigest,
			semanticRevision,
			collectedDataEpoch,
			deletionGeneration,
			recordedAtMs,
			replayFootprintChecksum(
				footprintKind,
				identityDigest,
				semanticRevision,
				collectedDataEpoch,
				deletionGeneration,
				recordedAtMs,
			),
		)

	fun replayFootprintChecksum(value: AmbientCellReplayFootprintEntity): String =
		replayFootprintChecksum(
			value.footprintKind,
			value.identityDigest,
			value.semanticRevision,
			value.collectedDataEpoch,
			value.deletionGeneration,
			value.recordedAtMs,
		)

	private fun replayFootprintChecksum(
		footprintKind: String,
		identityDigest: String,
		semanticRevision: Long,
		collectedDataEpoch: Long,
		deletionGeneration: Long,
		recordedAtMs: Long,
	): String =
		AmbientCellAuthorityIntegrity.digest(
			"ambient-cell-replay-footprint-v1",
			footprintKind,
			identityDigest,
			semanticRevision,
			collectedDataEpoch,
			deletionGeneration,
			recordedAtMs,
		)

	fun isAuthentic(value: AmbientCellDeletionMarkerEntity): Boolean =
		value.effectChecksum == deletionChecksum(value)

	fun isAuthentic(value: AmbientCellReplayFootprintEntity): Boolean =
		value.effectChecksum == replayFootprintChecksum(value)

	fun logicalFactId(
		sourceDeliveryIdentity: String,
		ambientConsentEpoch: Long,
		collectedDataEpoch: Long,
		scopeDeletionGeneration: Long,
	): String = AmbientCellAuthorityIntegrity.digest(
		"ambient-cell-fact-v1",
		sourceDeliveryIdentity,
		ambientConsentEpoch,
		collectedDataEpoch,
		scopeDeletionGeneration,
	)

	fun mutationId(logicalFactId: String, semanticRevision: Long): String =
		AmbientCellAuthorityIntegrity.digest(
			"ambient-cell-mutation-v1",
			logicalFactId,
			semanticRevision,
		)

	fun effectChecksum(value: AmbientCellFactRevisionEntity): String =
		AmbientCellAuthorityIntegrity.digest(
			"ambient-cell-effect-v1",
			value.writerId,
			value.writerVersion,
			value.writerOwnerGeneration,
			value.logicalFactId,
			value.semanticRevision,
			value.supersedesSemanticRevision,
			value.mutationId,
			value.factKind,
			value.aggregateOwnerLogicalFactId,
			value.aggregateOwnerSemanticRevision,
			value.sourceEventId,
			value.sourceAdmissionOrdinal,
			value.walIntegrityIdentity,
			value.payloadChecksum,
			value.sourceDeliveryIdentity,
			value.sourceInstanceId,
			value.registrationGeneration,
			value.configurationRevision,
			value.physicalConfigurationFingerprint,
			value.authorizationRevision,
			value.authorizationFingerprint,
			value.purposeEligibilityMask,
			value.sourcePolicyRevision,
			value.ambientConsentEpoch,
			value.retentionPolicyId,
			value.retentionApprovalRevision,
			value.collectedDataEpoch,
			value.scopeDeletionGeneration,
			value.clockDomainId,
			value.storedZoneId,
			value.structuralEpochDay,
			value.structuralDayStartTimeMs,
			value.structuralDayEndTimeMs,
			value.observedIntervalStartNanos,
			value.observedElapsedNanos,
			value.receivedElapsedNanos,
			value.coverageStartTimeMs,
			value.observedWallTimeMs,
			value.wallTimeUncertaintyMs,
			value.coverageCompleteness,
			value.subscriptionCompleteness,
			value.observationCount,
			value.registeredObservationCount,
			value.gsmCount,
			value.cdmaCount,
			value.wcdmaCount,
			value.tdscdmaCount,
			value.lteCount,
			value.nrCount,
			value.qualityUnknownCount,
			value.qualityNoneOrUnknownCount,
			value.qualityPoorCount,
			value.qualityModerateCount,
			value.qualityGoodCount,
			value.qualityGreatCount,
			value.qualityFlags,
			value.qualityConfidence,
			value.appliedAtMs,
		)

	fun deletionChecksum(value: AmbientCellDeletionMarkerEntity): String =
		deletionChecksum(
			value.collectedDataEpoch,
			value.deletionGeneration,
			value.throughConsentEpoch,
			value.reason,
			value.deletedAtMs,
		)

	fun createDeletionMarker(
		collectedDataEpoch: Long,
		deletionGeneration: Long,
		throughConsentEpoch: Long,
		reason: String,
		deletedAtMs: Long,
	): AmbientCellDeletionMarkerEntity = AmbientCellDeletionMarkerEntity(
		collectedDataEpoch,
		deletionGeneration,
		throughConsentEpoch,
		reason,
		deletedAtMs,
		deletionChecksum(
			collectedDataEpoch,
			deletionGeneration,
			throughConsentEpoch,
			reason,
			deletedAtMs,
		),
	)

	private fun deletionChecksum(
		collectedDataEpoch: Long,
		deletionGeneration: Long,
		throughConsentEpoch: Long,
		reason: String,
		deletedAtMs: Long,
	): String =
		AmbientCellAuthorityIntegrity.digest(
			"ambient-cell-deletion-v1",
			collectedDataEpoch,
			deletionGeneration,
			throughConsentEpoch,
			reason,
			deletedAtMs,
		)

	fun createGap(
		gapId: String,
		reason: String,
		gapStartTimeMs: Long,
		gapEndTimeMs: Long,
		storedZoneId: String,
		structuralEpochDay: Long,
		sourcePolicyRevision: Long,
		ambientConsentEpoch: Long,
		retentionPolicyId: String,
		retentionApprovalRevision: Long,
		collectedDataEpoch: Long,
		scopeDeletionGeneration: Long,
		createdAtMs: Long,
	): AmbientCellGapEntity = AmbientCellGapEntity(
		gapId,
		reason,
		gapStartTimeMs,
		gapEndTimeMs,
		storedZoneId,
		structuralEpochDay,
		sourcePolicyRevision,
		ambientConsentEpoch,
		retentionPolicyId,
		retentionApprovalRevision,
		collectedDataEpoch,
		scopeDeletionGeneration,
		createdAtMs,
		gapChecksum(
			gapId,
			reason,
			gapStartTimeMs,
			gapEndTimeMs,
			storedZoneId,
			structuralEpochDay,
			sourcePolicyRevision,
			ambientConsentEpoch,
			retentionPolicyId,
			retentionApprovalRevision,
			collectedDataEpoch,
			scopeDeletionGeneration,
			createdAtMs,
		),
	)

	fun gapChecksum(value: AmbientCellGapEntity): String = gapChecksum(
		value.gapId,
		value.reason,
		value.gapStartTimeMs,
		value.gapEndTimeMs,
		value.storedZoneId,
		value.structuralEpochDay,
		value.sourcePolicyRevision,
		value.ambientConsentEpoch,
		value.retentionPolicyId,
		value.retentionApprovalRevision,
		value.collectedDataEpoch,
		value.scopeDeletionGeneration,
		value.createdAtMs,
	)

	private fun gapChecksum(
		gapId: String,
		reason: String,
		gapStartTimeMs: Long,
		gapEndTimeMs: Long,
		storedZoneId: String,
		structuralEpochDay: Long,
		sourcePolicyRevision: Long,
		ambientConsentEpoch: Long,
		retentionPolicyId: String,
		retentionApprovalRevision: Long,
		collectedDataEpoch: Long,
		scopeDeletionGeneration: Long,
		createdAtMs: Long,
	): String = AmbientCellAuthorityIntegrity.digest(
		"ambient-cell-gap-effect-v1",
		gapId,
		reason,
		gapStartTimeMs,
		gapEndTimeMs,
		storedZoneId,
		structuralEpochDay,
		sourcePolicyRevision,
		ambientConsentEpoch,
		retentionPolicyId,
		retentionApprovalRevision,
		collectedDataEpoch,
		scopeDeletionGeneration,
		createdAtMs,
	)

	fun importTombstoneChecksum(value: ImportedAmbientCellTombstoneEntity): String =
		importTombstoneChecksum(
			value.archiveId,
			value.collectedDataEpoch,
			value.deletionGeneration,
			value.deletedAtMs,
		)

	fun createImportTombstone(
		archiveId: String,
		collectedDataEpoch: Long,
		deletionGeneration: Long,
		deletedAtMs: Long,
	): ImportedAmbientCellTombstoneEntity = ImportedAmbientCellTombstoneEntity(
		archiveId,
		collectedDataEpoch,
		deletionGeneration,
		deletedAtMs,
		importTombstoneChecksum(
			archiveId,
			collectedDataEpoch,
			deletionGeneration,
			deletedAtMs,
		),
	)

	private fun importTombstoneChecksum(
		archiveId: String,
		collectedDataEpoch: Long,
		deletionGeneration: Long,
		deletedAtMs: Long,
	): String =
		AmbientCellAuthorityIntegrity.digest(
			"ambient-cell-import-tombstone-v1",
			archiveId,
			collectedDataEpoch,
			deletionGeneration,
			deletedAtMs,
		)
}

private const val MAX_AMBIENT_CELL_SEMANTIC_REVISIONS = 65_536L
