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

@Dao
interface ImportedPortableStepsCountDomainDao {
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertGraph(value: ImportedPortableStepsCountDomainGraphEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertReceipts(values: List<ImportedPortableStepsCountDomainReceiptEntity>)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertOwners(values: List<ImportedPortableStepsCountDomainOwnerRevisionEntity>)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertMarkers(values: List<ImportedPortableStepsCountDomainCompletenessEntity>)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertRoots(values: List<ImportedPortableStepsCountDomainRootEntity>)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertBinding(value: ImportedPortableStepsCountDomainBindingEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertFileReceipt(value: ImportedPortableStepsFileReceiptEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertFileReceipts(values: List<ImportedPortableStepsFileReceiptEntity>)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertOwnerFences(values: List<ImportedPortableStepsCountDomainOwnerFenceEntity>)

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	fun upsertOwnerFencesForFullClear(values: List<ImportedPortableStepsCountDomainOwnerFenceEntity>)

	@Query("SELECT * FROM imported_steps_count_domain_graph WHERE graph_identity = :identity")
	suspend fun graph(identity: String): ImportedPortableStepsCountDomainGraphEntity?

	@Query(
		"SELECT * FROM imported_steps_count_domain_binding WHERE product_kind = :productKind " +
			"AND product_identity = :productIdentity AND product_revision = :productRevision",
	)
	suspend fun binding(
		productKind: String,
		productIdentity: String,
		productRevision: Long,
	): ImportedPortableStepsCountDomainBindingEntity?

	@Query(
		"SELECT * FROM imported_steps_count_domain_binding WHERE product_kind = :productKind " +
			"AND product_identity IN (:productIdentities) ORDER BY product_identity, product_revision",
	)
	suspend fun bindings(
		productKind: String,
		productIdentities: List<String>,
	): List<ImportedPortableStepsCountDomainBindingEntity>

	@Query(
		"SELECT * FROM imported_steps_count_domain_binding WHERE product_kind = :productKind " +
			"AND product_identity = :productIdentity ORDER BY product_revision LIMIT :limit",
	)
	suspend fun bindingsForProduct(
		productKind: String,
		productIdentity: String,
		limit: Int,
	): List<ImportedPortableStepsCountDomainBindingEntity>

	@Query(
		"SELECT COUNT(*) FROM imported_steps_count_domain_binding " +
			"WHERE product_kind = :productKind AND product_identity = :productIdentity",
	)
	suspend fun bindingEvidenceCountForProduct(
		productKind: String,
		productIdentity: String,
	): Int

	@Query(
		"SELECT * FROM imported_steps_count_domain_binding " +
			"WHERE graph_identity IN (:graphIdentities) " +
			"ORDER BY product_kind, product_identity, product_revision LIMIT :limit",
	)
	suspend fun bindingsForGraphs(
		graphIdentities: List<String>,
		limit: Int,
	): List<ImportedPortableStepsCountDomainBindingEntity>

	@Query(
		"SELECT * FROM imported_steps_count_domain_receipt WHERE graph_identity = :graphIdentity " +
			"ORDER BY owner_kind, owner_identity, owner_revision LIMIT :limit",
	)
	suspend fun receipts(
		graphIdentity: String,
		limit: Int,
	): List<ImportedPortableStepsCountDomainReceiptEntity>

	@Query(
		"SELECT * FROM imported_steps_count_domain_owner_revision " +
			"WHERE graph_identity = :graphIdentity " +
			"ORDER BY owner_kind, owner_identity, owner_revision LIMIT :limit",
	)
	suspend fun owners(
		graphIdentity: String,
		limit: Int,
	): List<ImportedPortableStepsCountDomainOwnerRevisionEntity>

	@Query(
		"SELECT * FROM imported_steps_count_domain_completeness " +
			"WHERE graph_identity = :graphIdentity " +
			"ORDER BY owner_identity, owner_revision LIMIT :limit",
	)
	suspend fun markers(
		graphIdentity: String,
		limit: Int,
	): List<ImportedPortableStepsCountDomainCompletenessEntity>

	@Query(
		"SELECT * FROM imported_steps_count_domain_root WHERE graph_identity = :graphIdentity " +
			"ORDER BY container_identity, product_identity, owner_kind, owner_identity LIMIT :limit",
	)
	suspend fun roots(
		graphIdentity: String,
		limit: Int,
	): List<ImportedPortableStepsCountDomainRootEntity>

	@Query(
		"SELECT * FROM imported_steps_count_domain_root " +
			"WHERE owner_identity IN (:ownerIdentities) " +
			"ORDER BY owner_kind, owner_identity, owner_revision LIMIT :limit",
	)
	suspend fun rootsForOwners(
		ownerIdentities: List<String>,
		limit: Int,
	): List<ImportedPortableStepsCountDomainRootEntity>

	@Query(
		"SELECT * FROM imported_steps_count_domain_owner_fence " +
			"WHERE owner_identity IN (:ownerIdentities) ORDER BY owner_kind, owner_identity LIMIT :limit",
	)
	suspend fun ownerFences(
		ownerIdentities: List<String>,
		limit: Int,
	): List<ImportedPortableStepsCountDomainOwnerFenceEntity>

	@Query(
		"SELECT * FROM imported_steps_file_receipt WHERE import_job_id = :jobId " +
			"AND entry_key = :entryKey",
	)
	suspend fun fileReceipt(
		jobId: String,
		entryKey: String,
	): ImportedPortableStepsFileReceiptEntity?

	@Query(
		"SELECT * FROM imported_steps_file_receipt WHERE receipt_identity = :receiptIdentity",
	)
	suspend fun fileReceiptByIdentity(
		receiptIdentity: String,
	): ImportedPortableStepsFileReceiptEntity?

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

	@Query(
		"UPDATE imported_steps_file_receipt SET graph_identity = :newGraphIdentity " +
			"WHERE entry_identity = :entryIdentity AND graph_identity = :expectedGraphIdentity",
	)
	suspend fun updateFileReceiptGraphIdentity(
		entryIdentity: String,
		expectedGraphIdentity: String,
		newGraphIdentity: String,
	): Int

	@Query(
		"SELECT COUNT(*) FROM imported_steps_file_receipt WHERE entry_identity = :entryIdentity",
	)
	suspend fun fileReceiptCountForEntry(entryIdentity: String): Int

	@Query(
		"SELECT COUNT(*) FROM imported_steps_count_domain_graph WHERE graph_identity IN (:graphIdentities)",
	)
	suspend fun graphEvidenceCount(graphIdentities: List<String>): Int

	@Query(
		"SELECT COUNT(*) FROM imported_steps_count_domain_binding " +
			"WHERE graph_identity IN (:graphIdentities)",
	)
	suspend fun bindingEvidenceCountForGraphs(graphIdentities: List<String>): Int

	@Query(
		"SELECT COUNT(*) FROM imported_steps_count_domain_receipt " +
			"WHERE owner_identity IN (:ownerIdentities)",
	)
	suspend fun receiptEvidenceCountForOwners(ownerIdentities: List<String>): Int

	@Query(
		"SELECT COUNT(*) FROM imported_steps_count_domain_owner_revision " +
			"WHERE owner_identity IN (:ownerIdentities)",
	)
	suspend fun ownerEvidenceCount(ownerIdentities: List<String>): Int

	@Query(
		"SELECT COUNT(*) FROM imported_steps_count_domain_completeness " +
			"WHERE owner_identity IN (:ownerIdentities)",
	)
	suspend fun completenessEvidenceCount(ownerIdentities: List<String>): Int

	@Query(
		"SELECT COUNT(*) FROM imported_steps_count_domain_root " +
			"WHERE container_identity IN (:containerIdentities)",
	)
	suspend fun rootEvidenceCountForContainers(containerIdentities: List<String>): Int

	@Query(
		"SELECT COUNT(*) FROM imported_steps_count_domain_root " +
			"WHERE product_identity IN (:productIdentities)",
	)
	suspend fun rootEvidenceCountForProducts(productIdentities: List<String>): Int

	@Query(
		"SELECT COUNT(*) FROM imported_steps_count_domain_root " +
			"WHERE owner_identity IN (:ownerIdentities)",
	)
	suspend fun rootEvidenceCountForOwners(ownerIdentities: List<String>): Int

	@Query(
		"SELECT COUNT(*) FROM imported_steps_count_domain_owner_fence " +
			"WHERE owner_identity IN (:ownerIdentities)",
	)
	suspend fun ownerFenceEvidenceCount(ownerIdentities: List<String>): Int

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

	@Query(
		"DELETE FROM imported_steps_count_domain_graph WHERE graph_identity = :graphIdentity " +
			"AND NOT EXISTS (SELECT 1 FROM imported_steps_count_domain_binding " +
			"WHERE graph_identity = :graphIdentity)",
	)
	suspend fun deleteGraphIfUnbound(graphIdentity: String): Int

	@Query(
		"SELECT * FROM imported_steps_count_domain_binding " +
			"ORDER BY product_kind, product_identity, product_revision LIMIT :limit",
	)
	fun allBindingsForFullClear(limit: Int): List<ImportedPortableStepsCountDomainBindingEntity>

	@Query(
		"SELECT * FROM imported_steps_count_domain_graph " +
			"WHERE (:afterGraphIdentity IS NULL OR graph_identity > :afterGraphIdentity) " +
			"ORDER BY graph_identity LIMIT :limit",
	)
	fun graphPageForFullClear(
		afterGraphIdentity: String?,
		limit: Int,
	): List<ImportedPortableStepsCountDomainGraphEntity>

	@Query(
		"SELECT * FROM imported_steps_count_domain_binding WHERE product_kind = :productKind " +
			"AND product_identity = :productIdentity ORDER BY product_revision LIMIT :limit",
	)
	fun bindingsForFullClear(
		productKind: String,
		productIdentity: String,
		limit: Int,
	): List<ImportedPortableStepsCountDomainBindingEntity>

	@Query("SELECT * FROM imported_steps_count_domain_graph WHERE graph_identity = :identity")
	fun graphForFullClear(identity: String): ImportedPortableStepsCountDomainGraphEntity?

	@Query(
		"SELECT * FROM imported_steps_count_domain_receipt WHERE graph_identity = :graphIdentity " +
			"ORDER BY owner_kind, owner_identity, owner_revision LIMIT :limit",
	)
	fun receiptsForFullClear(
		graphIdentity: String,
		limit: Int,
	): List<ImportedPortableStepsCountDomainReceiptEntity>

	@Query(
		"SELECT * FROM imported_steps_count_domain_owner_revision " +
			"WHERE graph_identity = :graphIdentity " +
			"ORDER BY owner_kind, owner_identity, owner_revision LIMIT :limit",
	)
	fun ownersForFullClear(
		graphIdentity: String,
		limit: Int,
	): List<ImportedPortableStepsCountDomainOwnerRevisionEntity>

	@Query(
		"SELECT * FROM imported_steps_count_domain_completeness " +
			"WHERE graph_identity = :graphIdentity " +
			"ORDER BY owner_identity, owner_revision LIMIT :limit",
	)
	fun markersForFullClear(
		graphIdentity: String,
		limit: Int,
	): List<ImportedPortableStepsCountDomainCompletenessEntity>

	@Query(
		"SELECT root.* FROM imported_steps_count_domain_root AS root " +
			"WHERE root.graph_identity = :graphIdentity " +
			"ORDER BY root.container_identity, product_identity, owner_kind, owner_identity LIMIT :limit",
	)
	fun rootsForFullClear(
		graphIdentity: String,
		limit: Int,
	): List<ImportedPortableStepsCountDomainRootEntity>

	@Query(
		"SELECT * FROM imported_steps_file_receipt WHERE receipt_identity = :receiptIdentity",
	)
	fun fileReceiptByIdentityForFullClear(
		receiptIdentity: String,
	): ImportedPortableStepsFileReceiptEntity?

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

	@Query(
		"SELECT COUNT(*) FROM imported_steps_file_receipt WHERE entry_identity = :entryIdentity",
	)
	fun fileReceiptCountForEntryForFullClear(entryIdentity: String): Int

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

	@Query(
		"SELECT * FROM imported_steps_count_domain_owner_fence " +
			"WHERE owner_identity IN (:ownerIdentities) ORDER BY owner_kind, owner_identity",
	)
	fun ownerFencesForFullClear(
		ownerIdentities: List<String>,
	): List<ImportedPortableStepsCountDomainOwnerFenceEntity>

	@Query("DELETE FROM imported_steps_count_domain_binding")
	fun deleteAllBindingsForFullClear()

	@Query("DELETE FROM imported_steps_count_domain_graph")
	fun deleteAllGraphsForFullClear()

	@Query("DELETE FROM imported_steps_file_receipt")
	fun deleteAllFileReceiptsForFullClear()

	companion object {
		const val MAX_FILE_RECEIPTS_PER_ENTRY = 4_096
		const val FILE_RECEIPT_PAGE_SIZE = 128
	}
}
