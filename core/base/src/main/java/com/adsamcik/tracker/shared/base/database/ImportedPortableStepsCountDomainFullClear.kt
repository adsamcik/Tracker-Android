package com.adsamcik.tracker.shared.base.database

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteProgram
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainBindingEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainGraphEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainOwnerFenceEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableCountDomainIdentity
import com.adsamcik.tracker.shared.base.database.dao.ImportedAmbientStepsDao
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedReader
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedTraversal
import com.adsamcik.tracker.shared.base.database.steps.imported.RetainedImportedStepsEntry
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainGraphV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainOperation
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainOwnerKind
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainOwnerRevisionV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainRootV2
import com.adsamcik.tracker.shared.model.steps.portable.StepsPortableFormatV1

/**
 * Converts every live imported portable owner into a value-free terminal fence before full clear.
 */
internal suspend fun AppDatabase.preserveImportedPortableCountDomainFullClearFences(
	oldCollectedDataEpoch: Long,
	newCollectedDataEpoch: Long,
	fencedAtMs: Long,
) {
	require(oldCollectedDataEpoch >= 0L)
	require(newCollectedDataEpoch > oldCollectedDataEpoch)
	require(fencedAtMs >= 0L)
	reconcileImportedPortableLegacyGraphsBeforeFullClear(oldCollectedDataEpoch)
	val dao = importedPortableStepsCountDomainDao()
	val staging = AuthenticatedFullClearOwnerFenceStaging(
		sqlite = openHelper.writableDatabase,
		fenceKind = ImportedPortableStepsCountDomainOwnerFenceEntity.FENCE_FULL_CLEAR,
		collectedDataEpoch = newCollectedDataEpoch,
		fencedAtMs = fencedAtMs,
		maximumOwnerCount = null,
		maximumOwnerRevisionCount = null,
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

internal fun AppDatabase.stageAuthenticatedPortableOwnerFencesForFullClear(
	graphs: Sequence<AuthenticatedFullClearGraphAppearance>,
	fenceKind: String,
	collectedDataEpoch: Long,
	fencedAtMs: Long,
	maximumOwnerCount: Int,
	maximumOwnerRevisionCount: Int,
) {
	require(collectedDataEpoch >= 0L)
	require(fencedAtMs >= 0L)
	require(maximumOwnerCount > 0)
	require(maximumOwnerRevisionCount > 0)
	val staging = AuthenticatedFullClearOwnerFenceStaging(
		openHelper.writableDatabase,
		fenceKind,
		collectedDataEpoch,
		fencedAtMs,
		maximumOwnerCount.toLong(),
		maximumOwnerRevisionCount.toLong(),
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
	private val fenceKind: String,
	private val collectedDataEpoch: Long,
	private val fencedAtMs: Long,
	maximumOwnerCount: Long?,
	maximumOwnerRevisionCount: Long?,
) {
	private val cardinality = FullClearStagingCardinality(
		maximumOwnerCount = maximumOwnerCount,
		maximumOwnerRevisionCount = maximumOwnerRevisionCount,
	)

	init {
		dropTables()
		try {
			sqlite.execSQL(
				"""
				CREATE TEMP TABLE $BINDING_TABLE (
					product_kind TEXT NOT NULL,
					product_identity TEXT NOT NULL,
					product_revision INTEGER NOT NULL,
					graph_identity TEXT NOT NULL,
					source_schema_version INTEGER NOT NULL,
					source_receipt_identity TEXT,
					source_archive_identity TEXT,
					source_archive_content_checksum TEXT,
					consumed INTEGER NOT NULL DEFAULT 0,
					PRIMARY KEY(product_kind, product_identity, product_revision)
				)
				""".trimIndent(),
			)
			sqlite.execSQL(
				"CREATE INDEX $BINDING_GRAPH_INDEX ON $BINDING_TABLE(graph_identity, consumed)",
			)
			sqlite.execSQL(
				"""
				CREATE TEMP TABLE $SESSION_PRODUCT_TABLE (
					product_identity TEXT NOT NULL PRIMARY KEY,
					content_checksum TEXT NOT NULL,
					source_format TEXT NOT NULL,
					source_schema_version INTEGER NOT NULL,
					graph_identity TEXT NOT NULL,
					source_receipt_identity TEXT,
					source_archive_content_checksum TEXT,
					has_stored_binding INTEGER NOT NULL,
					source_receipt_observed INTEGER NOT NULL DEFAULT 0
				)
				""".trimIndent(),
			)
			sqlite.execSQL(
				"""
				CREATE TEMP TABLE $OWNER_TABLE (
					owner_kind TEXT NOT NULL,
					owner_identity TEXT NOT NULL,
					scope_identity TEXT NOT NULL,
					container_identity TEXT NOT NULL,
					root_product_identity TEXT NOT NULL,
					product_kind TEXT NOT NULL,
					bound_product_identity TEXT,
					latest_graph_identity TEXT NOT NULL,
					latest_owner_revision INTEGER NOT NULL,
					latest_graph_revision INTEGER NOT NULL,
					latest_is_bound INTEGER NOT NULL,
					latest_compare_product_identity TEXT NOT NULL,
					explicit_lineage_length INTEGER NOT NULL,
					PRIMARY KEY(owner_kind, owner_identity)
				)
				""".trimIndent(),
			)
			sqlite.execSQL(
				"""
				CREATE TEMP TABLE $REVISION_TABLE (
					owner_kind TEXT NOT NULL,
					owner_identity TEXT NOT NULL,
					owner_revision INTEGER NOT NULL,
					scope_identity TEXT NOT NULL,
					operation TEXT NOT NULL,
					receipt_identity TEXT,
					owner_effect_checksum TEXT NOT NULL,
					source_linked_at_ms INTEGER NOT NULL,
					legacy_ambient INTEGER NOT NULL,
					PRIMARY KEY(owner_kind, owner_identity, owner_revision)
				)
				""".trimIndent(),
			)
			sqlite.execSQL(
				"""
				CREATE TEMP TABLE $EXPLICIT_TABLE (
					owner_kind TEXT NOT NULL,
					owner_identity TEXT NOT NULL,
					lineage_ordinal INTEGER NOT NULL,
					owner_revision INTEGER NOT NULL,
					PRIMARY KEY(owner_kind, owner_identity, lineage_ordinal),
					UNIQUE(owner_kind, owner_identity, owner_revision)
				)
				""".trimIndent(),
			)
		} catch (failure: Exception) {
			dropTables()
			throw failure
		}
	}

	fun addBinding(binding: ImportedPortableStepsCountDomainBindingEntity) {
		sqlite.execSQL(
			"INSERT INTO $BINDING_TABLE (" +
				"product_kind, product_identity, product_revision, graph_identity, " +
				"source_schema_version, source_receipt_identity, source_archive_identity, " +
				"source_archive_content_checksum) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
			arrayOf(
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
		return sqlite.query(
			"SELECT product_revision, graph_identity, source_schema_version, " +
				"source_receipt_identity, source_archive_identity, " +
				"source_archive_content_checksum FROM $BINDING_TABLE " +
				"WHERE product_kind = ? AND product_identity = ? " +
				"ORDER BY product_revision LIMIT ?",
			arrayOf(productKind, productIdentity, limit),
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
		sqlite.compileStatement(
			"UPDATE $BINDING_TABLE SET consumed = 1 WHERE product_kind = ? " +
				"AND product_identity = ? AND product_revision = ? AND graph_identity = ? " +
				"AND source_schema_version = ? AND " +
				"source_receipt_identity IS ? AND source_archive_identity IS ? AND " +
				"source_archive_content_checksum IS ? AND consumed = 0",
		).use { statement ->
			statement.bindString(1, binding.productKind)
			statement.bindString(2, binding.productIdentity)
			statement.bindLong(3, binding.productRevision)
			statement.bindString(4, binding.graphIdentity)
			statement.bindLong(5, binding.sourceSchemaVersion.toLong())
			statement.bindNullableString(6, binding.sourceReceiptIdentity)
			statement.bindNullableString(7, binding.sourceArchiveIdentity)
			statement.bindNullableString(8, binding.sourceArchiveContentChecksum)
			require(statement.executeUpdateDelete() == 1) {
				"Imported portable graph binding was missing, duplicated, or changed"
			}
		}
	}

	fun requireEveryBindingConsumed() {
		val remaining = sqlite.query(
			"SELECT COUNT(*) FROM $BINDING_TABLE WHERE consumed = 0",
		).use { cursor ->
			check(cursor.moveToFirst())
			cursor.getLong(0)
		}
		require(remaining == 0L) {
			"Imported portable graph binding was not consumed by a product"
		}
	}

	fun bindingCountForGraph(graphIdentity: String): Long = sqlite.query(
		"SELECT COUNT(*) FROM $BINDING_TABLE WHERE graph_identity = ?",
		arrayOf(graphIdentity),
	).use { cursor ->
		check(cursor.moveToFirst())
		cursor.getLong(0)
	}

	fun isGraphAuthenticated(graphIdentity: String): Boolean = sqlite.query(
		"SELECT 1 FROM $BINDING_TABLE WHERE graph_identity = ? AND consumed = 1 LIMIT 1",
		arrayOf(graphIdentity),
	).use { cursor -> cursor.moveToFirst() }

	fun addSessionProduct(
		product: RetainedImportedStepsEntry,
		authenticated: AuthenticatedImportedPortableGraphBinding,
		hasStoredBinding: Boolean,
	) {
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
				"product_identity, content_checksum, source_format, source_schema_version, " +
				"graph_identity, source_receipt_identity, source_archive_content_checksum, " +
				"has_stored_binding, source_receipt_observed) " +
				"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
			arrayOf(
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
		val placeholders = identities.joinToString(separator = ",") { "?" }
		return sqlite.query(
			"SELECT product_identity, content_checksum, source_format, source_schema_version, " +
				"graph_identity, source_receipt_identity, source_archive_content_checksum, " +
				"has_stored_binding FROM $SESSION_PRODUCT_TABLE " +
				"WHERE product_identity IN ($placeholders)",
			identities.toTypedArray(),
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
		sqlite.compileStatement(
			"UPDATE $SESSION_PRODUCT_TABLE SET source_receipt_observed = 1 " +
				"WHERE product_identity = ? AND source_receipt_identity = ? " +
				"AND source_archive_content_checksum = ?",
		).use { statement ->
			statement.bindString(1, productIdentity)
			statement.bindString(2, receiptIdentity)
			statement.bindString(3, archiveContentChecksum)
			require(statement.executeUpdateDelete() == 1) {
				"Imported Steps source receipt conflicts with staged product provenance"
			}
		}
	}

	fun requireEverySourceReceiptObserved() {
		val missing = sqlite.query(
			"SELECT COUNT(*) FROM $SESSION_PRODUCT_TABLE " +
				"WHERE source_receipt_identity IS NOT NULL AND source_receipt_observed = 0",
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
		graph.graph.roots.forEach { root ->
			val lineage = graph.graph.ownerRevisions.filter {
				it.ownerKind == root.ownerKind && it.ownerIdentity == root.ownerIdentity
			}
			check(lineage.isNotEmpty() && lineage.last().ownerRevision == root.ownerRevision)
			addOwnerAppearance(graph, root, lineage)
		}
	}

	fun install(dao: com.adsamcik.tracker.shared.base.database.dao.ImportedPortableStepsCountDomainDao) {
		var afterKind: String? = null
		var afterIdentity: String? = null
		var installedOwnerCount = 0L
		while (true) {
			val page = ownerPage(afterKind, afterIdentity)
			if (page.isEmpty()) break
			page.forEach { owner ->
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
		dropTables()
	}

	private fun addOwnerAppearance(
		graph: AuthenticatedFullClearGraphAppearance,
		root: PortableCountDomainRootV2,
		lineage: List<PortableCountDomainOwnerRevisionV2>,
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
					"owner_kind, owner_identity, scope_identity, container_identity, " +
					"root_product_identity, product_kind, bound_product_identity, " +
					"latest_graph_identity, latest_owner_revision, latest_graph_revision, " +
					"latest_is_bound, latest_compare_product_identity, explicit_lineage_length" +
					") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)",
				arrayOf(
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
							"WHERE owner_kind = ? AND owner_identity = ?",
						arrayOf(boundProductIdentity, ownerKind, ownerIdentity),
					)
				}
			}
			if (rank > stored.rank) {
				sqlite.execSQL(
					"UPDATE $OWNER_TABLE SET latest_graph_identity = ?, " +
						"latest_owner_revision = ?, latest_graph_revision = ?, " +
						"latest_is_bound = ?, latest_compare_product_identity = ? " +
						"WHERE owner_kind = ? AND owner_identity = ?",
					arrayOf(
						rank.graphIdentity,
						rank.ownerRevision,
						rank.graphRevision,
						if (rank.isBound) 1 else 0,
						rank.compareProductIdentity,
						ownerKind,
						ownerIdentity,
					),
				)
			}
		}

		val isLegacyAmbient = graph.isLegacyAmbientAppearance(lineage)
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
					"owner_kind, owner_identity, owner_revision, scope_identity, operation, " +
					"receipt_identity, owner_effect_checksum, source_linked_at_ms, legacy_ambient" +
					") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
				arrayOf(
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
						"WHERE owner_kind = ? AND owner_identity = ? AND owner_revision = ?",
					arrayOf(
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
					"owner_kind, owner_identity, lineage_ordinal, owner_revision" +
					") VALUES (?, ?, ?, ?)",
				arrayOf(ownerKind, ownerIdentity, ordinal, lineage[ordinal].ownerRevision),
			)
		}
		if (lineage.size > storedLength) {
			sqlite.execSQL(
				"UPDATE $OWNER_TABLE SET explicit_lineage_length = ? " +
					"WHERE owner_kind = ? AND owner_identity = ?",
				arrayOf(lineage.size, ownerKind, ownerIdentity),
			)
		}
	}

	private fun requireLegacyRevisionsCoveredByExplicitLineage(owner: StagedFullClearOwner) {
		if (owner.explicitLineageLength == 0) return
		val missingCount = sqlite.query(
			"SELECT COUNT(*) FROM $REVISION_TABLE AS revision " +
				"WHERE revision.owner_kind = ? AND revision.owner_identity = ? " +
				"AND revision.legacy_ambient = 1 AND NOT EXISTS (" +
				"SELECT 1 FROM $EXPLICIT_TABLE AS lineage " +
				"WHERE lineage.owner_kind = revision.owner_kind " +
				"AND lineage.owner_identity = revision.owner_identity " +
				"AND lineage.owner_revision = revision.owner_revision)",
			arrayOf(owner.ownerKind, owner.ownerIdentity),
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
				"WHERE owner_kind = ? AND owner_identity = ?",
			arrayOf(ownerKind, ownerIdentity),
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
				"FROM $OWNER_TABLE ORDER BY owner_kind, owner_identity LIMIT ?"
		} else {
			"SELECT owner_kind, owner_identity, scope_identity, container_identity, " +
				"root_product_identity, product_kind, bound_product_identity, " +
				"latest_graph_identity, latest_owner_revision, latest_graph_revision, " +
				"latest_is_bound, latest_compare_product_identity, explicit_lineage_length " +
				"FROM $OWNER_TABLE WHERE owner_kind > ? OR " +
				"(owner_kind = ? AND owner_identity > ?) " +
				"ORDER BY owner_kind, owner_identity LIMIT ?"
		}
		val arguments = if (afterKind == null) {
			arrayOf<Any>(OWNER_INSTALL_PAGE_SIZE)
		} else {
			arrayOf(afterKind, afterKind, requireNotNull(afterIdentity), OWNER_INSTALL_PAGE_SIZE)
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
			"WHERE owner_kind = ? AND owner_identity = ? AND owner_revision = ?",
		arrayOf(ownerKind, ownerIdentity, ownerRevision),
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
			"WHERE owner_kind = ? AND owner_identity = ? ORDER BY owner_revision DESC LIMIT 1",
		arrayOf(ownerKind, ownerIdentity),
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
			"WHERE owner_kind = ? AND owner_identity = ? AND lineage_ordinal = ?",
		arrayOf(ownerKind, ownerIdentity, ordinal),
	).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }

	private fun explicitContainsRevision(
		ownerKind: String,
		ownerIdentity: String,
		ownerRevision: Long,
	): Boolean = sqlite.query(
		"SELECT 1 FROM $EXPLICIT_TABLE WHERE owner_kind = ? AND owner_identity = ? " +
			"AND owner_revision = ? LIMIT 1",
		arrayOf(ownerKind, ownerIdentity, ownerRevision),
	).use { it.moveToFirst() }

	private fun tableRowCount(table: String): Long = sqlite.query(
		"SELECT COUNT(*) FROM $table",
	).use { cursor ->
		check(cursor.moveToFirst())
		cursor.getLong(0)
	}

	private fun dropTables() {
		sqlite.execSQL("DROP TABLE IF EXISTS $EXPLICIT_TABLE")
		sqlite.execSQL("DROP TABLE IF EXISTS $REVISION_TABLE")
		sqlite.execSQL("DROP TABLE IF EXISTS $OWNER_TABLE")
		sqlite.execSQL("DROP TABLE IF EXISTS $SESSION_PRODUCT_TABLE")
		sqlite.execSQL("DROP TABLE IF EXISTS $BINDING_TABLE")
	}

	private companion object {
		const val BINDING_TABLE = "imported_steps_full_clear_binding_stage"
		const val BINDING_GRAPH_INDEX = "imported_steps_full_clear_binding_graph_stage"
		const val SESSION_PRODUCT_TABLE = "imported_steps_full_clear_session_product_stage"
		const val OWNER_TABLE = "imported_steps_full_clear_owner_stage"
		const val REVISION_TABLE = "imported_steps_full_clear_owner_revision_stage"
		const val EXPLICIT_TABLE = "imported_steps_full_clear_explicit_lineage_stage"
		const val OWNER_INSTALL_PAGE_SIZE = 256
	}
}

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

private fun AuthenticatedFullClearGraphAppearance.isLegacyAmbientAppearance(
	lineage: List<PortableCountDomainOwnerRevisionV2>,
): Boolean {
	if (productKind != ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY) {
		return false
	}
	if (sourceSchemaVersion != null) {
		return sourceSchemaVersion == AmbientStepsPortableFormatV1.SCHEMA_VERSION
	}
	return graph.receipts.isEmpty() &&
		graph.completenessMarkers.isEmpty() &&
		graph.ownerRevisions.groupBy { it.ownerKind to it.ownerIdentity }.values.all {
			it.size == 1 &&
				it.single().operation == PortableCountDomainOperation.UNPROVEN &&
				it.single().receiptIdentity == null &&
				it.single().linkedAtMs == 0L
		} &&
		lineage.size == 1
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
		val page = ambientDao.fullClearDayCandidatePage(afterDayId, AMBIENT_FULL_CLEAR_PAGE_SIZE)
		if (page.isEmpty()) break
		page.forEach { candidate ->
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

private fun AppDatabase.requireSessionGraphCoversRetainedProduct(
	authenticated: AuthenticatedImportedPortableGraphBinding,
	product: RetainedImportedStepsEntry,
) {
	val liveRunIds = product.runs.mapTo(linkedSetOf()) { it.identity }
	val expectedRoots = buildSet {
		product.portableRunsById.values.forEach { run ->
			run.facts.forEach { fact ->
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
	extraRoots.forEach { root ->
		require(root.containerIdentity.value !in liveRunIds ||
			root.ownerKind == PortableCountDomainOwnerKind.SESSION_FACT
		) {
			"Imported Steps graph has an unfenced extra live-run root"
		}
		val owner = graph.ownerRevisions.single {
			it.ownerKind == root.ownerKind &&
				it.ownerIdentity == root.ownerIdentity &&
				it.ownerRevision == root.ownerRevision
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
