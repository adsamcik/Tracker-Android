package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * Source-local, disk-backed staging used only by imported portable Steps full clear.
 *
 * Every key begins with the durable collected-data deletion operation identity. The rows carry no
 * authority after the enclosing transaction and are deleted on success or recovery.
 */
internal object ImportedPortableStepsFullClearStagingSchema {
	const val BINDING_TABLE = "imported_steps_full_clear_binding_stage"
	const val BINDING_GRAPH_INDEX = "idx_imported_steps_full_clear_binding_graph"
	const val SESSION_PRODUCT_TABLE = "imported_steps_full_clear_session_product_stage"
	const val OWNER_TABLE = "imported_steps_full_clear_owner_stage"
	const val REVISION_TABLE = "imported_steps_full_clear_owner_revision_stage"
	const val EXPLICIT_TABLE = "imported_steps_full_clear_explicit_lineage_stage"
	const val EXPLICIT_REVISION_INDEX = "idx_imported_steps_full_clear_explicit_revision"

	val TABLES_IN_DELETE_ORDER = listOf(
		EXPLICIT_TABLE,
		REVISION_TABLE,
		OWNER_TABLE,
		SESSION_PRODUCT_TABLE,
		BINDING_TABLE,
	)
}

@Entity(
	tableName = ImportedPortableStepsFullClearStagingSchema.BINDING_TABLE,
	primaryKeys = ["operation_id", "product_kind", "product_identity", "product_revision"],
	indices = [
		Index(
			value = ["operation_id", "graph_identity", "consumed"],
			name = ImportedPortableStepsFullClearStagingSchema.BINDING_GRAPH_INDEX,
		),
	],
)
internal data class ImportedPortableStepsFullClearBindingStageEntity(
	@ColumnInfo(name = "operation_id") val operationId: String,
	@ColumnInfo(name = "product_kind") val productKind: String,
	@ColumnInfo(name = "product_identity") val productIdentity: String,
	@ColumnInfo(name = "product_revision") val productRevision: Long,
	@ColumnInfo(name = "graph_identity") val graphIdentity: String,
	@ColumnInfo(name = "source_schema_version") val sourceSchemaVersion: Int,
	@ColumnInfo(name = "source_receipt_identity") val sourceReceiptIdentity: String?,
	@ColumnInfo(name = "source_archive_identity") val sourceArchiveIdentity: String?,
	@ColumnInfo(name = "source_archive_content_checksum")
	val sourceArchiveContentChecksum: String?,
	@ColumnInfo(name = "consumed", defaultValue = "0") val consumed: Boolean = false,
)

@Entity(
	tableName = ImportedPortableStepsFullClearStagingSchema.SESSION_PRODUCT_TABLE,
	primaryKeys = ["operation_id", "product_identity"],
)
internal data class ImportedPortableStepsFullClearSessionProductStageEntity(
	@ColumnInfo(name = "operation_id") val operationId: String,
	@ColumnInfo(name = "product_identity") val productIdentity: String,
	@ColumnInfo(name = "content_checksum") val contentChecksum: String,
	@ColumnInfo(name = "source_format") val sourceFormat: String,
	@ColumnInfo(name = "source_schema_version") val sourceSchemaVersion: Int,
	@ColumnInfo(name = "graph_identity") val graphIdentity: String,
	@ColumnInfo(name = "source_receipt_identity") val sourceReceiptIdentity: String?,
	@ColumnInfo(name = "source_archive_content_checksum")
	val sourceArchiveContentChecksum: String?,
	@ColumnInfo(name = "has_stored_binding") val hasStoredBinding: Boolean,
	@ColumnInfo(name = "source_receipt_observed", defaultValue = "0")
	val sourceReceiptObserved: Boolean = false,
)

@Entity(
	tableName = ImportedPortableStepsFullClearStagingSchema.OWNER_TABLE,
	primaryKeys = ["operation_id", "owner_kind", "owner_identity"],
)
internal data class ImportedPortableStepsFullClearOwnerStageEntity(
	@ColumnInfo(name = "operation_id") val operationId: String,
	@ColumnInfo(name = "owner_kind") val ownerKind: String,
	@ColumnInfo(name = "owner_identity") val ownerIdentity: String,
	@ColumnInfo(name = "scope_identity") val scopeIdentity: String,
	@ColumnInfo(name = "container_identity") val containerIdentity: String,
	@ColumnInfo(name = "root_product_identity") val rootProductIdentity: String,
	@ColumnInfo(name = "product_kind") val productKind: String,
	@ColumnInfo(name = "bound_product_identity") val boundProductIdentity: String?,
	@ColumnInfo(name = "latest_graph_identity") val latestGraphIdentity: String,
	@ColumnInfo(name = "latest_owner_revision") val latestOwnerRevision: Long,
	@ColumnInfo(name = "latest_graph_revision") val latestGraphRevision: Long,
	@ColumnInfo(name = "latest_is_bound") val latestIsBound: Boolean,
	@ColumnInfo(name = "latest_compare_product_identity")
	val latestCompareProductIdentity: String,
	@ColumnInfo(name = "explicit_lineage_length") val explicitLineageLength: Int,
)

@Entity(
	tableName = ImportedPortableStepsFullClearStagingSchema.REVISION_TABLE,
	primaryKeys = ["operation_id", "owner_kind", "owner_identity", "owner_revision"],
)
internal data class ImportedPortableStepsFullClearOwnerRevisionStageEntity(
	@ColumnInfo(name = "operation_id") val operationId: String,
	@ColumnInfo(name = "owner_kind") val ownerKind: String,
	@ColumnInfo(name = "owner_identity") val ownerIdentity: String,
	@ColumnInfo(name = "owner_revision") val ownerRevision: Long,
	@ColumnInfo(name = "scope_identity") val scopeIdentity: String,
	@ColumnInfo(name = "operation") val operation: String,
	@ColumnInfo(name = "receipt_identity") val receiptIdentity: String?,
	@ColumnInfo(name = "owner_effect_checksum") val ownerEffectChecksum: String,
	@ColumnInfo(name = "source_linked_at_ms") val sourceLinkedAtMs: Long,
	@ColumnInfo(name = "legacy_ambient") val legacyAmbient: Boolean,
)

@Entity(
	tableName = ImportedPortableStepsFullClearStagingSchema.EXPLICIT_TABLE,
	primaryKeys = ["operation_id", "owner_kind", "owner_identity", "lineage_ordinal"],
	indices = [
		Index(
			value = ["operation_id", "owner_kind", "owner_identity", "owner_revision"],
			unique = true,
			name = ImportedPortableStepsFullClearStagingSchema.EXPLICIT_REVISION_INDEX,
		),
	],
)
internal data class ImportedPortableStepsFullClearExplicitLineageStageEntity(
	@ColumnInfo(name = "operation_id") val operationId: String,
	@ColumnInfo(name = "owner_kind") val ownerKind: String,
	@ColumnInfo(name = "owner_identity") val ownerIdentity: String,
	@ColumnInfo(name = "lineage_ordinal") val lineageOrdinal: Int,
	@ColumnInfo(name = "owner_revision") val ownerRevision: Long,
)
