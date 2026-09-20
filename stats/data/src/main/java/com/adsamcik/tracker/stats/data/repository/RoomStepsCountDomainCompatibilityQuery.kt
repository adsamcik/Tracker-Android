package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.StepsCountDomainOwnerLineageKey
import com.adsamcik.tracker.shared.base.database.StepsCountDomainOwnerLookupKey
import com.adsamcik.tracker.shared.base.database.StepsCountDomainOwnerRead
import com.adsamcik.tracker.shared.base.database.StepsCountDomainStore
import com.adsamcik.tracker.shared.base.database.StepsCountDomainStoredOwner
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.loadAuthenticatedAmbientStepsLineage
import com.adsamcik.tracker.shared.base.database.loadAuthenticatedImportedAmbientStepsGraphLineage
import com.adsamcik.tracker.shared.base.database.loadAuthenticatedImportedSessionCountDomainBinding
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainBindingEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainCompletenessMarkerEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptIntegrity
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedRead
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedReader
import com.adsamcik.tracker.stats.api.repository.MAX_STEPS_COUNT_DOMAIN_REQUESTS
import com.adsamcik.tracker.stats.api.repository.MAX_STEPS_COUNT_DOMAIN_OWNERS_PER_BATCH
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainCompatibilityQuery
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainCompatibilityRequest
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainCompatibilityResult
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainOwnerEffect
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainOwnerIdentity
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainOwnerKind
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainOwnerOrigin
import com.adsamcik.tracker.stats.api.repository.StepsCountDomainOwnerReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Bounded native receipt lookup.
 *
 * This reader never accepts time, count, zone, or source display names. The AppDatabase schema
 * owner may install the additive tables and sentinel later; a completely absent schema is
 * Unproven, while partial, markerless, legacy, or corrupt storage is Unverifiable.
 */
@Singleton
internal class RoomStepsCountDomainCompatibilityQuery @Inject constructor(
	private val database: AppDatabase,
) : StepsCountDomainCompatibilityQuery {
	private val store = StepsCountDomainStore(database)

	override suspend fun compare(
		requests: List<StepsCountDomainCompatibilityRequest>,
	): List<StepsCountDomainCompatibilityResult> {
		if (requests.size > MAX_STEPS_COUNT_DOMAIN_REQUESTS) {
			return List(requests.size) { StepsCountDomainCompatibilityResult.Unverifiable }
		}
		val snapshot = requests.toList()
		val results = MutableList<StepsCountDomainCompatibilityResult?>(snapshot.size) { null }
		val bounded = snapshot.mapIndexedNotNull { index, request ->
			if (request.exceedsOwnerBounds) {
				results[index] = StepsCountDomainCompatibilityResult.Unverifiable
				null
			} else {
				IndexedRequest(index, request)
			}
		}
		for (chunk in bounded.toOwnerBoundedChunks()) {
			currentCoroutineContext().ensureActive()
			val references = chunk.flatMap { indexed ->
				indexed.request.sessionOwners + indexed.request.ambientOwners
			}
			val nativeKeys = references
				.filter { it.origin == StepsCountDomainOwnerOrigin.NATIVE }
				.map { it.toLookupKey() }
				.distinct()
			val importedReferences = references
				.filter { it.origin == StepsCountDomainOwnerOrigin.IMPORTED_PORTABLE }
				.distinct()
			val importedRead = readImportedOwners(importedReferences)
			if (importedRead == null) {
				chunk.forEach {
					results[it.index] = StepsCountDomainCompatibilityResult.Unverifiable
				}
				continue
			}
			when (val read = if (nativeKeys.isEmpty()) {
				StepsCountDomainOwnerRead.Ready(emptyMap(), emptyMap())
			} else {
				store.readOwners(nativeKeys)
			}) {
				StepsCountDomainOwnerRead.SchemaUnavailable ->
					chunk.forEach {
						results[it.index] = StepsCountDomainCompatibilityResult.Unproven
					}
				StepsCountDomainOwnerRead.Overflow,
				StepsCountDomainOwnerRead.Unverifiable,
				-> chunk.forEach {
					results[it.index] = StepsCountDomainCompatibilityResult.Unverifiable
				}
				is StepsCountDomainOwnerRead.Ready ->
					chunk.forEach {
						results[it.index] = if (
							(it.request.sessionOwners + it.request.ambientOwners)
								.all { owner ->
									owner.origin == StepsCountDomainOwnerOrigin.NATIVE
								}
						) {
							resolveStepsCountDomainCompatibility(it.request, read)
						} else {
							resolveStepsCountDomainCompatibility(
								it.request,
								read,
								importedRead,
							)
						}
					}
			}
		}
		return results.map { it ?: StepsCountDomainCompatibilityResult.Unverifiable }
	}

	private suspend fun readImportedOwners(
		references: List<StepsCountDomainOwnerReference>,
	): ImportedPortableOwnerRead? = try {
		readImportedOwnersUnchecked(references)
	} catch (_: IllegalArgumentException) {
		null
	} catch (_: IllegalStateException) {
		null
	} catch (_: ArithmeticException) {
		null
	}

	private suspend fun readImportedOwnersUnchecked(
		references: List<StepsCountDomainOwnerReference>,
	): ImportedPortableOwnerRead? {
		if (references.isEmpty()) return ImportedPortableOwnerRead(emptyMap(), emptyMap())
		val dao = database.importedPortableStepsCountDomainDao()
		val identities = references.map { it.identity.encoded }.distinct()
		val roots = dao.rootsForOwners(identities, MAX_IMPORTED_ROOT_LOOKUP + 1)
		if (roots.size > MAX_IMPORTED_ROOT_LOOKUP) return null
		val graphIds = roots.map { it.graphIdentity }.distinct()
		if (graphIds.size > MAX_IMPORTED_GRAPH_LOOKUP) return null
		if (graphIds.isEmpty()) return ImportedPortableOwnerRead(emptyMap(), emptyMap())
		val bindings = dao.bindingsForGraphs(graphIds, MAX_IMPORTED_GRAPH_LOOKUP + 1)
		if (bindings.size > MAX_IMPORTED_GRAPH_LOOKUP ||
			bindings.groupBy { it.graphIdentity }.values.any { it.size != 1 } ||
			bindings.mapTo(linkedSetOf()) { it.graphIdentity } != graphIds.toSet()
		) return null
		val authenticatedGraphs = linkedMapOf<
			String,
			com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainGraphV2>()
		try {
			for (binding in bindings.filter {
				it.productKind ==
					ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY
			}) {
				val retained = when (
					val read = ImportedStepsRetainedReader(database)
						.readEntriesForRetentionInTransaction(listOf(binding.productIdentity))
				) {
					is ImportedStepsRetainedRead.Ready -> {
						if (read.unverifiableEntries.isNotEmpty() || read.entries.size != 1) {
							return null
						}
						read.entries.single()
					}
					is ImportedStepsRetainedRead.Unverifiable -> return null
				}
				val authenticated = database
					.loadAuthenticatedImportedSessionCountDomainBinding(retained)
					?: return null
				if (authenticated.binding != binding) return null
				authenticatedGraphs[binding.graphIdentity] = authenticated.graph
			}
			val ambientBindings = bindings.filter {
				it.productKind ==
					ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY
			}.groupBy { it.productIdentity }
			if (ambientBindings.isNotEmpty()) {
				val state = database.sourceEvidenceStateDao().get() ?: return null
				for ((dayIdentity, expectedBindings) in ambientBindings) {
					val lineage = database.importedAmbientStepsDao()
						.loadAuthenticatedAmbientStepsLineage(
							dayIdentity,
							state.collectedDataEpoch,
						)
					val graphLineage =
						database.loadAuthenticatedImportedAmbientStepsGraphLineage(lineage)
					if (graphLineage.map { it.binding }.filter {
							it.graphIdentity in graphIds
						} != expectedBindings
					) return null
					graphLineage.forEach { revision ->
						authenticatedGraphs[revision.binding.graphIdentity] = revision.graph
					}
				}
			}
		} catch (_: IllegalArgumentException) {
			return null
		} catch (_: IllegalStateException) {
			return null
		} catch (_: ArithmeticException) {
			return null
		}
		if (!authenticatedGraphs.keys.containsAll(graphIds)) return null
		val owners = linkedMapOf<ImportedPortableOwnerKey, ImportedPortableStoredOwner>()
		val latest = mutableMapOf<ImportedPortableLineageKey, Long>()
		for (graphId in graphIds) {
			currentCoroutineContext().ensureActive()
			val graph = authenticatedGraphs[graphId] ?: return null
			graph.roots.filter { it.ownerIdentity.value in identities }.forEach { root ->
				val owner = graph.ownerRevisions.singleOrNull {
					it.ownerKind == root.ownerKind &&
						it.ownerIdentity == root.ownerIdentity &&
						it.ownerRevision == root.ownerRevision
				} ?: return null
				val receipt = owner.receiptIdentity?.let { identity ->
					graph.receipts.singleOrNull { it.identity == identity }
				}
				val marker = graph.completenessMarkers.singleOrNull {
					it.ownerIdentity == owner.ownerIdentity &&
						it.ownerRevision == owner.ownerRevision
				}
				val key = ImportedPortableOwnerKey(
					root.ownerKind.name,
					root.ownerIdentity.value,
					root.ownerRevision,
				)
				val value = ImportedPortableStoredOwner(
					owner.operation.name,
					owner.scopeIdentity.value,
					owner.ownerEffectChecksum.value,
					receipt?.domainIdentity?.value,
					receipt?.collectedDataEpoch,
					receipt?.countDomainVersion,
					marker?.terminalState?.name,
				)
				val prior = owners.putIfAbsent(key, value)
				if (prior != null && prior != value) return null
				val lineage = ImportedPortableLineageKey(key.ownerKind, key.ownerIdentity)
				latest[lineage] = maxOf(latest[lineage] ?: 0L, key.ownerRevision)
			}
		}
		return ImportedPortableOwnerRead(owners, latest)
	}

	private companion object {
		const val MAX_IMPORTED_ROOT_LOOKUP = 8_192
		const val MAX_IMPORTED_GRAPH_LOOKUP = 4_096
	}
}

internal suspend fun StepsCountDomainCompatibilityQuery.compareInProductionChunks(
	requests: List<StepsCountDomainCompatibilityRequest>,
): List<StepsCountDomainCompatibilityResult> {
	if (requests.isEmpty()) return emptyList()
	val results = ArrayList<StepsCountDomainCompatibilityResult>(requests.size)
	var startIndex = 0
	while (startIndex < requests.size) {
		currentCoroutineContext().ensureActive()
		val endIndex = minOf(startIndex + MAX_STEPS_COUNT_DOMAIN_REQUESTS, requests.size)
		val chunk = requests.subList(startIndex, endIndex)
		val chunkResults = compare(chunk)
		check(chunkResults.size == chunk.size) {
			"Steps count-domain query returned a result count that does not match its request count"
		}
		results.addAll(chunkResults)
		startIndex = endIndex
	}
	return results
}

private data class IndexedRequest(
	val index: Int,
	val request: StepsCountDomainCompatibilityRequest,
)

private fun List<IndexedRequest>.toOwnerBoundedChunks(): List<List<IndexedRequest>> {
	if (isEmpty()) return emptyList()
	val chunks = mutableListOf<MutableList<IndexedRequest>>()
	var current = mutableListOf<IndexedRequest>()
	var currentOwnerCount = 0
	for (indexed in this) {
		val ownerCount = indexed.request.sessionOwners.size + indexed.request.ambientOwners.size
		if (current.isNotEmpty() &&
			currentOwnerCount + ownerCount > MAX_STEPS_COUNT_DOMAIN_OWNERS_PER_BATCH
		) {
			chunks += current
			current = mutableListOf()
			currentOwnerCount = 0
		}
		current += indexed
		currentOwnerCount += ownerCount
	}
	if (current.isNotEmpty()) chunks += current
	return chunks
}

internal fun resolveStepsCountDomainCompatibility(
	request: StepsCountDomainCompatibilityRequest,
	read: StepsCountDomainOwnerRead.Ready,
): StepsCountDomainCompatibilityResult {
	if (request.exceedsOwnerBounds) {
		return StepsCountDomainCompatibilityResult.Unverifiable
	}
	if (request.sessionOwners.isEmpty() || request.ambientOwners.isEmpty() ||
		request.sessionOwners.none {
			it.kind == StepsCountDomainOwnerKind.SESSION_FACT
		} ||
		request.sessionOwners.none {
			it.kind == StepsCountDomainOwnerKind.SESSION_COMPLETENESS
		}
	) {
		return StepsCountDomainCompatibilityResult.Unproven
	}
	val references = request.sessionOwners + request.ambientOwners
	// The current Ambient UNPROVEN revision is a valid unknown result; only superseded references
	// are stale and therefore unverifiable.
	if (references.any { reference -> read.isStale(reference) }) {
		return StepsCountDomainCompatibilityResult.Unverifiable
	}
	val session = request.sessionOwners.map { reference ->
		read.ownerFor(reference) ?: return StepsCountDomainCompatibilityResult.Unproven
	}
	val ambient = request.ambientOwners.map { reference ->
		read.ownerFor(reference) ?: return StepsCountDomainCompatibilityResult.Unproven
	}
	val all = session + ambient
	if (all.any { (reference, stored) ->
			!stored.hasAuthenticSelectedOwner(reference)
		}) {
		return StepsCountDomainCompatibilityResult.Unverifiable
	}
	if (all.any { (_, stored) ->
			stored.owner.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_RETRACT
		}) {
		return StepsCountDomainCompatibilityResult.Deleted
	}
	if (all.any { (_, stored) ->
			stored.owner.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN
		}
	) {
		return StepsCountDomainCompatibilityResult.Unproven
	}
	val sessionScopes = session.mapTo(linkedSetOf()) { (_, stored) ->
		stored.owner.scopeIdentity
	}
	if (sessionScopes.size != 1) return StepsCountDomainCompatibilityResult.Unverifiable

	val sessionDomains = session.mapTo(linkedSetOf()) { (_, stored) ->
		requireNotNull(stored.receipt).compatibilityKey()
	}
	val ambientDomains = ambient.mapTo(linkedSetOf()) { (_, stored) ->
		requireNotNull(stored.receipt).compatibilityKey()
	}
	if (sessionDomains.size != 1 || ambientDomains.size != 1) {
		return StepsCountDomainCompatibilityResult.Conflict
	}
	return if (sessionDomains.single() == ambientDomains.single()) {
		StepsCountDomainCompatibilityResult.ExactCompatible
	} else {
		StepsCountDomainCompatibilityResult.Conflict
	}
}

private fun resolveStepsCountDomainCompatibility(
	request: StepsCountDomainCompatibilityRequest,
	nativeRead: StepsCountDomainOwnerRead.Ready,
	importedRead: ImportedPortableOwnerRead,
): StepsCountDomainCompatibilityResult {
	if (request.exceedsOwnerBounds) return StepsCountDomainCompatibilityResult.Unverifiable
	if (request.sessionOwners.isEmpty() || request.ambientOwners.isEmpty() ||
		request.sessionOwners.none { it.kind == StepsCountDomainOwnerKind.SESSION_FACT } ||
		request.sessionOwners.none { it.kind == StepsCountDomainOwnerKind.SESSION_COMPLETENESS }
	) return StepsCountDomainCompatibilityResult.Unproven
	val references = request.sessionOwners + request.ambientOwners
	if (references.any { reference ->
			when (reference.origin) {
				StepsCountDomainOwnerOrigin.NATIVE -> nativeRead.isStale(reference)
				StepsCountDomainOwnerOrigin.IMPORTED_PORTABLE ->
					importedRead.latestRevisions[
						ImportedPortableLineageKey(
							reference.kind.storedCode,
							reference.identity.encoded,
						)
					]?.let { it != reference.revision } == true
			}
		}
	) return StepsCountDomainCompatibilityResult.Unverifiable
	val selected = references.map { reference ->
		when (reference.origin) {
			StepsCountDomainOwnerOrigin.NATIVE -> {
				val stored = nativeRead.ownerFor(reference)
					?: return StepsCountDomainCompatibilityResult.Unproven
				if (!stored.second.hasAuthenticSelectedOwner(reference)) {
					return StepsCountDomainCompatibilityResult.Unverifiable
				}
				UnifiedPortableOwner(
					reference,
					stored.second.owner.operation,
					stored.second.owner.scopeIdentity,
					stored.second.receipt?.compatibilityKey(),
				)
			}
			StepsCountDomainOwnerOrigin.IMPORTED_PORTABLE -> {
				val stored = importedRead.owners[
					ImportedPortableOwnerKey(
						reference.kind.storedCode,
						reference.identity.encoded,
						reference.revision,
					)
				] ?: return StepsCountDomainCompatibilityResult.Unproven
				if (stored.ownerEffectChecksum != reference.effect.encoded) {
					return StepsCountDomainCompatibilityResult.Unverifiable
				}
				val key = if (stored.domainIdentity == null) {
					null
				} else {
					StepsCountDomainCompatibilityKey(
						stored.domainIdentity,
						stored.collectedDataEpoch
							?: return StepsCountDomainCompatibilityResult.Unverifiable,
						stored.countDomainVersion
							?: return StepsCountDomainCompatibilityResult.Unverifiable,
					)
				}
				UnifiedPortableOwner(reference, stored.operation, stored.scopeIdentity, key)
			}
		}
	}
	if (selected.any { it.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_RETRACT }) {
		return StepsCountDomainCompatibilityResult.Deleted
	}
	if (selected.any { it.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN }) {
		return StepsCountDomainCompatibilityResult.Unproven
	}
	if (selected.any {
			it.operation != StepsCountDomainOwnerRevisionEntity.OPERATION_BIND ||
				it.compatibilityKey == null
		}
	) return StepsCountDomainCompatibilityResult.Unverifiable
	val sessionScopes = selected.filter { it.reference in request.sessionOwners }
		.mapTo(linkedSetOf(), UnifiedPortableOwner::scopeIdentity)
	if (sessionScopes.size != 1) return StepsCountDomainCompatibilityResult.Unverifiable
	val sessionDomains = selected.filter { it.reference in request.sessionOwners }
		.mapTo(linkedSetOf()) { requireNotNull(it.compatibilityKey) }
	val ambientDomains = selected.filter { it.reference in request.ambientOwners }
		.mapTo(linkedSetOf()) { requireNotNull(it.compatibilityKey) }
	if (sessionDomains.size != 1 || ambientDomains.size != 1) {
		return StepsCountDomainCompatibilityResult.Conflict
	}
	return if (sessionDomains.single() == ambientDomains.single()) {
		StepsCountDomainCompatibilityResult.ExactCompatible
	} else {
		StepsCountDomainCompatibilityResult.Conflict
	}
}

private data class ImportedPortableOwnerKey(
	val ownerKind: String,
	val ownerIdentity: String,
	val ownerRevision: Long,
)

private data class ImportedPortableLineageKey(
	val ownerKind: String,
	val ownerIdentity: String,
)

private data class ImportedPortableStoredOwner(
	val operation: String,
	val scopeIdentity: String,
	val ownerEffectChecksum: String,
	val domainIdentity: String?,
	val collectedDataEpoch: Long?,
	val countDomainVersion: Int?,
	val completenessState: String?,
)

private data class ImportedPortableOwnerRead(
	val owners: Map<ImportedPortableOwnerKey, ImportedPortableStoredOwner>,
	val latestRevisions: Map<ImportedPortableLineageKey, Long>,
)

private data class UnifiedPortableOwner(
	val reference: StepsCountDomainOwnerReference,
	val operation: String,
	val scopeIdentity: String,
	val compatibilityKey: StepsCountDomainCompatibilityKey?,
)

private fun StepsCountDomainOwnerRead.Ready.isStale(
	reference: StepsCountDomainOwnerReference,
): Boolean {
	val key = reference.toLookupKey()
	val latest = latestRevisions[
		StepsCountDomainOwnerLineageKey(key.ownerKind, key.ownerIdentity)
	] ?: return false
	return latest != reference.revision
}

private fun StepsCountDomainOwnerRead.Ready.ownerFor(
	reference: StepsCountDomainOwnerReference,
): Pair<StepsCountDomainOwnerReference, StepsCountDomainStoredOwner>? {
	val key = reference.toLookupKey()
	return owners[key]?.let { reference to it }
}

private fun StepsCountDomainStoredOwner.hasAuthenticSelectedOwner(
	reference: StepsCountDomainOwnerReference,
): Boolean {
	if (owner.ownerKind != reference.kind.storedCode ||
		owner.ownerIdentity != reference.identity.encoded ||
		owner.ownerRevision != reference.revision ||
		owner.ownerEffectChecksum != reference.effect.encoded
	) return false
	return when (owner.operation) {
		StepsCountDomainOwnerRevisionEntity.OPERATION_BIND -> hasAuthenticBinding()
		StepsCountDomainOwnerRevisionEntity.OPERATION_RETRACT ->
			receipt == null && completenessMarker == null
		StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN ->
			receipt == null && if (
				owner.ownerKind ==
				StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS
			) {
				completenessMarker?.terminalState ==
					StepsCountDomainCompletenessMarkerEntity.STATE_UNPROVEN
			} else {
				completenessMarker == null
			}
		else -> false
	}
}

private fun StepsCountDomainStoredOwner.hasAuthenticBinding(): Boolean {
	val receipt = receipt ?: return false
	return owner.receiptIdentity == receipt.receiptIdentity &&
		owner.ownerEffectChecksum == receipt.effectChecksum &&
		receipt.ownerKind == owner.ownerKind &&
		receipt.scopeIdentity == owner.scopeIdentity &&
		receipt.ownerIdentity == owner.ownerIdentity &&
		receipt.ownerRevision == owner.ownerRevision &&
		StepsCountDomainReceiptIntegrity.hasValidReceipt(receipt) &&
		if (owner.ownerKind ==
			StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS
		) {
			completenessMarker?.let { marker ->
				marker.terminalState ==
					StepsCountDomainCompletenessMarkerEntity.STATE_COMPLETE &&
					marker.evidenceChecksum == receipt.completionEvidenceChecksum
			} == true
		} else {
			completenessMarker == null && receipt.completionEvidenceChecksum == null
		}
}

private data class StepsCountDomainCompatibilityKey(
	val domainIdentity: String,
	val collectedDataEpoch: Long,
	val countDomainVersion: Int,
)

private fun StepsCountDomainReceiptEntity.compatibilityKey() =
	StepsCountDomainCompatibilityKey(
		domainIdentity,
		collectedDataEpoch,
		countDomainVersion,
	)

private fun StepsCountDomainOwnerReference.toLookupKey() = StepsCountDomainOwnerLookupKey(
	ownerKind = kind.storedCode,
	ownerIdentity = identity.encoded,
	ownerRevision = revision,
)

private val StepsCountDomainOwnerKind.storedCode: String
	get() = when (this) {
		StepsCountDomainOwnerKind.SESSION_FACT ->
			StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT
		StepsCountDomainOwnerKind.SESSION_COMPLETENESS ->
			StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS
		StepsCountDomainOwnerKind.AMBIENT_FACT ->
			StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT
	}

internal fun StepFactRevisionEntity.countDomainOwnerReferenceOrNull():
	StepsCountDomainOwnerReference? = try {
		StepsCountDomainOwnerReference(
			kind = StepsCountDomainOwnerKind.SESSION_FACT,
			identity = StepsCountDomainOwnerIdentity.opaque(
				StepsCountDomainReceiptIntegrity.sessionFactOwnerIdentity(
					writerProjectionId,
					writerProjectionVersion,
					logicalFactId,
				),
			),
			revision = semanticRevision,
			effect = StepsCountDomainOwnerEffect.opaque(effectChecksum),
		)
	} catch (_: IllegalArgumentException) {
		null
	}

internal fun SourceSessionCompletenessEntity.countDomainOwnerReferenceOrNull():
	StepsCountDomainOwnerReference? = try {
		val effectChecksum = StepsCountDomainReceiptIntegrity.completenessEffectChecksum(this)
		StepsCountDomainOwnerReference(
			kind = StepsCountDomainOwnerKind.SESSION_COMPLETENESS,
			identity = StepsCountDomainOwnerIdentity.opaque(
				StepsCountDomainReceiptIntegrity.sessionCompletenessOwnerIdentity(
					logicalTrackingId,
					serviceRunId,
					sourceInstanceId,
					registrationGeneration,
				),
			),
			revision = StepsCountDomainReceiptIntegrity.completenessOwnerRevision(this),
			effect = StepsCountDomainOwnerEffect.opaque(effectChecksum),
		)
	} catch (_: IllegalArgumentException) {
		null
	} catch (_: ArithmeticException) {
		null
	}

internal fun AmbientStepsFactRevisionEntity.countDomainOwnerReferenceOrNull():
	StepsCountDomainOwnerReference? = try {
		StepsCountDomainOwnerReference(
			kind = StepsCountDomainOwnerKind.AMBIENT_FACT,
			identity = StepsCountDomainOwnerIdentity.opaque(
				StepsCountDomainReceiptIntegrity.ambientFactOwnerIdentity(
					writerId,
					writerVersion,
					logicalFactId,
				),
			),
			revision = semanticRevision,
			effect = StepsCountDomainOwnerEffect.opaque(effectChecksum),
		)
	} catch (_: IllegalArgumentException) {
		null
	}
