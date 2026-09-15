package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Identity-free, sessionless Wi-Fi product evidence derived from one exact durable source WAL row. */
@Entity(
	tableName = "ambient_wifi_fact_revision",
	primaryKeys = ["writer_id", "writer_version", "logical_fact_id", "semantic_revision"],
	indices = [
		Index(
			value = ["writer_id", "writer_version", "mutation_id"],
			unique = true,
			name = "idx_ambient_wifi_fact_mutation",
		),
		Index(
			value = ["source_admission_ordinal"],
			name = "idx_ambient_wifi_fact_admission",
		),
		Index(
			value = ["structural_epoch_day", "stored_zone_id", "observed_wall_time_ms"],
			name = "idx_ambient_wifi_fact_day",
		),
		Index(
			value = ["observed_wall_time_ms", "logical_fact_id"],
			name = "idx_ambient_wifi_fact_window",
		),
		Index(
			value = ["aggregate_owner_logical_fact_id", "aggregate_owner_semantic_revision"],
			name = "idx_ambient_wifi_fact_aggregate_owner",
		),
	],
)
@Suppress("LongParameterList")
data class AmbientWifiFactRevisionEntity(
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
	@ColumnInfo(name = "observation_count") val observationCount: Int?,
	@ColumnInfo(name = "two_point_four_ghz_count") val twoPointFourGhzCount: Int?,
	@ColumnInfo(name = "five_ghz_count") val fiveGhzCount: Int?,
	@ColumnInfo(name = "six_ghz_count") val sixGhzCount: Int?,
	@ColumnInfo(name = "other_band_count") val otherBandCount: Int?,
	@ColumnInfo(name = "strongest_signal_dbm") val strongestSignalDbm: Int?,
	@ColumnInfo(name = "weakest_signal_dbm") val weakestSignalDbm: Int?,
	@ColumnInfo(name = "signal_sum_dbm") val signalSumDbm: Long?,
	@ColumnInfo(name = "quality_flags") val qualityFlags: Long,
	@ColumnInfo(name = "quality_confidence") val qualityConfidence: Float?,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
	@ColumnInfo(name = "applied_at_ms") val appliedAtMs: Long,
) {
	init {
		require(writerId == WRITER_ID && writerVersion == WRITER_VERSION)
		require(writerOwnerGeneration > 0L)
		require(AmbientWifiAuthorityIntegrity.isDigest(logicalFactId))
		require(semanticRevision in 1L..MAX_AMBIENT_WIFI_SEMANTIC_REVISIONS)
		require(supersedesSemanticRevision == semanticRevision.takeIf { it > 1L }?.minus(1L))
		require(mutationId == AmbientWifiFactIntegrity.mutationId(logicalFactId, semanticRevision))
		require(factKind in FACT_KINDS)
		require((aggregateOwnerLogicalFactId == null) == (aggregateOwnerSemanticRevision == null))
		aggregateOwnerLogicalFactId?.let(AmbientWifiAuthorityIntegrity::isDigest).also {
			require(it != false)
		}
		require(aggregateOwnerSemanticRevision?.let { it > 0L } != false)
		require(sourceEventId.isNotBlank() && sourceAdmissionOrdinal > 0L)
		require(AmbientWifiAuthorityIntegrity.isDigest(walIntegrityIdentity))
		require(AmbientWifiAuthorityIntegrity.isDigest(payloadChecksum))
		require(AmbientWifiAuthorityIntegrity.isDigest(sourceDeliveryIdentity))
		require(sourceInstanceId.isNotBlank() && registrationGeneration > 0L)
		require(physicalConfigurationFingerprint.isNotBlank())
		require(authorizationRevision > 0L)
		require(AmbientWifiAuthorityIntegrity.isDigest(authorizationFingerprint))
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
		require(qualityFlags >= 0L && qualityConfidence?.let { it in 0f..1f } != false)
		requireFactShape()
		require(
			logicalFactId == AmbientWifiFactIntegrity.logicalFactId(
				sourceDeliveryIdentity,
				ambientConsentEpoch,
				collectedDataEpoch,
				scopeDeletionGeneration,
			),
		)
		require(AmbientWifiAuthorityIntegrity.isDigest(effectChecksum))
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
			twoPointFourGhzCount,
			fiveGhzCount,
			sixGhzCount,
			otherBandCount,
			strongestSignalDbm,
			weakestSignalDbm,
			signalSumDbm,
		)
		if (factKind == FACT_KIND_AGGREGATE) {
			require(aggregateOwnerLogicalFactId == null && aggregate.all { it != null })
			val count = requireNotNull(observationCount)
			require(count > 0)
			require(
				listOf(
					twoPointFourGhzCount,
					fiveGhzCount,
					sixGhzCount,
					otherBandCount,
				).filterNotNull().sum() == count,
			)
			require(requireNotNull(strongestSignalDbm) >= requireNotNull(weakestSignalDbm))
		} else {
			require(aggregateOwnerLogicalFactId != null && aggregate.all { it == null })
		}
	}

	companion object {
		const val WRITER_ID = "ambient-wifi-facts"
		const val WRITER_VERSION = 1
		const val FACT_KIND_AGGREGATE = "AGGREGATE"
		const val FACT_KIND_COVERAGE_ONLY = "COVERAGE_ONLY"
		const val COMPLETENESS_COMPLETE = "COMPLETE"
		const val COMPLETENESS_UNVERIFIABLE = "UNVERIFIABLE"
		private val FACT_KINDS = setOf(FACT_KIND_AGGREGATE, FACT_KIND_COVERAGE_ONLY)
		private val COMPLETENESS = setOf(COMPLETENESS_COMPLETE, COMPLETENESS_UNVERIFIABLE)
	}
}

@Entity(
	tableName = "ambient_wifi_fact_cursor",
	primaryKeys = ["writer_id", "writer_version", "logical_fact_id"],
)
data class AmbientWifiFactCursorEntity(
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
		require(writerId == AmbientWifiFactRevisionEntity.WRITER_ID)
		require(writerVersion == AmbientWifiFactRevisionEntity.WRITER_VERSION)
		require(AmbientWifiAuthorityIntegrity.isDigest(logicalFactId))
		require(latestSemanticRevision in 1L..MAX_AMBIENT_WIFI_SEMANTIC_REVISIONS)
		require(AmbientWifiAuthorityIntegrity.isDigest(latestMutationId))
		require(AmbientWifiAuthorityIntegrity.isDigest(latestEffectChecksum))
		require(latestSourceAdmissionOrdinal > 0L)
		require(collectedDataEpoch >= 0L && scopeDeletionGeneration >= 0L)
		require(cursorRevision > 0L && updatedAtMs >= 0L)
	}
}

@Entity(
	tableName = "ambient_wifi_gap",
	primaryKeys = ["gap_id"],
	indices = [
		Index(
			value = ["structural_epoch_day", "stored_zone_id", "gap_start_time_ms"],
			name = "idx_ambient_wifi_gap_day",
		),
		Index(
			value = ["gap_start_time_ms", "gap_end_time_ms"],
			name = "idx_ambient_wifi_gap_window",
		),
	],
)
data class AmbientWifiGapEntity(
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
		require(AmbientWifiAuthorityIntegrity.isDigest(gapId))
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
		require(effectChecksum == AmbientWifiFactIntegrity.gapChecksum(this))
	}

	companion object {
		const val REASON_CLOCK_UNVERIFIABLE = "CLOCK_UNVERIFIABLE"
		const val REASON_PROVIDER_COMPLETENESS_UNVERIFIABLE =
			"PROVIDER_COMPLETENESS_UNVERIFIABLE"
		const val REASON_STORAGE_DISCONTINUITY = "STORAGE_DISCONTINUITY"
		private val REASONS = setOf(
			REASON_CLOCK_UNVERIFIABLE,
			REASON_PROVIDER_COMPLETENESS_UNVERIFIABLE,
			REASON_STORAGE_DISCONTINUITY,
		)
	}
}

@Entity(
	tableName = "ambient_wifi_deletion_marker",
	primaryKeys = ["collected_data_epoch", "deletion_generation"],
)
data class AmbientWifiDeletionMarkerEntity(
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "deletion_generation") val deletionGeneration: Long,
	@ColumnInfo(name = "through_consent_epoch") val throughConsentEpoch: Long,
	@ColumnInfo(name = "reason") val reason: String,
	@ColumnInfo(name = "deleted_at_ms") val deletedAtMs: Long,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
) {
	init {
		require(collectedDataEpoch >= 0L && deletionGeneration > 0L)
		require(throughConsentEpoch >= 0L)
		require(reason.isNotBlank())
		require(deletedAtMs >= 0L)
		require(effectChecksum == AmbientWifiFactIntegrity.deletionChecksum(this))
	}
}

/** Portable ambient evidence is product-only and intentionally carries no local provider authority. */
@Entity(
	tableName = "imported_ambient_wifi_fact",
	primaryKeys = ["archive_id", "fact_id", "semantic_revision"],
	indices = [
		Index(
			value = ["structural_epoch_day", "stored_zone_id", "observed_time_ms"],
			name = "idx_imported_ambient_wifi_day",
		),
		Index(
			value = ["fact_id", "semantic_revision"],
			unique = true,
			name = "idx_imported_ambient_wifi_fact_revision",
		),
	],
)
@Suppress("LongParameterList")
data class ImportedAmbientWifiFactEntity(
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
	@ColumnInfo(name = "observation_count") val observationCount: Int,
	@ColumnInfo(name = "two_point_four_ghz_count") val twoPointFourGhzCount: Int,
	@ColumnInfo(name = "five_ghz_count") val fiveGhzCount: Int,
	@ColumnInfo(name = "six_ghz_count") val sixGhzCount: Int,
	@ColumnInfo(name = "other_band_count") val otherBandCount: Int,
	@ColumnInfo(name = "strongest_signal_dbm") val strongestSignalDbm: Int,
	@ColumnInfo(name = "weakest_signal_dbm") val weakestSignalDbm: Int,
	@ColumnInfo(name = "mean_signal_dbm") val meanSignalDbm: Double,
	@ColumnInfo(name = "retention_policy_id") val retentionPolicyId: String,
	@ColumnInfo(name = "retention_approval_revision") val retentionApprovalRevision: Long,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "import_deletion_generation") val importDeletionGeneration: Long,
	@ColumnInfo(name = "received_at_ms") val receivedAtMs: Long,
) {
	init {
		require(AmbientWifiAuthorityIntegrity.isDigest(archiveId))
		require(AmbientWifiAuthorityIntegrity.isDigest(factId))
		require(semanticRevision in 1L..MAX_AMBIENT_WIFI_SEMANTIC_REVISIONS)
		require(supersedesSemanticRevision == semanticRevision.takeIf { it > 1L }?.minus(1L))
		require(AmbientWifiAuthorityIntegrity.isDigest(contentChecksum))
		require(AmbientWifiAuthorityIntegrity.isDigest(portableEffectChecksum))
		require(portableOrigin in setOf("LOCAL_DEVICE", "PORTABLE_IMPORT"))
		require(coverageStartTimeMs >= 0L && observedTimeMs >= coverageStartTimeMs)
		require(latestPossibleTimeMs >= observedTimeMs)
		val zone = ZoneId.of(storedZoneId)
		require(
			Instant.ofEpochMilli(observedTimeMs).atZone(zone).toLocalDate().toEpochDay() ==
				structuralEpochDay,
		)
		require(coverageCompleteness in setOf(
			AmbientWifiFactRevisionEntity.COMPLETENESS_COMPLETE,
			AmbientWifiFactRevisionEntity.COMPLETENESS_UNVERIFIABLE,
		))
		require(observationCount > 0)
		require(
			twoPointFourGhzCount + fiveGhzCount + sixGhzCount + otherBandCount ==
				observationCount,
		)
		require(strongestSignalDbm >= weakestSignalDbm)
		require(meanSignalDbm.isFinite() &&
			meanSignalDbm in weakestSignalDbm.toDouble()..strongestSignalDbm.toDouble())
		require(retentionPolicyId.isNotBlank() && retentionApprovalRevision > 0L)
		require(collectedDataEpoch >= 0L && importDeletionGeneration >= 0L)
		require(receivedAtMs >= latestPossibleTimeMs)
	}
}

@Entity(
	tableName = "imported_ambient_wifi_gap",
	primaryKeys = ["archive_id", "gap_id"],
	indices = [
		Index(
			value = ["start_time_ms", "end_time_ms"],
			name = "idx_imported_ambient_wifi_gap_window",
		),
		Index(
			value = ["gap_id"],
			unique = true,
			name = "idx_imported_ambient_wifi_gap_identity",
		),
	],
)
data class ImportedAmbientWifiGapEntity(
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
		require(AmbientWifiAuthorityIntegrity.isDigest(archiveId))
		require(AmbientWifiAuthorityIntegrity.isDigest(gapId))
		require(AmbientWifiAuthorityIntegrity.isDigest(contentChecksum))
		require(AmbientWifiAuthorityIntegrity.isDigest(portableEffectChecksum))
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
	tableName = "imported_ambient_wifi_receipt",
	primaryKeys = ["import_job_id", "import_entry_key"],
	indices = [
		Index(value = ["archive_id"], name = "idx_imported_ambient_wifi_receipt_archive"),
	],
)
data class ImportedAmbientWifiReceiptEntity(
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
		require(AmbientWifiAuthorityIntegrity.isDigest(archiveId))
		require(AmbientWifiAuthorityIntegrity.isDigest(archiveChecksum))
		require(collectedDataEpoch >= 0L && receivedAtMs >= 0L)
	}
}

@Entity(
	tableName = "imported_ambient_wifi_tombstone",
	primaryKeys = ["archive_id"],
)
data class ImportedAmbientWifiTombstoneEntity(
	@ColumnInfo(name = "archive_id") val archiveId: String,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "deletion_generation") val deletionGeneration: Long,
	@ColumnInfo(name = "deleted_at_ms") val deletedAtMs: Long,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
) {
	init {
		require(AmbientWifiAuthorityIntegrity.isDigest(archiveId))
		require(collectedDataEpoch >= 0L && deletionGeneration > 0L && deletedAtMs >= 0L)
		require(effectChecksum == AmbientWifiFactIntegrity.importTombstoneChecksum(this))
	}
}

@Entity(
	tableName = "ambient_wifi_replay_footprint",
	primaryKeys = ["footprint_kind", "identity_digest", "semantic_revision"],
)
data class AmbientWifiReplayFootprintEntity(
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
		require(AmbientWifiAuthorityIntegrity.isDigest(identityDigest))
		require(semanticRevision >= 0L)
		require(
			(footprintKind == KIND_FACT_IDENTITY || footprintKind == KIND_LOCAL_FACT) ==
				(semanticRevision > 0L),
		)
		require(collectedDataEpoch >= 0L && deletionGeneration > 0L && recordedAtMs >= 0L)
		require(effectChecksum == AmbientWifiFactIntegrity.replayFootprintChecksum(this))
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

object AmbientWifiFactIntegrity {
	fun createReplayFootprint(
		footprintKind: String,
		identityDigest: String,
		semanticRevision: Long,
		collectedDataEpoch: Long,
		deletionGeneration: Long,
		recordedAtMs: Long,
	): AmbientWifiReplayFootprintEntity =
		AmbientWifiReplayFootprintEntity(
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

	fun replayFootprintChecksum(value: AmbientWifiReplayFootprintEntity): String =
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
		AmbientWifiAuthorityIntegrity.digest(
			"ambient-wifi-replay-footprint-v1",
			footprintKind,
			identityDigest,
			semanticRevision,
			collectedDataEpoch,
			deletionGeneration,
			recordedAtMs,
		)

	fun isAuthentic(value: AmbientWifiDeletionMarkerEntity): Boolean =
		value.effectChecksum == deletionChecksum(value)

	fun isAuthentic(value: AmbientWifiReplayFootprintEntity): Boolean =
		value.effectChecksum == replayFootprintChecksum(value)

	fun logicalFactId(
		sourceDeliveryIdentity: String,
		ambientConsentEpoch: Long,
		collectedDataEpoch: Long,
		scopeDeletionGeneration: Long,
	): String = AmbientWifiAuthorityIntegrity.digest(
		"ambient-wifi-fact-v1",
		sourceDeliveryIdentity,
		ambientConsentEpoch,
		collectedDataEpoch,
		scopeDeletionGeneration,
	)

	fun mutationId(logicalFactId: String, semanticRevision: Long): String =
		AmbientWifiAuthorityIntegrity.digest(
			"ambient-wifi-mutation-v1",
			logicalFactId,
			semanticRevision,
		)

	fun effectChecksum(value: AmbientWifiFactRevisionEntity): String =
		AmbientWifiAuthorityIntegrity.digest(
			"ambient-wifi-effect-v1",
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
			value.observationCount,
			value.twoPointFourGhzCount,
			value.fiveGhzCount,
			value.sixGhzCount,
			value.otherBandCount,
			value.strongestSignalDbm,
			value.weakestSignalDbm,
			value.signalSumDbm,
			value.qualityFlags,
			value.qualityConfidence,
			value.appliedAtMs,
		)

	fun deletionChecksum(value: AmbientWifiDeletionMarkerEntity): String =
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
	): AmbientWifiDeletionMarkerEntity = AmbientWifiDeletionMarkerEntity(
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
		AmbientWifiAuthorityIntegrity.digest(
			"ambient-wifi-deletion-v1",
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
	): AmbientWifiGapEntity = AmbientWifiGapEntity(
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

	fun gapChecksum(value: AmbientWifiGapEntity): String = gapChecksum(
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
	): String = AmbientWifiAuthorityIntegrity.digest(
		"ambient-wifi-gap-effect-v1",
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

	fun importTombstoneChecksum(value: ImportedAmbientWifiTombstoneEntity): String =
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
	): ImportedAmbientWifiTombstoneEntity = ImportedAmbientWifiTombstoneEntity(
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
		AmbientWifiAuthorityIntegrity.digest(
			"ambient-wifi-import-tombstone-v1",
			archiveId,
			collectedDataEpoch,
			deletionGeneration,
			deletedAtMs,
		)
}

private const val MAX_AMBIENT_WIFI_SEMANTIC_REVISIONS = 65_536L
