package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.dao.ImportedAmbientStepsDao
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsArchiveEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsDayFenceEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsDayRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsIdentity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsReceiptEntity
import com.adsamcik.tracker.shared.base.database.hasCompletePortableOwnerLineages
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableDigest
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableOpaqueIdentity
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableV2Integrity
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsArchiveV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsArchiveV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV2
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainGraphV2
import com.adsamcik.tracker.shared.model.steps.portable.deletionScopeIdentity
import com.adsamcik.tracker.shared.model.steps.portable.identity
import com.adsamcik.tracker.shared.model.steps.portable.withExplicitUnprovenCountDomain
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientStepsRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientStepsV2Request
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsImportReceipt

internal data class AmbientStepsDayImportPlan(
	val day: PortableAmbientStepsDayV1,
	val productRevision: Long,
	val graphRevision: Long,
	val appendProduct: Boolean,
	val appendGraph: Boolean,
	val graph: PortableCountDomainGraphV2,
)

internal data class AmbientImportEnvelope(
	val archive: PortableAmbientStepsArchiveV1,
	val sourceArchiveIdentity: AmbientStepsPortableOpaqueIdentity,
	val sourceArchiveContentChecksum: AmbientStepsPortableDigest,
	val sourceFormat: String,
	val sourceSchemaVersion: Int,
	val receipt: PortableAmbientStepsImportReceipt,
	val encodedByteCount: Long,
	val dayCount: Int,
	val factCount: Int,
	val gapCount: Int,
	val expectedCollectedDataEpoch: Long,
	val graphsByDayIdentity: Map<String, PortableCountDomainGraphV2>,
) {
	fun graphFor(
		day: PortableAmbientStepsDayV1,
		graphRevision: Long,
	): PortableCountDomainGraphV2 =
		graphsByDayIdentity[day.identity.value]
			?: day.withExplicitUnprovenCountDomain(graphRevision).countDomainGraph

	val hasExplicitCountDomainGraphs: Boolean
		get() = graphsByDayIdentity.isNotEmpty()

	fun incomingOwnerIdentities(): List<String> {
		val graphs = incomingGraphs()
		return graphs.flatMap { graph -> graph.roots.map { it.ownerIdentity.value } }.distinct()
	}

	fun ownsRoot(
		containerIdentity: String,
		productIdentity: String,
		ownerKind: String,
		ownerIdentity: String,
	): Boolean = incomingGraphs().any { graph ->
		graph.roots.any { root ->
			root.containerIdentity.value == containerIdentity &&
				root.productIdentity.value == productIdentity &&
				root.ownerKind.name == ownerKind &&
				root.ownerIdentity.value == ownerIdentity
		}
	}

	private fun incomingGraphs() = if (graphsByDayIdentity.isNotEmpty()) {
		graphsByDayIdentity.values
	} else {
		archive.days.map {
			it.withExplicitUnprovenCountDomain().countDomainGraph
		}
	}

	companion object {
		fun fromV1(request: ImportPortableAmbientStepsRequest) = AmbientImportEnvelope(
			archive = request.archive,
			sourceArchiveIdentity = request.archive.identity,
			sourceArchiveContentChecksum = request.archive.contentChecksum,
			sourceFormat = request.archive.format,
			sourceSchemaVersion = request.archive.schemaVersion,
			receipt = request.receipt,
			encodedByteCount = request.metadata.encodedByteCount,
			dayCount = request.metadata.dayCount,
			factCount = request.metadata.factCount,
			gapCount = request.metadata.gapCount,
			expectedCollectedDataEpoch = request.expectedCollectedDataEpoch,
			graphsByDayIdentity = emptyMap(),
		)

		fun fromV2(request: ImportPortableAmbientStepsV2Request): AmbientImportEnvelope {
			val sourceArchiveIdentity = request.archive.identity
			val sourceArchiveContentChecksum = request.archive.contentChecksum
			val sourceFormat = request.archive.format
			val sourceSchemaVersion = request.archive.schemaVersion
			val days = request.archive.days.map { day ->
				PortableAmbientStepsDayV2(
					product = day.product.copy(
						partialCauses = day.product.partialCauses.toList(),
						facts = day.product.facts.map { it.copy() },
						gaps = day.product.gaps.map { it.copy() },
					),
					countDomainGraph = day.countDomainGraph.copy(
						receipts = day.countDomainGraph.receipts.map { it.copy() },
						ownerRevisions = day.countDomainGraph.ownerRevisions.map { it.copy() },
						completenessMarkers =
							day.countDomainGraph.completenessMarkers.map { it.copy() },
						roots = day.countDomainGraph.roots.map { it.copy() },
					),
				)
			}
			val snapshot = PortableAmbientStepsArchiveV2(
				format = sourceFormat,
				schemaVersion = sourceSchemaVersion,
				contentChecksum = AmbientStepsPortableV2Integrity.archiveChecksum(days),
				days = days,
			)
			require(snapshot.identity == sourceArchiveIdentity)
			require(snapshot.contentChecksum == sourceArchiveContentChecksum)
			require(snapshot.contentChecksum == request.metadata.archiveContentChecksum)
			val products = snapshot.days.map(PortableAmbientStepsDayV2::product)
			val graphMap = snapshot.days.associate {
				it.product.identity.value to it.countDomainGraph
			}
			require(graphMap.size == products.size)
			require(
				request.metadata.receiptCount ==
					graphMap.values.sumOf { it.receipts.size },
			)
			require(
				request.metadata.ownerRevisionCount ==
					graphMap.values.sumOf { it.ownerRevisions.size },
			)
			require(request.metadata.rootCount == graphMap.values.sumOf { it.roots.size })
			require(graphMap.values.all { it.hasCompletePortableOwnerLineages() })
			return AmbientImportEnvelope(
				archive = PortableAmbientStepsArchiveV1.create(products),
				sourceArchiveIdentity = sourceArchiveIdentity,
				sourceArchiveContentChecksum = sourceArchiveContentChecksum,
				sourceFormat = sourceFormat,
				sourceSchemaVersion = sourceSchemaVersion,
				receipt = request.receipt,
				encodedByteCount = request.metadata.encodedByteCount,
				dayCount = request.metadata.dayCount,
				factCount = request.metadata.factCount,
				gapCount = request.metadata.gapCount,
				expectedCollectedDataEpoch = request.expectedCollectedDataEpoch,
				graphsByDayIdentity = graphMap,
			)
		}
	}
}

internal data class NewImportedAmbientStepsRows(
	val archives: Long = 0L,
	val receipts: Long = 0L,
	val archiveDays: Long = 0L,
	val dayRevisions: Long = 0L,
	val facts: Long = 0L,
	val gaps: Long = 0L,
)

internal fun importedAmbientStepsLineageAdditionFits(
	existingArchiveMemberCount: Int,
	existingReceiptCount: Int,
	incomingArchiveMemberCount: Int,
	addedReceiptCount: Int = 1,
): Boolean {
	require(existingArchiveMemberCount >= 0)
	require(existingReceiptCount >= 0)
	require(incomingArchiveMemberCount >= 0)
	require(addedReceiptCount >= 0)
	return existingArchiveMemberCount.toLong() + incomingArchiveMemberCount <=
		ImportedAmbientStepsDao.MAX_ARCHIVE_MEMBERS_PER_LINEAGE &&
		existingReceiptCount.toLong() + addedReceiptCount <=
		ImportedAmbientStepsDao.MAX_RECEIPTS_PER_LINEAGE
}

internal data class StructuralAmbientStepsDayKey(
	val epochDay: Long,
	val zoneId: String,
	val startTimeMs: Long,
	val endTimeMs: Long,
) {
	constructor(day: PortableAmbientStepsDayV1) : this(
		day.structuralEpochDay,
		day.storedZoneId,
		day.structuralDayStartTimeMs,
		day.structuralDayEndTimeMs,
	)
}

internal enum class IncomingAmbientStepsIdentityKind {
	ARCHIVE,
	DAY,
	DELETION_SCOPE,
	FACT,
	GAP,
	RECEIPT,
}

internal class IncomingAmbientStepsOwnership(
	archive: PortableAmbientStepsArchiveV1,
	archiveIdentity: String,
	receiptIdentity: String,
) {
	val kinds = linkedMapOf<String, IncomingAmbientStepsIdentityKind>()
	val dayOwners = mutableMapOf<String, String>()
	val scopeOwners = mutableMapOf<String, String>()
	val factOwners = mutableMapOf<String, String>()
	val gapOwners = mutableMapOf<String, String>()

	init {
		add(archiveIdentity, IncomingAmbientStepsIdentityKind.ARCHIVE)
		add(receiptIdentity, IncomingAmbientStepsIdentityKind.RECEIPT)
		archive.days.forEach { day ->
			add(day.identity.value, IncomingAmbientStepsIdentityKind.DAY)
			dayOwners[day.identity.value] = day.identity.value
			add(day.deletionScopeIdentity.value, IncomingAmbientStepsIdentityKind.DELETION_SCOPE)
			scopeOwners[day.deletionScopeIdentity.value] = day.identity.value
			day.facts.forEach { fact ->
				add(fact.identity.value, IncomingAmbientStepsIdentityKind.FACT)
				require(factOwners.put(fact.identity.value, day.identity.value) == null)
			}
			day.gaps.forEach { gap ->
				add(gap.identity.value, IncomingAmbientStepsIdentityKind.GAP)
				require(gapOwners.put(gap.identity.value, day.identity.value) == null)
			}
		}
	}

	val allIdentities: List<String>
		get() = kinds.keys.toList()

	private fun add(identity: String, kind: IncomingAmbientStepsIdentityKind) {
		require(kinds.put(identity, kind) == null)
	}
}

internal fun structuralDayKey(
	fence: ImportedAmbientStepsDayFenceEntity,
) = StructuralAmbientStepsDayKey(
	fence.structuralEpochDay,
	fence.storedZoneId,
	fence.structuralDayStartTimeMs,
	fence.structuralDayEndTimeMs,
)

internal fun PortableAmbientStepsDayV1.crossesLocalRetentionFloor(floor: Long): Boolean {
	if (structuralDayEndTimeMs <= floor) {
		return true
	}
	if (structuralDayStartTimeMs < floor &&
		(retainedFromTimeMs?.let { it < floor } ?: true)
	) {
		return true
	}
	return facts.any { it.intervalStartTimeMs < floor } ||
		gaps.any { it.intervalStartTimeMs < floor }
}

internal fun PortableAmbientStepsDayV1.hasSameStructuralAuthorityAs(
	other: PortableAmbientStepsDayV1,
): Boolean = identity == other.identity &&
	structuralEpochDay == other.structuralEpochDay &&
	storedZoneId == other.storedZoneId &&
	structuralDayStartTimeMs == other.structuralDayStartTimeMs &&
	structuralDayEndTimeMs == other.structuralDayEndTimeMs &&
	deletionScopeIdentity == other.deletionScopeIdentity

internal fun AmbientImportEnvelope.toArchiveEntity() =
	ImportedAmbientStepsArchiveEntity(
		archiveIdentity = sourceArchiveIdentity.value,
		contentChecksum = sourceArchiveContentChecksum.value,
		sourceFormat = sourceFormat,
		sourceSchemaVersion = sourceSchemaVersion,
		dayCount = archive.days.size,
		factCount = archive.days.sumOf { it.facts.size },
		gapCount = archive.days.sumOf { it.gaps.size },
		collectedDataEpoch = expectedCollectedDataEpoch,
		firstReceivedAtMs = receipt.receivedAtMs,
	)

internal fun AmbientImportEnvelope.toReceiptEntity() =
	ImportedAmbientStepsReceiptEntity(
		importJobId = receipt.jobId,
		archiveKey = receipt.archiveKey,
		receiptIdentity = ImportedAmbientStepsIdentity.receipt(receipt.jobId, receipt.archiveKey),
		sourceName = receipt.sourceName,
		receivedAtMs = receipt.receivedAtMs,
		archiveIdentity = sourceArchiveIdentity.value,
		archiveContentChecksum = sourceArchiveContentChecksum.value,
		encodedByteCount = encodedByteCount,
		collectedDataEpoch = expectedCollectedDataEpoch,
	)

internal fun PortableAmbientStepsDayV1.toEntity(
	request: AmbientImportEnvelope,
	revision: Long,
) = ImportedAmbientStepsDayRevisionEntity(
	dayIdentity = identity.value,
	importRevision = revision,
	supersedesImportRevision = if (revision == 1L) {
		null
	} else {
		revision - 1L
	},
	archiveIdentity = request.sourceArchiveIdentity.value,
	dayContentChecksum = contentChecksum.value,
	deletionScopeIdentity = deletionScopeIdentity.value,
	structuralEpochDay = structuralEpochDay,
	storedZoneId = storedZoneId,
	structuralDayStartTimeMs = structuralDayStartTimeMs,
	structuralDayEndTimeMs = structuralDayEndTimeMs,
	retainedFromTimeMs = retainedFromTimeMs,
	coverage = coverage.name,
	partialCauses = ImportedAmbientStepsIdentity.encodePartialCauses(partialCauses),
	retainedStepCount = retainedStepCount,
	factCount = facts.size,
	gapCount = gaps.size,
	collectedDataEpoch = request.expectedCollectedDataEpoch,
	receivedAtMs = request.receipt.receivedAtMs,
)

internal const val AMBIENT_STEPS_IMPORT_IDENTITY_CHUNK_SIZE = 256
