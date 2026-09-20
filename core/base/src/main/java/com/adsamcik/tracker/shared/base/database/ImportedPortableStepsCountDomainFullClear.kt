package com.adsamcik.tracker.shared.base.database

import androidx.sqlite.db.SupportSQLiteDatabase
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainBindingEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainGraphEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainOwnerFenceEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableCountDomainIdentity
import com.adsamcik.tracker.shared.base.database.dao.ImportedAmbientStepsDao
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedRead
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedReader
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
		maximumOwnerCount = MAX_FULL_CLEAR_OWNER_FENCES,
		maximumOwnerRevisionCount = MAX_FULL_CLEAR_OWNER_REVISIONS,
	)
	try {
		val bindings = dao.allBindingsForFullClear(MAX_FULL_CLEAR_BINDINGS + 1)
		require(bindings.size <= MAX_FULL_CLEAR_BINDINGS)
		require(bindings.distinct().size == bindings.size)
		val consumedBindings = linkedSetOf<ImportedPortableStepsCountDomainBindingEntity>()
		val authenticatedGraphIdentities = linkedSetOf<String>()
		val consumeAuthenticatedGraph:
			(AuthenticatedImportedPortableGraphBinding, Boolean) -> Unit = { current, isStored ->
			if (isStored) authenticatedGraphIdentities += current.binding.graphIdentity
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
		val sessionBindings = bindings.filter {
			it.productKind == ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY
		}.associateBy { it.productIdentity }
		require(sessionBindings.size == bindings.count {
			it.productKind == ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY
		})
		authenticatedImportedSessionBindingsForFullClear(
			sessionBindings,
			consumedBindings,
			consumeAuthenticatedGraph,
			staging,
		)
		val ambientBindings = bindings.filter {
			it.productKind == ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY
		}.groupBy { it.productIdentity }
		authenticatedImportedAmbientBindingsForFullClear(
			oldCollectedDataEpoch,
			ambientBindings,
			consumedBindings,
			consumeAuthenticatedGraph,
		)
		require(consumedBindings == bindings.toSet())
		require(authenticatedGraphIdentities == bindings.mapTo(linkedSetOf()) { it.graphIdentity })
		authenticateAndFoldOrphanPortableGraphsForFullClear(
			authenticatedGraphIdentities = authenticatedGraphIdentities,
			bindings = bindings,
		) { orphan ->
			staging.add(orphan)
		}
		authenticateAllPortableSessionFileReceiptsForFullClear(
			sessionBindings = sessionBindings,
			sessionProducts = staging,
			authenticatedGraphIdentities = authenticatedGraphIdentities,
		)
		staging.install(dao)
		dao.deleteAllBindingsForFullClear()
		dao.deleteAllGraphsForFullClear()
		dao.deleteAllFileReceiptsForFullClear()
	} finally {
		staging.close()
	}
}

private suspend fun AppDatabase.authenticatedImportedSessionBindingsForFullClear(
	bindingsByEntry: Map<String, ImportedPortableStepsCountDomainBindingEntity>,
	consumedBindings: MutableSet<ImportedPortableStepsCountDomainBindingEntity>,
	consume: (AuthenticatedImportedPortableGraphBinding, Boolean) -> Unit,
	staging: AuthenticatedFullClearOwnerFenceStaging,
) {
	val reader = ImportedStepsRetainedReader(this)
	var beforeStartTimeMs: Long? = null
	var beforeIdentity: String? = null
	var entryCount = 0
	while (true) {
		val page = importedStepsDao().entryPage(
			beforeStartTimeMs,
			beforeIdentity,
			SESSION_FULL_CLEAR_PAGE_SIZE,
		)
		if (page.isEmpty()) break
		require(page.size <= SESSION_FULL_CLEAR_PAGE_SIZE)
		entryCount = Math.addExact(entryCount, page.size)
		require(entryCount <= MAX_FULL_CLEAR_BINDINGS)
		val retained = when (
			val read = reader.readEntriesForRetentionInTransaction(page.map { it.identity })
		) {
			is ImportedStepsRetainedRead.Ready -> {
				require(read.unverifiableEntries.isEmpty())
				require(read.entries.map { it.metadata.identity }.toSet() ==
					page.map { it.identity }.toSet())
				read.entries.associateBy { it.metadata.identity }
			}
			is ImportedStepsRetainedRead.Unverifiable -> {
				error("Imported Steps product is unverifiable during full clear: ${read.reason}")
			}
		}
		page.forEach { entry ->
			val product = requireNotNull(retained[entry.identity])
			val binding = bindingsByEntry[entry.identity]
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
				consumedBindings += binding
				loadAuthenticatedImportedSessionCountDomainBindingForFullClear(binding, product)
			}
			requireSessionGraphCoversRetainedProduct(authenticated, product)
			staging.addSessionProduct(product, authenticated, binding != null)
			consume(authenticated, binding != null)
		}
		beforeStartTimeMs = page.last().startTimeMs
		beforeIdentity = page.last().identity
		if (page.size < SESSION_FULL_CLEAR_PAGE_SIZE) break
	}
}

private suspend fun AppDatabase.authenticatedImportedAmbientBindingsForFullClear(
	oldCollectedDataEpoch: Long,
	bindingsByDay: Map<String, List<ImportedPortableStepsCountDomainBindingEntity>>,
	consumedBindings: MutableSet<ImportedPortableStepsCountDomainBindingEntity>,
	consume: (AuthenticatedImportedPortableGraphBinding, Boolean) -> Unit,
) {
	val ambientDao = importedAmbientStepsDao()
	var afterDayId: String? = null
	var dayCount = 0
	while (true) {
		val page = ambientDao.fullClearDayCandidatePage(afterDayId, AMBIENT_FULL_CLEAR_PAGE_SIZE)
		if (page.isEmpty()) break
		require(page.size <= AMBIENT_FULL_CLEAR_PAGE_SIZE)
		dayCount = Math.addExact(dayCount, page.size)
		require(dayCount <= MAX_FULL_CLEAR_BINDINGS)
		page.forEach { candidate ->
			val lineage = ambientDao.loadAuthenticatedAmbientStepsLineageForFullClear(
				candidate,
				oldCollectedDataEpoch,
			)
			val storedBindings = bindingsByDay[candidate.dayIdentity].orEmpty()
			val graphLineage = if (storedBindings.isEmpty()) {
				reconstructGraphlessLegacyAmbientLineage(lineage).also {
					requireGraphlessLegacyAmbientProvenance(lineage, it)
				}
			} else {
				loadAuthenticatedImportedAmbientStepsGraphLineageForFullClear(lineage).also {
					require(it.map(AuthenticatedImportedAmbientStepsGraphRevision::binding) ==
						storedBindings)
					consumedBindings += storedBindings
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
	authenticatedGraphIdentities: Set<String>,
	bindings: List<ImportedPortableStepsCountDomainBindingEntity>,
	consume: (AuthenticatedFullClearGraphAppearance) -> Unit,
) {
	val dao = importedPortableStepsCountDomainDao()
	val bindingsByGraph = bindings.groupBy { it.graphIdentity }
	val remainingBoundGraphs = authenticatedGraphIdentities.toMutableSet()
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
			if (graphRow.graphIdentity in authenticatedGraphIdentities) {
				require(bindingsByGraph[graphRow.graphIdentity].orEmpty().isNotEmpty())
				remainingBoundGraphs -= graphRow.graphIdentity
			} else {
				require(bindingsByGraph[graphRow.graphIdentity].orEmpty().isEmpty()) {
					"Imported portable graph binding was not consumed by a product"
				}
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
	require(remainingBoundGraphs.isEmpty()) {
		"Authenticated imported portable binding references a missing graph"
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
		maximumOwnerCount,
		maximumOwnerRevisionCount,
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
	private val maximumOwnerCount: Int,
	private val maximumOwnerRevisionCount: Int,
) {
	private var ownerCount = 0
	private var ownerRevisionCount = 0

	init {
		dropTables()
		try {
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
					has_stored_binding INTEGER NOT NULL
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
				"has_stored_binding) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
			arrayOf(
				product.metadata.identity,
				product.metadata.contentChecksum,
				StepsPortableFormatV1.FORMAT,
				binding.sourceSchemaVersion,
				binding.graphIdentity,
				binding.sourceReceiptIdentity,
				binding.sourceArchiveContentChecksum,
				if (hasStoredBinding) 1 else 0,
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
		var installedOwnerCount = 0
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
				installedOwnerCount = Math.addExact(installedOwnerCount, 1)
			}
			afterKind = page.last().ownerKind
			afterIdentity = page.last().ownerIdentity
		}
		require(installedOwnerCount == ownerCount)
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
			require(ownerCount < maximumOwnerCount) {
				"Imported portable distinct owner count exceeds its full-clear bound"
			}
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
			ownerCount = Math.addExact(ownerCount, 1)
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
			require(ownerRevisionCount < maximumOwnerRevisionCount) {
				"Imported portable distinct owner revision count exceeds its full-clear bound"
			}
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
			ownerRevisionCount = Math.addExact(ownerRevisionCount, 1)
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

	private fun dropTables() {
		sqlite.execSQL("DROP TABLE IF EXISTS $EXPLICIT_TABLE")
		sqlite.execSQL("DROP TABLE IF EXISTS $REVISION_TABLE")
		sqlite.execSQL("DROP TABLE IF EXISTS $OWNER_TABLE")
		sqlite.execSQL("DROP TABLE IF EXISTS $SESSION_PRODUCT_TABLE")
	}

	private companion object {
		const val SESSION_PRODUCT_TABLE = "imported_steps_full_clear_session_product_stage"
		const val OWNER_TABLE = "imported_steps_full_clear_owner_stage"
		const val REVISION_TABLE = "imported_steps_full_clear_owner_revision_stage"
		const val EXPLICIT_TABLE = "imported_steps_full_clear_explicit_lineage_stage"
		const val OWNER_INSTALL_PAGE_SIZE = 256
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
	sessionBindings: Map<String, ImportedPortableStepsCountDomainBindingEntity>,
	sessionProducts: AuthenticatedFullClearOwnerFenceStaging,
	authenticatedGraphIdentities: Set<String>,
) {
	val dao = importedPortableStepsCountDomainDao()
	val observedSourceReceipts = linkedSetOf<String>()
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
			require(product.graphIdentity in authenticatedGraphIdentities)
			require(receipt.entryOrdinal in 0 until StepsPortableFormatV1.MAX_ENTRIES)
			if (product.sourceSchemaVersion == StepsPortableFormatV1.SCHEMA_VERSION) {
				require(receipt.archiveContentChecksum == product.contentChecksum)
			}
			if (product.sourceReceiptIdentity == receipt.receiptIdentity) {
				require(
					product.sourceArchiveContentChecksum == receipt.archiveContentChecksum,
				)
				observedSourceReceipts += receipt.receiptIdentity
			}
		}
		val last = page.last()
		afterJobId = last.importJobId
		afterEntryKey = last.entryKey
		if (page.size < FILE_RECEIPT_FULL_CLEAR_PAGE_SIZE) break
	}
	sessionBindings.values.mapNotNull { it.sourceReceiptIdentity }.forEach { sourceReceipt ->
		require(sourceReceipt in observedSourceReceipts) {
			"Imported Steps binding source receipt was not independently enumerated"
		}
	}
}

private suspend fun AppDatabase.reconcileImportedPortableLegacyGraphsBeforeFullClear(
	oldCollectedDataEpoch: Long,
) {
	val sessionReader = ImportedStepsRetainedReader(this)
	var beforeStartTimeMs: Long? = null
	var beforeIdentity: String? = null
	var sessionCount = 0
	while (true) {
		val page = importedStepsDao().entryPage(
			beforeStartTimeMs,
			beforeIdentity,
			SESSION_FULL_CLEAR_PAGE_SIZE,
		)
		if (page.isEmpty()) break
		sessionCount = Math.addExact(sessionCount, page.size)
		require(sessionCount <= MAX_FULL_CLEAR_BINDINGS)
		val retained = when (
			val read = sessionReader.readEntriesForRetentionInTransaction(
				page.map { it.identity },
			)
		) {
			is ImportedStepsRetainedRead.Ready -> {
				require(read.unverifiableEntries.isEmpty())
				read.entries.associateBy { it.metadata.identity }
			}
			is ImportedStepsRetainedRead.Unverifiable ->
				error("Imported Steps product is unverifiable during graph reconciliation")
		}
		page.forEach { entry ->
			val product = requireNotNull(retained[entry.identity])
			if (importedPortableStepsCountDomainDao().bindingEvidenceCountForProduct(
					ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
					entry.identity,
				) > 0
			) {
				val expected = product.legacyUnprovenCountDomainGraph()
				val loaded = requireNotNull(
					loadImportedSessionCountDomainBindingForFullClear(entry.identity),
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
		beforeStartTimeMs = page.last().startTimeMs
		beforeIdentity = page.last().identity
		if (page.size < SESSION_FULL_CLEAR_PAGE_SIZE) break
	}

	val ambientDao = importedAmbientStepsDao()
	var afterDayId: String? = null
	var ambientCount = 0
	while (true) {
		val page = ambientDao.fullClearDayCandidatePage(afterDayId, AMBIENT_FULL_CLEAR_PAGE_SIZE)
		if (page.isEmpty()) break
		ambientCount = Math.addExact(ambientCount, page.size)
		require(ambientCount <= MAX_FULL_CLEAR_BINDINGS)
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

private const val MAX_FULL_CLEAR_BINDINGS = 131_072
private const val MAX_FULL_CLEAR_OWNER_FENCES = 262_144
private const val MAX_FULL_CLEAR_OWNER_REVISIONS =
	MAX_FULL_CLEAR_OWNER_FENCES * ImportedAmbientStepsDao.MAX_REVISIONS_PER_DAY
private const val SESSION_FULL_CLEAR_PAGE_SIZE = 32
private const val AMBIENT_FULL_CLEAR_PAGE_SIZE = 256
private const val GRAPH_FULL_CLEAR_PAGE_SIZE = 64
private const val FILE_RECEIPT_FULL_CLEAR_PAGE_SIZE = 256
private const val SESSION_PRODUCT_REVISION = 1L
private const val FENCE_QUERY_BATCH = 400
private val SESSION_PRUNING_FENCE_KINDS = setOf(
	ImportedPortableStepsCountDomainOwnerFenceEntity.FENCE_RETENTION,
	ImportedPortableStepsCountDomainOwnerFenceEntity.FENCE_SELECTED_DELETE,
)
