package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import java.security.MessageDigest

/**
 * Value-free identity authority for one revision in a retained Pressure correction lineage.
 *
 * The parent remains the run, lineage, and retention-floor authority. This child binds every
 * deleted revision and source-event identity to that exact parent without retaining Pressure data.
 */
@Entity(
	tableName = "pressure_fact_retention_revision_authority",
	primaryKeys = [
		"writer_projection_id",
		"writer_projection_version",
		"logical_fact_id",
		"semantic_revision",
	],
	foreignKeys = [
		ForeignKey(
			entity = PressureFactRetentionAuthorityEntity::class,
			parentColumns = [
				"writer_projection_id",
				"writer_projection_version",
				"logical_fact_id",
			],
			childColumns = [
				"writer_projection_id",
				"writer_projection_version",
				"logical_fact_id",
			],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [
		Index(
			value = [
				"writer_projection_id",
				"writer_projection_version",
				"source_event_id",
			],
			unique = true,
			name = "idx_pressure_retention_revision_event",
		),
		Index(
			value = [
				"writer_projection_id",
				"writer_projection_version",
				"source_admission_ordinal",
			],
			unique = true,
			name = "idx_pressure_retention_revision_admission",
		),
	],
)
@Suppress("LongParameterList")
data class PressureFactRetentionRevisionAuthorityEntity(
	@ColumnInfo(name = "writer_projection_id")
	val writerProjectionId: String,
	@ColumnInfo(name = "writer_projection_version")
	val writerProjectionVersion: Int,
	@ColumnInfo(name = "logical_fact_id")
	val logicalFactId: String,
	@ColumnInfo(name = "semantic_revision")
	val semanticRevision: Long,
	@ColumnInfo(name = "source_event_id")
	val sourceEventId: String,
	@ColumnInfo(name = "source_admission_ordinal")
	val sourceAdmissionOrdinal: Long,
	@ColumnInfo(name = "source_policy_revision")
	val sourcePolicyRevision: Long,
	@ColumnInfo(name = "capture_consent_epoch")
	val captureConsentEpoch: Long,
	@ColumnInfo(name = "revision_effect_checksum")
	val revisionEffectChecksum: String,
	@ColumnInfo(name = "parent_authority_checksum")
	val parentAuthorityChecksum: String,
	@ColumnInfo(name = "effect_checksum")
	val effectChecksum: String,
) {
	/**
	 * Checks the record's stored identity, value constraints and deterministic digest before using
	 * retained metadata.
	 */
	// Checks the complete immutable child/parent tuple and the child digest in one place.
	@Suppress("CyclomaticComplexMethod")
	fun hasValidStoredIntegrity(parent: PressureFactRetentionAuthorityEntity): Boolean =
		writerProjectionId == parent.writerProjectionId &&
			writerProjectionVersion == parent.writerProjectionVersion &&
			logicalFactId == parent.logicalFactId &&
			semanticRevision in 1L..parent.currentSemanticRevision &&
			sourceEventId.isNotBlank() &&
			sourceAdmissionOrdinal > 0L &&
			sourcePolicyRevision > 0L &&
			captureConsentEpoch >= 0L &&
			revisionEffectChecksum.isNotBlank() &&
			parentAuthorityChecksum == parent.effectChecksum &&
			effectChecksum == calculatedEffectChecksum()

	/**
	 * Compares retained Pressure revision metadata with the original saved evidence and parent
	 * witness.
	 */
	@Suppress("LongParameterList")
	fun authenticatesEvidence(
		parent: PressureFactRetentionAuthorityEntity,
		expectedSourceEventId: String,
		expectedSourceAdmissionOrdinal: Long?,
		expectedSemanticRevision: Long?,
		expectedSourcePolicyRevision: Long,
		expectedCaptureConsentEpoch: Long,
	): Boolean = hasValidStoredIntegrity(parent) &&
		sourceEventId == expectedSourceEventId &&
		(expectedSourceAdmissionOrdinal == null ||
			sourceAdmissionOrdinal == expectedSourceAdmissionOrdinal) &&
		(expectedSemanticRevision == null || semanticRevision == expectedSemanticRevision) &&
		sourcePolicyRevision == expectedSourcePolicyRevision &&
		captureConsentEpoch == expectedCaptureConsentEpoch

	/**
	 * Recomputes the digest from this record's persisted values for replay and corruption checks.
	 */
	fun calculatedEffectChecksum(): String = checksum(
		CONTRACT,
		writerProjectionId,
		writerProjectionVersion,
		logicalFactId,
		semanticRevision,
		sourceEventId,
		sourceAdmissionOrdinal,
		sourcePolicyRevision,
		captureConsentEpoch,
		revisionEffectChecksum,
		parentAuthorityChecksum,
	)

	/**
	 * Stable persisted codes, including `CONTRACT`, `SET_CONTRACT`, and any identity-bound record
	 * factories.
	 */
	companion object {
		private const val CONTRACT = "pressure-fact-retention-revision-authority-v1"
		private const val SET_CONTRACT = "pressure-fact-retention-revision-authority-set-v1"

		/**
		 * Builds one retained revision witness after checking its parent digest and exact writer/fact
		 * ownership.
		 */
		fun create(
			parent: PressureFactRetentionAuthorityEntity,
			revision: PressureFactRevisionEntity,
		): PressureFactRetentionRevisionAuthorityEntity {
			require(parent.hasValidStoredIntegrity())
			require(revision.writerProjectionId == parent.writerProjectionId)
			require(revision.writerProjectionVersion == parent.writerProjectionVersion)
			require(revision.logicalFactId == parent.logicalFactId)
			val unsigned = PressureFactRetentionRevisionAuthorityEntity(
				writerProjectionId = revision.writerProjectionId,
				writerProjectionVersion = revision.writerProjectionVersion,
				logicalFactId = revision.logicalFactId,
				semanticRevision = revision.semanticRevision,
				sourceEventId = revision.sourceEventId,
				sourceAdmissionOrdinal = revision.sourceAdmissionOrdinal,
				sourcePolicyRevision = revision.sourcePolicyRevision,
				captureConsentEpoch = revision.captureConsentEpoch,
				revisionEffectChecksum = revision.effectChecksum,
				parentAuthorityChecksum = parent.effectChecksum,
				effectChecksum = "",
			)
			return unsigned.copy(effectChecksum = unsigned.calculatedEffectChecksum())
		}

		/**
		 * Builds sorted Pressure revision witnesses and requires them to reproduce the parent's complete
		 * lineage digest.
		 */
		fun createAll(
			parent: PressureFactRetentionAuthorityEntity,
			revisions: List<PressureFactRevisionEntity>,
		): List<PressureFactRetentionRevisionAuthorityEntity> {
			require(revisions.size == parent.lineageRevisionCount)
			val authorities = revisions
				.sortedBy(PressureFactRevisionEntity::semanticRevision)
				.map { revision -> create(parent, revision) }
			require(parent.authenticatesRevisionAuthorities(authorities))
			return authorities
		}

		internal fun authoritySetChecksumForFacts(
			revisions: List<PressureFactRevisionEntity>,
		): String = checksum(
			SET_CONTRACT,
			*revisions.sortedBy(PressureFactRevisionEntity::semanticRevision)
				.flatMap { revision ->
					listOf(
						revision.semanticRevision,
						revision.sourceEventId,
						revision.sourceAdmissionOrdinal,
						revision.sourcePolicyRevision,
						revision.captureConsentEpoch,
						revision.effectChecksum,
					)
				}.toTypedArray<Any?>(),
		)

		internal fun authoritySetChecksum(
			authorities: List<PressureFactRetentionRevisionAuthorityEntity>,
		): String = checksum(
			SET_CONTRACT,
			*authorities.sortedBy(PressureFactRetentionRevisionAuthorityEntity::semanticRevision)
				.flatMap { authority ->
					listOf(
						authority.semanticRevision,
						authority.sourceEventId,
						authority.sourceAdmissionOrdinal,
						authority.sourcePolicyRevision,
						authority.captureConsentEpoch,
						authority.revisionEffectChecksum,
					)
				}.toTypedArray<Any?>(),
		)

		private fun checksum(vararg values: Any?): String {
			val canonical = values.joinToString(separator = "") { value ->
				val text = value?.toString()
				if (text == null) {
					"-1:"
				} else {
					"${text.length}:$text"
				}
			}
			return MessageDigest.getInstance("SHA-256")
				.digest(canonical.toByteArray(Charsets.UTF_8))
				.joinToString(separator = "") { byte -> "%02x".format(byte) }
		}
	}
}

const val MAX_PRESSURE_RETENTION_REVISION_AUTHORITIES = 8_192
