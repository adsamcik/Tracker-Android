package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import java.security.MessageDigest
import java.time.ZoneId

/** Immutable destination-local revision of one captured Wi-Fi portable entry. */
@Entity(
	tableName = "imported_wifi_entry_revision",
	primaryKeys = ["identity", "import_revision"],
	indices = [Index(
		value = ["import_job_id", "import_entry_key"],
		unique = true,
		name = "idx_imported_wifi_entry_receipt",
	)],
)
@Suppress("LongParameterList")
data class ImportedWifiEntryRevisionEntity(
	@ColumnInfo(name = "identity") val identity: String,
	@ColumnInfo(name = "import_revision") val importRevision: Long,
	@ColumnInfo(name = "supersedes_import_revision") val supersedesImportRevision: Long?,
	@ColumnInfo(name = "content_checksum") val contentChecksum: String,
	@ColumnInfo(name = "source_format") val sourceFormat: String,
	@ColumnInfo(name = "source_schema_version") val sourceSchemaVersion: Int,
	@ColumnInfo(name = "session_mode") val sessionMode: String,
	@ColumnInfo(name = "start_time_ms") val startTimeMs: Long,
	@ColumnInfo(name = "end_time_ms") val endTimeMs: Long,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "import_job_id") val importJobId: String,
	@ColumnInfo(name = "import_entry_key") val importEntryKey: String,
	@ColumnInfo(name = "import_source_name") val importSourceName: String,
	@ColumnInfo(name = "received_at_ms") val receivedAtMs: Long,
) {
	init {
		requireWifiOpaque(identity)
		requireWifiOpaque(contentChecksum)
		require(importRevision > 0L)
		require(supersedesImportRevision == importRevision.takeIf { it > 1L }?.minus(1L))
		require(sourceFormat == SOURCE_FORMAT && sourceSchemaVersion == SOURCE_SCHEMA_VERSION)
		require(sessionMode in SESSION_MODES)
		require(startTimeMs >= 0L && endTimeMs >= startTimeMs)
		require(collectedDataEpoch >= 0L && receivedAtMs >= 0L)
		requireWifiImportProvenance(importJobId, importEntryKey, importSourceName)
	}

	companion object {
		const val SOURCE_FORMAT = "tracker-portable-captured-wifi"
		const val SOURCE_SCHEMA_VERSION = 1
		private val SESSION_MODES = setOf("MANUAL", "AUTOMATIC")
	}
}

/** Immutable source-specific receipt authority for an imported Wi-Fi revision. */
@Entity(
	tableName = "imported_wifi_receipt",
	primaryKeys = ["import_job_id", "import_entry_key"],
	foreignKeys = [ForeignKey(
		entity = ImportedWifiEntryRevisionEntity::class,
		parentColumns = ["identity", "import_revision"],
		childColumns = ["entry_identity", "entry_import_revision"],
		onDelete = ForeignKey.CASCADE,
	)],
	indices = [Index(
		value = ["entry_identity", "entry_import_revision"],
		name = "idx_imported_wifi_receipt_entry",
	)],
)
data class ImportedWifiReceiptEntity(
	@ColumnInfo(name = "import_job_id") val importJobId: String,
	@ColumnInfo(name = "import_entry_key") val importEntryKey: String,
	@ColumnInfo(name = "import_source_name") val importSourceName: String,
	@ColumnInfo(name = "received_at_ms") val receivedAtMs: Long,
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "entry_import_revision") val entryImportRevision: Long,
	@ColumnInfo(name = "entry_content_checksum") val entryContentChecksum: String,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
) {
	init {
		requireWifiImportProvenance(importJobId, importEntryKey, importSourceName)
		require(receivedAtMs >= 0L)
		requireWifiOpaque(entryIdentity)
		require(entryImportRevision > 0L)
		requireWifiOpaque(entryContentChecksum)
		require(collectedDataEpoch >= 0L)
	}
}

/** Exact foreign physical membership; it grants no local service-run or provider authority. */
@Entity(
	tableName = "imported_wifi_run",
	primaryKeys = ["entry_identity", "entry_import_revision", "identity"],
	foreignKeys = [ForeignKey(
		entity = ImportedWifiEntryRevisionEntity::class,
		parentColumns = ["identity", "import_revision"],
		childColumns = ["entry_identity", "entry_import_revision"],
		onDelete = ForeignKey.CASCADE,
	)],
	indices = [
		Index(
			value = ["entry_identity", "entry_import_revision"],
			name = "idx_imported_wifi_run_entry",
		),
		Index(value = ["identity"], name = "idx_imported_wifi_run_identity"),
		Index(value = ["deletion_scope_digest"], name = "idx_imported_wifi_run_scope"),
	],
)
@Suppress("LongParameterList")
data class ImportedWifiRunEntity(
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "entry_import_revision") val entryImportRevision: Long,
	@ColumnInfo(name = "identity") val identity: String,
	@ColumnInfo(name = "deletion_scope_digest") val deletionScopeDigest: String,
	@ColumnInfo(name = "content_checksum") val contentChecksum: String,
	@ColumnInfo(name = "start_time_ms") val startTimeMs: Long,
	@ColumnInfo(name = "end_time_ms") val endTimeMs: Long,
	@ColumnInfo(name = "capture_coverage") val captureCoverage: String,
	@ColumnInfo(name = "availability") val availability: String,
	@ColumnInfo(name = "acquisition_completeness") val acquisitionCompleteness: String,
	@ColumnInfo(name = "has_unresolved_provider_range") val hasUnresolvedProviderRange: Boolean,
	@ColumnInfo(name = "retention_loss") val retentionLoss: Boolean,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "scope_deletion_generation") val scopeDeletionGeneration: Long,
) {
	init {
		requireWifiOpaque(entryIdentity)
		requireWifiOpaque(identity)
		requireWifiOpaque(deletionScopeDigest)
		requireWifiOpaque(contentChecksum)
		require(entryImportRevision > 0L)
		require(startTimeMs >= 0L && endTimeMs >= startTimeMs)
		require(captureCoverage in CAPTURE_COVERAGES)
		require(availability in AVAILABILITIES)
		require(acquisitionCompleteness in ACQUISITION_COMPLETENESS)
		require(collectedDataEpoch >= 0L && scopeDeletionGeneration >= 0L)
		if (availability == "NOT_CAPTURED") {
			require(captureCoverage == "NOT_CAPTURED")
			require(acquisitionCompleteness == "UNKNOWN")
			require(!hasUnresolvedProviderRange && !retentionLoss)
		} else {
			require(captureCoverage != "NOT_CAPTURED")
		}
		require(!hasUnresolvedProviderRange || acquisitionCompleteness != "COMPLETE")
	}

	private companion object {
		val CAPTURE_COVERAGES = setOf("WHOLE_RUN", "PARTIAL_RUN", "NOT_CAPTURED")
		val AVAILABILITIES = setOf("RETAINED", "NO_RETAINED_OBSERVATION", "NOT_CAPTURED")
		val ACQUISITION_COMPLETENESS = setOf("COMPLETE", "PARTIAL", "UNKNOWN")
	}
}

/** Ordered civil-time evidence attached to one exact imported run. */
@Entity(
	tableName = "imported_wifi_run_zone",
	primaryKeys = ["entry_identity", "entry_import_revision", "run_identity", "ordinal"],
	foreignKeys = [ForeignKey(
		entity = ImportedWifiRunEntity::class,
		parentColumns = ["entry_identity", "entry_import_revision", "identity"],
		childColumns = ["entry_identity", "entry_import_revision", "run_identity"],
		onDelete = ForeignKey.CASCADE,
	)],
	indices = [Index(
		value = ["entry_identity", "entry_import_revision", "run_identity"],
		name = "idx_imported_wifi_run_zone_run",
	)],
)
data class ImportedWifiRunZoneEntity(
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "entry_import_revision") val entryImportRevision: Long,
	@ColumnInfo(name = "run_identity") val runIdentity: String,
	@ColumnInfo(name = "ordinal") val ordinal: Int,
	@ColumnInfo(name = "zone_id") val zoneId: String,
) {
	init {
		requireWifiOpaque(entryIdentity)
		requireWifiOpaque(runIdentity)
		require(entryImportRevision > 0L && ordinal >= 0)
		require(zoneId.isNotBlank() && zoneId.length <= MAX_ZONE_LENGTH)
		ZoneId.of(zoneId)
	}

	private companion object { const val MAX_ZONE_LENGTH = 128 }
}

/** One identity-free Wi-Fi aggregate/coverage observation from the portable hierarchy. */
@Entity(
	tableName = "imported_wifi_observation",
	primaryKeys = ["entry_identity", "entry_import_revision", "run_identity", "identity"],
	foreignKeys = [
		ForeignKey(
			entity = ImportedWifiRunEntity::class,
			parentColumns = ["entry_identity", "entry_import_revision", "identity"],
			childColumns = ["entry_identity", "entry_import_revision", "run_identity"],
			onDelete = ForeignKey.CASCADE,
		),
		ForeignKey(
			entity = ImportedWifiObservationEntity::class,
			parentColumns = [
				"entry_identity", "entry_import_revision", "run_identity", "identity", "semantic_revision",
			],
			childColumns = [
				"entry_identity", "entry_import_revision", "run_identity",
				"aggregate_owner_identity", "aggregate_owner_semantic_revision",
			],
			onDelete = ForeignKey.NO_ACTION,
			deferred = true,
		),
	],
	indices = [
		Index(
			value = ["entry_identity", "entry_import_revision", "run_identity"],
			name = "idx_imported_wifi_observation_run",
		),
		Index(value = ["identity"], name = "idx_imported_wifi_observation_identity"),
		Index(
			value = [
				"entry_identity", "entry_import_revision", "run_identity", "identity", "semantic_revision",
			],
			unique = true,
			name = "idx_imported_wifi_observation_semantic_owner",
		),
		Index(
			value = [
				"entry_identity", "entry_import_revision", "run_identity",
				"aggregate_owner_identity", "aggregate_owner_semantic_revision",
			],
			name = "idx_imported_wifi_observation_aggregate_owner",
		),
	],
)
@Suppress("LongParameterList")
data class ImportedWifiObservationEntity(
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "entry_import_revision") val entryImportRevision: Long,
	@ColumnInfo(name = "run_identity") val runIdentity: String,
	@ColumnInfo(name = "identity") val identity: String,
	@ColumnInfo(name = "semantic_revision") val semanticRevision: Long,
	@ColumnInfo(name = "supersedes_semantic_revision") val supersedesSemanticRevision: Long?,
	@ColumnInfo(name = "aggregate_owner_identity") val aggregateOwnerIdentity: String?,
	@ColumnInfo(name = "aggregate_owner_semantic_revision") val aggregateOwnerSemanticRevision: Long?,
	@ColumnInfo(name = "content_checksum") val contentChecksum: String,
	@ColumnInfo(name = "coverage_start_time_ms") val coverageStartTimeMs: Long,
	@ColumnInfo(name = "observed_time_ms") val observedTimeMs: Long,
	@ColumnInfo(name = "latest_possible_time_ms") val latestPossibleTimeMs: Long,
	@ColumnInfo(name = "wall_time_uncertainty_ms") val wallTimeUncertaintyMs: Long,
	@ColumnInfo(name = "stored_zone_id") val storedZoneId: String,
	@ColumnInfo(name = "availability") val availability: String,
	@ColumnInfo(name = "result_completeness") val resultCompleteness: String,
	@ColumnInfo(name = "submitted_result_count") val submittedResultCount: Int,
	@ColumnInfo(name = "accepted_result_count") val acceptedResultCount: Int,
	@ColumnInfo(name = "stale_result_count") val staleResultCount: Int,
	@ColumnInfo(name = "clock_unverifiable_result_count") val clockUnverifiableResultCount: Int,
	@ColumnInfo(name = "malformed_result_count") val malformedResultCount: Int,
	@ColumnInfo(name = "observation_count") val observationCount: Int,
	@ColumnInfo(name = "two_point_four_ghz_count") val twoPointFourGhzCount: Int,
	@ColumnInfo(name = "five_ghz_count") val fiveGhzCount: Int,
	@ColumnInfo(name = "six_ghz_count") val sixGhzCount: Int,
	@ColumnInfo(name = "other_band_count") val otherBandCount: Int,
	@ColumnInfo(name = "strongest_signal_dbm") val strongestSignalDbm: Int,
	@ColumnInfo(name = "weakest_signal_dbm") val weakestSignalDbm: Int,
	@ColumnInfo(name = "mean_signal_dbm") val meanSignalDbm: Double,
	@ColumnInfo(name = "source_quality_flags") val sourceQualityFlags: Long,
	@ColumnInfo(name = "source_quality_confidence") val sourceQualityConfidence: Float?,
) {
	init {
		requireWifiOpaque(entryIdentity)
		requireWifiOpaque(runIdentity)
		requireWifiOpaque(identity)
		requireWifiOpaque(contentChecksum)
		require(entryImportRevision > 0L && semanticRevision > 0L)
		require(supersedesSemanticRevision == semanticRevision.takeIf { it > 1L }?.minus(1L))
		require((aggregateOwnerIdentity == null) == (aggregateOwnerSemanticRevision == null))
		aggregateOwnerIdentity?.let {
			requireWifiOpaque(it)
			require(it != identity && requireNotNull(aggregateOwnerSemanticRevision) > 0L)
		}
		require(wallTimeUncertaintyMs in 0L..observedTimeMs)
		require(coverageStartTimeMs in 0L..(observedTimeMs - wallTimeUncertaintyMs))
		require(latestPossibleTimeMs == Math.addExact(observedTimeMs, wallTimeUncertaintyMs))
		require(storedZoneId.isNotBlank() && storedZoneId.length <= MAX_ZONE_LENGTH)
		ZoneId.of(storedZoneId)
		require(availability == "AVAILABLE")
		require(resultCompleteness in RESULT_COMPLETENESS)
		require(listOf(staleResultCount, clockUnverifiableResultCount, malformedResultCount).all { it >= 0 })
		val rejected = Math.addExact(
			Math.addExact(staleResultCount, clockUnverifiableResultCount),
			malformedResultCount,
		)
		require(submittedResultCount > 0 && acceptedResultCount > 0)
		require(Math.addExact(acceptedResultCount, rejected) == submittedResultCount)
		require((resultCompleteness == "COMPLETE") == (rejected == 0))
		require(observationCount == acceptedResultCount)
		val bandTotal = Math.addExact(
			Math.addExact(twoPointFourGhzCount, fiveGhzCount),
			Math.addExact(sixGhzCount, otherBandCount),
		)
		require(listOf(twoPointFourGhzCount, fiveGhzCount, sixGhzCount, otherBandCount).all { it >= 0 })
		require(bandTotal == observationCount)
		require(strongestSignalDbm >= weakestSignalDbm)
		require(meanSignalDbm.isFinite() &&
			meanSignalDbm in weakestSignalDbm.toDouble()..strongestSignalDbm.toDouble())
		require(sourceQualityFlags >= 0L)
		require(sourceQualityConfidence?.let { it in 0f..1f } != false)
	}

	private companion object {
		const val MAX_ZONE_LENGTH = 128
		val RESULT_COMPLETENESS = setOf("COMPLETE", "PARTIAL")
	}
}

/** Logical-entry privacy authority retained after later selected imported deletion. */
@Entity(tableName = "imported_wifi_entry_deletion", primaryKeys = ["entry_identity"])
data class ImportedWifiEntryDeletionEntity(
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "deleted_import_revision") val deletedImportRevision: Long,
	@ColumnInfo(name = "deleted_at_ms") val deletedAtMs: Long,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
) {
	init {
		requireWifiOpaque(entryIdentity)
		require(collectedDataEpoch >= 0L && deletedImportRevision > 0L && deletedAtMs >= 0L)
		require(effectChecksum == checksum(
			entryIdentity, collectedDataEpoch, deletedImportRevision, deletedAtMs,
		))
	}

	companion object {
		fun create(
			entryIdentity: String,
			collectedDataEpoch: Long,
			deletedImportRevision: Long,
			deletedAtMs: Long,
		): ImportedWifiEntryDeletionEntity = ImportedWifiEntryDeletionEntity(
			entryIdentity,
			collectedDataEpoch,
			deletedImportRevision,
			deletedAtMs,
			checksum(entryIdentity, collectedDataEpoch, deletedImportRevision, deletedAtMs),
		)

		private fun checksum(
			entryIdentity: String,
			collectedDataEpoch: Long,
			deletedImportRevision: Long,
			deletedAtMs: Long,
		): String = wifiAuthorityDigest(
			"tracker-imported-wifi-entry-deletion-v1",
			entryIdentity,
			collectedDataEpoch,
			deletedImportRevision,
			deletedAtMs,
		)
	}
}

/** Payload-free run/scope generation; full collected-data clear is its only erase path. */
@Entity(
	tableName = "imported_wifi_deletion_generation",
	primaryKeys = ["run_identity"],
	indices = [
		Index(value = ["entry_identity"], name = "idx_imported_wifi_deletion_entry"),
		Index(value = ["deletion_scope_digest"], unique = true, name = "idx_imported_wifi_deletion_scope"),
	],
)
data class ImportedWifiDeletionGenerationEntity(
	@ColumnInfo(name = "run_identity") val runIdentity: String,
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "deletion_scope_digest") val deletionScopeDigest: String,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "generation") val generation: Long,
	@ColumnInfo(name = "deleted_at_ms") val deletedAtMs: Long,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
) {
	init {
		requireWifiOpaque(runIdentity)
		requireWifiOpaque(entryIdentity)
		requireWifiOpaque(deletionScopeDigest)
		require(collectedDataEpoch >= 0L && generation > 0L && deletedAtMs >= 0L)
		require(effectChecksum == checksum(
			runIdentity, entryIdentity, deletionScopeDigest, collectedDataEpoch, generation, deletedAtMs,
		))
	}

	companion object {
		fun create(
			runIdentity: String,
			entryIdentity: String,
			deletionScopeDigest: String,
			collectedDataEpoch: Long,
			generation: Long,
			deletedAtMs: Long,
		): ImportedWifiDeletionGenerationEntity = ImportedWifiDeletionGenerationEntity(
			runIdentity,
			entryIdentity,
			deletionScopeDigest,
			collectedDataEpoch,
			generation,
			deletedAtMs,
			checksum(
				runIdentity,
				entryIdentity,
				deletionScopeDigest,
				collectedDataEpoch,
				generation,
				deletedAtMs,
			),
		)

		private fun checksum(
			runIdentity: String,
			entryIdentity: String,
			deletionScopeDigest: String,
			collectedDataEpoch: Long,
			generation: Long,
			deletedAtMs: Long,
		): String = wifiAuthorityDigest(
			"tracker-imported-wifi-deletion-generation-v1",
			runIdentity,
			entryIdentity,
			deletionScopeDigest,
			collectedDataEpoch,
			generation,
			deletedAtMs,
		)
	}
}

/** Value-free selected local/imported deletion receipt retained after payload cascade. */
@Entity(
	tableName = "wifi_selected_deletion_receipt",
	primaryKeys = ["selection_identity", "origin"],
)
@Suppress("LongParameterList")
data class WifiSelectedDeletionReceiptEntity(
	@ColumnInfo(name = "selection_identity") val selectionIdentity: String,
	@ColumnInfo(name = "origin") val origin: String,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "selected_import_revision") val selectedImportRevision: Long?,
	@ColumnInfo(name = "selected_content_checksum") val selectedContentChecksum: String?,
	@ColumnInfo(name = "start_time_ms") val startTimeMs: Long,
	@ColumnInfo(name = "end_time_ms") val endTimeMs: Long,
	@ColumnInfo(name = "expected_run_count") val expectedRunCount: Int,
	@ColumnInfo(name = "expected_observation_count") val expectedObservationCount: Int,
	@ColumnInfo(name = "expected_protected_identity_count") val expectedProtectedIdentityCount: Int,
	@ColumnInfo(name = "protected_identity_set_checksum") val protectedIdentitySetChecksum: String,
	@ColumnInfo(name = "run_deletion_set_checksum") val runDeletionSetChecksum: String,
	@ColumnInfo(name = "source_fence_set_checksum") val sourceFenceSetChecksum: String,
	@ColumnInfo(name = "retained_from_ms") val retainedFromMs: Long?,
	@ColumnInfo(name = "deleted_at_ms") val deletedAtMs: Long,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
) {
	init {
		requireWifiOpaque(selectionIdentity)
		require(origin in ORIGINS)
		require(collectedDataEpoch >= 0L)
		require((origin == ORIGIN_IMPORTED) == (selectedImportRevision != null))
		require((origin == ORIGIN_IMPORTED) == (selectedContentChecksum != null))
		require(selectedImportRevision?.let { it > 0L } != false)
		selectedContentChecksum?.let(::requireWifiOpaque)
		require(startTimeMs >= 0L && endTimeMs >= startTimeMs)
		require(expectedRunCount > 0)
		require(expectedObservationCount >= 0)
		require(expectedProtectedIdentityCount >= expectedRunCount + 1)
		listOf(
			protectedIdentitySetChecksum,
			runDeletionSetChecksum,
			sourceFenceSetChecksum,
		).forEach(::requireWifiOpaque)
		require(retainedFromMs?.let { it >= 0L } != false)
		require(deletedAtMs >= 0L)
		require(effectChecksum == checksum(this))
	}

	companion object {
		const val ORIGIN_LOCAL = "LOCAL"
		const val ORIGIN_IMPORTED = "IMPORTED"
		private val ORIGINS = setOf(ORIGIN_LOCAL, ORIGIN_IMPORTED)

		@Suppress("LongParameterList")
		fun create(
			selectionIdentity: String,
			origin: String,
			collectedDataEpoch: Long,
			selectedImportRevision: Long?,
			selectedContentChecksum: String?,
			startTimeMs: Long,
			endTimeMs: Long,
			protectedIdentities: List<WifiSelectedDeletionProtectedIdentityEntity>,
			runDeletionRows: List<WifiSelectedDeletionRunMarker>,
			sourceFences: List<SourceDeletionFenceEntity>,
			retainedFromMs: Long?,
			deletedAtMs: Long,
		): WifiSelectedDeletionReceiptEntity {
			require(protectedIdentities.isNotEmpty())
			require(protectedIdentities.all {
				it.selectionIdentity == selectionIdentity && it.receiptOrigin == origin
			})
			require(protectedIdentities.distinctBy {
				it.identityKind to it.protectedIdentity
			}.size == protectedIdentities.size)
			val expectedRunCount = protectedIdentities.count {
				it.identityKind == WifiSelectedDeletionProtectedIdentityEntity.KIND_RUN
			}
			val expectedObservationCount = protectedIdentities.count {
				it.identityKind == WifiSelectedDeletionProtectedIdentityEntity.KIND_OBSERVATION
			}
			val protectedChecksum = checksumProtectedIdentities(protectedIdentities)
			val protectedRuns = protectedIdentities.filter {
				it.identityKind == WifiSelectedDeletionProtectedIdentityEntity.KIND_RUN
			}.mapTo(linkedSetOf(), WifiSelectedDeletionProtectedIdentityEntity::protectedIdentity)
			val protectedScopes = protectedIdentities.filter {
				it.identityKind == WifiSelectedDeletionProtectedIdentityEntity.KIND_DELETION_SCOPE
			}.mapTo(linkedSetOf(), WifiSelectedDeletionProtectedIdentityEntity::protectedIdentity)
			require(runDeletionRows.distinctBy(WifiSelectedDeletionRunMarker::runIdentity).size ==
				runDeletionRows.size)
			require(runDeletionRows.mapTo(linkedSetOf(), WifiSelectedDeletionRunMarker::runIdentity) ==
				protectedRuns)
			require(runDeletionRows.mapTo(linkedSetOf(), WifiSelectedDeletionRunMarker::deletionScopeDigest) ==
				protectedScopes)
			require(runDeletionRows.all {
				it.entryIdentity == selectionIdentity && it.collectedDataEpoch == collectedDataEpoch &&
					it.generation > 0L
			})
			require(sourceFences.all {
				it.sourceKind == SourceDestinationOwnerEntity.SOURCE_WIFI &&
					it.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
					it.scopeKind == SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN &&
					it.scopeIdentityDigest in protectedScopes &&
					it.collectedDataEpoch == collectedDataEpoch && it.fenceGeneration > 0L
			})
			require(sourceFences.distinctBy(SourceDeletionFenceEntity::scopeIdentityDigest).size ==
				sourceFences.size)
			val runChecksum = checksumRunDeletions(runDeletionRows)
			val fenceChecksum = checksumSourceFences(sourceFences)
			return WifiSelectedDeletionReceiptEntity(
				selectionIdentity = selectionIdentity,
				origin = origin,
				collectedDataEpoch = collectedDataEpoch,
				selectedImportRevision = selectedImportRevision,
				selectedContentChecksum = selectedContentChecksum,
				startTimeMs = startTimeMs,
				endTimeMs = endTimeMs,
				expectedRunCount = expectedRunCount,
				expectedObservationCount = expectedObservationCount,
				expectedProtectedIdentityCount = protectedIdentities.size,
				protectedIdentitySetChecksum = protectedChecksum,
				runDeletionSetChecksum = runChecksum,
				sourceFenceSetChecksum = fenceChecksum,
				retainedFromMs = retainedFromMs,
				deletedAtMs = deletedAtMs,
				effectChecksum = checksum(
					selectionIdentity,
					origin,
					collectedDataEpoch,
					selectedImportRevision,
					selectedContentChecksum,
					startTimeMs,
					endTimeMs,
					expectedRunCount,
					expectedObservationCount,
					protectedIdentities.size,
					protectedChecksum,
					runChecksum,
					fenceChecksum,
					retainedFromMs,
					deletedAtMs,
				),
			)
		}

		fun checksumProtectedIdentities(
			values: List<WifiSelectedDeletionProtectedIdentityEntity>,
		): String = wifiAuthorityDigest(
			"tracker-wifi-selected-deletion-protected-set-v1",
			*values.sortedWith(
				compareBy<WifiSelectedDeletionProtectedIdentityEntity>(
					WifiSelectedDeletionProtectedIdentityEntity::identityKind,
					WifiSelectedDeletionProtectedIdentityEntity::protectedIdentity,
				),
			).flatMap { value ->
				listOf(
					value.identityKind,
					value.protectedIdentity,
					value.ownerEntryIdentity,
					value.ownerRunIdentity ?: "NONE",
					value.deletionScopeDigest ?: "NONE",
					value.aggregateOwnerIdentity ?: "NONE",
					value.aggregateOwnerSemanticRevision?.toString() ?: "NONE",
					value.revisionCount.toString(),
					value.revisionSetChecksum,
				)
			}.toTypedArray(),
		)

		fun checksumRunDeletions(values: List<WifiSelectedDeletionRunMarker>): String =
			wifiAuthorityDigest(
				"tracker-wifi-selected-deletion-run-set-v1",
				*values.sortedBy(WifiSelectedDeletionRunMarker::runIdentity).flatMap { value ->
					listOf(
						value.runIdentity,
						value.entryIdentity,
						value.deletionScopeDigest,
						value.collectedDataEpoch.toString(),
						value.generation.toString(),
						value.deletedAtMs.toString(),
					)
				}.toTypedArray(),
			)

		fun checksumSourceFences(values: List<SourceDeletionFenceEntity>): String =
			wifiAuthorityDigest(
				"tracker-wifi-selected-deletion-source-fence-set-v1",
				*values.sortedBy(SourceDeletionFenceEntity::scopeIdentityDigest).flatMap { value ->
					listOf(
						value.sourceKind.toString(),
						value.purpose,
						value.scopeKind,
						value.scopeIdentityDigest,
						value.fenceGeneration.toString(),
						value.collectedDataEpoch.toString(),
						value.deletedAtMs.toString(),
						value.effectChecksum,
					)
				}.toTypedArray(),
			)

		private fun checksum(value: WifiSelectedDeletionReceiptEntity): String = checksum(
			value.selectionIdentity,
			value.origin,
			value.collectedDataEpoch,
			value.selectedImportRevision,
			value.selectedContentChecksum,
			value.startTimeMs,
			value.endTimeMs,
			value.expectedRunCount,
			value.expectedObservationCount,
			value.expectedProtectedIdentityCount,
			value.protectedIdentitySetChecksum,
			value.runDeletionSetChecksum,
			value.sourceFenceSetChecksum,
			value.retainedFromMs,
			value.deletedAtMs,
		)

		@Suppress("LongParameterList")
		private fun checksum(
			selectionIdentity: String,
			origin: String,
			collectedDataEpoch: Long,
			selectedImportRevision: Long?,
			selectedContentChecksum: String?,
			startTimeMs: Long,
			endTimeMs: Long,
			expectedRunCount: Int,
			expectedObservationCount: Int,
			expectedProtectedIdentityCount: Int,
			protectedIdentitySetChecksum: String,
			runDeletionSetChecksum: String,
			sourceFenceSetChecksum: String,
			retainedFromMs: Long?,
			deletedAtMs: Long,
		): String = wifiAuthorityDigest(
			"tracker-wifi-selected-deletion-receipt-v1",
			selectionIdentity,
			origin,
			collectedDataEpoch,
			selectedImportRevision ?: "NONE",
			selectedContentChecksum ?: "NONE",
			startTimeMs,
			endTimeMs,
			expectedRunCount,
			expectedObservationCount,
			expectedProtectedIdentityCount,
			protectedIdentitySetChecksum,
			runDeletionSetChecksum,
			sourceFenceSetChecksum,
			retainedFromMs ?: "NONE",
			deletedAtMs,
		)
	}
}

/** Typed child-owner footprint retained after one selected Wi-Fi hierarchy is removed. */
@Entity(
	tableName = "wifi_selected_deletion_protected_identity",
	primaryKeys = ["selection_identity", "receipt_origin", "identity_kind", "protected_identity"],
	foreignKeys = [ForeignKey(
		entity = WifiSelectedDeletionReceiptEntity::class,
		parentColumns = ["selection_identity", "origin"],
		childColumns = ["selection_identity", "receipt_origin"],
		onDelete = ForeignKey.CASCADE,
	)],
	indices = [
		Index(
			value = ["selection_identity", "receipt_origin"],
			name = "idx_wifi_selected_deletion_protected_receipt",
		),
		Index(value = ["protected_identity"], name = "idx_wifi_selected_deletion_protected_identity"),
		Index(value = ["owner_entry_identity"], name = "idx_wifi_selected_deletion_owner_entry"),
		Index(value = ["owner_run_identity"], name = "idx_wifi_selected_deletion_owner_run"),
		Index(value = ["deletion_scope_digest"], name = "idx_wifi_selected_deletion_scope"),
		Index(value = ["aggregate_owner_identity"], name = "idx_wifi_selected_deletion_aggregate_owner"),
	],
)
@Suppress("LongParameterList")
data class WifiSelectedDeletionProtectedIdentityEntity(
	@ColumnInfo(name = "selection_identity") val selectionIdentity: String,
	@ColumnInfo(name = "receipt_origin") val receiptOrigin: String,
	@ColumnInfo(name = "identity_kind") val identityKind: String,
	@ColumnInfo(name = "protected_identity") val protectedIdentity: String,
	@ColumnInfo(name = "owner_entry_identity") val ownerEntryIdentity: String,
	@ColumnInfo(name = "owner_run_identity") val ownerRunIdentity: String?,
	@ColumnInfo(name = "deletion_scope_digest") val deletionScopeDigest: String?,
	@ColumnInfo(name = "aggregate_owner_identity") val aggregateOwnerIdentity: String?,
	@ColumnInfo(name = "aggregate_owner_semantic_revision") val aggregateOwnerSemanticRevision: Long?,
	@ColumnInfo(name = "revision_count") val revisionCount: Int,
	@ColumnInfo(name = "revision_set_checksum") val revisionSetChecksum: String,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
) {
	init {
		listOf(
			selectionIdentity,
			protectedIdentity,
			ownerEntryIdentity,
			revisionSetChecksum,
			effectChecksum,
		).forEach(::requireWifiOpaque)
		require(identityKind in KINDS)
		require(receiptOrigin in setOf(
			WifiSelectedDeletionReceiptEntity.ORIGIN_LOCAL,
			WifiSelectedDeletionReceiptEntity.ORIGIN_IMPORTED,
		))
		ownerRunIdentity?.let(::requireWifiOpaque)
		deletionScopeDigest?.let(::requireWifiOpaque)
		aggregateOwnerIdentity?.let(::requireWifiOpaque)
		require((aggregateOwnerIdentity == null) == (aggregateOwnerSemanticRevision == null))
		require(aggregateOwnerSemanticRevision?.let { it > 0L } != false)
		require(revisionCount > 0 && collectedDataEpoch >= 0L)
		when (identityKind) {
			KIND_ENTRY -> require(
				protectedIdentity == ownerEntryIdentity && ownerRunIdentity == null &&
					deletionScopeDigest == null && aggregateOwnerIdentity == null,
			)
			KIND_RUN -> require(
				protectedIdentity == ownerRunIdentity && deletionScopeDigest != null &&
					aggregateOwnerIdentity == null,
			)
			KIND_OBSERVATION -> require(ownerRunIdentity != null && deletionScopeDigest == null)
			KIND_DELETION_SCOPE -> require(
				protectedIdentity == deletionScopeDigest && ownerRunIdentity != null &&
					aggregateOwnerIdentity == null,
			)
		}
		require(effectChecksum == checksum(this))
	}

	companion object {
		const val KIND_ENTRY = "ENTRY"
		const val KIND_RUN = "RUN"
		const val KIND_OBSERVATION = "OBSERVATION"
		const val KIND_DELETION_SCOPE = "DELETION_SCOPE"
		private val KINDS = setOf(KIND_ENTRY, KIND_RUN, KIND_OBSERVATION, KIND_DELETION_SCOPE)

		@Suppress("LongParameterList")
		fun create(
			selectionIdentity: String,
			receiptOrigin: String,
			identityKind: String,
			protectedIdentity: String,
			ownerEntryIdentity: String,
			ownerRunIdentity: String?,
			deletionScopeDigest: String?,
			aggregateOwnerIdentity: String?,
			aggregateOwnerSemanticRevision: Long?,
			revisionCount: Int,
			revisionSetChecksum: String,
			collectedDataEpoch: Long,
		): WifiSelectedDeletionProtectedIdentityEntity {
			return WifiSelectedDeletionProtectedIdentityEntity(
				selectionIdentity,
				receiptOrigin,
				identityKind,
				protectedIdentity,
				ownerEntryIdentity,
				ownerRunIdentity,
				deletionScopeDigest,
				aggregateOwnerIdentity,
				aggregateOwnerSemanticRevision,
				revisionCount,
				revisionSetChecksum,
				collectedDataEpoch,
				checksum(
					selectionIdentity,
					receiptOrigin,
					identityKind,
					protectedIdentity,
					ownerEntryIdentity,
					ownerRunIdentity,
					deletionScopeDigest,
					aggregateOwnerIdentity,
					aggregateOwnerSemanticRevision,
					revisionCount,
					revisionSetChecksum,
					collectedDataEpoch,
				),
			)
		}

		private fun checksum(value: WifiSelectedDeletionProtectedIdentityEntity): String =
			checksum(
				value.selectionIdentity,
				value.receiptOrigin,
				value.identityKind,
				value.protectedIdentity,
				value.ownerEntryIdentity,
				value.ownerRunIdentity,
				value.deletionScopeDigest,
				value.aggregateOwnerIdentity,
				value.aggregateOwnerSemanticRevision,
				value.revisionCount,
				value.revisionSetChecksum,
				value.collectedDataEpoch,
			)

		@Suppress("LongParameterList")
		private fun checksum(
			selectionIdentity: String,
			receiptOrigin: String,
			identityKind: String,
			protectedIdentity: String,
			ownerEntryIdentity: String,
			ownerRunIdentity: String?,
			deletionScopeDigest: String?,
			aggregateOwnerIdentity: String?,
			aggregateOwnerSemanticRevision: Long?,
			revisionCount: Int,
			revisionSetChecksum: String,
			collectedDataEpoch: Long,
		): String = wifiAuthorityDigest(
			"tracker-wifi-selected-deletion-protected-v1",
			selectionIdentity,
			receiptOrigin,
			identityKind,
			protectedIdentity,
			ownerEntryIdentity,
			ownerRunIdentity ?: "NONE",
			deletionScopeDigest ?: "NONE",
			aggregateOwnerIdentity ?: "NONE",
			aggregateOwnerSemanticRevision ?: "NONE",
			revisionCount,
			revisionSetChecksum,
			collectedDataEpoch,
		)
	}
}

data class WifiSelectedDeletionRunMarker(
	val runIdentity: String,
	val entryIdentity: String,
	val deletionScopeDigest: String,
	val collectedDataEpoch: Long,
	val generation: Long,
	val deletedAtMs: Long,
) {
	init {
		requireWifiOpaque(runIdentity)
		requireWifiOpaque(entryIdentity)
		requireWifiOpaque(deletionScopeDigest)
		require(collectedDataEpoch >= 0L && generation > 0L && deletedAtMs >= 0L)
	}
}

private val WIFI_OPAQUE = Regex("[0-9a-f]{64}")
private const val MAX_IMPORT_PROVENANCE_LENGTH = 4_096

private fun requireWifiOpaque(value: String) = require(WIFI_OPAQUE.matches(value))

private fun requireWifiImportProvenance(jobId: String, entryKey: String, sourceName: String) {
	listOf(jobId, entryKey, sourceName).forEach { value ->
		require(value.isNotBlank() && value.length <= MAX_IMPORT_PROVENANCE_LENGTH)
	}
}

private fun wifiAuthorityDigest(namespace: String, vararg values: Any): String {
	val parts = listOf(namespace) + values.map(Any::toString)
	val canonical = parts.joinToString(separator = "") { value -> "${value.length}:$value" }
	return MessageDigest.getInstance("SHA-256")
		.digest(canonical.toByteArray(Charsets.UTF_8))
		.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
