package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import java.security.MessageDigest

/**
 * Value-free terminal authority for one Pressure correction lineage removed by retention.
 *
 * The row survives both the fact lineage and its WAL carriers. It binds the current revision used
 * by production history, the revision that crossed the floor, and the complete child identity set
 * for the authenticated lineage without retaining any Pressure values.
 */
@Entity(
	tableName = "pressure_fact_retention_authority",
	primaryKeys = [
		"writer_projection_id",
		"writer_projection_version",
		"logical_fact_id",
	],
	indices = [
		Index(
			value = [
				"writer_projection_id",
				"writer_projection_version",
				"current_source_event_id",
			],
			unique = true,
			name = "idx_pressure_retention_current_event",
		),
		Index(
			value = [
				"writer_projection_id",
				"writer_projection_version",
				"current_source_admission_ordinal",
			],
			unique = true,
			name = "idx_pressure_retention_current_admission",
		),
		Index(
			value = ["trigger_source_event_id", "trigger_source_admission_ordinal"],
			name = "idx_pressure_retention_trigger_evidence",
		),
		Index(
			value = ["service_run_id", "logical_tracking_id", "logical_fact_id"],
			name = "idx_pressure_retention_run_scope",
		),
	],
)
@Suppress("LongParameterList")
data class PressureFactRetentionAuthorityEntity(
	@ColumnInfo(name = "writer_projection_id")
	val writerProjectionId: String,
	@ColumnInfo(name = "writer_projection_version")
	val writerProjectionVersion: Int,
	@ColumnInfo(name = "logical_fact_id")
	val logicalFactId: String,
	@ColumnInfo(name = "current_semantic_revision")
	val currentSemanticRevision: Long,
	@ColumnInfo(name = "current_source_event_id")
	val currentSourceEventId: String,
	@ColumnInfo(name = "current_source_admission_ordinal")
	val currentSourceAdmissionOrdinal: Long,
	@ColumnInfo(name = "current_source_policy_revision")
	val currentSourcePolicyRevision: Long,
	@ColumnInfo(name = "current_capture_consent_epoch")
	val currentCaptureConsentEpoch: Long,
	@ColumnInfo(name = "trigger_semantic_revision")
	val triggerSemanticRevision: Long,
	@ColumnInfo(name = "trigger_source_event_id")
	val triggerSourceEventId: String,
	@ColumnInfo(name = "trigger_source_admission_ordinal")
	val triggerSourceAdmissionOrdinal: Long,
	@ColumnInfo(name = "trigger_source_policy_revision")
	val triggerSourcePolicyRevision: Long,
	@ColumnInfo(name = "trigger_capture_consent_epoch")
	val triggerCaptureConsentEpoch: Long,
	@ColumnInfo(name = "logical_tracking_id")
	val logicalTrackingId: String,
	@ColumnInfo(name = "service_run_id")
	val serviceRunId: String,
	@ColumnInfo(name = "collected_data_epoch")
	val collectedDataEpoch: Long,
	@ColumnInfo(name = "retained_from_ms")
	val retainedFromMs: Long,
	@ColumnInfo(name = "lineage_interval_start_ms")
	val lineageIntervalStartMs: Long,
	@ColumnInfo(name = "lineage_interval_end_ms")
	val lineageIntervalEndMs: Long,
	@ColumnInfo(name = "lineage_earliest_possible_start_ms")
	val lineageEarliestPossibleStartMs: Long,
	@ColumnInfo(name = "lineage_revision_count")
	val lineageRevisionCount: Int,
	@ColumnInfo(name = "lineage_effect_checksum")
	val lineageEffectChecksum: String,
	@ColumnInfo(name = "revision_authority_set_checksum")
	val revisionAuthoritySetChecksum: String,
	@ColumnInfo(name = "retained_at_ms")
	val retainedAtMs: Long,
	@ColumnInfo(name = "reason")
	val reason: String,
	@ColumnInfo(name = "effect_checksum")
	val effectChecksum: String,
) {
	fun hasValidStoredIntegrity(): Boolean =
		writerProjectionId == SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID &&
			writerProjectionVersion ==
			SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION &&
			logicalFactId.isNotBlank() &&
			currentSemanticRevision > 0L &&
			currentSourceEventId.isNotBlank() &&
			currentSourceAdmissionOrdinal > 0L &&
			currentSourcePolicyRevision > 0L &&
			currentCaptureConsentEpoch >= 0L &&
			triggerSemanticRevision in 1L..currentSemanticRevision &&
			triggerSourceEventId.isNotBlank() &&
			triggerSourceAdmissionOrdinal > 0L &&
			triggerSourcePolicyRevision > 0L &&
			triggerCaptureConsentEpoch >= 0L &&
			logicalTrackingId.isNotBlank() &&
			serviceRunId.isNotBlank() &&
			collectedDataEpoch >= 0L &&
			retainedFromMs >= 0L &&
			lineageIntervalStartMs >= 0L &&
			lineageIntervalEndMs >= lineageIntervalStartMs &&
			lineageEarliestPossibleStartMs in 0L..lineageIntervalStartMs &&
			lineageEarliestPossibleStartMs < retainedFromMs &&
			lineageRevisionCount > 0 &&
			lineageRevisionCount <= MAX_PRESSURE_RETENTION_REVISION_AUTHORITIES &&
			currentSemanticRevision == lineageRevisionCount.toLong() &&
			(triggerSemanticRevision != currentSemanticRevision ||
				(triggerSourceEventId == currentSourceEventId &&
					triggerSourceAdmissionOrdinal == currentSourceAdmissionOrdinal &&
					triggerSourcePolicyRevision == currentSourcePolicyRevision &&
					triggerCaptureConsentEpoch == currentCaptureConsentEpoch)) &&
			lineageEffectChecksum.matches(SHA_256_HEX) &&
			revisionAuthoritySetChecksum.matches(SHA_256_HEX) &&
			retainedAtMs >= 0L &&
			reason == REASON_RETENTION_EXPIRED &&
			effectChecksum == calculatedEffectChecksum()

	fun calculatedEffectChecksum(): String = checksum(
		CONTRACT,
		writerProjectionId,
		writerProjectionVersion,
		logicalFactId,
		currentSemanticRevision,
		currentSourceEventId,
		currentSourceAdmissionOrdinal,
		currentSourcePolicyRevision,
		currentCaptureConsentEpoch,
		triggerSemanticRevision,
		triggerSourceEventId,
		triggerSourceAdmissionOrdinal,
		triggerSourcePolicyRevision,
		triggerCaptureConsentEpoch,
		logicalTrackingId,
		serviceRunId,
		collectedDataEpoch,
		retainedFromMs,
		lineageIntervalStartMs,
		lineageIntervalEndMs,
		lineageEarliestPossibleStartMs,
		lineageRevisionCount,
		lineageEffectChecksum,
		revisionAuthoritySetChecksum,
		retainedAtMs,
		reason,
	)

	fun authenticatesRevisionAuthorities(
		authorities: List<PressureFactRetentionRevisionAuthorityEntity>,
	): Boolean {
		if (!hasValidStoredIntegrity() || authorities.size != lineageRevisionCount) return false
		val ordered = authorities.sortedBy(
			PressureFactRetentionRevisionAuthorityEntity::semanticRevision,
		)
		if (ordered.map(PressureFactRetentionRevisionAuthorityEntity::semanticRevision) !=
			(1L..lineageRevisionCount.toLong()).toList() ||
			ordered.map(PressureFactRetentionRevisionAuthorityEntity::sourceEventId)
				.distinct().size != ordered.size ||
			ordered.map(PressureFactRetentionRevisionAuthorityEntity::sourceAdmissionOrdinal)
				.distinct().size != ordered.size ||
			ordered.any { authority -> !authority.hasValidStoredIntegrity(this) } ||
			PressureFactRetentionRevisionAuthorityEntity.authoritySetChecksum(ordered) !=
			revisionAuthoritySetChecksum
		) {
			return false
		}
		val current = ordered.last()
		val trigger = ordered.firstOrNull { it.semanticRevision == triggerSemanticRevision }
			?: return false
		return current.sourceEventId == currentSourceEventId &&
			current.sourceAdmissionOrdinal == currentSourceAdmissionOrdinal &&
			current.sourcePolicyRevision == currentSourcePolicyRevision &&
			current.captureConsentEpoch == currentCaptureConsentEpoch &&
			trigger.sourceEventId == triggerSourceEventId &&
			trigger.sourceAdmissionOrdinal == triggerSourceAdmissionOrdinal &&
			trigger.sourcePolicyRevision == triggerSourcePolicyRevision &&
			trigger.captureConsentEpoch == triggerCaptureConsentEpoch
	}

	companion object {
		const val REASON_RETENTION_EXPIRED = "RETENTION_EXPIRED"
		private const val CONTRACT = "pressure-fact-retention-authority-v2"
		private const val LINEAGE_CONTRACT = "pressure-fact-retention-lineage-v1"
		private val SHA_256_HEX = Regex("[0-9a-f]{64}")

		@Suppress("LongMethod")
		fun create(
			revisions: List<PressureFactRevisionEntity>,
			retainedFromMs: Long,
			retainedAtMs: Long,
		): PressureFactRetentionAuthorityEntity {
			require(revisions.isNotEmpty())
			require(revisions.size <= MAX_PRESSURE_RETENTION_REVISION_AUTHORITIES)
			require(retainedFromMs >= 0L)
			require(retainedAtMs >= 0L)
			val ordered = revisions.sortedBy(PressureFactRevisionEntity::semanticRevision)
			val first = ordered.first()
			require(ordered.map(PressureFactRevisionEntity::semanticRevision) ==
				(1L..ordered.size.toLong()).toList())
			require(ordered.all { revision ->
				revision.writerProjectionId == first.writerProjectionId &&
					revision.writerProjectionVersion == first.writerProjectionVersion &&
					revision.logicalFactId == first.logicalFactId &&
					revision.logicalTrackingId == first.logicalTrackingId &&
					revision.serviceRunId == first.serviceRunId &&
					revision.collectedDataEpoch == first.collectedDataEpoch
			})
			require(ordered.map(PressureFactRevisionEntity::sourceEventId).distinct().size ==
				ordered.size)
			require(ordered.map(PressureFactRevisionEntity::sourceAdmissionOrdinal).distinct().size ==
				ordered.size)
			val trigger = ordered.firstOrNull { revision ->
				earliestPossibleStart(revision) < retainedFromMs
			} ?: error("Pressure retention authority requires a floor-crossing revision")
			val current = ordered.last()
			val lineageChecksumValues = buildList<Any?> {
				add(LINEAGE_CONTRACT)
				ordered.forEach { revision ->
					add(revision.semanticRevision)
					add(revision.sourceEventId)
					add(revision.sourceAdmissionOrdinal)
					add(revision.mutationId)
					add(revision.sourcePolicyRevision)
					add(revision.captureConsentEpoch)
					add(revision.intervalStartTimeMs)
					add(revision.intervalEndTimeMs)
					add(revision.wallTimeUncertaintyMs)
					add(revision.effectChecksum)
				}
			}
			val unsigned = PressureFactRetentionAuthorityEntity(
				writerProjectionId = first.writerProjectionId,
				writerProjectionVersion = first.writerProjectionVersion,
				logicalFactId = first.logicalFactId,
				currentSemanticRevision = current.semanticRevision,
				currentSourceEventId = current.sourceEventId,
				currentSourceAdmissionOrdinal = current.sourceAdmissionOrdinal,
				currentSourcePolicyRevision = current.sourcePolicyRevision,
				currentCaptureConsentEpoch = current.captureConsentEpoch,
				triggerSemanticRevision = trigger.semanticRevision,
				triggerSourceEventId = trigger.sourceEventId,
				triggerSourceAdmissionOrdinal = trigger.sourceAdmissionOrdinal,
				triggerSourcePolicyRevision = trigger.sourcePolicyRevision,
				triggerCaptureConsentEpoch = trigger.captureConsentEpoch,
				logicalTrackingId = first.logicalTrackingId,
				serviceRunId = first.serviceRunId,
				collectedDataEpoch = first.collectedDataEpoch,
				retainedFromMs = retainedFromMs,
				lineageIntervalStartMs =
				ordered.minOf(PressureFactRevisionEntity::intervalStartTimeMs),
				lineageIntervalEndMs =
				ordered.maxOf(PressureFactRevisionEntity::intervalEndTimeMs),
				lineageEarliestPossibleStartMs = ordered.minOf(::earliestPossibleStart),
				lineageRevisionCount = ordered.size,
				lineageEffectChecksum = checksum(*lineageChecksumValues.toTypedArray()),
				revisionAuthoritySetChecksum =
					PressureFactRetentionRevisionAuthorityEntity.authoritySetChecksumForFacts(
						ordered,
					),
				retainedAtMs = retainedAtMs,
				reason = REASON_RETENTION_EXPIRED,
				effectChecksum = "",
			)
			return unsigned.copy(effectChecksum = unsigned.calculatedEffectChecksum())
		}

		private fun earliestPossibleStart(revision: PressureFactRevisionEntity): Long =
			if (revision.wallTimeUncertaintyMs > revision.intervalStartTimeMs) {
				0L
			} else {
				revision.intervalStartTimeMs - revision.wallTimeUncertaintyMs
			}

		private fun checksum(vararg values: Any?): String {
			val canonical = values.joinToString(separator = "") { value ->
				val text = value?.toString()
				if (text == null) "-1:" else "${text.length}:$text"
			}
			return MessageDigest.getInstance("SHA-256")
				.digest(canonical.toByteArray(Charsets.UTF_8))
				.joinToString(separator = "") { byte -> "%02x".format(byte) }
		}
	}
}
