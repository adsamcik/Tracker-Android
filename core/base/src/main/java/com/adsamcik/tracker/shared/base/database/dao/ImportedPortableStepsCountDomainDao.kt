package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainBindingEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainGraphEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainOwnerFenceEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainRootEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsFileReceiptEntity

/**
 * Reads and persists portable Steps counter-domain graph lineage and its product/deletion
 * bindings.
 */
@Dao
interface ImportedPortableStepsCountDomainDao {
	/**
	 * Inserts [ImportedPortableStepsCountDomainGraphEntity] rows. A conflicting key aborts the
	 * insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertGraph(value: ImportedPortableStepsCountDomainGraphEntity)

	/**
	 * Inserts [ImportedPortableStepsCountDomainReceiptEntity] rows. A conflicting key aborts the
	 * insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertReceipts(values: List<ImportedPortableStepsCountDomainReceiptEntity>)

	/**
	 * Inserts [ImportedPortableStepsCountDomainOwnerRevisionEntity] rows. A conflicting key aborts
	 * the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertOwners(values: List<ImportedPortableStepsCountDomainOwnerRevisionEntity>)

	/**
	 * Inserts [ImportedPortableStepsCountDomainCompletenessEntity] rows. A conflicting key aborts the
	 * insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertMarkers(values: List<ImportedPortableStepsCountDomainCompletenessEntity>)

	/**
	 * Inserts [ImportedPortableStepsCountDomainRootEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertRoots(values: List<ImportedPortableStepsCountDomainRootEntity>)

	/**
	 * Inserts [ImportedPortableStepsCountDomainBindingEntity] rows. A conflicting key aborts the
	 * insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertBinding(value: ImportedPortableStepsCountDomainBindingEntity)

	/**
	 * Inserts [ImportedPortableStepsFileReceiptEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertFileReceipt(value: ImportedPortableStepsFileReceiptEntity)

	/**
	 * Inserts [ImportedPortableStepsFileReceiptEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertFileReceipts(values: List<ImportedPortableStepsFileReceiptEntity>)

	/**
	 * Inserts [ImportedPortableStepsCountDomainOwnerFenceEntity] rows. A conflicting key aborts the
	 * insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertOwnerFences(values: List<ImportedPortableStepsCountDomainOwnerFenceEntity>)

	/**
	 * Inserts [ImportedPortableStepsCountDomainOwnerFenceEntity] rows. A conflicting key aborts the
	 * insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	fun insertOwnerFenceForFullClear(value: ImportedPortableStepsCountDomainOwnerFenceEntity)

	/**
	 * Reads `imported_steps_count_domain_graph` rows. Query conditions include `graph_identity =
	 * :identity`. Returns null when no row matches.
	 */
	@Query("SELECT * FROM imported_steps_count_domain_graph WHERE graph_identity = :identity")
	suspend fun graph(identity: String): ImportedPortableStepsCountDomainGraphEntity?

	/**
	 * Reads `imported_steps_count_domain_binding` rows. Query conditions include `product_kind =
	 * :productKind`, `product_identity = :productIdentity`, `product_revision = :productRevision`.
	 * Returns null when no row matches.
	 */
	@Query(
		"SELECT * FROM imported_steps_count_domain_binding WHERE product_kind = :productKind " +
			"AND product_identity = :productIdentity AND product_revision = :productRevision",
	)
	suspend fun binding(
		productKind: String,
		productIdentity: String,
		productRevision: Long,
	): ImportedPortableStepsCountDomainBindingEntity?

	/**
	 * Reads `imported_steps_count_domain_binding` rows. Query conditions include `product_kind =
	 * :productKind`, `product_identity IN (:productIdentities)`. Orders by `product_identity,
	 * product_revision`.
	 */
	@Query(
		"SELECT * FROM imported_steps_count_domain_binding WHERE product_kind = :productKind " +
			"AND product_identity IN (:productIdentities) ORDER BY product_identity, product_revision",
	)
	suspend fun bindings(
		productKind: String,
		productIdentities: List<String>,
	): List<ImportedPortableStepsCountDomainBindingEntity>

	/**
	 * Reads `imported_steps_count_domain_binding` rows. Query conditions include `product_kind =
	 * :productKind`, `product_identity = :productIdentity`. Orders by `product_revision`. Returns at
	 * most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_steps_count_domain_binding WHERE product_kind = :productKind " +
			"AND product_identity = :productIdentity ORDER BY product_revision LIMIT :limit",
	)
	suspend fun bindingsForProduct(
		productKind: String,
		productIdentity: String,
		limit: Int,
	): List<ImportedPortableStepsCountDomainBindingEntity>

	/**
	 * Counts rows in `imported_steps_count_domain_binding`. Query conditions include `product_kind =
	 * :productKind`, `product_identity = :productIdentity`.
	 */
	@Query(
		"SELECT COUNT(*) FROM imported_steps_count_domain_binding " +
			"WHERE product_kind = :productKind AND product_identity = :productIdentity",
	)
	suspend fun bindingEvidenceCountForProduct(
		productKind: String,
		productIdentity: String,
	): Int

	/**
	 * Reads `imported_steps_count_domain_binding` rows. Query conditions include `graph_identity IN
	 * (:graphIdentities)`. Orders by `product_kind, product_identity, product_revision`. Returns at
	 * most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_steps_count_domain_binding " +
			"WHERE graph_identity IN (:graphIdentities) " +
			"ORDER BY product_kind, product_identity, product_revision LIMIT :limit",
	)
	suspend fun bindingsForGraphs(
		graphIdentities: List<String>,
		limit: Int,
	): List<ImportedPortableStepsCountDomainBindingEntity>

	/**
	 * Reads `imported_steps_count_domain_receipt` rows. Query conditions include `graph_identity =
	 * :graphIdentity`. Orders by `owner_kind, owner_identity, owner_revision`. Returns at most
	 * `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_steps_count_domain_receipt WHERE graph_identity = :graphIdentity " +
			"ORDER BY owner_kind, owner_identity, owner_revision LIMIT :limit",
	)
	suspend fun receipts(
		graphIdentity: String,
		limit: Int,
	): List<ImportedPortableStepsCountDomainReceiptEntity>

	/**
	 * Reads `imported_steps_count_domain_owner_revision` rows. Query conditions include
	 * `graph_identity = :graphIdentity`. Orders by `owner_kind, owner_identity, owner_revision`.
	 * Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_steps_count_domain_owner_revision " +
			"WHERE graph_identity = :graphIdentity " +
			"ORDER BY owner_kind, owner_identity, owner_revision LIMIT :limit",
	)
	suspend fun owners(
		graphIdentity: String,
		limit: Int,
	): List<ImportedPortableStepsCountDomainOwnerRevisionEntity>

	/**
	 * Reads `imported_steps_count_domain_completeness` rows. Query conditions include `graph_identity
	 * = :graphIdentity`. Orders by `owner_identity, owner_revision`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_steps_count_domain_completeness " +
			"WHERE graph_identity = :graphIdentity " +
			"ORDER BY owner_identity, owner_revision LIMIT :limit",
	)
	suspend fun markers(
		graphIdentity: String,
		limit: Int,
	): List<ImportedPortableStepsCountDomainCompletenessEntity>

	/**
	 * Reads `imported_steps_count_domain_root` rows. Query conditions include `graph_identity =
	 * :graphIdentity`. Orders by `container_identity, product_identity, owner_kind, owner_identity`.
	 * Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_steps_count_domain_root WHERE graph_identity = :graphIdentity " +
			"ORDER BY container_identity, product_identity, owner_kind, owner_identity LIMIT :limit",
	)
	suspend fun roots(
		graphIdentity: String,
		limit: Int,
	): List<ImportedPortableStepsCountDomainRootEntity>

	/**
	 * Reads `imported_steps_count_domain_root` rows. Query conditions include `owner_identity IN
	 * (:ownerIdentities)`. Orders by `owner_kind, owner_identity, owner_revision`. Returns at most
	 * `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_steps_count_domain_root " +
			"WHERE owner_identity IN (:ownerIdentities) " +
			"ORDER BY owner_kind, owner_identity, owner_revision LIMIT :limit",
	)
	suspend fun rootsForOwners(
		ownerIdentities: List<String>,
		limit: Int,
	): List<ImportedPortableStepsCountDomainRootEntity>

	/**
	 * Reads `imported_steps_count_domain_owner_fence` rows. Query conditions include `owner_identity
	 * IN (:ownerIdentities)`. Orders by `owner_kind, owner_identity`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_steps_count_domain_owner_fence " +
			"WHERE owner_identity IN (:ownerIdentities) ORDER BY owner_kind, owner_identity LIMIT :limit",
	)
	suspend fun ownerFences(
		ownerIdentities: List<String>,
		limit: Int,
	): List<ImportedPortableStepsCountDomainOwnerFenceEntity>

	/**
	 * Reads `imported_steps_file_receipt` rows. Query conditions include `import_job_id = :jobId`,
	 * `entry_key = :entryKey`. Returns null when no row matches.
	 */
	@Query(
		"SELECT * FROM imported_steps_file_receipt WHERE import_job_id = :jobId " +
			"AND entry_key = :entryKey",
	)
	suspend fun fileReceipt(
		jobId: String,
		entryKey: String,
	): ImportedPortableStepsFileReceiptEntity?

	/**
	 * Reads `imported_steps_file_receipt` rows. Query conditions include `receipt_identity =
	 * :receiptIdentity`. Returns null when no row matches.
	 */
	@Query(
		"SELECT * FROM imported_steps_file_receipt WHERE receipt_identity = :receiptIdentity",
	)
	suspend fun fileReceiptByIdentity(
		receiptIdentity: String,
	): ImportedPortableStepsFileReceiptEntity?

	/**
	 * Reads `imported_steps_file_receipt` rows. Query conditions include `entry_identity =
	 * :entryIdentity`, `import_job_id > :afterJobId`, `import_job_id = :afterJobId`, `entry_key >
	 * :afterEntryKey`. Orders by `import_job_id, entry_key`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_steps_file_receipt WHERE entry_identity = :entryIdentity " +
			"AND (:afterJobId IS NULL OR import_job_id > :afterJobId OR " +
			"(import_job_id = :afterJobId AND entry_key > :afterEntryKey)) " +
			"ORDER BY import_job_id, entry_key LIMIT :limit",
	)
	suspend fun fileReceiptPageForEntry(
		entryIdentity: String,
		afterJobId: String?,
		afterEntryKey: String?,
		limit: Int,
	): List<ImportedPortableStepsFileReceiptEntity>

	/**
	 * Updates `imported_steps_file_receipt` rows matching the supplied scope. Query conditions
	 * include `entry_identity = :entryIdentity`, `graph_identity = :expectedGraphIdentity`. Returns
	 * the affected row count.
	 */
	@Query(
		"UPDATE imported_steps_file_receipt SET graph_identity = :newGraphIdentity " +
			"WHERE entry_identity = :entryIdentity AND graph_identity = :expectedGraphIdentity",
	)
	suspend fun updateFileReceiptGraphIdentity(
		entryIdentity: String,
		expectedGraphIdentity: String,
		newGraphIdentity: String,
	): Int

	/**
	 * Counts rows in `imported_steps_file_receipt`. Query conditions include `entry_identity =
	 * :entryIdentity`.
	 */
	@Query(
		"SELECT COUNT(*) FROM imported_steps_file_receipt WHERE entry_identity = :entryIdentity",
	)
	suspend fun fileReceiptCountForEntry(entryIdentity: String): Int

	/**
	 * Counts rows in `imported_steps_count_domain_graph`. Query conditions include `graph_identity IN
	 * (:graphIdentities)`.
	 */
	@Query(
		"SELECT COUNT(*) FROM imported_steps_count_domain_graph WHERE graph_identity IN (:graphIdentities)",
	)
	suspend fun graphEvidenceCount(graphIdentities: List<String>): Int

	/**
	 * Counts rows in `imported_steps_count_domain_binding`. Query conditions include `graph_identity
	 * IN (:graphIdentities)`.
	 */
	@Query(
		"SELECT COUNT(*) FROM imported_steps_count_domain_binding " +
			"WHERE graph_identity IN (:graphIdentities)",
	)
	suspend fun bindingEvidenceCountForGraphs(graphIdentities: List<String>): Int

	/**
	 * Counts rows in `imported_steps_count_domain_receipt`. Query conditions include `owner_identity
	 * IN (:ownerIdentities)`.
	 */
	@Query(
		"SELECT COUNT(*) FROM imported_steps_count_domain_receipt " +
			"WHERE owner_identity IN (:ownerIdentities)",
	)
	suspend fun receiptEvidenceCountForOwners(ownerIdentities: List<String>): Int

	/**
	 * Counts rows in `imported_steps_count_domain_owner_revision`. Query conditions include
	 * `owner_identity IN (:ownerIdentities)`.
	 */
	@Query(
		"SELECT COUNT(*) FROM imported_steps_count_domain_owner_revision " +
			"WHERE owner_identity IN (:ownerIdentities)",
	)
	suspend fun ownerEvidenceCount(ownerIdentities: List<String>): Int

	/**
	 * Counts rows in `imported_steps_count_domain_completeness`. Query conditions include
	 * `owner_identity IN (:ownerIdentities)`.
	 */
	@Query(
		"SELECT COUNT(*) FROM imported_steps_count_domain_completeness " +
			"WHERE owner_identity IN (:ownerIdentities)",
	)
	suspend fun completenessEvidenceCount(ownerIdentities: List<String>): Int

	/**
	 * Counts rows in `imported_steps_count_domain_root`. Query conditions include `container_identity
	 * IN (:containerIdentities)`.
	 */
	@Query(
		"SELECT COUNT(*) FROM imported_steps_count_domain_root " +
			"WHERE container_identity IN (:containerIdentities)",
	)
	suspend fun rootEvidenceCountForContainers(containerIdentities: List<String>): Int

	/**
	 * Counts rows in `imported_steps_count_domain_root`. Query conditions include `product_identity
	 * IN (:productIdentities)`.
	 */
	@Query(
		"SELECT COUNT(*) FROM imported_steps_count_domain_root " +
			"WHERE product_identity IN (:productIdentities)",
	)
	suspend fun rootEvidenceCountForProducts(productIdentities: List<String>): Int

	/**
	 * Counts rows in `imported_steps_count_domain_root`. Query conditions include `owner_identity IN
	 * (:ownerIdentities)`.
	 */
	@Query(
		"SELECT COUNT(*) FROM imported_steps_count_domain_root " +
			"WHERE owner_identity IN (:ownerIdentities)",
	)
	suspend fun rootEvidenceCountForOwners(ownerIdentities: List<String>): Int

	/**
	 * Counts rows in `imported_steps_count_domain_owner_fence`. Query conditions include
	 * `owner_identity IN (:ownerIdentities)`.
	 */
	@Query(
		"SELECT COUNT(*) FROM imported_steps_count_domain_owner_fence " +
			"WHERE owner_identity IN (:ownerIdentities)",
	)
	suspend fun ownerFenceEvidenceCount(ownerIdentities: List<String>): Int

	/**
	 * Deletes `imported_steps_count_domain_binding` rows matching the supplied scope. Query
	 * conditions include `product_kind = :productKind`, `product_identity = :productIdentity`,
	 * `product_revision = :productRevision`, `graph_identity = :graphIdentity`. Returns the affected
	 * row count.
	 */
	@Query(
		"DELETE FROM imported_steps_count_domain_binding WHERE product_kind = :productKind " +
			"AND product_identity = :productIdentity AND product_revision = :productRevision " +
			"AND graph_identity = :graphIdentity",
	)
	suspend fun deleteBindingExact(
		productKind: String,
		productIdentity: String,
		productRevision: Long,
		graphIdentity: String,
	): Int

	/**
	 * Deletes `imported_steps_count_domain_graph` rows matching the supplied scope. Query conditions
	 * include `graph_identity = :graphIdentity`. Returns the affected row count.
	 */
	@Query(
		"DELETE FROM imported_steps_count_domain_graph WHERE graph_identity = :graphIdentity " +
			"AND NOT EXISTS (SELECT 1 FROM imported_steps_count_domain_binding " +
			"WHERE graph_identity = :graphIdentity)",
	)
	suspend fun deleteGraphIfUnbound(graphIdentity: String): Int

	/**
	 * Reads `imported_steps_count_domain_binding` rows. Query conditions include `product_kind >
	 * :afterProductKind`, `product_kind = :afterProductKind`, `product_identity >
	 * :afterProductIdentity`, `product_identity = :afterProductIdentity`. Orders by `product_kind,
	 * product_identity, product_revision`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_steps_count_domain_binding WHERE " +
			":afterProductKind IS NULL OR product_kind > :afterProductKind OR " +
			"(product_kind = :afterProductKind AND product_identity > :afterProductIdentity) OR " +
			"(product_kind = :afterProductKind AND product_identity = :afterProductIdentity " +
			"AND product_revision > :afterProductRevision) " +
			"ORDER BY product_kind, product_identity, product_revision LIMIT :limit",
	)
	fun bindingPageForFullClear(
		afterProductKind: String?,
		afterProductIdentity: String?,
		afterProductRevision: Long?,
		limit: Int,
	): List<ImportedPortableStepsCountDomainBindingEntity>

	/**
	 * Reads `imported_steps_count_domain_graph` rows. Query conditions include `graph_identity >
	 * :afterGraphIdentity`. Orders by `graph_identity`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_steps_count_domain_graph " +
			"WHERE (:afterGraphIdentity IS NULL OR graph_identity > :afterGraphIdentity) " +
			"ORDER BY graph_identity LIMIT :limit",
	)
	fun graphPageForFullClear(
		afterGraphIdentity: String?,
		limit: Int,
	): List<ImportedPortableStepsCountDomainGraphEntity>

	/**
	 * Reads `imported_steps_count_domain_binding` rows. Query conditions include `product_kind =
	 * :productKind`, `product_identity = :productIdentity`. Orders by `product_revision`. Returns at
	 * most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_steps_count_domain_binding WHERE product_kind = :productKind " +
			"AND product_identity = :productIdentity ORDER BY product_revision LIMIT :limit",
	)
	fun bindingsForFullClear(
		productKind: String,
		productIdentity: String,
		limit: Int,
	): List<ImportedPortableStepsCountDomainBindingEntity>

	/**
	 * Reads `imported_steps_count_domain_graph` rows. Query conditions include `graph_identity =
	 * :identity`. Returns null when no row matches.
	 */
	@Query("SELECT * FROM imported_steps_count_domain_graph WHERE graph_identity = :identity")
	fun graphForFullClear(identity: String): ImportedPortableStepsCountDomainGraphEntity?

	/**
	 * Reads `imported_steps_count_domain_receipt` rows. Query conditions include `graph_identity =
	 * :graphIdentity`. Orders by `owner_kind, owner_identity, owner_revision`. Returns at most
	 * `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_steps_count_domain_receipt WHERE graph_identity = :graphIdentity " +
			"ORDER BY owner_kind, owner_identity, owner_revision LIMIT :limit",
	)
	fun receiptsForFullClear(
		graphIdentity: String,
		limit: Int,
	): List<ImportedPortableStepsCountDomainReceiptEntity>

	/**
	 * Reads `imported_steps_count_domain_owner_revision` rows. Query conditions include
	 * `graph_identity = :graphIdentity`. Orders by `owner_kind, owner_identity, owner_revision`.
	 * Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_steps_count_domain_owner_revision " +
			"WHERE graph_identity = :graphIdentity " +
			"ORDER BY owner_kind, owner_identity, owner_revision LIMIT :limit",
	)
	fun ownersForFullClear(
		graphIdentity: String,
		limit: Int,
	): List<ImportedPortableStepsCountDomainOwnerRevisionEntity>

	/**
	 * Reads `imported_steps_count_domain_completeness` rows. Query conditions include `graph_identity
	 * = :graphIdentity`. Orders by `owner_identity, owner_revision`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_steps_count_domain_completeness " +
			"WHERE graph_identity = :graphIdentity " +
			"ORDER BY owner_identity, owner_revision LIMIT :limit",
	)
	fun markersForFullClear(
		graphIdentity: String,
		limit: Int,
	): List<ImportedPortableStepsCountDomainCompletenessEntity>

	/**
	 * Reads `imported_steps_count_domain_root` rows. Query conditions include `root.graph_identity =
	 * :graphIdentity`. Orders by `root.container_identity, product_identity, owner_kind,
	 * owner_identity`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT root.* FROM imported_steps_count_domain_root AS root " +
			"WHERE root.graph_identity = :graphIdentity " +
			"ORDER BY root.container_identity, product_identity, owner_kind, owner_identity LIMIT :limit",
	)
	fun rootsForFullClear(
		graphIdentity: String,
		limit: Int,
	): List<ImportedPortableStepsCountDomainRootEntity>

	/**
	 * Reads `imported_steps_file_receipt` rows. Query conditions include `receipt_identity =
	 * :receiptIdentity`. Returns null when no row matches.
	 */
	@Query(
		"SELECT * FROM imported_steps_file_receipt WHERE receipt_identity = :receiptIdentity",
	)
	fun fileReceiptByIdentityForFullClear(
		receiptIdentity: String,
	): ImportedPortableStepsFileReceiptEntity?

	/**
	 * Reads `imported_steps_file_receipt` rows. Query conditions include `import_job_id >
	 * :afterJobId`, `import_job_id = :afterJobId`, `entry_key > :afterEntryKey`. Orders by
	 * `import_job_id, entry_key`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_steps_file_receipt " +
			"WHERE (:afterJobId IS NULL OR import_job_id > :afterJobId OR " +
			"(import_job_id = :afterJobId AND entry_key > :afterEntryKey)) " +
			"ORDER BY import_job_id, entry_key LIMIT :limit",
	)
	fun fileReceiptPageForFullClear(
		afterJobId: String?,
		afterEntryKey: String?,
		limit: Int,
	): List<ImportedPortableStepsFileReceiptEntity>

	/**
	 * Counts rows in `imported_steps_file_receipt`. Query conditions include `entry_identity =
	 * :entryIdentity`.
	 */
	@Query(
		"SELECT COUNT(*) FROM imported_steps_file_receipt WHERE entry_identity = :entryIdentity",
	)
	fun fileReceiptCountForEntryForFullClear(entryIdentity: String): Int

	/**
	 * Reads `imported_steps_count_domain_owner_revision` rows. Query conditions include
	 * `root.graph_identity = :graphIdentity`. Orders by `owner.owner_kind, owner.owner_identity`.
	 * Returns at most `limit` rows.
	 */
	@Query(
		"SELECT owner.* FROM imported_steps_count_domain_owner_revision AS owner " +
			"INNER JOIN imported_steps_count_domain_root AS root " +
			"ON root.graph_identity = owner.graph_identity " +
			"AND root.owner_kind = owner.owner_kind " +
			"AND root.owner_identity = owner.owner_identity " +
			"AND root.owner_revision = owner.owner_revision " +
			"WHERE root.graph_identity = :graphIdentity " +
			"ORDER BY owner.owner_kind, owner.owner_identity LIMIT :limit",
	)
	fun latestOwnersForFullClear(
		graphIdentity: String,
		limit: Int,
	): List<ImportedPortableStepsCountDomainOwnerRevisionEntity>

	/**
	 * Reads `imported_steps_count_domain_owner_fence` rows. Query conditions include `owner_identity
	 * IN (:ownerIdentities)`. Orders by `owner_kind, owner_identity`.
	 */
	@Query(
		"SELECT * FROM imported_steps_count_domain_owner_fence " +
			"WHERE owner_identity IN (:ownerIdentities) ORDER BY owner_kind, owner_identity",
	)
	fun ownerFencesForFullClear(
		ownerIdentities: List<String>,
	): List<ImportedPortableStepsCountDomainOwnerFenceEntity>

	/**
	 * Reads `imported_steps_count_domain_owner_fence` rows. Query conditions include `owner_kind =
	 * :ownerKind`, `owner_identity = :ownerIdentity`. Returns null when no row matches.
	 */
	@Query(
		"SELECT * FROM imported_steps_count_domain_owner_fence " +
			"WHERE owner_kind = :ownerKind AND owner_identity = :ownerIdentity",
	)
	fun ownerFenceForFullClear(
		ownerKind: String,
		ownerIdentity: String,
	): ImportedPortableStepsCountDomainOwnerFenceEntity?

	/**
	 * Deletes `imported_steps_count_domain_binding` rows.
	 */
	@Query("DELETE FROM imported_steps_count_domain_binding")
	fun deleteAllBindingsForFullClear()

	/**
	 * Deletes `imported_steps_count_domain_graph` rows.
	 */
	@Query("DELETE FROM imported_steps_count_domain_graph")
	fun deleteAllGraphsForFullClear()

	/**
	 * Deletes `imported_steps_file_receipt` rows.
	 */
	@Query("DELETE FROM imported_steps_file_receipt")
	fun deleteAllFileReceiptsForFullClear()

	/**
	 * Limits for bounded portable Steps admission, lineage and maintenance reads; callers detect
	 * overflow before loading more dependencies.
	 */
	companion object {
		const val MAX_FILE_RECEIPTS_PER_ENTRY = 4_096
		const val FILE_RECEIPT_PAGE_SIZE = 128
	}
}
