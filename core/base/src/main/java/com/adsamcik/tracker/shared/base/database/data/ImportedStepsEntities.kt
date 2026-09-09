package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import java.time.ZoneId

/**
 * Original portable-v1 entry identity and semantic envelope, not a local lifecycle session.
 *
 * Dormant storage: presence is not admission or product qualification. The authoritative importer
 * must verify the complete hierarchy against [contentChecksum] and local fences in one transaction.
 */
@Entity(tableName = "imported_steps_entry", primaryKeys = ["identity"])
data class ImportedStepsEntryEntity(
	@ColumnInfo(name = "identity") val identity: String,
	@ColumnInfo(name = "content_checksum") val contentChecksum: String,
	@ColumnInfo(name = "session_mode") val sessionMode: String,
	@ColumnInfo(name = "start_time_ms") val startTimeMs: Long,
	@ColumnInfo(name = "end_time_ms") val endTimeMs: Long,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
) {
	init {
		require(ImportedStepsIdentity.isOpaque(identity))
		require(ImportedStepsIdentity.isOpaque(contentChecksum))
		require(sessionMode == "MANUAL" || sessionMode == "AUTOMATIC")
		require(startTimeMs >= 0L && endTimeMs >= startTimeMs)
		require(collectedDataEpoch >= 0L)
	}
}

/**
 * Exact foreign physical membership and minimized settlement. Never creates SourceServiceRun,
 * provider, boot, or locally granted consent authority. All identities/digests are retained verbatim.
 */
@Entity(
	tableName = "imported_steps_run",
	primaryKeys = ["identity"],
	foreignKeys = [ForeignKey(
		entity = ImportedStepsEntryEntity::class,
		parentColumns = ["identity"],
		childColumns = ["entry_identity"],
		onDelete = ForeignKey.CASCADE,
	)],
	indices = [
		Index(value = ["entry_identity"], name = "idx_imported_steps_run_entry"),
		Index(value = ["deletion_scope_digest"], unique = true, name = "idx_imported_steps_run_scope"),
	],
)
data class ImportedStepsRunEntity(
	@ColumnInfo(name = "identity") val identity: String,
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "deletion_scope_digest") val deletionScopeDigest: String,
	@ColumnInfo(name = "start_time_ms") val startTimeMs: Long,
	@ColumnInfo(name = "end_time_ms") val endTimeMs: Long,
	@ColumnInfo(name = "stored_zone_id") val storedZoneId: String,
	@ColumnInfo(name = "capture_coverage") val captureCoverage: String,
	@ColumnInfo(name = "provider_coverage") val providerCoverage: String,
	@ColumnInfo(name = "app_drain_complete") val appDrainComplete: Boolean,
	@ColumnInfo(name = "stop_complete") val stopComplete: Boolean,
	@ColumnInfo(name = "has_unresolved_provider_range") val hasUnresolvedProviderRange: Boolean,
) {
	init {
		require(ImportedStepsIdentity.isOpaque(identity))
		require(ImportedStepsIdentity.isOpaque(entryIdentity))
		require(ImportedStepsIdentity.isDeletionScope(deletionScopeDigest))
		require(startTimeMs >= 0L && endTimeMs >= startTimeMs)
		require(storedZoneId.isNotBlank() && storedZoneId.length <= MAX_IMPORTED_STEPS_ZONE_ID_LENGTH)
		ZoneId.of(storedZoneId)
		require(captureCoverage == "WHOLE_RUN" || captureCoverage == "PARTIAL")
		require(providerCoverage in setOf("COMPLETE", "PARTIAL", "UNOBSERVABLE"))
	}
}

/** Original captured Steps attribution only; imported policy/consent numbers are not local grants. */
@Entity(
	tableName = "imported_steps_manifest",
	primaryKeys = ["run_identity", "revision"],
	foreignKeys = [ForeignKey(
		entity = ImportedStepsRunEntity::class,
		parentColumns = ["identity"],
		childColumns = ["run_identity"],
		onDelete = ForeignKey.CASCADE,
	)],
)
data class ImportedStepsManifestEntity(
	@ColumnInfo(name = "run_identity") val runIdentity: String,
	@ColumnInfo(name = "revision") val revision: Long,
	@ColumnInfo(name = "effective_wall_time_ms") val effectiveWallTimeMs: Long,
	@ColumnInfo(name = "origin_source_policy_revision") val originSourcePolicyRevision: Long,
	@ColumnInfo(name = "capture_consent_epoch") val captureConsentEpoch: Long,
) {
	init {
		require(ImportedStepsIdentity.isOpaque(runIdentity))
		require(revision > 0L && effectiveWallTimeMs >= 0L)
		require(originSourcePolicyRevision > 0L && captureConsentEpoch >= 0L)
	}
}

/** Shape checks only. The wire checksum and original deletion digest are never derived here. */
internal object ImportedStepsIdentity {
	private val opaque = Regex("sha256:[0-9a-f]{64}")
	private val deletionScope = Regex("[0-9a-f]{64}")
	fun isOpaque(value: String?): Boolean = value != null && opaque.matches(value)
	fun isDeletionScope(value: String): Boolean = deletionScope.matches(value)
}

private const val MAX_IMPORTED_STEPS_ZONE_ID_LENGTH = 128
