package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainCompletenessState
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainFormatV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainOperation
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainOwnerKind
import java.security.MessageDigest

/**
 * Imported-only container for an authenticated portable graph.
 *
 * It is intentionally disconnected from native `steps_count_domain_*` tables.
 */
@Entity(tableName = "imported_steps_count_domain_graph", primaryKeys = ["graph_identity"])
data class ImportedPortableStepsCountDomainGraphEntity(
	@ColumnInfo(name = "graph_identity") val graphIdentity: String,
	@ColumnInfo(name = "content_checksum") val contentChecksum: String,
	@ColumnInfo(name = "source_format") val sourceFormat: String,
	@ColumnInfo(name = "source_schema_version") val sourceSchemaVersion: Int,
	@ColumnInfo(name = "receipt_count") val receiptCount: Int,
	@ColumnInfo(name = "owner_revision_count") val ownerRevisionCount: Int,
	@ColumnInfo(name = "completeness_marker_count") val completenessMarkerCount: Int,
	@ColumnInfo(name = "root_count") val rootCount: Int,
) {
	init {
		require(ImportedPortableCountDomainIdentity.isOpaque(graphIdentity))
		require(ImportedPortableCountDomainIdentity.isOpaque(contentChecksum))
		require(sourceFormat in SOURCE_FORMATS)
		require(sourceSchemaVersion == 2)
		require(receiptCount in 0..PortableCountDomainFormatV2.MAX_RECEIPTS)
		require(ownerRevisionCount in 1..PortableCountDomainFormatV2.MAX_OWNER_REVISIONS)
		require(completenessMarkerCount in 0..PortableCountDomainFormatV2.MAX_COMPLETENESS_MARKERS)
		require(rootCount in 1..PortableCountDomainFormatV2.MAX_ROOTS)
	}

	companion object {
		const val SOURCE_SESSION_STEPS = "tracker-portable-steps"
		const val SOURCE_AMBIENT_STEPS = "tracker-portable-ambient-steps"
		private val SOURCE_FORMATS = setOf(SOURCE_SESSION_STEPS, SOURCE_AMBIENT_STEPS)
	}
}

@Entity(
	tableName = "imported_steps_count_domain_receipt",
	primaryKeys = ["graph_identity", "receipt_identity"],
	foreignKeys = [ForeignKey(
		entity = ImportedPortableStepsCountDomainGraphEntity::class,
		parentColumns = ["graph_identity"],
		childColumns = ["graph_identity"],
		onDelete = ForeignKey.CASCADE,
	)],
	indices = [
		Index(value = ["graph_identity"], name = "idx_imported_steps_count_receipt_graph"),
		Index(value = ["receipt_identity"], name = "idx_imported_steps_count_receipt_identity"),
	],
)
@Suppress("LongParameterList")
data class ImportedPortableStepsCountDomainReceiptEntity(
	@ColumnInfo(name = "graph_identity") val graphIdentity: String,
	@ColumnInfo(name = "receipt_identity") val receiptIdentity: String,
	@ColumnInfo(name = "domain_identity") val domainIdentity: String,
	@ColumnInfo(name = "owner_kind") val ownerKind: String,
	@ColumnInfo(name = "scope_identity") val scopeIdentity: String,
	@ColumnInfo(name = "owner_identity") val ownerIdentity: String,
	@ColumnInfo(name = "owner_revision") val ownerRevision: Long,
	@ColumnInfo(name = "registration_generation") val registrationGeneration: Long,
	@ColumnInfo(name = "source_collected_data_epoch") val sourceCollectedDataEpoch: Long,
	@ColumnInfo(name = "authority_revision") val authorityRevision: Long,
	@ColumnInfo(name = "authority_fingerprint") val authorityFingerprint: String,
	@ColumnInfo(name = "coverage") val coverage: String,
	@ColumnInfo(name = "coverage_version") val coverageVersion: Int,
	@ColumnInfo(name = "count_domain_version") val countDomainVersion: Int,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
	@ColumnInfo(name = "completeness_evidence_checksum")
	val completenessEvidenceChecksum: String?,
) {
	init {
		listOf(graphIdentity, receiptIdentity, domainIdentity, scopeIdentity, ownerIdentity)
			.forEach { require(ImportedPortableCountDomainIdentity.isOpaque(it)) }
		require(ownerKind in PortableCountDomainOwnerKind.entries.map { it.name })
		require(ownerRevision > 0L)
		require(registrationGeneration > 0L)
		require(sourceCollectedDataEpoch >= 0L)
		require(authorityRevision > 0L)
		require(ImportedPortableCountDomainIdentity.isDigest(authorityFingerprint))
		require(coverage in PortableCountDomainCoverage.entries.map { it.name })
		require(coverageVersion > 0)
		require(countDomainVersion > 0)
		require(ImportedPortableCountDomainIdentity.isDigest(effectChecksum))
		require(
			completenessEvidenceChecksum == null ||
				ImportedPortableCountDomainIdentity.isDigest(completenessEvidenceChecksum),
		)
	}
}

@Entity(
	tableName = "imported_steps_count_domain_owner_revision",
	primaryKeys = ["graph_identity", "owner_kind", "owner_identity", "owner_revision"],
	foreignKeys = [ForeignKey(
		entity = ImportedPortableStepsCountDomainGraphEntity::class,
		parentColumns = ["graph_identity"],
		childColumns = ["graph_identity"],
		onDelete = ForeignKey.CASCADE,
	)],
	indices = [
		Index(
			value = ["graph_identity", "owner_kind", "owner_identity"],
			name = "idx_imported_steps_count_owner_graph",
		),
		Index(
			value = ["owner_kind", "owner_identity", "owner_revision"],
			name = "idx_imported_steps_count_owner_identity",
		),
		Index(
			value = ["receipt_identity"],
			name = "idx_imported_steps_count_owner_receipt",
		),
	],
)
data class ImportedPortableStepsCountDomainOwnerRevisionEntity(
	@ColumnInfo(name = "graph_identity") val graphIdentity: String,
	@ColumnInfo(name = "owner_kind") val ownerKind: String,
	@ColumnInfo(name = "scope_identity") val scopeIdentity: String,
	@ColumnInfo(name = "owner_identity") val ownerIdentity: String,
	@ColumnInfo(name = "owner_revision") val ownerRevision: Long,
	@ColumnInfo(name = "operation") val operation: String,
	@ColumnInfo(name = "receipt_identity") val receiptIdentity: String?,
	@ColumnInfo(name = "owner_effect_checksum") val ownerEffectChecksum: String,
	@ColumnInfo(name = "source_linked_at_ms") val sourceLinkedAtMs: Long,
) {
	init {
		listOf(graphIdentity, scopeIdentity, ownerIdentity)
			.forEach { require(ImportedPortableCountDomainIdentity.isOpaque(it)) }
		require(ownerKind in PortableCountDomainOwnerKind.entries.map { it.name })
		require(ownerRevision > 0L)
		require(operation in PortableCountDomainOperation.entries.map { it.name })
		require(
			(operation == PortableCountDomainOperation.BIND.name) ==
				(receiptIdentity != null),
		)
		require(
			receiptIdentity == null ||
				ImportedPortableCountDomainIdentity.isOpaque(receiptIdentity),
		)
		require(ImportedPortableCountDomainIdentity.isDigest(ownerEffectChecksum))
		require(sourceLinkedAtMs >= 0L)
	}
}

@Entity(
	tableName = "imported_steps_count_domain_completeness",
	primaryKeys = ["graph_identity", "owner_identity", "owner_revision"],
	foreignKeys = [ForeignKey(
		entity = ImportedPortableStepsCountDomainGraphEntity::class,
		parentColumns = ["graph_identity"],
		childColumns = ["graph_identity"],
		onDelete = ForeignKey.CASCADE,
	)],
	indices = [
		Index(
			value = ["graph_identity", "owner_identity", "owner_revision"],
			name = "idx_imported_steps_count_completeness_graph",
		),
	],
)
@Suppress("LongParameterList")
data class ImportedPortableStepsCountDomainCompletenessEntity(
	@ColumnInfo(name = "graph_identity") val graphIdentity: String,
	@ColumnInfo(name = "owner_identity") val ownerIdentity: String,
	@ColumnInfo(name = "owner_revision") val ownerRevision: Long,
	@ColumnInfo(name = "terminal_state") val terminalState: String,
	@ColumnInfo(name = "last_admission_ordinal") val lastAdmissionOrdinal: Long?,
	@ColumnInfo(name = "last_source_sequence") val lastSourceSequence: Long?,
	@ColumnInfo(name = "provider_flush_outcome") val providerFlushOutcome: String,
	@ColumnInfo(name = "registration_removal_outcome") val registrationRemovalOutcome: String,
	@ColumnInfo(name = "registration_timeline_checksum") val registrationTimelineChecksum: String,
	@ColumnInfo(name = "evidence_checksum") val evidenceChecksum: String,
) {
	init {
		require(ImportedPortableCountDomainIdentity.isOpaque(graphIdentity))
		require(ImportedPortableCountDomainIdentity.isOpaque(ownerIdentity))
		require(ownerRevision > 0L)
		require(terminalState in PortableCountDomainCompletenessState.entries.map { it.name })
		require((lastAdmissionOrdinal == null) == (lastSourceSequence == null))
		require(lastAdmissionOrdinal == null || lastAdmissionOrdinal > 0L)
		require(lastSourceSequence == null || lastSourceSequence >= 0L)
		require(providerFlushOutcome.isNotBlank())
		require(registrationRemovalOutcome.isNotBlank())
		require(ImportedPortableCountDomainIdentity.isDigest(registrationTimelineChecksum))
		require(ImportedPortableCountDomainIdentity.isDigest(evidenceChecksum))
	}
}

@Entity(
	tableName = "imported_steps_count_domain_root",
	primaryKeys = [
		"graph_identity",
		"container_identity",
		"product_identity",
		"owner_kind",
		"owner_identity",
	],
	foreignKeys = [ForeignKey(
		entity = ImportedPortableStepsCountDomainGraphEntity::class,
		parentColumns = ["graph_identity"],
		childColumns = ["graph_identity"],
		onDelete = ForeignKey.CASCADE,
	)],
	indices = [
		Index(value = ["graph_identity"], name = "idx_imported_steps_count_root_graph"),
		Index(
			value = ["product_identity", "owner_kind"],
			name = "idx_imported_steps_count_root_product",
		),
		Index(
			value = ["owner_kind", "owner_identity", "owner_revision"],
			name = "idx_imported_steps_count_root_owner",
		),
	],
)
data class ImportedPortableStepsCountDomainRootEntity(
	@ColumnInfo(name = "graph_identity") val graphIdentity: String,
	@ColumnInfo(name = "container_identity") val containerIdentity: String,
	@ColumnInfo(name = "product_identity") val productIdentity: String,
	@ColumnInfo(name = "owner_kind") val ownerKind: String,
	@ColumnInfo(name = "owner_identity") val ownerIdentity: String,
	@ColumnInfo(name = "owner_revision") val ownerRevision: Long,
) {
	init {
		listOf(graphIdentity, containerIdentity, productIdentity, ownerIdentity)
			.forEach { require(ImportedPortableCountDomainIdentity.isOpaque(it)) }
		require(ownerKind in PortableCountDomainOwnerKind.entries.map { it.name })
		require(ownerRevision > 0L)
	}
}

/**
 * Session entries use [productRevision] as their immutable product revision. Ambient days use it
 * as an independent graph-lineage revision; archive membership stores the bound day revision
 * separately so a graph-only correction never fabricates another day revision.
 */
@Entity(
	tableName = "imported_steps_count_domain_binding",
	primaryKeys = ["product_kind", "product_identity", "product_revision"],
	foreignKeys = [ForeignKey(
		entity = ImportedPortableStepsCountDomainGraphEntity::class,
		parentColumns = ["graph_identity"],
		childColumns = ["graph_identity"],
		onDelete = ForeignKey.RESTRICT,
	)],
	indices = [
		Index(value = ["graph_identity"], name = "idx_imported_steps_count_binding_graph"),
	],
)
data class ImportedPortableStepsCountDomainBindingEntity(
	@ColumnInfo(name = "product_kind") val productKind: String,
	@ColumnInfo(name = "product_identity") val productIdentity: String,
	@ColumnInfo(name = "product_revision") val productRevision: Long,
	@ColumnInfo(name = "graph_identity") val graphIdentity: String,
	@ColumnInfo(name = "source_schema_version") val sourceSchemaVersion: Int,
) {
	init {
		require(productKind in PRODUCT_KINDS)
		require(ImportedPortableCountDomainIdentity.isOpaque(productIdentity))
		require(productRevision > 0L)
		require(ImportedPortableCountDomainIdentity.isOpaque(graphIdentity))
		require(sourceSchemaVersion in 1..2)
	}

	companion object {
		const val PRODUCT_SESSION_ENTRY = "SESSION_ENTRY"
		const val PRODUCT_AMBIENT_DAY = "AMBIENT_DAY"
		private val PRODUCT_KINDS = setOf(PRODUCT_SESSION_ENTRY, PRODUCT_AMBIENT_DAY)
	}
}

/** Durable file provenance for a portable session entry; never serialized on re-export. */
@Entity(
	tableName = "imported_steps_file_receipt",
	primaryKeys = ["import_job_id", "entry_key"],
	foreignKeys = [
		ForeignKey(
			entity = ImportedStepsEntryEntity::class,
			parentColumns = ["identity"],
			childColumns = ["entry_identity"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [
		Index(value = ["receipt_identity"], unique = true, name = "idx_imported_steps_file_receipt_id"),
		Index(value = ["entry_identity"], name = "idx_imported_steps_file_receipt_entry"),
	],
)
data class ImportedPortableStepsFileReceiptEntity(
	@ColumnInfo(name = "import_job_id") val importJobId: String,
	@ColumnInfo(name = "entry_key") val entryKey: String,
	@ColumnInfo(name = "receipt_identity") val receiptIdentity: String,
	@ColumnInfo(name = "source_name") val sourceName: String,
	@ColumnInfo(name = "received_at_ms") val receivedAtMs: Long,
	@ColumnInfo(name = "archive_content_checksum") val archiveContentChecksum: String,
	@ColumnInfo(name = "entry_ordinal") val entryOrdinal: Int,
	@ColumnInfo(name = "entry_identity") val entryIdentity: String,
	@ColumnInfo(name = "graph_identity") val graphIdentity: String,
) {
	init {
		listOf(importJobId, entryKey, sourceName).forEach {
			require(it.isNotBlank() && it.length <= 4_096)
		}
		require(receiptIdentity == ImportedPortableCountDomainIdentity.fileReceipt(
			importJobId,
			entryKey,
		))
		require(receivedAtMs >= 0L)
		require(ImportedPortableCountDomainIdentity.isOpaque(archiveContentChecksum))
		require(entryOrdinal >= 0)
		require(ImportedPortableCountDomainIdentity.isOpaque(entryIdentity))
		require(ImportedPortableCountDomainIdentity.isOpaque(graphIdentity))
	}
}

/** Value-free terminal imported owner fence retained after payload/graph deletion. */
@Entity(
	tableName = "imported_steps_count_domain_owner_fence",
	primaryKeys = ["owner_kind", "owner_identity"],
	indices = [
		Index(value = ["product_kind", "product_identity"], name = "idx_imported_steps_count_fence_product"),
		Index(value = ["graph_identity"], name = "idx_imported_steps_count_fence_graph"),
	],
)
@Suppress("LongParameterList")
data class ImportedPortableStepsCountDomainOwnerFenceEntity(
	@ColumnInfo(name = "owner_kind") val ownerKind: String,
	@ColumnInfo(name = "owner_identity") val ownerIdentity: String,
	@ColumnInfo(name = "scope_identity") val scopeIdentity: String,
	@ColumnInfo(name = "latest_source_revision") val latestSourceRevision: Long,
	@ColumnInfo(name = "latest_owner_effect_checksum") val latestOwnerEffectChecksum: String,
	@ColumnInfo(name = "product_kind") val productKind: String,
	@ColumnInfo(name = "product_identity") val productIdentity: String,
	@ColumnInfo(name = "graph_identity") val graphIdentity: String,
	@ColumnInfo(name = "fence_kind") val fenceKind: String,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "fenced_at_ms") val fencedAtMs: Long,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
) {
	init {
		require(ownerKind in PortableCountDomainOwnerKind.entries.map { it.name })
		listOf(ownerIdentity, scopeIdentity, productIdentity, graphIdentity).forEach {
			require(ImportedPortableCountDomainIdentity.isOpaque(it))
		}
		require(latestSourceRevision > 0L)
		require(ImportedPortableCountDomainIdentity.isDigest(latestOwnerEffectChecksum))
		require(productKind in setOf(
			ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
			ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY,
		))
		require(fenceKind in FENCE_KINDS)
		require(collectedDataEpoch >= 0L)
		require(fencedAtMs >= 0L)
		require(effectChecksum == ImportedPortableCountDomainIdentity.ownerFenceChecksum(this))
	}

	companion object {
		const val FENCE_SELECTED_DELETE = "SELECTED_DELETE"
		const val FENCE_RETENTION = "RETENTION"
		const val FENCE_SOURCE_ERASE = "SOURCE_ERASE"
		const val FENCE_FULL_CLEAR = "FULL_CLEAR"
		private val FENCE_KINDS = setOf(
			FENCE_SELECTED_DELETE,
			FENCE_RETENTION,
			FENCE_SOURCE_ERASE,
			FENCE_FULL_CLEAR,
		)

		fun create(
			ownerKind: String,
			ownerIdentity: String,
			scopeIdentity: String,
			latestSourceRevision: Long,
			latestOwnerEffectChecksum: String,
			productKind: String,
			productIdentity: String,
			graphIdentity: String,
			fenceKind: String,
			collectedDataEpoch: Long,
			fencedAtMs: Long,
		): ImportedPortableStepsCountDomainOwnerFenceEntity {
			val checksum = ImportedPortableCountDomainIdentity.ownerFenceChecksum(
				ownerKind,
				ownerIdentity,
				scopeIdentity,
				latestSourceRevision,
				latestOwnerEffectChecksum,
				productKind,
				productIdentity,
				graphIdentity,
				fenceKind,
				collectedDataEpoch,
				fencedAtMs,
			)
			return ImportedPortableStepsCountDomainOwnerFenceEntity(
				ownerKind,
				ownerIdentity,
				scopeIdentity,
				latestSourceRevision,
				latestOwnerEffectChecksum,
				productKind,
				productIdentity,
				graphIdentity,
				fenceKind,
				collectedDataEpoch,
				fencedAtMs,
				checksum,
			)
		}
	}
}

object ImportedPortableCountDomainIdentity {
	fun isOpaque(value: String?): Boolean = value != null && OPAQUE.matches(value)
	fun isDigest(value: String?): Boolean = value != null && DIGEST.matches(value)

	fun fileReceipt(jobId: String, entryKey: String): String =
		"sha256:${digest("tracker-imported-steps-file-receipt-v2", listOf(jobId, entryKey))}"

	fun ownerFenceChecksum(value: ImportedPortableStepsCountDomainOwnerFenceEntity): String =
		ownerFenceChecksum(
			value.ownerKind,
			value.ownerIdentity,
			value.scopeIdentity,
			value.latestSourceRevision,
			value.latestOwnerEffectChecksum,
			value.productKind,
			value.productIdentity,
			value.graphIdentity,
			value.fenceKind,
			value.collectedDataEpoch,
			value.fencedAtMs,
		)

	@Suppress("LongParameterList")
	fun ownerFenceChecksum(
		ownerKind: String,
		ownerIdentity: String,
		scopeIdentity: String,
		latestSourceRevision: Long,
		latestOwnerEffectChecksum: String,
		productKind: String,
		productIdentity: String,
		graphIdentity: String,
		fenceKind: String,
		collectedDataEpoch: Long,
		fencedAtMs: Long,
	): String = digest(
		"tracker-imported-steps-count-domain-owner-fence-v1",
		listOf(
			ownerKind,
			ownerIdentity,
			scopeIdentity,
			latestSourceRevision,
			latestOwnerEffectChecksum,
			productKind,
			productIdentity,
			graphIdentity,
			fenceKind,
			collectedDataEpoch,
			fencedAtMs,
		),
	)

	private fun digest(namespace: String, values: List<Any?>): String {
		val canonical = listOf(namespace, values).toString()
		return MessageDigest.getInstance("SHA-256")
			.digest(canonical.toByteArray(Charsets.UTF_8))
			.joinToString("") { byte ->
				(byte.toInt() and 0xff).toString(16).padStart(2, '0')
			}
	}

	private val OPAQUE = Regex("sha256:[0-9a-f]{64}")
	private val DIGEST = Regex("[0-9a-f]{64}")
}
