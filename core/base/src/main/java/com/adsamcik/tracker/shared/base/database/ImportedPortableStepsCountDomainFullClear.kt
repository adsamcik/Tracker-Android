package com.adsamcik.tracker.shared.base.database

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteProgram
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainBindingEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainGraphEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainOwnerFenceEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableCountDomainIdentity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsFullClearStagingSchema
import com.adsamcik.tracker.shared.base.database.dao.ImportedAmbientStepsDao
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedReader
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedTraversal
import com.adsamcik.tracker.shared.base.database.steps.imported.RetainedImportedStepsEntry
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV1
import com.adsamcik.tracker.shared.model.steps.portable.PORTABLE_COUNT_DOMAIN_OWNER_ORDER
import com.adsamcik.tracker.shared.model.steps.portable.PORTABLE_COUNT_DOMAIN_ROOT_ORDER
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainGraphV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainFormatV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainOperation
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainOwnerKind
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainOwnerRevisionV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainRootV2
import com.adsamcik.tracker.shared.model.steps.portable.StepsPortableFormatV1
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Converts every live imported portable owner into a value-free terminal fence before full clear.
 */
internal suspend fun AppDatabase.preserveImportedPortableCountDomainFullClearFences(
	operationId: String,
	oldCollectedDataEpoch: Long,
	newCollectedDataEpoch: Long,
	fencedAtMs: Long,
) {
	require(operationId.isNotBlank())
	require(oldCollectedDataEpoch >= 0L)
	require(newCollectedDataEpoch > oldCollectedDataEpoch)
	require(fencedAtMs >= 0L)
	val coroutineContext = currentCoroutineContext()
	val sqlite = openHelper.writableDatabase
	check(sqlite.inTransaction()) {
		"Imported portable full clear requires the owning Room transaction"
	}
	reconcileImportedPortableLegacyGraphsBeforeFullClear(oldCollectedDataEpoch)
	val dao = importedPortableStepsCountDomainDao()
	val staging = AuthenticatedFullClearOwnerFenceStaging(
		sqlite = sqlite,
		operationId = operationId,
		fenceKind = ImportedPortableStepsCountDomainOwnerFenceEntity.FENCE_FULL_CLEAR,
		collectedDataEpoch = newCollectedDataEpoch,
		fencedAtMs = fencedAtMs,
		maximumOwnerCount = null,
		maximumOwnerRevisionCount = null,
		checkpoint = coroutineContext::ensureActive,
	)
	try {
		stageImportedPortableBindingsForFullClear(dao, staging)
		val consumeAuthenticatedGraph:
			(AuthenticatedImportedPortableGraphBinding, Boolean) -> Unit = { current, isStored ->
			if (isStored) staging.consumeBinding(current.binding)
			staging.add(
				AuthenticatedFullClearGraphAppearance(
					graph = current.graph,
					graphIdentity = current.binding.graphIdentity,
					productKind = current.binding.productKind,
					productIdentity = current.binding.productIdentity,
					graphRevision = current.binding.productRevision,
					sourceSchemaVersion = current.binding.sourceSchemaVersion,
					isBound = true,
				),
			)
		}
		authenticatedImportedSessionBindingsForFullClear(
			consumeAuthenticatedGraph,
			staging,
		)
		authenticatedImportedAmbientBindingsForFullClear(
			oldCollectedDataEpoch,
			consumeAuthenticatedGraph,
			staging,
		)
		staging.requireEveryBindingConsumed()
		authenticateAndFoldOrphanPortableGraphsForFullClear(
			staging = staging,
		) { orphan ->
			staging.add(orphan)
		}
		authenticateAllPortableSessionFileReceiptsForFullClear(
			sessionProducts = staging,
		)
		staging.install(dao)
		dao.deleteAllBindingsForFullClear()
		dao.deleteAllGraphsForFullClear()
		dao.deleteAllFileReceiptsForFullClear()
	} finally {
		staging.close()
	}
}

private fun stageImportedPortableBindingsForFullClear(
	dao: com.adsamcik.tracker.shared.base.database.dao.ImportedPortableStepsCountDomainDao,
	staging: AuthenticatedFullClearOwnerFenceStaging,
) {
	var afterProductKind: String? = null
	var afterProductIdentity: String? = null
	var afterProductRevision: Long? = null
	while (true) {
		val page = dao.bindingPageForFullClear(
			afterProductKind,
			afterProductIdentity,
			afterProductRevision,
			BINDING_FULL_CLEAR_PAGE_SIZE,
		)
		if (page.isEmpty()) break
		require(page.size <= BINDING_FULL_CLEAR_PAGE_SIZE)
		page.forEach { binding ->
			require(
				afterProductKind == null ||
					binding.productKind > afterProductKind!! ||
					(binding.productKind == afterProductKind &&
						binding.productIdentity > afterProductIdentity!!) ||
					(binding.productKind == afterProductKind &&
						binding.productIdentity == afterProductIdentity &&
						binding.productRevision > afterProductRevision!!),
			) {
				"Imported portable binding keyset did not advance"
			}
			staging.addBinding(binding)
		}
		val last = page.last()
		afterProductKind = last.productKind
		afterProductIdentity = last.productIdentity
		afterProductRevision = last.productRevision
		if (page.size < BINDING_FULL_CLEAR_PAGE_SIZE) break
	}
}

private suspend fun AppDatabase.authenticatedImportedSessionBindingsForFullClear(
	consume: (AuthenticatedImportedPortableGraphBinding, Boolean) -> Unit,
	staging: AuthenticatedFullClearOwnerFenceStaging,
) {
	val reader = ImportedStepsRetainedReader(this)
	when (
		val traversal = reader.forEachEntryForRetentionInTransaction { product ->
			val binding = staging.singleBinding(
				ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
				product.metadata.identity,
			)
			val authenticated = if (binding == null) {
				val graph = product.legacyUnprovenCountDomainGraph()
				requireGraphlessLegacySessionProvenance(product, graph)
				AuthenticatedImportedPortableGraphBinding(
					ImportedPortableStepsCountDomainBindingEntity(
						productKind =
							ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
						productIdentity = product.metadata.identity,
						productRevision = SESSION_PRODUCT_REVISION,
						graphIdentity = graph.identity.value,
						sourceSchemaVersion = StepsPortableFormatV1.SCHEMA_VERSION,
					),
					graph,
				)
			} else {
				loadAuthenticatedImportedSessionCountDomainBindingForFullClear(binding, product)
			}
			requireSessionGraphCoversRetainedProduct(authenticated, product)
			staging.addSessionProduct(product, authenticated, binding != null)
			consume(authenticated, binding != null)
		}
	) {
		is ImportedStepsRetainedTraversal.Complete -> Unit
		is ImportedStepsRetainedTraversal.Unverifiable -> error(
			"Imported Steps product is unverifiable during full clear: " +
				"${traversal.entryIdentity}:${traversal.reason}",
		)
	}
}

private suspend fun AppDatabase.authenticatedImportedAmbientBindingsForFullClear(
	oldCollectedDataEpoch: Long,
	consume: (AuthenticatedImportedPortableGraphBinding, Boolean) -> Unit,
	staging: AuthenticatedFullClearOwnerFenceStaging,
) {
	val ambientDao = importedAmbientStepsDao()
	var afterDayId: String? = null
	while (true) {
		val page = ambientDao.fullClearDayCandidatePage(afterDayId, AMBIENT_FULL_CLEAR_PAGE_SIZE)
		if (page.isEmpty()) break
		require(page.size <= AMBIENT_FULL_CLEAR_PAGE_SIZE)
		page.forEach { candidate ->
			val lineage = ambientDao.loadAuthenticatedAmbientStepsLineageForFullClear(
				candidate,
				oldCollectedDataEpoch,
			)
			val storedBindings = staging.bindings(
				ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY,
				candidate.dayIdentity,
				ImportedAmbientStepsDao.MAX_ARCHIVES_PER_DAY + 1,
			)
			require(storedBindings.size <= ImportedAmbientStepsDao.MAX_ARCHIVES_PER_DAY)
			val graphLineage = if (storedBindings.isEmpty()) {
				reconstructGraphlessLegacyAmbientLineage(lineage).also {
					requireGraphlessLegacyAmbientProvenance(lineage, it)
				}
			} else {
				loadAuthenticatedImportedAmbientStepsGraphLineageForFullClear(lineage).also {
					require(it.map(AuthenticatedImportedAmbientStepsGraphRevision::binding) ==
						storedBindings)
				}
			}
			graphLineage.forEach {
				consume(
					AuthenticatedImportedPortableGraphBinding(it.binding, it.graph),
					storedBindings.isNotEmpty(),
				)
			}
		}
		afterDayId = page.last().dayIdentity
		if (page.size < AMBIENT_FULL_CLEAR_PAGE_SIZE) break
	}
}

private fun AppDatabase.authenticateAndFoldOrphanPortableGraphsForFullClear(
	staging: AuthenticatedFullClearOwnerFenceStaging,
	consume: (AuthenticatedFullClearGraphAppearance) -> Unit,
) {
	val dao = importedPortableStepsCountDomainDao()
	var afterGraphIdentity: String? = null
	while (true) {
		val page = dao.graphPageForFullClear(afterGraphIdentity, GRAPH_FULL_CLEAR_PAGE_SIZE)
		if (page.isEmpty()) break
		require(page.size <= GRAPH_FULL_CLEAR_PAGE_SIZE)
		page.forEach { graphRow ->
			require(afterGraphIdentity == null || graphRow.graphIdentity > afterGraphIdentity!!)
			val graph = requireNotNull(
				dao.authenticatedGraphForFullClear(
					graphRow.graphIdentity,
					graphRow.sourceFormat,
				),
			) {
				"Imported portable graph is not independently authentic"
			}
			if (staging.bindingCountForGraph(graphRow.graphIdentity) > 0L) {
				require(staging.isGraphAuthenticated(graphRow.graphIdentity)) {
					"Imported portable graph binding was not authenticated by its product"
				}
			} else {
				consume(
					AuthenticatedFullClearGraphAppearance(
					graph = graph,
					graphIdentity = graphRow.graphIdentity,
					productKind = graphRow.sourceFormat.toPortableProductKind(),
					productIdentity = null,
					graphRevision = null,
					sourceSchemaVersion = null,
					isBound = false,
					),
				)
			}
		}
		afterGraphIdentity = page.last().graphIdentity
		if (page.size < GRAPH_FULL_CLEAR_PAGE_SIZE) break
	}
}

internal data class AuthenticatedFullClearGraphAppearance(
	val graph: PortableCountDomainGraphV2,
	val graphIdentity: String,
	val productKind: String,
	val productIdentity: String?,
	val graphRevision: Long?,
	val sourceSchemaVersion: Int?,
	val isBound: Boolean,
)

internal suspend fun AppDatabase.stageAuthenticatedPortableOwnerFencesForFullClear(
	graphs: Sequence<AuthenticatedFullClearGraphAppearance>,
	operationId: String,
	fenceKind: String,
	collectedDataEpoch: Long,
	fencedAtMs: Long,
	maximumOwnerCount: Int,
	maximumOwnerRevisionCount: Int,
) {
	require(operationId.isNotBlank())
	require(collectedDataEpoch >= 0L)
	require(fencedAtMs >= 0L)
	require(maximumOwnerCount > 0)
	require(maximumOwnerRevisionCount > 0)
	val coroutineContext = currentCoroutineContext()
	val staging = AuthenticatedFullClearOwnerFenceStaging(
		openHelper.writableDatabase,
		operationId,
		fenceKind,
		collectedDataEpoch,
		fencedAtMs,
		maximumOwnerCount.toLong(),
		maximumOwnerRevisionCount.toLong(),
		checkpoint = coroutineContext::ensureActive,
	)
	try {
		graphs.forEach(staging::add)
		staging.install(importedPortableStepsCountDomainDao())
	} finally {
		staging.close()
	}
}

private class AuthenticatedFullClearOwnerFenceStaging(
	private val sqlite: SupportSQLiteDatabase,
	private val operationId: String,
	private val fenceKind: String,
	private val collectedDataEpoch: Long,
	private val fencedAtMs: Long,
	maximumOwnerCount: Long?,
	maximumOwnerRevisionCount: Long?,
	private val checkpoint: () -> Unit,
) {
	private val cardinality = FullClearStagingCardinality(
		maximumOwnerCount = maximumOwnerCount,
		maximumOwnerRevisionCount = maximumOwnerRevisionCount,
	)

	init {
		check(sqlite.inTransaction()) {
			"Imported portable full-clear staging requires the owning Room transaction"
		}
		require(operationId.isNotBlank())
		clearOperation()
	}

	fun addBinding(binding: ImportedPortableStepsCountDomainBindingEntity) {
		checkpoint()
		sqlite.execSQL(
			"INSERT INTO $BINDING_TABLE (" +
				"operation_id, product_kind, product_identity, product_revision, graph_identity, " +
				"source_schema_version, source_receipt_identity, source_archive_identity, " +
				"source_archive_content_checksum) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
			arrayOf(
				operationId,
				binding.productKind,
				binding.productIdentity,
				binding.productRevision,
				binding.graphIdentity,
				binding.sourceSchemaVersion,
				binding.sourceReceiptIdentity,
				binding.sourceArchiveIdentity,
				binding.sourceArchiveContentChecksum,
			),
		)
	}

	fun singleBinding(
		productKind: String,
		productIdentity: String,
	): ImportedPortableStepsCountDomainBindingEntity? {
		val matches = bindings(productKind, productIdentity, 2)
		require(matches.size <= 1) {
			"Imported portable session product has multiple graph bindings"
		}
		return matches.singleOrNull()
	}

	fun bindings(
		productKind: String,
		productIdentity: String,
		limit: Int,
	): List<ImportedPortableStepsCountDomainBindingEntity> {
		require(limit > 0)
		checkpoint()
		return sqlite.query(
			"SELECT product_revision, graph_identity, source_schema_version, " +
				"source_receipt_identity, source_archive_identity, " +
				"source_archive_content_checksum FROM $BINDING_TABLE " +
				"WHERE operation_id = ? AND product_kind = ? AND product_identity = ? " +
				"ORDER BY product_revision LIMIT ?",
			arrayOf(operationId, productKind, productIdentity, limit),
		).use { cursor ->
			buildList {
				while (cursor.moveToNext()) {
					add(
						ImportedPortableStepsCountDomainBindingEntity(
							productKind = productKind,
							productIdentity = productIdentity,
							productRevision = cursor.getLong(0),
							graphIdentity = cursor.getString(1),
							sourceSchemaVersion = cursor.getInt(2),
							sourceReceiptIdentity =
								if (cursor.isNull(3)) null else cursor.getString(3),
							sourceArchiveIdentity =
								if (cursor.isNull(4)) null else cursor.getString(4),
							sourceArchiveContentChecksum =
								if (cursor.isNull(5)) null else cursor.getString(5),
						),
					)
				}
			}
		}
	}

	fun consumeBinding(binding: ImportedPortableStepsCountDomainBindingEntity) {
		checkpoint()
		sqlite.compileStatement(
			"UPDATE $BINDING_TABLE SET consumed = 1 WHERE operation_id = ? AND product_kind = ? " +
				"AND product_identity = ? AND product_revision = ? AND graph_identity = ? " +
				"AND source_schema_version = ? AND " +
				"source_receipt_identity IS ? AND source_archive_identity IS ? AND " +
				"source_archive_content_checksum IS ? AND consumed = 0",
		).use { statement ->
			statement.bindString(1, operationId)
			statement.bindString(2, binding.productKind)
			statement.bindString(3, binding.productIdentity)
			statement.bindLong(4, binding.productRevision)
			statement.bindString(5, binding.graphIdentity)
			statement.bindLong(6, binding.sourceSchemaVersion.toLong())
			statement.bindNullableString(7, binding.sourceReceiptIdentity)
			statement.bindNullableString(8, binding.sourceArchiveIdentity)
			statement.bindNullableString(9, binding.sourceArchiveContentChecksum)
			require(statement.executeUpdateDelete() == 1) {
				"Imported portable graph binding was missing, duplicated, or changed"
			}
		}
	}

	fun requireEveryBindingConsumed() {
		checkpoint()
		val remaining = sqlite.query(
			"SELECT COUNT(*) FROM $BINDING_TABLE WHERE operation_id = ? AND consumed = 0",
			arrayOf(operationId),
		).use { cursor ->
			check(cursor.moveToFirst())
			cursor.getLong(0)
		}
		require(remaining == 0L) {
			"Imported portable graph binding was not consumed by a product"
		}
	}

	fun bindingCountForGraph(graphIdentity: String): Long {
		checkpoint()
		return sqlite.query(
			"SELECT COUNT(*) FROM $BINDING_TABLE WHERE operation_id = ? AND graph_identity = ?",
			arrayOf(operationId, graphIdentity),
		).use { cursor ->
			check(cursor.moveToFirst())
			cursor.getLong(0)
		}
	}

	fun isGraphAuthenticated(graphIdentity: String): Boolean {
		checkpoint()
		return sqlite.query(
			"SELECT 1 FROM $BINDING_TABLE WHERE operation_id = ? " +
				"AND graph_identity = ? AND consumed = 1 LIMIT 1",
			arrayOf(operationId, graphIdentity),
		).use { cursor -> cursor.moveToFirst() }
	}

	fun addSessionProduct(
		product: RetainedImportedStepsEntry,
		authenticated: AuthenticatedImportedPortableGraphBinding,
		hasStoredBinding: Boolean,
	) {
		checkpoint()
		val binding = authenticated.binding
		require(
			binding.productKind ==
				ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
		)
		require(binding.productIdentity == product.metadata.identity)
		require(binding.productRevision == SESSION_PRODUCT_REVISION)
		require(binding.graphIdentity == authenticated.graph.identity.value)
		require(
			hasStoredBinding ||
				(binding.sourceSchemaVersion == StepsPortableFormatV1.SCHEMA_VERSION &&
					binding.sourceReceiptIdentity == null &&
					binding.sourceArchiveContentChecksum == null),
		)
		sqlite.execSQL(
			"INSERT INTO $SESSION_PRODUCT_TABLE (" +
				"operation_id, product_identity, content_checksum, source_format, source_schema_version, " +
				"graph_identity, source_receipt_identity, source_archive_content_checksum, " +
				"has_stored_binding, source_receipt_observed) " +
				"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
			arrayOf(
				operationId,
				product.metadata.identity,
				product.metadata.contentChecksum,
				StepsPortableFormatV1.FORMAT,
				binding.sourceSchemaVersion,
				binding.graphIdentity,
				binding.sourceReceiptIdentity,
				binding.sourceArchiveContentChecksum,
				if (hasStoredBinding) 1 else 0,
				0,
			),
		)
	}

	fun sessionProducts(
		productIdentities: List<String>,
	): Map<String, StagedFullClearSessionProduct> {
		val identities = productIdentities.distinct()
		if (identities.isEmpty()) return emptyMap()
		require(identities.size <= FILE_RECEIPT_FULL_CLEAR_PAGE_SIZE)
		checkpoint()
		val placeholders = identities.joinToString(separator = ",") { "?" }
		return sqlite.query(
			"SELECT product_identity, content_checksum, source_format, source_schema_version, " +
				"graph_identity, source_receipt_identity, source_archive_content_checksum, " +
				"has_stored_binding FROM $SESSION_PRODUCT_TABLE " +
				"WHERE operation_id = ? AND product_identity IN ($placeholders)",
			arrayOf(operationId, *identities.toTypedArray()),
		).use { cursor ->
			buildMap {
				while (cursor.moveToNext()) {
					val product = StagedFullClearSessionProduct(
						productIdentity = cursor.getString(0),
						contentChecksum = cursor.getString(1),
						sourceFormat = cursor.getString(2),
						sourceSchemaVersion = cursor.getInt(3),
						graphIdentity = cursor.getString(4),
						sourceReceiptIdentity =
							if (cursor.isNull(5)) null else cursor.getString(5),
						sourceArchiveContentChecksum =
							if (cursor.isNull(6)) null else cursor.getString(6),
						hasStoredBinding = cursor.getInt(7) == 1,
					)
					require(put(product.productIdentity, product) == null)
				}
			}
		}
	}

	fun markSourceReceiptObserved(
		productIdentity: String,
		receiptIdentity: String,
		archiveContentChecksum: String,
	) {
		checkpoint()
		sqlite.compileStatement(
			"UPDATE $SESSION_PRODUCT_TABLE SET source_receipt_observed = 1 " +
				"WHERE operation_id = ? AND product_identity = ? AND source_receipt_identity = ? " +
				"AND source_archive_content_checksum = ?",
		).use { statement ->
			statement.bindString(1, operationId)
			statement.bindString(2, productIdentity)
			statement.bindString(3, receiptIdentity)
			statement.bindString(4, archiveContentChecksum)
			require(statement.executeUpdateDelete() == 1) {
				"Imported Steps source receipt conflicts with staged product provenance"
			}
		}
	}

	fun requireEverySourceReceiptObserved() {
		checkpoint()
		val missing = sqlite.query(
			"SELECT COUNT(*) FROM $SESSION_PRODUCT_TABLE " +
				"WHERE operation_id = ? AND source_receipt_identity IS NOT NULL " +
				"AND source_receipt_observed = 0",
			arrayOf(operationId),
		).use { cursor ->
			check(cursor.moveToFirst())
			cursor.getLong(0)
		}
		require(missing == 0L) {
			"Imported Steps binding source receipt was not independently enumerated"
		}
	}

	fun add(graph: AuthenticatedFullClearGraphAppearance) {
		check(graph.graph.identity.value == graph.graphIdentity)
		val isLegacyAmbient = graph.isLegacyAmbientGraphAppearance(checkpoint)
		graph.graph.forEachCanonicalOwnerLineageForFullClear(checkpoint) { root, lineage ->
			addOwnerAppearance(graph, root, lineage, isLegacyAmbient)
		}
	}

	fun install(dao: com.adsamcik.tracker.shared.base.database.dao.ImportedPortableStepsCountDomainDao) {
		var afterKind: String? = null
		var afterIdentity: String? = null
		var installedOwnerCount = 0L
		while (true) {
			checkpoint()
			val page = ownerPage(afterKind, afterIdentity)
			if (page.isEmpty()) break
			page.forEach { owner ->
				checkpoint()
				requireLegacyRevisionsCoveredByExplicitLineage(owner)
				val latest = requireNotNull(latestRevision(owner.ownerKind, owner.ownerIdentity))
				require(latest.ownerRevision == owner.latestOwnerRevision)
				val candidate = ImportedPortableStepsCountDomainOwnerFenceEntity.create(
					ownerKind = owner.ownerKind,
					ownerIdentity = owner.ownerIdentity,
					scopeIdentity = owner.scopeIdentity,
					latestSourceRevision = latest.ownerRevision,
					latestOwnerEffectChecksum = latest.ownerEffectChecksum,
					productKind = owner.productKind,
					productIdentity = owner.boundProductIdentity ?: owner.rootProductIdentity,
					graphIdentity = owner.latestGraphIdentity,
					fenceKind = fenceKind,
					collectedDataEpoch = collectedDataEpoch,
					fencedAtMs = fencedAtMs,
				)
				val stored = dao.ownerFenceForFullClear(owner.ownerKind, owner.ownerIdentity)
				if (stored == null) {
					dao.insertOwnerFenceForFullClear(candidate)
				} else {
					require(
						stored.effectChecksum ==
							ImportedPortableCountDomainIdentity.ownerFenceChecksum(stored),
					)
					require(stored.hasCompatibleTerminalAuthority(candidate))
					require(stored.latestSourceRevision == latest.ownerRevision)
					require(stored.latestOwnerEffectChecksum == latest.ownerEffectChecksum) {
						"Stored imported portable fence conflicts with authenticated owner lineage"
					}
					require(stored.collectedDataEpoch <= candidate.collectedDataEpoch)
					require(stored.fencedAtMs <= candidate.fencedAtMs)
				}
				installedOwnerCount = Math.addExact(installedOwnerCount, 1L)
			}
			afterKind = page.last().ownerKind
			afterIdentity = page.last().ownerIdentity
		}
		require(installedOwnerCount == cardinality.ownerCount)
		require(tableRowCount(REVISION_TABLE) == cardinality.ownerRevisionCount) {
			"Imported portable staged owner revision cardinality changed"
		}
	}

	fun close() {
		clearOperation()
	}

	private fun addOwnerAppearance(
		graph: AuthenticatedFullClearGraphAppearance,
		root: PortableCountDomainRootV2,
		lineage: List<PortableCountDomainOwnerRevisionV2>,
		isLegacyAmbient: Boolean,
	) {
		val first = lineage.first()
		require(lineage.all {
			it.ownerKind == first.ownerKind && it.ownerIdentity == first.ownerIdentity
		})
		require(lineage.all { it.scopeIdentity == first.scopeIdentity }) {
			"Imported portable owner lineage changes scope"
		}
		val ownerKind = root.ownerKind.name
		val ownerIdentity = root.ownerIdentity.value
		val boundProductIdentity = if (graph.isBound) requireNotNull(graph.productIdentity) else null
		val rank = FullClearStagedAppearanceRank(
			ownerRevision = root.ownerRevision,
			graphRevision = graph.graphRevision ?: 0L,
			isBound = graph.isBound,
			compareProductIdentity = graph.productIdentity ?: root.productIdentity.value,
			graphIdentity = graph.graphIdentity,
		)
		val stored = owner(ownerKind, ownerIdentity)
		if (stored == null) {
			cardinality.recordOwner()
			sqlite.execSQL(
				"INSERT INTO $OWNER_TABLE (" +
					"operation_id, owner_kind, owner_identity, scope_identity, container_identity, " +
					"root_product_identity, product_kind, bound_product_identity, " +
					"latest_graph_identity, latest_owner_revision, latest_graph_revision, " +
					"latest_is_bound, latest_compare_product_identity, explicit_lineage_length" +
					") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)",
				arrayOf(
					operationId,
					ownerKind,
					ownerIdentity,
					first.scopeIdentity.value,
					root.containerIdentity.value,
					root.productIdentity.value,
					graph.productKind,
					boundProductIdentity,
					graph.graphIdentity,
					root.ownerRevision,
					rank.graphRevision,
					if (rank.isBound) 1 else 0,
					rank.compareProductIdentity,
				),
			)
		} else {
			require(stored.scopeIdentity == first.scopeIdentity.value) {
				"Imported portable owner appears in conflicting scopes"
			}
			require(
				stored.containerIdentity == root.containerIdentity.value &&
					stored.rootProductIdentity == root.productIdentity.value,
			) {
				"Imported portable owner appears in conflicting product roots"
			}
			require(stored.productKind == graph.productKind) {
				"Imported portable owner appears in conflicting product kinds"
			}
			if (boundProductIdentity != null) {
				require(
					stored.boundProductIdentity == null ||
						stored.boundProductIdentity == boundProductIdentity,
				) {
					"Imported portable owner appears in conflicting bound products"
				}
				if (stored.boundProductIdentity == null) {
					sqlite.execSQL(
						"UPDATE $OWNER_TABLE SET bound_product_identity = ? " +
							"WHERE operation_id = ? AND owner_kind = ? AND owner_identity = ?",
						arrayOf(boundProductIdentity, operationId, ownerKind, ownerIdentity),
					)
				}
			}
			if (rank > stored.rank) {
				sqlite.execSQL(
					"UPDATE $OWNER_TABLE SET latest_graph_identity = ?, " +
						"latest_owner_revision = ?, latest_graph_revision = ?, " +
						"latest_is_bound = ?, latest_compare_product_identity = ? " +
						"WHERE operation_id = ? AND owner_kind = ? AND owner_identity = ?",
					arrayOf(
						rank.graphIdentity,
						rank.ownerRevision,
						rank.graphRevision,
						if (rank.isBound) 1 else 0,
						rank.compareProductIdentity,
						operationId,
						ownerKind,
						ownerIdentity,
					),
				)
			}
		}

		if (isLegacyAmbient) require(lineage.size == 1)
		lineage.forEach { revision ->
			stageRevision(revision, isLegacyAmbient)
		}
		if (isLegacyAmbient) {
			val explicitLength = requireNotNull(owner(ownerKind, ownerIdentity)).explicitLineageLength
			if (explicitLength > 0) {
				require(explicitContainsRevision(ownerKind, ownerIdentity, lineage.single().ownerRevision)) {
					"Explicit portable lineage does not preserve legacy Ambient authority"
				}
			}
		} else {
			stageExplicitLineage(ownerKind, ownerIdentity, lineage)
			requireLegacyRevisionsCoveredByExplicitLineage(
				requireNotNull(owner(ownerKind, ownerIdentity)),
			)
		}
	}

	private fun stageRevision(
		revision: PortableCountDomainOwnerRevisionV2,
		isLegacyAmbient: Boolean,
	) {
		val stored = revision(
			revision.ownerKind.name,
			revision.ownerIdentity.value,
			revision.ownerRevision,
		)
		if (stored == null) {
			cardinality.recordOwnerRevision()
			sqlite.execSQL(
				"INSERT INTO $REVISION_TABLE (" +
					"operation_id, owner_kind, owner_identity, owner_revision, scope_identity, operation, " +
					"receipt_identity, owner_effect_checksum, source_linked_at_ms, legacy_ambient" +
					") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
				arrayOf(
					operationId,
					revision.ownerKind.name,
					revision.ownerIdentity.value,
					revision.ownerRevision,
					revision.scopeIdentity.value,
					revision.operation.name,
					revision.receiptIdentity?.value,
					revision.ownerEffectChecksum.value,
					revision.linkedAtMs,
					if (isLegacyAmbient) 1 else 0,
				),
			)
		} else {
			require(stored.matches(revision)) {
				"Imported portable owner revision has conflicting graph appearances"
			}
			if (isLegacyAmbient && !stored.isLegacyAmbient) {
				sqlite.execSQL(
					"UPDATE $REVISION_TABLE SET legacy_ambient = 1 " +
						"WHERE operation_id = ? AND owner_kind = ? " +
						"AND owner_identity = ? AND owner_revision = ?",
					arrayOf(
						operationId,
						revision.ownerKind.name,
						revision.ownerIdentity.value,
						revision.ownerRevision,
					),
				)
			}
		}
	}

	private fun stageExplicitLineage(
		ownerKind: String,
		ownerIdentity: String,
		lineage: List<PortableCountDomainOwnerRevisionV2>,
	) {
		val storedLength = requireNotNull(owner(ownerKind, ownerIdentity)).explicitLineageLength
		val sharedLength = minOf(storedLength, lineage.size)
		repeat(sharedLength) { ordinal ->
			require(
				explicitRevision(ownerKind, ownerIdentity, ordinal) ==
					lineage[ordinal].ownerRevision,
			) {
				"Imported portable owner has conflicting explicit graph lineage"
			}
		}
		for (ordinal in storedLength until lineage.size) {
			require(
				!explicitContainsRevision(
					ownerKind,
					ownerIdentity,
					lineage[ordinal].ownerRevision,
				),
			) {
				"Imported portable owner has conflicting explicit graph lineage"
			}
			sqlite.execSQL(
				"INSERT INTO $EXPLICIT_TABLE (" +
					"operation_id, owner_kind, owner_identity, lineage_ordinal, owner_revision" +
					") VALUES (?, ?, ?, ?, ?)",
				arrayOf(
					operationId,
					ownerKind,
					ownerIdentity,
					ordinal,
					lineage[ordinal].ownerRevision,
				),
			)
		}
		if (lineage.size > storedLength) {
			sqlite.execSQL(
				"UPDATE $OWNER_TABLE SET explicit_lineage_length = ? " +
					"WHERE operation_id = ? AND owner_kind = ? AND owner_identity = ?",
				arrayOf(lineage.size, operationId, ownerKind, ownerIdentity),
			)
		}
	}

	private fun requireLegacyRevisionsCoveredByExplicitLineage(owner: StagedFullClearOwner) {
		if (owner.explicitLineageLength == 0) return
		val missingCount = sqlite.query(
			"SELECT COUNT(*) FROM $REVISION_TABLE AS revision " +
				"WHERE revision.operation_id = ? AND revision.owner_kind = ? " +
				"AND revision.owner_identity = ? " +
				"AND revision.legacy_ambient = 1 AND NOT EXISTS (" +
				"SELECT 1 FROM $EXPLICIT_TABLE AS lineage " +
				"WHERE lineage.operation_id = revision.operation_id " +
				"AND lineage.owner_kind = revision.owner_kind " +
				"AND lineage.owner_identity = revision.owner_identity " +
				"AND lineage.owner_revision = revision.owner_revision)",
			arrayOf(operationId, owner.ownerKind, owner.ownerIdentity),
		).use { cursor ->
			check(cursor.moveToFirst())
			cursor.getLong(0)
		}
		require(missingCount == 0L) {
			"Explicit portable lineage does not preserve legacy Ambient authority"
		}
	}

	private fun owner(ownerKind: String, ownerIdentity: String): StagedFullClearOwner? =
		sqlite.query(
			"SELECT scope_identity, container_identity, root_product_identity, product_kind, " +
				"bound_product_identity, latest_graph_identity, latest_owner_revision, " +
				"latest_graph_revision, latest_is_bound, latest_compare_product_identity, " +
				"explicit_lineage_length FROM $OWNER_TABLE " +
				"WHERE operation_id = ? AND owner_kind = ? AND owner_identity = ?",
			arrayOf(operationId, ownerKind, ownerIdentity),
		).use { cursor ->
			if (!cursor.moveToFirst()) null else StagedFullClearOwner(
				ownerKind = ownerKind,
				ownerIdentity = ownerIdentity,
				scopeIdentity = cursor.getString(0),
				containerIdentity = cursor.getString(1),
				rootProductIdentity = cursor.getString(2),
				productKind = cursor.getString(3),
				boundProductIdentity = if (cursor.isNull(4)) null else cursor.getString(4),
				latestGraphIdentity = cursor.getString(5),
				latestOwnerRevision = cursor.getLong(6),
				rank = FullClearStagedAppearanceRank(
					ownerRevision = cursor.getLong(6),
					graphRevision = cursor.getLong(7),
					isBound = cursor.getInt(8) == 1,
					compareProductIdentity = cursor.getString(9),
					graphIdentity = cursor.getString(5),
				),
				explicitLineageLength = cursor.getInt(10),
			)
		}

	private fun ownerPage(
		afterKind: String?,
		afterIdentity: String?,
	): List<StagedFullClearOwner> {
		val query = if (afterKind == null) {
			"SELECT owner_kind, owner_identity, scope_identity, container_identity, " +
				"root_product_identity, product_kind, bound_product_identity, " +
				"latest_graph_identity, latest_owner_revision, latest_graph_revision, " +
				"latest_is_bound, latest_compare_product_identity, explicit_lineage_length " +
				"FROM $OWNER_TABLE WHERE operation_id = ? " +
				"ORDER BY owner_kind, owner_identity LIMIT ?"
		} else {
			"SELECT owner_kind, owner_identity, scope_identity, container_identity, " +
				"root_product_identity, product_kind, bound_product_identity, " +
				"latest_graph_identity, latest_owner_revision, latest_graph_revision, " +
				"latest_is_bound, latest_compare_product_identity, explicit_lineage_length " +
				"FROM $OWNER_TABLE WHERE operation_id = ? AND (owner_kind > ? OR " +
				"(owner_kind = ? AND owner_identity > ?) " +
				") ORDER BY owner_kind, owner_identity LIMIT ?"
		}
		val arguments = if (afterKind == null) {
			arrayOf<Any>(operationId, OWNER_INSTALL_PAGE_SIZE)
		} else {
			arrayOf(
				operationId,
				afterKind,
				afterKind,
				requireNotNull(afterIdentity),
				OWNER_INSTALL_PAGE_SIZE,
			)
		}
		return sqlite.query(query, arguments).use { cursor ->
			buildList {
				while (cursor.moveToNext()) {
					add(
						StagedFullClearOwner(
							ownerKind = cursor.getString(0),
							ownerIdentity = cursor.getString(1),
							scopeIdentity = cursor.getString(2),
							containerIdentity = cursor.getString(3),
							rootProductIdentity = cursor.getString(4),
							productKind = cursor.getString(5),
							boundProductIdentity =
								if (cursor.isNull(6)) null else cursor.getString(6),
							latestGraphIdentity = cursor.getString(7),
							latestOwnerRevision = cursor.getLong(8),
							rank = FullClearStagedAppearanceRank(
								ownerRevision = cursor.getLong(8),
								graphRevision = cursor.getLong(9),
								isBound = cursor.getInt(10) == 1,
								compareProductIdentity = cursor.getString(11),
								graphIdentity = cursor.getString(7),
							),
							explicitLineageLength = cursor.getInt(12),
						),
					)
				}
			}
		}.also { require(it.size <= OWNER_INSTALL_PAGE_SIZE) }
	}

	private fun revision(
		ownerKind: String,
		ownerIdentity: String,
		ownerRevision: Long,
	): StagedFullClearRevision? = sqlite.query(
		"SELECT scope_identity, operation, receipt_identity, owner_effect_checksum, " +
			"source_linked_at_ms, legacy_ambient FROM $REVISION_TABLE " +
			"WHERE operation_id = ? AND owner_kind = ? " +
			"AND owner_identity = ? AND owner_revision = ?",
		arrayOf(operationId, ownerKind, ownerIdentity, ownerRevision),
	).use { cursor ->
		if (!cursor.moveToFirst()) null else StagedFullClearRevision(
			ownerKind = ownerKind,
			ownerIdentity = ownerIdentity,
			ownerRevision = ownerRevision,
			scopeIdentity = cursor.getString(0),
			operation = cursor.getString(1),
			receiptIdentity = if (cursor.isNull(2)) null else cursor.getString(2),
			ownerEffectChecksum = cursor.getString(3),
			sourceLinkedAtMs = cursor.getLong(4),
			isLegacyAmbient = cursor.getInt(5) == 1,
		)
	}

	private fun latestRevision(
		ownerKind: String,
		ownerIdentity: String,
	): StagedFullClearRevision? = sqlite.query(
		"SELECT owner_revision, scope_identity, operation, receipt_identity, " +
			"owner_effect_checksum, source_linked_at_ms, legacy_ambient FROM $REVISION_TABLE " +
			"WHERE operation_id = ? AND owner_kind = ? AND owner_identity = ? " +
			"ORDER BY owner_revision DESC LIMIT 1",
		arrayOf(operationId, ownerKind, ownerIdentity),
	).use { cursor ->
		if (!cursor.moveToFirst()) null else StagedFullClearRevision(
			ownerKind = ownerKind,
			ownerIdentity = ownerIdentity,
			ownerRevision = cursor.getLong(0),
			scopeIdentity = cursor.getString(1),
			operation = cursor.getString(2),
			receiptIdentity = if (cursor.isNull(3)) null else cursor.getString(3),
			ownerEffectChecksum = cursor.getString(4),
			sourceLinkedAtMs = cursor.getLong(5),
			isLegacyAmbient = cursor.getInt(6) == 1,
		)
	}

	private fun explicitRevision(
		ownerKind: String,
		ownerIdentity: String,
		ordinal: Int,
	): Long? = sqlite.query(
		"SELECT owner_revision FROM $EXPLICIT_TABLE " +
			"WHERE operation_id = ? AND owner_kind = ? " +
			"AND owner_identity = ? AND lineage_ordinal = ?",
		arrayOf(operationId, ownerKind, ownerIdentity, ordinal),
	).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }

	private fun explicitContainsRevision(
		ownerKind: String,
		ownerIdentity: String,
		ownerRevision: Long,
	): Boolean = sqlite.query(
		"SELECT 1 FROM $EXPLICIT_TABLE WHERE operation_id = ? " +
			"AND owner_kind = ? AND owner_identity = ? " +
			"AND owner_revision = ? LIMIT 1",
		arrayOf(operationId, ownerKind, ownerIdentity, ownerRevision),
	).use { it.moveToFirst() }

	private fun tableRowCount(table: String): Long = sqlite.query(
		"SELECT COUNT(*) FROM $table WHERE operation_id = ?",
		arrayOf(operationId),
	).use { cursor ->
		check(cursor.moveToFirst())
		cursor.getLong(0)
	}

	private fun clearOperation() {
		ImportedPortableStepsFullClearStagingSchema.TABLES_IN_DELETE_ORDER.forEach { table ->
			sqlite.execSQL(
				"DELETE FROM main.$table WHERE operation_id = ?",
				arrayOf(operationId),
			)
		}
	}

	private companion object {
		const val BINDING_TABLE =
			"main.${ImportedPortableStepsFullClearStagingSchema.BINDING_TABLE}"
		const val SESSION_PRODUCT_TABLE =
			"main.${ImportedPortableStepsFullClearStagingSchema.SESSION_PRODUCT_TABLE}"
		const val OWNER_TABLE =
			"main.${ImportedPortableStepsFullClearStagingSchema.OWNER_TABLE}"
		const val REVISION_TABLE =
			"main.${ImportedPortableStepsFullClearStagingSchema.REVISION_TABLE}"
		const val EXPLICIT_TABLE =
			"main.${ImportedPortableStepsFullClearStagingSchema.EXPLICIT_TABLE}"
		const val OWNER_INSTALL_PAGE_SIZE = 256
	}
}

internal fun AppDatabase.cleanupImportedPortableStepsFullClearStagingInCurrentTransaction() {
	openHelper.writableDatabase.cleanupImportedPortableStepsFullClearStagingInCurrentTransaction()
}

internal fun SupportSQLiteDatabase.cleanupImportedPortableStepsFullClearStagingInCurrentTransaction() {
	val sqlite = this
	check(sqlite.inTransaction()) {
		"Imported portable full-clear staging cleanup requires a Room transaction"
	}
	ImportedPortableStepsFullClearStagingSchema.TABLES_IN_DELETE_ORDER.forEach { table ->
		sqlite.execSQL("DELETE FROM main.$table")
	}
}

internal fun PortableCountDomainGraphV2.forEachCanonicalOwnerLineageForFullClear(
	checkpoint: () -> Unit,
	consume: (PortableCountDomainRootV2, List<PortableCountDomainOwnerRevisionV2>) -> Unit,
) = forEachCanonicalPortableOwnerLineageForFullClear(
	roots = roots,
	ownerRevisions = ownerRevisions,
	checkpoint = checkpoint,
	consume = consume,
)

internal fun forEachCanonicalPortableOwnerLineageForFullClear(
	roots: List<PortableCountDomainRootV2>,
	ownerRevisions: List<PortableCountDomainOwnerRevisionV2>,
	checkpoint: () -> Unit,
	consume: (PortableCountDomainRootV2, List<PortableCountDomainOwnerRevisionV2>) -> Unit,
) {
	require(ownerRevisions.isNotEmpty())
	require(ownerRevisions.size <= PortableCountDomainFormatV2.MAX_OWNER_REVISIONS)
	require(roots.isNotEmpty())
	require(roots.size <= PortableCountDomainFormatV2.MAX_ROOTS)
	val rootsByOwner = HashMap<FullClearOwnerKey, PortableCountDomainRootV2>(roots.size)
	var previousRoot: PortableCountDomainRootV2? = null
	roots.forEach { root ->
		checkpoint()
		previousRoot?.let {
			require(PORTABLE_COUNT_DOMAIN_ROOT_ORDER.compare(it, root) < 0) {
				"Imported portable roots are duplicated or out of canonical order"
			}
		}
		previousRoot = root
		require(
			rootsByOwner.put(
				FullClearOwnerKey(root.ownerKind, root.ownerIdentity.value),
				root,
			) == null,
		) {
			"Imported portable owner has multiple product roots"
		}
	}

	var revisionIndex = 0
	var previousRevision: PortableCountDomainOwnerRevisionV2? = null
	while (revisionIndex < ownerRevisions.size) {
		val lineageStart = revisionIndex
		val first = ownerRevisions[lineageStart]
		val key = FullClearOwnerKey(first.ownerKind, first.ownerIdentity.value)
		var priorLineageRevision: Long? = null
		while (revisionIndex < ownerRevisions.size) {
			val revision = ownerRevisions[revisionIndex]
			if (revision.ownerKind != first.ownerKind ||
				revision.ownerIdentity != first.ownerIdentity
			) {
				break
			}
			checkpoint()
			previousRevision?.let {
				require(PORTABLE_COUNT_DOMAIN_OWNER_ORDER.compare(it, revision) < 0) {
					"Imported portable owner revisions are duplicated or out of canonical order"
				}
			}
			require(revision.scopeIdentity == first.scopeIdentity) {
				"Imported portable owner lineage changes scope"
			}
			require(
				priorLineageRevision == null ||
					revision.ownerRevision == Math.addExact(priorLineageRevision!!, 1L),
			) {
				"Imported portable owner lineage is not contiguous"
			}
			priorLineageRevision = revision.ownerRevision
			previousRevision = revision
			revisionIndex++
		}
		val lineage = ownerRevisions.subList(lineageStart, revisionIndex)
		require(lineage.size <= PortableCountDomainFormatV2.MAX_OWNER_LINEAGE_REVISIONS)
		val root = requireNotNull(rootsByOwner.remove(key)) {
			"Imported portable owner lineage has no product root"
		}
		require(root.ownerRevision == lineage.last().ownerRevision) {
			"Imported portable product root does not name the latest owner revision"
		}
		consume(root, lineage)
	}
	require(rootsByOwner.isEmpty()) {
		"Imported portable product root has no owner lineage"
	}
}

private data class FullClearOwnerKey(
	val ownerKind: PortableCountDomainOwnerKind,
	val ownerIdentity: String,
)

private data class FullClearOwnerRevisionKey(
	val ownerKind: PortableCountDomainOwnerKind,
	val ownerIdentity: String,
	val ownerRevision: Long,
)

private fun SupportSQLiteProgram.bindNullableString(index: Int, value: String?) {
	if (value == null) bindNull(index) else bindString(index, value)
}

internal class FullClearStagingCardinality(
	private val maximumOwnerCount: Long? = null,
	private val maximumOwnerRevisionCount: Long? = null,
	ownerCount: Long = 0L,
	ownerRevisionCount: Long = 0L,
) {
	var ownerCount: Long = ownerCount
		private set
	var ownerRevisionCount: Long = ownerRevisionCount
		private set

	init {
		require(ownerCount >= 0L)
		require(ownerRevisionCount >= 0L)
		require(maximumOwnerCount == null || ownerCount <= maximumOwnerCount)
		require(
			maximumOwnerRevisionCount == null ||
				ownerRevisionCount <= maximumOwnerRevisionCount,
		)
	}

	fun recordOwner(): Long {
		val next = Math.addExact(ownerCount, 1L)
		require(maximumOwnerCount == null || next <= maximumOwnerCount) {
			"Imported portable distinct owner count exceeds its full-clear bound"
		}
		ownerCount = next
		return next
	}

	fun recordOwnerRevision(): Long {
		val next = Math.addExact(ownerRevisionCount, 1L)
		require(
			maximumOwnerRevisionCount == null ||
				next <= maximumOwnerRevisionCount,
		) {
			"Imported portable distinct owner revision count exceeds its full-clear bound"
		}
		ownerRevisionCount = next
		return next
	}
}

private data class StagedFullClearSessionProduct(
	val productIdentity: String,
	val contentChecksum: String,
	val sourceFormat: String,
	val sourceSchemaVersion: Int,
	val graphIdentity: String,
	val sourceReceiptIdentity: String?,
	val sourceArchiveContentChecksum: String?,
	val hasStoredBinding: Boolean,
)

private data class StagedFullClearOwner(
	val ownerKind: String,
	val ownerIdentity: String,
	val scopeIdentity: String,
	val containerIdentity: String,
	val rootProductIdentity: String,
	val productKind: String,
	val boundProductIdentity: String?,
	val latestGraphIdentity: String,
	val latestOwnerRevision: Long,
	val rank: FullClearStagedAppearanceRank,
	val explicitLineageLength: Int,
)

private data class StagedFullClearRevision(
	val ownerKind: String,
	val ownerIdentity: String,
	val ownerRevision: Long,
	val scopeIdentity: String,
	val operation: String,
	val receiptIdentity: String?,
	val ownerEffectChecksum: String,
	val sourceLinkedAtMs: Long,
	val isLegacyAmbient: Boolean,
) {
	fun matches(value: PortableCountDomainOwnerRevisionV2): Boolean =
		ownerKind == value.ownerKind.name &&
			ownerIdentity == value.ownerIdentity.value &&
			ownerRevision == value.ownerRevision &&
			scopeIdentity == value.scopeIdentity.value &&
			operation == value.operation.name &&
			receiptIdentity == value.receiptIdentity?.value &&
			ownerEffectChecksum == value.ownerEffectChecksum.value &&
			sourceLinkedAtMs == value.linkedAtMs
}

private data class FullClearStagedAppearanceRank(
	val ownerRevision: Long,
	val graphRevision: Long,
	val isBound: Boolean,
	val compareProductIdentity: String,
	val graphIdentity: String,
) : Comparable<FullClearStagedAppearanceRank> {
	override fun compareTo(other: FullClearStagedAppearanceRank): Int = compareValuesBy(
		this,
		other,
		FullClearStagedAppearanceRank::ownerRevision,
		FullClearStagedAppearanceRank::graphRevision,
		{ if (it.isBound) 1 else 0 },
		FullClearStagedAppearanceRank::compareProductIdentity,
		FullClearStagedAppearanceRank::graphIdentity,
	)
}

private fun AuthenticatedFullClearGraphAppearance.isLegacyAmbientGraphAppearance(
	checkpoint: () -> Unit,
): Boolean {
	if (productKind != ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY) {
		return false
	}
	if (sourceSchemaVersion != null) {
		return sourceSchemaVersion == AmbientStepsPortableFormatV1.SCHEMA_VERSION
	}
	return graph.receipts.isEmpty() &&
		graph.completenessMarkers.isEmpty() &&
		graph.ownerRevisions.withIndex().all { (index, revision) ->
			checkpoint()
			val previous = graph.ownerRevisions.getOrNull(index - 1)
			(previous == null ||
				previous.ownerKind != revision.ownerKind ||
				previous.ownerIdentity != revision.ownerIdentity) &&
				revision.operation == PortableCountDomainOperation.UNPROVEN &&
				revision.receiptIdentity == null &&
				revision.linkedAtMs == 0L
		}
}

private fun String.toPortableProductKind(): String = when (this) {
	ImportedPortableStepsCountDomainGraphEntity.SOURCE_SESSION_STEPS ->
		ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY
	ImportedPortableStepsCountDomainGraphEntity.SOURCE_AMBIENT_STEPS ->
		ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY
	else -> error("Unsupported imported portable graph source")
}

private fun AppDatabase.authenticateAllPortableSessionFileReceiptsForFullClear(
	sessionProducts: AuthenticatedFullClearOwnerFenceStaging,
) {
	val dao = importedPortableStepsCountDomainDao()
	var afterJobId: String? = null
	var afterEntryKey: String? = null
	while (true) {
		val page = dao.fileReceiptPageForFullClear(
			afterJobId,
			afterEntryKey,
			FILE_RECEIPT_FULL_CLEAR_PAGE_SIZE,
		)
		if (page.isEmpty()) break
		require(page.size <= FILE_RECEIPT_FULL_CLEAR_PAGE_SIZE)
		val products = sessionProducts.sessionProducts(page.map { it.entryIdentity })
		require(products.keys == page.mapTo(linkedSetOf()) { it.entryIdentity }) {
			"Imported Steps file receipt has no authenticated product"
		}
		page.forEach { receipt ->
			require(
				afterJobId == null ||
					receipt.importJobId > afterJobId!! ||
					(receipt.importJobId == afterJobId && receipt.entryKey > afterEntryKey!!),
			)
			val product = requireNotNull(products[receipt.entryIdentity])
			require(product.hasStoredBinding) {
				"Imported Steps file receipt has no authenticated product binding"
			}
			require(product.productIdentity == receipt.entryIdentity)
			require(product.sourceFormat == StepsPortableFormatV1.FORMAT)
			require(product.graphIdentity == receipt.graphIdentity)
			require(sessionProducts.isGraphAuthenticated(product.graphIdentity))
			require(receipt.entryOrdinal in 0 until StepsPortableFormatV1.MAX_ENTRIES)
			if (product.sourceSchemaVersion == StepsPortableFormatV1.SCHEMA_VERSION) {
				require(receipt.archiveContentChecksum == product.contentChecksum)
			}
			if (product.sourceReceiptIdentity == receipt.receiptIdentity) {
				require(
					product.sourceArchiveContentChecksum == receipt.archiveContentChecksum,
				)
				sessionProducts.markSourceReceiptObserved(
					productIdentity = product.productIdentity,
					receiptIdentity = receipt.receiptIdentity,
					archiveContentChecksum = receipt.archiveContentChecksum,
				)
			}
		}
		val last = page.last()
		afterJobId = last.importJobId
		afterEntryKey = last.entryKey
		if (page.size < FILE_RECEIPT_FULL_CLEAR_PAGE_SIZE) break
	}
	sessionProducts.requireEverySourceReceiptObserved()
}

private suspend fun AppDatabase.reconcileImportedPortableLegacyGraphsBeforeFullClear(
	oldCollectedDataEpoch: Long,
) {
	val coroutineContext = currentCoroutineContext()
	val sessionReader = ImportedStepsRetainedReader(this)
	when (
		val traversal = sessionReader.forEachEntryForRetentionInTransaction { product ->
			if (importedPortableStepsCountDomainDao().bindingEvidenceCountForProduct(
					ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
					product.metadata.identity,
				) > 0
			) {
				val expected = product.legacyUnprovenCountDomainGraph()
				val loaded = requireNotNull(
					loadImportedSessionCountDomainBindingForFullClear(product.metadata.identity),
				)
				try {
					loadAuthenticatedImportedSessionCountDomainBindingForFullClear(
						loaded.binding,
						product,
					)
				} catch (failure: IllegalArgumentException) {
					if (!reconcilePreviouslyTruncatedLegacySessionBinding(
							product,
							loaded,
							expected,
						)
					) {
						throw failure
					}
				} catch (failure: IllegalStateException) {
					if (!reconcilePreviouslyTruncatedLegacySessionBinding(
							product,
							loaded,
							expected,
						)
					) {
						throw failure
					}
				}
			}
		}
	) {
		is ImportedStepsRetainedTraversal.Complete -> Unit
		is ImportedStepsRetainedTraversal.Unverifiable -> error(
			"Imported Steps product is unverifiable during graph reconciliation: " +
				"${traversal.entryIdentity}:${traversal.reason}",
		)
	}

	val ambientDao = importedAmbientStepsDao()
	var afterDayId: String? = null
	while (true) {
		coroutineContext.ensureActive()
		val page = ambientDao.fullClearDayCandidatePage(afterDayId, AMBIENT_FULL_CLEAR_PAGE_SIZE)
		if (page.isEmpty()) break
		page.forEach { candidate ->
			coroutineContext.ensureActive()
			val lineage = ambientDao.loadAuthenticatedAmbientStepsLineageForFullClear(
				candidate,
				oldCollectedDataEpoch,
			)
			if (importedPortableStepsCountDomainDao().bindingEvidenceCountForProduct(
					ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY,
					candidate.dayIdentity,
				) > 0
			) {
				loadAuthenticatedImportedAmbientStepsGraphLineage(lineage)
			}
		}
		afterDayId = page.last().dayIdentity
		if (page.size < AMBIENT_FULL_CLEAR_PAGE_SIZE) break
	}
}

private suspend fun AppDatabase.requireSessionGraphCoversRetainedProduct(
	authenticated: AuthenticatedImportedPortableGraphBinding,
	product: RetainedImportedStepsEntry,
) {
	val coroutineContext = currentCoroutineContext()
	val liveRunIds = product.runs.mapTo(linkedSetOf()) { it.identity }
	val expectedRoots = buildSet {
		product.portableRunsById.values.forEach { run ->
			coroutineContext.ensureActive()
			run.facts.forEach { fact ->
				coroutineContext.ensureActive()
				add(
					SessionRootKey(
						run.identity.value,
						fact.identity.value,
						PortableCountDomainOwnerKind.SESSION_FACT,
					),
				)
			}
			add(
				SessionRootKey(
					run.identity.value,
					run.identity.value,
					PortableCountDomainOwnerKind.SESSION_COMPLETENESS,
				),
			)
		}
	}
	val graph = authenticated.graph
	val storedRoots = graph.roots.mapTo(linkedSetOf()) {
			coroutineContext.ensureActive()
			SessionRootKey(
				it.containerIdentity.value,
				it.productIdentity.value,
				it.ownerKind,
			)
		}
	require(storedRoots.containsAll(expectedRoots)) {
		"Imported Steps graph is missing retained run or fact ownership"
	}
	val extraRoots = graph.roots.filter { root ->
		coroutineContext.ensureActive()
		SessionRootKey(
			root.containerIdentity.value,
			root.productIdentity.value,
			root.ownerKind,
		) !in expectedRoots
	}
	if (extraRoots.isEmpty()) return
	val dao = importedPortableStepsCountDomainDao()
	val fences = extraRoots.map { it.ownerIdentity.value }.distinct().chunked(FENCE_QUERY_BATCH)
		.flatMap { dao.ownerFencesForFullClear(it) }
	val fencesByOwner = fences.associateBy { it.ownerKind to it.ownerIdentity }
	require(fencesByOwner.size == fences.size)
	val ownerRevisionsByRoot = graph.ownerRevisions.associateBy {
		coroutineContext.ensureActive()
		FullClearOwnerRevisionKey(
			it.ownerKind,
			it.ownerIdentity.value,
			it.ownerRevision,
		)
	}
	require(ownerRevisionsByRoot.size == graph.ownerRevisions.size)
	extraRoots.forEach { root ->
		coroutineContext.ensureActive()
		require(root.containerIdentity.value !in liveRunIds ||
			root.ownerKind == PortableCountDomainOwnerKind.SESSION_FACT
		) {
			"Imported Steps graph has an unfenced extra live-run root"
		}
		val owner = requireNotNull(
			ownerRevisionsByRoot[
				FullClearOwnerRevisionKey(
					root.ownerKind,
					root.ownerIdentity.value,
					root.ownerRevision,
				)
			],
		) {
			"Imported Steps graph extra root has no exact owner revision"
		}
		val fence = requireNotNull(
			fencesByOwner[root.ownerKind.name to root.ownerIdentity.value],
		) {
			"Imported Steps graph has an extra root without a terminal fence"
		}
		require(
			fence.scopeIdentity == owner.scopeIdentity.value &&
				fence.latestSourceRevision == owner.ownerRevision &&
				fence.latestOwnerEffectChecksum == owner.ownerEffectChecksum.value &&
				fence.productKind == authenticated.binding.productKind &&
				fence.productIdentity == authenticated.binding.productIdentity &&
				fence.graphIdentity == authenticated.binding.graphIdentity &&
				fence.fenceKind in SESSION_PRUNING_FENCE_KINDS &&
				fence.collectedDataEpoch == product.metadata.collectedDataEpoch,
		) {
			"Imported Steps graph extra root has conflicting terminal authority"
		}
	}
}

private data class SessionRootKey(
	val containerIdentity: String,
	val productIdentity: String,
	val ownerKind: PortableCountDomainOwnerKind,
)

private const val BINDING_FULL_CLEAR_PAGE_SIZE = 256
private const val AMBIENT_FULL_CLEAR_PAGE_SIZE = 256
private const val GRAPH_FULL_CLEAR_PAGE_SIZE = 64
private const val FILE_RECEIPT_FULL_CLEAR_PAGE_SIZE = 256
private const val SESSION_PRODUCT_REVISION = 1L
private const val FENCE_QUERY_BATCH = 400
private val SESSION_PRUNING_FENCE_KINDS = setOf(
	ImportedPortableStepsCountDomainOwnerFenceEntity.FENCE_RETENTION,
	ImportedPortableStepsCountDomainOwnerFenceEntity.FENCE_SELECTED_DELETE,
)
