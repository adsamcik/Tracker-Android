package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.dao.ImportedAmbientStepsDao
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsArchiveDayEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsArchiveEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainBindingEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsFileReceiptEntity
import com.adsamcik.tracker.shared.base.database.steps.imported.RetainedImportedStepsEntry
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV1
import com.adsamcik.tracker.shared.model.steps.portable.PORTABLE_STEPS_RUN_ORDER
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainGraphV2
import com.adsamcik.tracker.shared.model.steps.portable.StepsPortableFormatV1
import com.adsamcik.tracker.shared.model.steps.portable.legacyUnprovenStepsCountDomainGraph
import com.adsamcik.tracker.shared.model.steps.portable.withExplicitUnprovenCountDomain

internal fun authenticateImportedSessionFileReceipts(
	binding: ImportedPortableStepsCountDomainBindingEntity,
	receipts: List<ImportedPortableStepsFileReceiptEntity>,
	expectedProductChecksum: String,
) {
	check(receipts.map { it.importJobId to it.entryKey }.distinct().size == receipts.size)
	check(receipts.map { it.receiptIdentity }.distinct().size == receipts.size)
	receipts.forEach { receipt ->
		check(receipt.entryIdentity == binding.productIdentity)
		check(receipt.graphIdentity == binding.graphIdentity)
		check(receipt.entryOrdinal in 0 until StepsPortableFormatV1.MAX_ENTRIES)
		if (binding.sourceSchemaVersion == StepsPortableFormatV1.SCHEMA_VERSION) {
			check(receipt.archiveContentChecksum == expectedProductChecksum)
		}
	}
	val sourceReceiptIdentity = binding.sourceReceiptIdentity ?: return
	val sourceReceipt = receipts.singleOrNull {
		it.receiptIdentity == sourceReceiptIdentity
	}
	checkNotNull(sourceReceipt)
	check(sourceReceipt.archiveContentChecksum == binding.sourceArchiveContentChecksum)
}

internal fun reconstructGraphlessLegacyAmbientLineage(
	lineage: AuthenticatedImportedAmbientStepsLineage,
): List<AuthenticatedImportedAmbientStepsGraphRevision> {
	val dayIdentity = lineage.latest.header.dayIdentity
	val members = lineage.archiveDays.filter { it.dayIdentity == dayIdentity }
	require(members.isNotEmpty())
	val revisions = lineage.revisions.associateBy { it.header.importRevision }
	val archives = lineage.archives.associateBy { it.archiveIdentity }
	val receipts = lineage.receipts.groupBy { it.archiveIdentity }
	val membersByGraphRevision = members.groupBy { it.boundCountDomainGraphRevision }
		.toSortedMap()
	require(
		membersByGraphRevision.keys.toList() ==
			(1L..membersByGraphRevision.size.toLong()).toList(),
	)
	return membersByGraphRevision.map { (graphRevision, graphMembers) ->
		val sourceMembers = graphMembers.sortedWith(
			compareBy(
				ImportedAmbientStepsArchiveDayEntity::archiveIdentity,
				ImportedAmbientStepsArchiveDayEntity::ordinal,
			),
		)
		val graphs = sourceMembers.map { member ->
			val archive = requireNotNull(archives[member.archiveIdentity])
			require(archive.sourceSchemaVersion == AmbientStepsPortableFormatV1.SCHEMA_VERSION)
			requireNotNull(revisions[member.boundDayImportRevision])
				.day
				.withExplicitUnprovenCountDomain(graphRevision)
				.countDomainGraph
		}.distinct()
		require(graphs.size == 1) {
			"Legacy Ambient Steps graph revision has conflicting synthetic ownership"
		}
		val source = sourceMembers.first()
		val archive = requireNotNull(archives[source.archiveIdentity])
		val receipt = receipts.getValue(source.archiveIdentity).minWith(
			compareBy(
				ImportedAmbientStepsReceiptEntity::importJobId,
				ImportedAmbientStepsReceiptEntity::archiveKey,
			),
		)
		AuthenticatedImportedAmbientStepsGraphRevision(
			binding = ImportedPortableStepsCountDomainBindingEntity(
				productKind = ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY,
				productIdentity = dayIdentity,
				productRevision = graphRevision,
				graphIdentity = graphs.single().identity.value,
				sourceSchemaVersion = AmbientStepsPortableFormatV1.SCHEMA_VERSION,
				sourceReceiptIdentity = receipt.receiptIdentity,
				sourceArchiveIdentity = archive.archiveIdentity,
				sourceArchiveContentChecksum = archive.contentChecksum,
			),
			productImportRevisions =
				sourceMembers.mapTo(linkedSetOf()) { it.boundDayImportRevision },
			graph = graphs.single(),
		)
	}.also { graphLineage ->
		require(lineage.latest.header.importRevision in
			graphLineage.last().productImportRevisions)
	}
}

internal fun RetainedImportedStepsEntry.legacyUnprovenCountDomainGraph(): PortableCountDomainGraphV2 =
	legacyUnprovenStepsCountDomainGraph(
		com.adsamcik.tracker.shared.model.steps.portable.PortableStepsDigest(
			metadata.contentChecksum,
		),
		portableRunsById.values.sortedWith(PORTABLE_STEPS_RUN_ORDER),
	)

// Compare the complete saved product/archive/receipt correspondence with ordered nullable lookups.
@Suppress("ReturnCount", "CyclomaticComplexMethod")
internal fun ImportedPortableStepsCountDomainBindingEntity.hasAuthenticatedLegacyAmbientProvenance(
	dayIdentity: String,
	archivesByIdentity: Map<String, ImportedAmbientStepsArchiveEntity>,
	receiptsByIdentity: Map<String, ImportedAmbientStepsReceiptEntity>,
): Boolean {
	if (productKind != ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY ||
		productIdentity != dayIdentity ||
		sourceSchemaVersion != AmbientStepsPortableFormatV1.SCHEMA_VERSION
	) {
		return false
	}
	val archiveIdentity = sourceArchiveIdentity ?: return false
	val archiveChecksum = sourceArchiveContentChecksum ?: return false
	val receiptIdentity = sourceReceiptIdentity ?: return false
	val archive = archivesByIdentity[archiveIdentity] ?: return false
	val receipt = receiptsByIdentity[receiptIdentity] ?: return false
	return archive.sourceSchemaVersion == AmbientStepsPortableFormatV1.SCHEMA_VERSION &&
		archive.contentChecksum == archiveChecksum &&
		receipt.archiveIdentity == archiveIdentity &&
		receipt.archiveContentChecksum == archiveChecksum
}

/**
 * Checks that the incoming all-day graph preserves prior immutable receipts/owners and carries
 * valid successor roots.
 */
fun isAuthenticatedAmbientGraphSuccessor(
	previous: PortableCountDomainGraphV2,
	incoming: PortableCountDomainGraphV2,
): Boolean {
	val previousByLineage = previous.ownerRevisions.groupBy { it.ownerKind to it.ownerIdentity }
	val incomingByLineage = incoming.ownerRevisions.groupBy { it.ownerKind to it.ownerIdentity }
	if (previousByLineage.keys != incomingByLineage.keys) {
		return false
	}
	if (previous.roots.map { it.stableBindingKey() }.toSet() !=
		incoming.roots.map { it.stableBindingKey() }.toSet()
	) {
		return false
	}
	var advanced = false
	for ((lineage, priorOwners) in previousByLineage) {
		val nextOwners = incomingByLineage.getValue(lineage)
		if (nextOwners.size < priorOwners.size ||
			nextOwners.take(priorOwners.size) != priorOwners
		) {
			return false
		}
		if (nextOwners.size > priorOwners.size) {
			advanced = true
		}
	}
	return advanced
}

internal fun authenticateImportedAmbientStepsGraphLineage(
	lineage: AuthenticatedImportedAmbientStepsLineage,
	bindings: List<ImportedPortableStepsCountDomainBindingEntity>,
	graphs: Map<String, PortableCountDomainGraphV2>,
): List<AuthenticatedImportedAmbientStepsGraphRevision> {
	if (lineage.revisions.isEmpty()) {
		check(lineage.archiveDays.isEmpty())
		check(bindings.isEmpty())
		check(graphs.isEmpty())
		return emptyList()
	}
	val dayIdentity = lineage.latest.header.dayIdentity
	val members = lineage.archiveDays.filter { it.dayIdentity == dayIdentity }
	check(members.isNotEmpty())
	check(bindings.isNotEmpty())
	check(bindings.size <= ImportedAmbientStepsDao.MAX_ARCHIVES_PER_DAY)
	check(bindings.map { it.productRevision } == (1L..bindings.size.toLong()).toList())
	check(
		bindings.mapTo(linkedSetOf()) { it.productRevision } ==
			members.mapTo(linkedSetOf()) { it.boundCountDomainGraphRevision },
	)
	check(graphs.keys == bindings.mapTo(linkedSetOf()) { it.graphIdentity })
	check(bindings.map { it.graphIdentity }.distinct().size == bindings.size)

	val revisionsByNumber = lineage.revisions.associateBy { it.header.importRevision }
	val archivesByIdentity = lineage.archives.associateBy { it.archiveIdentity }
	val receiptsByIdentity = lineage.receipts.associateBy { it.receiptIdentity }
	check(receiptsByIdentity.size == lineage.receipts.size)
	val graphLineage = bindings.map { binding ->
		authenticateImportedAmbientGraphRevision(
			binding, dayIdentity, members, archivesByIdentity, receiptsByIdentity, revisionsByNumber, graphs,
		)
	}
	graphLineage.zipWithNext().forEach { (previous, incoming) ->
		if (incoming.sourceSchemaVersion == 2) {
			check(isAuthenticatedAmbientGraphSuccessor(previous.graph, incoming.graph))
		}
	}
	check(lineage.latest.header.importRevision in graphLineage.last().productImportRevisions)
	return graphLineage
}

// The complete revision uses the already indexed saved rows; no new context owner or repeated indexing.
@Suppress("LongParameterList")
private fun authenticateImportedAmbientGraphRevision(
	binding: ImportedPortableStepsCountDomainBindingEntity,
	dayIdentity: String,
	members: List<ImportedAmbientStepsArchiveDayEntity>,
	archivesByIdentity: Map<String, ImportedAmbientStepsArchiveEntity>,
	receiptsByIdentity: Map<String, ImportedAmbientStepsReceiptEntity>,
	revisionsByNumber: Map<Long, AuthenticatedImportedAmbientStepsRevision>,
	graphs: Map<String, PortableCountDomainGraphV2>,
): AuthenticatedImportedAmbientStepsGraphRevision {
	check(binding.productKind == ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY)
	check(binding.productIdentity == dayIdentity)
	val sourceArchiveIdentity = checkNotNull(binding.sourceArchiveIdentity)
	val sourceArchiveChecksum = checkNotNull(binding.sourceArchiveContentChecksum)
	val sourceReceiptIdentity = checkNotNull(binding.sourceReceiptIdentity)
	val archive = checkNotNull(archivesByIdentity[sourceArchiveIdentity])
	val receipt = checkNotNull(receiptsByIdentity[sourceReceiptIdentity])
	check(receipt.archiveIdentity == sourceArchiveIdentity)
	check(receipt.archiveContentChecksum == sourceArchiveChecksum)
	check(archive.contentChecksum == sourceArchiveChecksum)
	check(archive.sourceSchemaVersion == binding.sourceSchemaVersion)
	val revisionMembers = members.filter {
		it.boundCountDomainGraphRevision == binding.productRevision
	}
	check(revisionMembers.isNotEmpty())
	check(revisionMembers.any { it.archiveIdentity == sourceArchiveIdentity })
	val productRevisions = revisionMembers.mapTo(linkedSetOf()) { it.boundDayImportRevision }
	val graph = checkNotNull(graphs[binding.graphIdentity])
	check(graph.identity.value == binding.graphIdentity)
	if (binding.sourceSchemaVersion == 2) {
		check(graph.hasCompletePortableOwnerLineages())
	}
	productRevisions.forEach { productRevision ->
		val day = checkNotNull(revisionsByNumber[productRevision]).day
		if (binding.sourceSchemaVersion == 1) {
			check(
				graph ==
					day.withExplicitUnprovenCountDomain(binding.productRevision).countDomainGraph,
			)
		} else {
			PortableAmbientStepsDayV2(day, graph)
		}
	}
	return AuthenticatedImportedAmbientStepsGraphRevision(
		binding = binding,
		productImportRevisions = productRevisions,
		graph = graph,
	)
}

internal fun authenticateImportedSessionBinding(
	binding: ImportedPortableStepsCountDomainBindingEntity,
	graph: PortableCountDomainGraphV2,
	fileReceipt: ImportedPortableStepsFileReceiptEntity?,
	expectedLegacyGraph: PortableCountDomainGraphV2,
) {
	check(binding.productKind == ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY)
	check(binding.productRevision == IMPORTED_SESSION_COUNT_DOMAIN_PRODUCT_REVISION)
	check(binding.graphIdentity == graph.identity.value)
	if (binding.sourceSchemaVersion == 2) {
		check(graph.hasCompletePortableOwnerLineages())
	} else {
		check(binding.sourceSchemaVersion == 1)
		check(graph == expectedLegacyGraph)
	}
	if (binding.sourceReceiptIdentity == null) {
		check(binding.sourceSchemaVersion == 1)
		check(binding.sourceArchiveContentChecksum == null)
		check(fileReceipt == null)
		return
	}
	val receipt = checkNotNull(fileReceipt)
	check(receipt.receiptIdentity == binding.sourceReceiptIdentity)
	check(receipt.entryIdentity == binding.productIdentity)
	check(receipt.graphIdentity == binding.graphIdentity)
	check(receipt.archiveContentChecksum == binding.sourceArchiveContentChecksum)
}

/**
 * Checks each portable owner identity for a complete ordered revision chain with reciprocal
 * predecessor semantics.
 */
fun PortableCountDomainGraphV2.hasCompletePortableOwnerLineages(): Boolean =
	ownerRevisions.groupBy { it.ownerKind to it.ownerIdentity }.values.all {
		it.first().ownerRevision == 1L
	}

private fun com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainRootV2
	.stableBindingKey(): List<String> = listOf(
	containerIdentity.value,
	productIdentity.value,
	ownerKind.name,
	ownerIdentity.value,
)
