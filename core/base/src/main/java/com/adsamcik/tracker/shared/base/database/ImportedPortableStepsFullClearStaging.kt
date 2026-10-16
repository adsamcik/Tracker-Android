package com.adsamcik.tracker.shared.base.database

import androidx.core.database.getStringOrNull
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainBindingEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainOwnerFenceEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableCountDomainIdentity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsFullClearStagingSchema
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

// One short-lived transaction staging owner shares its operation ID, SQLite tables, cardinality and cancellation checkpoint.
// Keeping its complete insert/reconcile/install/cleanup handshake together avoids a second coordinated staging lifetime.
@Suppress("LargeClass", "TooManyFunctions")
internal class AuthenticatedFullClearOwnerFenceStaging(
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
			arrayOf<Any?>(
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
			arrayOf<Any?>(operationId, productKind, productIdentity, limit),
		).use { cursor ->
			buildList {
				while (cursor.moveToNext()) {
					add(
						ImportedPortableStepsCountDomainBindingEntity(
							productKind = productKind,
							productIdentity = productIdentity,
							productRevision = cursor.getLong(cursor.getColumnIndexOrThrow("product_revision")),
							graphIdentity = cursor.getString(cursor.getColumnIndexOrThrow("graph_identity")),
							sourceSchemaVersion = cursor.getInt(cursor.getColumnIndexOrThrow("source_schema_version")),
							sourceReceiptIdentity =
								cursor.getStringOrNull(cursor.getColumnIndexOrThrow("source_receipt_identity")),
							sourceArchiveIdentity =
								cursor.getStringOrNull(cursor.getColumnIndexOrThrow("source_archive_identity")),
							sourceArchiveContentChecksum =
								cursor.getStringOrNull(cursor.getColumnIndexOrThrow("source_archive_content_checksum")),
						),
					)
				}
			}
		}
	}

	fun consumeAuthenticatedGraph(
		current: AuthenticatedImportedPortableGraphBinding,
		isStored: Boolean,
	) {
		if (isStored) {
			consumeBinding(current.binding)
		}
		add(
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

	fun consumeBinding(binding: ImportedPortableStepsCountDomainBindingEntity) {
		checkpoint()
		sqlite.compileStatement(
			"UPDATE $BINDING_TABLE SET consumed = 1 WHERE operation_id = ? AND product_kind = ? " +
				"AND product_identity = ? AND product_revision = ? AND graph_identity = ? " +
				"AND source_schema_version = ? AND " +
				"source_receipt_identity IS ? AND source_archive_identity IS ? AND " +
				"source_archive_content_checksum IS ? AND consumed = 0",
		).use { statement ->
			SimpleSQLiteQuery.bind(
				statement,
				arrayOf<Any?>(
					operationId,
					binding.productKind,
					binding.productIdentity,
					binding.productRevision,
					binding.graphIdentity,
					binding.sourceSchemaVersion.toLong(),
					binding.sourceReceiptIdentity,
					binding.sourceArchiveIdentity,
					binding.sourceArchiveContentChecksum,
				),
			)
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
			arrayOf<Any?>(
				operationId,
				product.metadata.identity,
				product.metadata.contentChecksum,
				StepsPortableFormatV1.FORMAT,
				binding.sourceSchemaVersion,
				binding.graphIdentity,
				binding.sourceReceiptIdentity,
				binding.sourceArchiveContentChecksum,
				if (hasStoredBinding) {
					1
				} else {
					0
				},
				0,
			),
		)
	}

	fun sessionProducts(
		productIdentities: List<String>,
	): Map<String, StagedFullClearSessionProduct> {
		val identities = productIdentities.distinct()
		if (identities.isEmpty()) {
			return emptyMap()
		}
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
						productIdentity = cursor.getString(cursor.getColumnIndexOrThrow("product_identity")),
						contentChecksum = cursor.getString(cursor.getColumnIndexOrThrow("content_checksum")),
						sourceFormat = cursor.getString(cursor.getColumnIndexOrThrow("source_format")),
						sourceSchemaVersion = cursor.getInt(cursor.getColumnIndexOrThrow("source_schema_version")),
						graphIdentity = cursor.getString(cursor.getColumnIndexOrThrow("graph_identity")),
						sourceReceiptIdentity =
							cursor.getStringOrNull(cursor.getColumnIndexOrThrow("source_receipt_identity")),
						sourceArchiveContentChecksum =
							cursor.getStringOrNull(cursor.getColumnIndexOrThrow("source_archive_content_checksum")),
						hasStoredBinding = cursor.getInt(cursor.getColumnIndexOrThrow("has_stored_binding")) == 1,
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
			SimpleSQLiteQuery.bind(
				statement,
				arrayOf<Any?>(operationId, productIdentity, receiptIdentity, archiveContentChecksum),
			)
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
			if (page.isEmpty()) {
				break
			}
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
		val boundProductIdentity = if (graph.isBound) {
			requireNotNull(graph.productIdentity)
		} else {
			null
		}
		val rank = FullClearStagedAppearanceRank(
			ownerRevision = root.ownerRevision,
			graphRevision = graph.graphRevision ?: 0L,
			isBound = graph.isBound,
			compareProductIdentity = graph.productIdentity ?: root.productIdentity.value,
			graphIdentity = graph.graphIdentity,
		)
		val stored = owner(ownerKind, ownerIdentity)
		if (stored == null) {
			insertOwnerAppearance(graph, root, first, rank, boundProductIdentity)
		} else {
			reconcileOwnerAppearance(stored, graph, root, first, rank, boundProductIdentity)
		}

		stageOwnerLineage(ownerKind, ownerIdentity, lineage, isLegacyAmbient)
	}

	private fun insertOwnerAppearance(
		graph: AuthenticatedFullClearGraphAppearance,
		root: PortableCountDomainRootV2,
		first: PortableCountDomainOwnerRevisionV2,
		rank: FullClearStagedAppearanceRank,
		boundProductIdentity: String?,
	) {
		val ownerKind = root.ownerKind.name
		val ownerIdentity = root.ownerIdentity.value
		cardinality.recordOwner()
		sqlite.execSQL(
			"INSERT INTO $OWNER_TABLE (" +
				"operation_id, owner_kind, owner_identity, scope_identity, container_identity, " +
				"root_product_identity, product_kind, bound_product_identity, " +
				"latest_graph_identity, latest_owner_revision, latest_graph_revision, " +
				"latest_is_bound, latest_compare_product_identity, explicit_lineage_length" +
				") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)",
			arrayOf<Any?>(
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
				if (rank.isBound) {
					1
				} else {
					0
				},
				rank.compareProductIdentity,
			),
		)
	}

	private fun reconcileOwnerAppearance(
		stored: StagedFullClearOwner,
		graph: AuthenticatedFullClearGraphAppearance,
		root: PortableCountDomainRootV2,
		first: PortableCountDomainOwnerRevisionV2,
		rank: FullClearStagedAppearanceRank,
		boundProductIdentity: String?,
	) {
		val ownerKind = root.ownerKind.name
		val ownerIdentity = root.ownerIdentity.value
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
				arrayOf<Any?>(
					rank.graphIdentity,
					rank.ownerRevision,
					rank.graphRevision,
					if (rank.isBound) {
						1
					} else {
						0
					},
					rank.compareProductIdentity,
					operationId,
					ownerKind,
					ownerIdentity,
				),
			)
		}
	}

	private fun stageOwnerLineage(
		ownerKind: String,
		ownerIdentity: String,
		lineage: List<PortableCountDomainOwnerRevisionV2>,
		isLegacyAmbient: Boolean,
	) {
		if (isLegacyAmbient) {
			require(lineage.size == 1)
		}
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
				arrayOf<Any?>(
					operationId,
					revision.ownerKind.name,
					revision.ownerIdentity.value,
					revision.ownerRevision,
					revision.scopeIdentity.value,
					revision.operation.name,
					revision.receiptIdentity?.value,
					revision.ownerEffectChecksum.value,
					revision.linkedAtMs,
					if (isLegacyAmbient) {
						1
					} else {
						0
					},
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
					arrayOf<Any?>(
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
				arrayOf<Any?>(
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
				arrayOf<Any?>(lineage.size, operationId, ownerKind, ownerIdentity),
			)
		}
	}

	private fun requireLegacyRevisionsCoveredByExplicitLineage(owner: StagedFullClearOwner) {
		if (owner.explicitLineageLength == 0) {
			return
		}
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
			if (!cursor.moveToFirst()) {
				null
			} else {
				cursor.stagedFullClearOwner(ownerKind, ownerIdentity)
			}
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
			arrayOf<Any?>(
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
						cursor.stagedFullClearOwner(
							cursor.getString(cursor.getColumnIndexOrThrow("owner_kind")),
							cursor.getString(cursor.getColumnIndexOrThrow("owner_identity")),
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
		arrayOf<Any?>(operationId, ownerKind, ownerIdentity, ownerRevision),
	).use { cursor ->
		if (!cursor.moveToFirst()) {
			null
		} else {
			StagedFullClearRevision(
			ownerKind = ownerKind,
			ownerIdentity = ownerIdentity,
			ownerRevision = ownerRevision,
			scopeIdentity = cursor.getString(cursor.getColumnIndexOrThrow("scope_identity")),
			operation = cursor.getString(cursor.getColumnIndexOrThrow("operation")),
			receiptIdentity = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("receipt_identity")),
			ownerEffectChecksum = cursor.getString(cursor.getColumnIndexOrThrow("owner_effect_checksum")),
			sourceLinkedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("source_linked_at_ms")),
			isLegacyAmbient = cursor.getInt(cursor.getColumnIndexOrThrow("legacy_ambient")) == 1,
		)
		}
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
		if (!cursor.moveToFirst()) {
			null
		} else {
			StagedFullClearRevision(
			ownerKind = ownerKind,
			ownerIdentity = ownerIdentity,
			ownerRevision = cursor.getLong(cursor.getColumnIndexOrThrow("owner_revision")),
			scopeIdentity = cursor.getString(cursor.getColumnIndexOrThrow("scope_identity")),
			operation = cursor.getString(cursor.getColumnIndexOrThrow("operation")),
			receiptIdentity = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("receipt_identity")),
			ownerEffectChecksum = cursor.getString(cursor.getColumnIndexOrThrow("owner_effect_checksum")),
			sourceLinkedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("source_linked_at_ms")),
			isLegacyAmbient = cursor.getInt(cursor.getColumnIndexOrThrow("legacy_ambient")) == 1,
		)
		}
	}

	private fun explicitRevision(
		ownerKind: String,
		ownerIdentity: String,
		ordinal: Int,
	): Long? = sqlite.query(
		"SELECT owner_revision FROM $EXPLICIT_TABLE " +
			"WHERE operation_id = ? AND owner_kind = ? " +
			"AND owner_identity = ? AND lineage_ordinal = ?",
		arrayOf<Any?>(operationId, ownerKind, ownerIdentity, ordinal),
	).use { cursor -> if (cursor.moveToFirst()) {
		cursor.getLong(0)
	} else {
		null
	} }

	private fun explicitContainsRevision(
		ownerKind: String,
		ownerIdentity: String,
		ownerRevision: Long,
	): Boolean = sqlite.query(
		"SELECT 1 FROM $EXPLICIT_TABLE WHERE operation_id = ? " +
			"AND owner_kind = ? AND owner_identity = ? " +
			"AND owner_revision = ? LIMIT 1",
		arrayOf<Any?>(operationId, ownerKind, ownerIdentity, ownerRevision),
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
	val rootsByOwner = canonicalFullClearRootsByOwner(roots, checkpoint)

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

private fun canonicalFullClearRootsByOwner(
	roots: List<PortableCountDomainRootV2>,
	checkpoint: () -> Unit,
): HashMap<FullClearOwnerKey, PortableCountDomainRootV2> {
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
	return rootsByOwner
}

private data class FullClearOwnerKey(
	val ownerKind: PortableCountDomainOwnerKind,
	val ownerIdentity: String,
)


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

internal data class StagedFullClearSessionProduct(
	val productIdentity: String,
	val contentChecksum: String,
	val sourceFormat: String,
	val sourceSchemaVersion: Int,
	val graphIdentity: String,
	val sourceReceiptIdentity: String?,
	val sourceArchiveContentChecksum: String?,
	val hasStoredBinding: Boolean,
)

private fun android.database.Cursor.stagedFullClearOwner(
	ownerKind: String,
	ownerIdentity: String,
): StagedFullClearOwner = StagedFullClearOwner(
	ownerKind = ownerKind,
	ownerIdentity = ownerIdentity,
	scopeIdentity = getString(getColumnIndexOrThrow("scope_identity")),
	containerIdentity = getString(getColumnIndexOrThrow("container_identity")),
	rootProductIdentity = getString(getColumnIndexOrThrow("root_product_identity")),
	productKind = getString(getColumnIndexOrThrow("product_kind")),
	boundProductIdentity = getStringOrNull(getColumnIndexOrThrow("bound_product_identity")),
	latestGraphIdentity = getString(getColumnIndexOrThrow("latest_graph_identity")),
	latestOwnerRevision = getLong(getColumnIndexOrThrow("latest_owner_revision")),
	rank = FullClearStagedAppearanceRank(
		ownerRevision = getLong(getColumnIndexOrThrow("latest_owner_revision")),
		graphRevision = getLong(getColumnIndexOrThrow("latest_graph_revision")),
		isBound = getInt(getColumnIndexOrThrow("latest_is_bound")) == 1,
		compareProductIdentity =
			getString(getColumnIndexOrThrow("latest_compare_product_identity")),
		graphIdentity = getString(getColumnIndexOrThrow("latest_graph_identity")),
	),
	explicitLineageLength = getInt(getColumnIndexOrThrow("explicit_lineage_length")),
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
		{ if (it.isBound) {
			1
		} else {
			0
		} },
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


internal const val FILE_RECEIPT_FULL_CLEAR_PAGE_SIZE = 256

internal const val SESSION_PRODUCT_REVISION = 1L
