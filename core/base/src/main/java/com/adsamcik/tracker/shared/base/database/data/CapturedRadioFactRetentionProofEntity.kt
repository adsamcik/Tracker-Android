package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import java.security.MessageDigest

/**
 * Value-free proof that retention removed one authenticated current Wi-Fi or Cell fact lineage.
 *
 * The receipt binds the current fact/cursor and, for coverage-only facts, the exact current
 * aggregate owner. It intentionally retains no observation counts, signal values, or provider
 * payload.
 */
@Entity(
	tableName = "captured_radio_fact_retention_proof",
	primaryKeys = [
		"source_kind", "writer_projection_id", "writer_projection_version", "logical_fact_id",
	],
	indices = [
		Index(
			value = ["source_kind", "source_event_id"],
			name = "idx_captured_radio_retention_event",
		),
		Index(
			value = ["source_kind", "retained_from_ms"],
			name = "idx_captured_radio_retention_floor",
		),
		Index(
			value = ["source_kind", "logical_tracking_id", "service_run_id",
				"collected_data_epoch", "source_admission_ordinal"],
			name = "idx_captured_radio_retention_run_admission",
		),
	],
)
@Suppress("LongParameterList")
data class CapturedRadioFactRetentionProofEntity(
	@ColumnInfo(name = "source_kind") val sourceKind: Int,
	@ColumnInfo(name = "writer_projection_id") val writerProjectionId: String,
	@ColumnInfo(name = "writer_projection_version") val writerProjectionVersion: Int,
	@ColumnInfo(name = "writer_binding_generation") val writerBindingGeneration: Long,
	@ColumnInfo(name = "writer_owner_generation") val writerOwnerGeneration: Long,
	@ColumnInfo(name = "logical_fact_id") val logicalFactId: String,
	@ColumnInfo(name = "semantic_revision") val semanticRevision: Long,
	@ColumnInfo(name = "mutation_id") val mutationId: String,
	@ColumnInfo(name = "fact_effect_checksum") val factEffectChecksum: String,
	@ColumnInfo(name = "source_event_id") val sourceEventId: String,
	@ColumnInfo(name = "source_admission_ordinal") val sourceAdmissionOrdinal: Long,
	@ColumnInfo(name = "wal_integrity_identity") val walIntegrityIdentity: String,
	@ColumnInfo(name = "source_policy_revision") val sourcePolicyRevision: Long,
	@ColumnInfo(name = "capture_consent_epoch") val captureConsentEpoch: Long,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String,
	@ColumnInfo(name = "service_run_id") val serviceRunId: String,
	@ColumnInfo(name = "scope_deletion_generation") val scopeDeletionGeneration: Long,
	@ColumnInfo(name = "logical_run_scope_digest") val logicalRunScopeDigest: String,
	@ColumnInfo(name = "cursor_revision") val cursorRevision: Long,
	@ColumnInfo(name = "cursor_mutation_id") val cursorMutationId: String,
	@ColumnInfo(name = "cursor_effect_checksum") val cursorEffectChecksum: String,
	@ColumnInfo(name = "cursor_source_admission_ordinal") val cursorSourceAdmissionOrdinal: Long,
	@ColumnInfo(name = "cursor_updated_at_ms") val cursorUpdatedAtMs: Long,
	@ColumnInfo(name = "aggregate_owner_logical_fact_id") val aggregateOwnerLogicalFactId: String?,
	@ColumnInfo(name = "aggregate_owner_semantic_revision") val aggregateOwnerSemanticRevision: Long?,
	@ColumnInfo(name = "aggregate_owner_source_event_id") val aggregateOwnerSourceEventId: String?,
	@ColumnInfo(name = "aggregate_owner_source_admission_ordinal")
	val aggregateOwnerSourceAdmissionOrdinal: Long?,
	@ColumnInfo(name = "aggregate_owner_wal_integrity_identity")
	val aggregateOwnerWalIntegrityIdentity: String?,
	@ColumnInfo(name = "aggregate_owner_effect_checksum") val aggregateOwnerEffectChecksum: String?,
	@ColumnInfo(name = "aggregate_owner_cursor_revision") val aggregateOwnerCursorRevision: Long?,
	@ColumnInfo(name = "retained_from_ms") val retainedFromMs: Long,
	@ColumnInfo(name = "retained_at_ms") val retainedAtMs: Long,
	@ColumnInfo(name = "reason") val reason: String,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
) {
	init {
		require(sourceKind in SUPPORTED_SOURCE_KINDS)
		require(writerProjectionId == expectedProjectionId(sourceKind))
		require(writerProjectionVersion == expectedProjectionVersion(sourceKind))
		require(SourceWriterGenerationContract.captureModeMaskForKnownBinding(writerBindingGeneration) != null)
		require(writerOwnerGeneration == SourceWriterGenerationContract.canonicalOwnerGeneration(writerBindingGeneration))
		require(SHA_256_HEX.matches(logicalFactId))
		require(semanticRevision > 0L && SHA_256_HEX.matches(mutationId))
		require(SHA_256_HEX.matches(factEffectChecksum))
		require(sourceEventId.isNotBlank() && sourceAdmissionOrdinal > 0L)
		require(SHA_256_HEX.matches(walIntegrityIdentity))
		require(sourcePolicyRevision > 0L && captureConsentEpoch >= 0L)
		require(collectedDataEpoch >= 0L)
		require(logicalTrackingId.isNotBlank() && serviceRunId.isNotBlank())
		require(scopeDeletionGeneration >= 0L && SHA_256_HEX.matches(logicalRunScopeDigest))
		require(logicalRunScopeDigest == SourceDeletionFenceEntity.logicalServiceRunIdentity(
			sourceKind = sourceKind,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
		))
		require(cursorRevision == semanticRevision)
		require(cursorMutationId == mutationId)
		require(cursorEffectChecksum == factEffectChecksum)
		require(cursorSourceAdmissionOrdinal == sourceAdmissionOrdinal)
		require(cursorUpdatedAtMs >= 0L)
		val ownerValues = listOf(
			aggregateOwnerLogicalFactId,
			aggregateOwnerSemanticRevision,
			aggregateOwnerSourceEventId,
			aggregateOwnerSourceAdmissionOrdinal,
			aggregateOwnerWalIntegrityIdentity,
			aggregateOwnerEffectChecksum,
			aggregateOwnerCursorRevision,
		)
		require(ownerValues.all { it == null } || ownerValues.all { it != null })
		if (aggregateOwnerLogicalFactId != null) {
			require(SHA_256_HEX.matches(aggregateOwnerLogicalFactId))
			require(requireNotNull(aggregateOwnerSemanticRevision) > 0L)
			require(requireNotNull(aggregateOwnerSourceEventId).isNotBlank())
			require(requireNotNull(aggregateOwnerSourceAdmissionOrdinal) > 0L)
			require(SHA_256_HEX.matches(requireNotNull(aggregateOwnerWalIntegrityIdentity)))
			require(SHA_256_HEX.matches(requireNotNull(aggregateOwnerEffectChecksum)))
			require(aggregateOwnerCursorRevision == aggregateOwnerSemanticRevision)
		}
		require(retainedFromMs >= 0L && retainedAtMs >= cursorUpdatedAtMs)
		require(reason in REASONS)
	}

	/**
	 * Checks the record's stored identity, value constraints and deterministic digest before using
	 * retained metadata.
	 */
	fun hasValidStoredIntegrity(): Boolean = runCatching {
		SHA_256_HEX.matches(effectChecksum) && effectChecksum == calculatedEffectChecksum()
	}.getOrDefault(false)

	/**
	 * Checks retained fact ownership against the supplied source-erase scope and original data epoch.
	 */
	// Compare the retained fact/cursor tuple and supplied erase bounds together.
	@Suppress("CyclomaticComplexMethod")
	fun authenticatesSourceErase(
		expectedSourceKind: Int,
		expectedCollectedDataEpoch: Long,
		currentRetainedFromMs: Long?,
		deletedAtMs: Long,
	): Boolean = hasValidStoredIntegrity() &&
		sourceKind == expectedSourceKind &&
		writerProjectionId == expectedProjectionId(expectedSourceKind) &&
		writerProjectionVersion == expectedProjectionVersion(expectedSourceKind) &&
		SourceWriterGenerationContract.captureModeMaskForKnownBinding(writerBindingGeneration) != null &&
		writerOwnerGeneration == SourceWriterGenerationContract.canonicalOwnerGeneration(writerBindingGeneration) &&
		mutationId == expectedMutationId(sourceKind, logicalFactId, semanticRevision) &&
		cursorRevision == semanticRevision &&
		cursorMutationId == mutationId &&
		cursorEffectChecksum == factEffectChecksum &&
		cursorSourceAdmissionOrdinal == sourceAdmissionOrdinal &&
		collectedDataEpoch == expectedCollectedDataEpoch &&
		currentRetainedFromMs?.let { retainedFromMs <= it } == true &&
		deletedAtMs >= retainedAtMs

	/**
	 * Returns the original session/run scope only when this retained record's capture metadata and
	 * digest are valid; otherwise returns null.
	 */
	fun authenticatedCaptureScope(
		expectedSourceKind: Int,
		expectedCollectedDataEpoch: Long,
		currentRetainedFromMs: Long?,
		deletedAtMs: Long,
	): RetainedCaptureScopeIdentity? {
		if (!authenticatesSourceErase(
				expectedSourceKind = expectedSourceKind,
				expectedCollectedDataEpoch = expectedCollectedDataEpoch,
				currentRetainedFromMs = currentRetainedFromMs,
				deletedAtMs = deletedAtMs,
			)
		) {
			return null
		}
		return RetainedCaptureScopeIdentity(
			sourceKind = sourceKind,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			collectedDataEpoch = collectedDataEpoch,
			scopeDeletionGeneration = scopeDeletionGeneration,
			logicalRunScopeDigest = logicalRunScopeDigest,
		).takeIf { it.isCanonicalFor(expectedSourceKind, expectedCollectedDataEpoch) }
	}

	/**
	 * Returns the original admitted event and capture scope only when retained metadata validates;
	 * otherwise returns null.
	 */
	fun authenticatedCaptureEvent(
		expectedSourceKind: Int,
		expectedCollectedDataEpoch: Long,
		currentRetainedFromMs: Long?,
		deletedAtMs: Long,
	): RetainedCaptureEventIdentity? {
		val scope = authenticatedCaptureScope(
			expectedSourceKind,
			expectedCollectedDataEpoch,
			currentRetainedFromMs,
			deletedAtMs,
		) ?: return null
		return RetainedCaptureEventIdentity(
			eventId = sourceEventId,
			sourceAdmissionOrdinal = sourceAdmissionOrdinal,
			sourcePolicyRevision = sourcePolicyRevision,
			captureConsentEpoch = captureConsentEpoch,
			walIntegrityIdentity = walIntegrityIdentity,
			scope = scope,
		)
	}

	/**
	 * Compares the requested fact/scope identity and epoch with this retained radio witness.
	 */
	// Match every requested ownership/cursor field to the same retained receipt.
	@Suppress("CyclomaticComplexMethod", "LongParameterList")
	fun authenticatesRequest(
		expectedSourceKind: Int,
		expectedWriterProjectionId: String,
		expectedWriterProjectionVersion: Int,
		expectedWriterBindingGeneration: Long,
		expectedWriterOwnerGeneration: Long,
		expectedLogicalFactId: String,
		expectedSemanticRevision: Long,
		expectedCursorRevision: Long,
		expectedSourceEventId: String,
		expectedSourceAdmissionOrdinal: Long,
		expectedSourcePolicyRevision: Long,
		expectedCaptureConsentEpoch: Long,
		expectedCollectedDataEpoch: Long,
		expectedScopeDeletionGeneration: Long,
		expectedLogicalRunScopeDigest: String,
		currentRetainedFromMs: Long?,
	): Boolean = hasValidStoredIntegrity() &&
		sourceKind == expectedSourceKind &&
		writerProjectionId == expectedWriterProjectionId &&
		writerProjectionVersion == expectedWriterProjectionVersion &&
		writerBindingGeneration == expectedWriterBindingGeneration &&
		writerOwnerGeneration == expectedWriterOwnerGeneration &&
		logicalFactId == expectedLogicalFactId &&
		semanticRevision == expectedSemanticRevision &&
		mutationId == expectedMutationId(sourceKind, logicalFactId, semanticRevision) &&
		sourceEventId == expectedSourceEventId &&
		sourceAdmissionOrdinal == expectedSourceAdmissionOrdinal &&
		sourcePolicyRevision == expectedSourcePolicyRevision &&
		captureConsentEpoch == expectedCaptureConsentEpoch &&
		collectedDataEpoch == expectedCollectedDataEpoch &&
		scopeDeletionGeneration == expectedScopeDeletionGeneration &&
		logicalRunScopeDigest == expectedLogicalRunScopeDigest &&
		cursorRevision == expectedCursorRevision &&
		cursorMutationId == mutationId &&
		cursorEffectChecksum == factEffectChecksum &&
		cursorSourceAdmissionOrdinal == sourceAdmissionOrdinal &&
		currentRetainedFromMs?.let { retainedFromMs <= it } == true

	/**
	 * Recomputes the digest from this record's persisted values for replay and corruption checks.
	 */
	fun calculatedEffectChecksum(): String = checksum(
		CONTRACT,
		sourceKind,
		writerProjectionId,
		writerProjectionVersion,
		writerBindingGeneration,
		writerOwnerGeneration,
		logicalFactId,
		semanticRevision,
		mutationId,
		factEffectChecksum,
		sourceEventId,
		sourceAdmissionOrdinal,
		walIntegrityIdentity,
		sourcePolicyRevision,
		captureConsentEpoch,
		collectedDataEpoch,
		logicalTrackingId,
		serviceRunId,
		scopeDeletionGeneration,
		logicalRunScopeDigest,
		cursorRevision,
		cursorMutationId,
		cursorEffectChecksum,
		cursorSourceAdmissionOrdinal,
		cursorUpdatedAtMs,
		aggregateOwnerLogicalFactId,
		aggregateOwnerSemanticRevision,
		aggregateOwnerSourceEventId,
		aggregateOwnerSourceAdmissionOrdinal,
		aggregateOwnerWalIntegrityIdentity,
		aggregateOwnerEffectChecksum,
		aggregateOwnerCursorRevision,
		retainedFromMs,
		retainedAtMs,
		reason,
	)

	/**
	 * Stable persisted codes, including `REASON_DIRECT_FLOOR`, `REASON_DEPENDENCY_CLOSURE`,
	 * `CONTRACT`, and any identity-bound record factories.
	 */
	companion object {
		const val REASON_DIRECT_FLOOR = "DIRECT_FLOOR"
		const val REASON_DEPENDENCY_CLOSURE = "DEPENDENCY_CLOSURE"

		private const val CONTRACT = "captured-radio-fact-retention-proof-v2"
		private val SHA_256_HEX = Regex("[0-9a-f]{64}")
		private val SUPPORTED_SOURCE_KINDS = setOf(
			SourceDestinationOwnerEntity.SOURCE_WIFI,
			SourceDestinationOwnerEntity.SOURCE_CELL,
		)
		private val REASONS = setOf(REASON_DIRECT_FLOOR, REASON_DEPENDENCY_CLOSURE)

		/**
		 * Builds a retained Wi-Fi fact witness from its exact revision/cursors and optional aggregate
		 * owner, preserving the retention boundary.
		 */
		fun createWifi(
			fact: WifiCapturedFactRevisionEntity,
			cursor: WifiCapturedFactCursorEntity,
			aggregateOwner: WifiCapturedFactRevisionEntity?,
			aggregateOwnerCursor: WifiCapturedFactCursorEntity?,
			retainedFromMs: Long,
			retainedAtMs: Long,
			reason: String,
		): CapturedRadioFactRetentionProofEntity = create(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_WIFI,
			fact = RadioRetentionFact.from(fact),
			cursor = RadioRetentionCursor.from(cursor),
			aggregateOwner = aggregateOwner?.let(RadioRetentionFact::from),
			aggregateOwnerCursor = aggregateOwnerCursor?.let(RadioRetentionCursor::from),
			retainedFromMs = retainedFromMs,
			retainedAtMs = retainedAtMs,
			reason = reason,
		)

		/**
		 * Builds a retained Cell fact witness from its exact revision/cursors and optional aggregate
		 * owner, preserving the retention boundary.
		 */
		fun createCell(
			fact: CellCapturedFactRevisionEntity,
			cursor: CellCapturedFactCursorEntity,
			aggregateOwner: CellCapturedFactRevisionEntity?,
			aggregateOwnerCursor: CellCapturedFactCursorEntity?,
			retainedFromMs: Long,
			retainedAtMs: Long,
			reason: String,
		): CapturedRadioFactRetentionProofEntity = create(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_CELL,
			fact = RadioRetentionFact.from(fact),
			cursor = RadioRetentionCursor.from(cursor),
			aggregateOwner = aggregateOwner?.let(RadioRetentionFact::from),
			aggregateOwnerCursor = aggregateOwnerCursor?.let(RadioRetentionCursor::from),
			retainedFromMs = retainedFromMs,
			retainedAtMs = retainedAtMs,
			reason = reason,
		)

		private fun create(
			sourceKind: Int,
			fact: RadioRetentionFact,
			cursor: RadioRetentionCursor,
			aggregateOwner: RadioRetentionFact?,
			aggregateOwnerCursor: RadioRetentionCursor?,
			retainedFromMs: Long,
			retainedAtMs: Long,
			reason: String,
		): CapturedRadioFactRetentionProofEntity {
			require((aggregateOwner == null) == (aggregateOwnerCursor == null))
			if (aggregateOwner != null) {
				require(aggregateOwner.logicalFactId == fact.aggregateOwnerLogicalFactId)
				require(aggregateOwner.semanticRevision == fact.aggregateOwnerSemanticRevision)
				require(aggregateOwnerCursor?.authenticates(aggregateOwner) == true)
				require(retainedAtMs >= requireNotNull(aggregateOwnerCursor).updatedAtMs)
				require(fact.aggregateOwnerCursorRevision == null ||
					fact.aggregateOwnerCursorRevision ==
					requireNotNull(aggregateOwnerCursor).cursorRevision)
			}
			require(cursor.authenticates(fact))
			val unsigned = CapturedRadioFactRetentionProofEntity(
				sourceKind = sourceKind,
				writerProjectionId = fact.writerProjectionId,
				writerProjectionVersion = fact.writerProjectionVersion,
				writerBindingGeneration = fact.writerBindingGeneration,
				writerOwnerGeneration = fact.writerOwnerGeneration,
				logicalFactId = fact.logicalFactId,
				semanticRevision = fact.semanticRevision,
				mutationId = fact.mutationId,
				factEffectChecksum = fact.effectChecksum,
				sourceEventId = fact.sourceEventId,
				sourceAdmissionOrdinal = fact.sourceAdmissionOrdinal,
				walIntegrityIdentity = fact.walIntegrityIdentity,
				sourcePolicyRevision = fact.sourcePolicyRevision,
				captureConsentEpoch = fact.captureConsentEpoch,
				collectedDataEpoch = fact.collectedDataEpoch,
				logicalTrackingId = fact.logicalTrackingId,
				serviceRunId = fact.serviceRunId,
				scopeDeletionGeneration = fact.scopeDeletionGeneration,
				logicalRunScopeDigest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
					sourceKind = sourceKind,
					purpose = SourceBrokerPurpose.SESSION_CAPTURE,
					logicalTrackingId = fact.logicalTrackingId,
					serviceRunId = fact.serviceRunId,
				),
				cursorRevision = cursor.cursorRevision,
				cursorMutationId = cursor.latestMutationId,
				cursorEffectChecksum = cursor.latestEffectChecksum,
				cursorSourceAdmissionOrdinal = cursor.latestSourceAdmissionOrdinal,
				cursorUpdatedAtMs = cursor.updatedAtMs,
				aggregateOwnerLogicalFactId = aggregateOwner?.logicalFactId,
				aggregateOwnerSemanticRevision = aggregateOwner?.semanticRevision,
				aggregateOwnerSourceEventId = aggregateOwner?.sourceEventId,
				aggregateOwnerSourceAdmissionOrdinal = aggregateOwner?.sourceAdmissionOrdinal,
				aggregateOwnerWalIntegrityIdentity = aggregateOwner?.walIntegrityIdentity,
				aggregateOwnerEffectChecksum = aggregateOwner?.effectChecksum,
				aggregateOwnerCursorRevision = aggregateOwnerCursor?.cursorRevision,
				retainedFromMs = retainedFromMs,
				retainedAtMs = retainedAtMs,
				reason = reason,
				effectChecksum = "",
			)
			return unsigned.copy(effectChecksum = unsigned.calculatedEffectChecksum())
		}

		private fun expectedProjectionId(sourceKind: Int): String = when (sourceKind) {
			SourceDestinationOwnerEntity.SOURCE_WIFI ->
				SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID
			SourceDestinationOwnerEntity.SOURCE_CELL ->
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID
			else -> error("Unsupported captured radio source $sourceKind")
		}

		private fun expectedProjectionVersion(sourceKind: Int): Int = when (sourceKind) {
			SourceDestinationOwnerEntity.SOURCE_WIFI ->
				SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION
			SourceDestinationOwnerEntity.SOURCE_CELL ->
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION
			else -> error("Unsupported captured radio source $sourceKind")
		}

		private fun expectedMutationId(
			sourceKind: Int,
			logicalFactId: String,
			semanticRevision: Long,
		): String = when (sourceKind) {
			SourceDestinationOwnerEntity.SOURCE_WIFI ->
				WifiCapturedFactRevisionIntegrity.mutationId(logicalFactId, semanticRevision)
			SourceDestinationOwnerEntity.SOURCE_CELL ->
				CellCapturedFactRevisionIntegrity.mutationId(logicalFactId, semanticRevision)
			else -> error("Unsupported captured radio source $sourceKind")
		}

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

private data class RadioRetentionFact(
	val writerProjectionId: String,
	val writerProjectionVersion: Int,
	val writerBindingGeneration: Long,
	val writerOwnerGeneration: Long,
	val logicalFactId: String,
	val semanticRevision: Long,
	val mutationId: String,
	val effectChecksum: String,
	val sourceEventId: String,
	val sourceAdmissionOrdinal: Long,
	val walIntegrityIdentity: String,
	val sourcePolicyRevision: Long,
	val captureConsentEpoch: Long,
	val collectedDataEpoch: Long,
	val logicalTrackingId: String,
	val serviceRunId: String,
	val scopeDeletionGeneration: Long,
	val aggregateOwnerLogicalFactId: String?,
	val aggregateOwnerSemanticRevision: Long?,
	val aggregateOwnerCursorRevision: Long?,
) {
	/**
	 * Factories and canonical digests binding this record to its original identity, revision and
	 * lifecycle fields.
	 */
	companion object {
		fun from(fact: WifiCapturedFactRevisionEntity) = RadioRetentionFact(
			fact.writerProjectionId, fact.writerProjectionVersion, fact.writerBindingGeneration,
			fact.writerOwnerGeneration, fact.logicalFactId, fact.semanticRevision, fact.mutationId,
			fact.effectChecksum, fact.sourceEventId, fact.sourceAdmissionOrdinal,
			fact.walIntegrityIdentity, fact.sourcePolicyRevision, fact.captureConsentEpoch,
			fact.collectedDataEpoch, fact.logicalTrackingId, fact.serviceRunId,
			fact.scopeDeletionGeneration,
			fact.aggregateOwnerLogicalFactId, fact.aggregateOwnerSemanticRevision,
			fact.aggregateOwnerCursorRevision,
		)

		fun from(fact: CellCapturedFactRevisionEntity) = RadioRetentionFact(
			fact.writerProjectionId, fact.writerProjectionVersion, fact.writerBindingGeneration,
			fact.writerOwnerGeneration, fact.logicalFactId, fact.semanticRevision, fact.mutationId,
			fact.effectChecksum, fact.sourceEventId, fact.sourceAdmissionOrdinal,
			fact.walIntegrityIdentity, fact.sourcePolicyRevision, fact.captureConsentEpoch,
			fact.collectedDataEpoch, fact.logicalTrackingId, fact.serviceRunId,
			fact.scopeDeletionGeneration,
			fact.aggregateOwnerLogicalFactId, fact.aggregateOwnerSemanticRevision, null,
		)
	}
}

private data class RadioRetentionCursor(
	val logicalFactId: String,
	val latestSemanticRevision: Long,
	val latestMutationId: String,
	val latestEffectChecksum: String,
	val latestSourceAdmissionOrdinal: Long,
	val cursorRevision: Long,
	val updatedAtMs: Long,
) {
	fun authenticates(fact: RadioRetentionFact): Boolean =
		logicalFactId == fact.logicalFactId &&
			latestSemanticRevision == fact.semanticRevision &&
			latestMutationId == fact.mutationId &&
			latestEffectChecksum == fact.effectChecksum &&
			latestSourceAdmissionOrdinal == fact.sourceAdmissionOrdinal &&
			cursorRevision == fact.semanticRevision

	/**
	 * Factories and canonical digests binding this record to its original identity, revision and
	 * lifecycle fields.
	 */
	companion object {
		fun from(cursor: WifiCapturedFactCursorEntity) = RadioRetentionCursor(
			cursor.logicalFactId, cursor.latestSemanticRevision, cursor.latestMutationId,
			cursor.latestEffectChecksum, cursor.latestSourceAdmissionOrdinal,
			cursor.cursorRevision, cursor.updatedAtMs,
		)

		fun from(cursor: CellCapturedFactCursorEntity) = RadioRetentionCursor(
			cursor.logicalFactId, cursor.latestSemanticRevision, cursor.latestMutationId,
			cursor.latestEffectChecksum, cursor.latestSourceAdmissionOrdinal,
			cursor.cursorRevision, cursor.updatedAtMs,
		)
	}
}
