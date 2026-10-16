package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.AuthenticatedImportedAmbientStepsGraphRevision
import com.adsamcik.tracker.shared.base.database.AuthenticatedImportedAmbientStepsLineage
import com.adsamcik.tracker.shared.base.database.authenticatedGraph
import com.adsamcik.tracker.shared.base.database.dao.ImportedAmbientStepsDao
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsArchiveDayEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsArchiveEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsIdentity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientStepsReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainBindingEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainGraphEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.hasCompletePortableOwnerLineages
import com.adsamcik.tracker.shared.base.database.insertAuthenticatedGraph
import com.adsamcik.tracker.shared.base.database.isAuthenticatedAmbientGraphSuccessor
import com.adsamcik.tracker.shared.base.database.loadAuthenticatedAmbientStepsLineage
import com.adsamcik.tracker.shared.base.database.loadAuthenticatedImportedAmbientStepsGraphLineage
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableCountDomainGraphV2
import com.adsamcik.tracker.shared.model.steps.portable.identity
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsImportBlockedReason
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsImportUnverifiableReason

internal suspend fun AppDatabase.authenticateAmbientStepsReceiptReplay(
	dao: ImportedAmbientStepsDao,
	request: AmbientImportEnvelope,
	stored: ImportedAmbientStepsReceiptEntity,
) {
	if (!stored.matchesAmbientStepsReceipt(request)) {
		blockAmbientStepsImport(PortableAmbientStepsImportBlockedReason.RECEIPT_CONFLICT)
	}
	val archive = ambientStepsStoredValue { dao.archive(stored.archiveIdentity) } ?: corruptAmbientStepsImport()
	authenticateAmbientStepsStoredArchive(dao, request, archive, addedReceiptCount = 0)
	val stats = ambientStepsStoredValue { dao.receiptStats(archive.archiveIdentity) }
	if (stats.receiptCount !in 1L..ImportedAmbientStepsDao.MAX_RECEIPTS_PER_ARCHIVE.toLong() ||
		stats.earliestReceivedAtMs != archive.firstReceivedAtMs
	) {
		corruptAmbientStepsImport()
	}
}

private fun ImportedAmbientStepsReceiptEntity.matchesAmbientStepsReceipt(
	request: AmbientImportEnvelope,
): Boolean {
	val sameJob = importJobId == request.receipt.jobId && archiveKey == request.receipt.archiveKey &&
		receiptIdentity == ImportedAmbientStepsIdentity.receipt(request.receipt.jobId, request.receipt.archiveKey)
	val sameOrigin = sourceName == request.receipt.sourceName && receivedAtMs == request.receipt.receivedAtMs
	return sameJob && sameOrigin && matchesAmbientStepsReceiptArchive(request)
}

private fun ImportedAmbientStepsReceiptEntity.matchesAmbientStepsReceiptArchive(
	request: AmbientImportEnvelope,
): Boolean = archiveIdentity == request.sourceArchiveIdentity.value &&
	archiveContentChecksum == request.sourceArchiveContentChecksum.value &&
	collectedDataEpoch == request.expectedCollectedDataEpoch

internal suspend fun AppDatabase.authenticateAmbientStepsStoredArchive(
	dao: ImportedAmbientStepsDao,
	request: AmbientImportEnvelope,
	stored: ImportedAmbientStepsArchiveEntity,
	addedReceiptCount: Int,
) {
	require(addedReceiptCount in 0..1)
	val archive = request.archive
	if (!stored.matchesAmbientStepsArchive(request)) {
		corruptAmbientStepsImport()
	}
	val members = ambientStepsStoredValue { dao.archiveDays(stored.archiveIdentity) }
	if (members.size != archive.days.size ||
		members.withIndex().any { (index, member) -> member.ordinal != index }
	) {
		corruptAmbientStepsImport()
	}
	members.zip(archive.days).forEach { (member, day) ->
		authenticateAmbientStepsArchiveMember(dao, request, stored, member, day, addedReceiptCount)
	}
}

private fun ImportedAmbientStepsArchiveEntity.matchesAmbientStepsArchive(
	request: AmbientImportEnvelope,
): Boolean {
	val sameFormat = archiveIdentity == request.sourceArchiveIdentity.value &&
		contentChecksum == request.sourceArchiveContentChecksum.value &&
		sourceFormat == request.sourceFormat && sourceSchemaVersion == request.sourceSchemaVersion
	val sameCounts = dayCount == request.archive.days.size &&
		factCount == request.archive.days.sumOf { it.facts.size } &&
		gapCount == request.archive.days.sumOf { it.gaps.size }
	return sameFormat && sameCounts && collectedDataEpoch == request.expectedCollectedDataEpoch
}

private suspend fun AppDatabase.authenticateAmbientStepsArchiveMember(
	dao: ImportedAmbientStepsDao,
	request: AmbientImportEnvelope,
	stored: ImportedAmbientStepsArchiveEntity,
	member: ImportedAmbientStepsArchiveDayEntity,
	day: PortableAmbientStepsDayV1,
	addedReceiptCount: Int,
) {
	if (!member.matchesAmbientStepsArchiveMember(request, day)) {
		corruptAmbientStepsImport()
	}
	val lineage = ambientStepsStoredValue {
		dao.loadAuthenticatedAmbientStepsLineage(
			member.dayIdentity,
			request.expectedCollectedDataEpoch,
		)
	}
	val bound = lineage.revisions.singleOrNull {
		it.header.importRevision == member.boundDayImportRevision
	} ?: corruptAmbientStepsImport()
	if (bound.day != day ||
		lineage.archives.singleOrNull {
			it.archiveIdentity == stored.archiveIdentity
		} != stored ||
		lineage.archiveDays.singleOrNull {
			it.archiveIdentity == stored.archiveIdentity &&
				it.dayIdentity == member.dayIdentity
		} != member
	) {
		corruptAmbientStepsImport()
	}
	authenticateAmbientStepsBoundGraph(request, member, day, lineage)
	if (!importedAmbientStepsLineageAdditionFits(
			existingArchiveMemberCount = lineage.archiveDays.size,
			existingReceiptCount = lineage.receipts.size,
			incomingArchiveMemberCount = 0,
			addedReceiptCount = addedReceiptCount,
		)
	) {
		ambientStepsImportOverflow()
	}
}

private fun ImportedAmbientStepsArchiveDayEntity.matchesAmbientStepsArchiveMember(
	request: AmbientImportEnvelope,
	day: PortableAmbientStepsDayV1,
): Boolean {
	val sameDay = archiveIdentity == request.sourceArchiveIdentity.value &&
		dayIdentity == day.identity.value && dayContentChecksum == day.contentChecksum.value
	return sameDay && factCount == day.facts.size && gapCount == day.gaps.size
}

internal suspend fun ImportedAmbientStepsDao.authenticateAmbientStepsPostInsertReceipt(
	request: AmbientImportEnvelope,
	stored: ImportedAmbientStepsArchiveEntity,
) {
	val stats = ambientStepsStoredValue { receiptStats(stored.archiveIdentity) }
	if (stats.receiptCount < 1L ||
		stats.receiptCount >= ImportedAmbientStepsDao.MAX_RECEIPTS_PER_ARCHIVE ||
		stats.earliestReceivedAtMs != stored.firstReceivedAtMs
	) {
		if (stats.receiptCount >= ImportedAmbientStepsDao.MAX_RECEIPTS_PER_ARCHIVE) {
			ambientStepsImportOverflow()
		}
		corruptAmbientStepsImport()
	}
	if (request.receipt.receivedAtMs < stored.firstReceivedAtMs) {
		blockAmbientStepsImport(PortableAmbientStepsImportBlockedReason.RECEIPT_CONFLICT)
	}
}

private suspend fun AppDatabase.authenticateAmbientStepsBoundGraph(
	request: AmbientImportEnvelope,
	member: ImportedAmbientStepsArchiveDayEntity,
	day: PortableAmbientStepsDayV1,
	lineage: AuthenticatedImportedAmbientStepsLineage,
) {
	val graphRevision = ambientStepsStoredValue {
		loadAuthenticatedImportedAmbientStepsGraphLineage(lineage)
	}.singleOrNull {
		it.graphRevision == member.boundCountDomainGraphRevision
	} ?: corruptAmbientStepsImport()
	if (member.boundDayImportRevision !in graphRevision.productImportRevisions ||
		graphRevision.graph != request.graphFor(
			day,
			member.boundCountDomainGraphRevision,
		)
	) {
		blockAmbientStepsImport(PortableAmbientStepsImportBlockedReason.CORRECTION_CONFLICT)
	}
}

private fun planAmbientStepsProductRevision(
	day: PortableAmbientStepsDayV1,
	request: AmbientImportEnvelope,
	lineage: AuthenticatedImportedAmbientStepsLineage,
): Pair<Long, Boolean> {
	authenticateAmbientStepsProductAppend(day, request, lineage)
	val nextRevision = try {
		Math.addExact(lineage.revisions.lastOrNull()?.header?.importRevision ?: 0L, 1L)
	} catch (_: ArithmeticException) {
		unverifiableAmbientStepsImport(PortableAmbientStepsImportUnverifiableReason.REVISION_OVERFLOW)
	}
	val identical = lineage.revisions.singleOrNull { it.day == day }
	val productRevision = identical?.header?.importRevision ?: nextRevision
	val appendProduct = identical == null
	if (appendProduct &&
		lineage.revisions.size >= ImportedAmbientStepsDao.MAX_REVISIONS_PER_DAY
	) {
		unverifiableAmbientStepsImport(PortableAmbientStepsImportUnverifiableReason.REVISION_OVERFLOW)
	}
	return productRevision to appendProduct
}

internal suspend fun AppDatabase.prepareAmbientStepsDayImport(
	dao: ImportedAmbientStepsDao,
	day: PortableAmbientStepsDayV1,
	request: AmbientImportEnvelope,
	state: SourceEvidenceState,
	checkpoint: suspend (ImportedAmbientStepsWriteCheckpoint) -> Unit,
): AmbientStepsDayImportPlan {
	val lineage = ambientStepsStoredValue {
		dao.loadAuthenticatedAmbientStepsLineage(
			day.identity.value,
			state,
		)
	}
	checkpoint(ImportedAmbientStepsWriteCheckpoint.LINEAGE_AUTHENTICATED)
	val (productRevision, appendProduct) = planAmbientStepsProductRevision(day, request, lineage)
	val graphLineage = ambientStepsStoredValue {
		loadAuthenticatedImportedAmbientStepsGraphLineage(lineage)
	}
	if (lineage.revisions.isNotEmpty() != graphLineage.isNotEmpty()) {
		corruptAmbientStepsImport()
	}
	val exactGraphRevision = graphLineage.singleOrNull { revision ->
		productRevision in revision.productImportRevisions &&
			revision.graph == request.graphFor(day, revision.graphRevision)
	}
	return if (exactGraphRevision != null) {
		AmbientStepsDayImportPlan(
			day = day,
			productRevision = productRevision,
			graphRevision = exactGraphRevision.graphRevision,
			appendProduct = appendProduct,
			appendGraph = false,
			graph = exactGraphRevision.graph,
		)
	} else {
		planAmbientStepsGraphAddition(day, request, lineage, graphLineage, productRevision, appendProduct)
	}
}

private fun planAmbientStepsGraphAddition(
	day: PortableAmbientStepsDayV1,
	request: AmbientImportEnvelope,
	lineage: AuthenticatedImportedAmbientStepsLineage,
	graphLineage: List<AuthenticatedImportedAmbientStepsGraphRevision>,
	productRevision: Long,
	appendProduct: Boolean,
): AmbientStepsDayImportPlan {
	val latestGraphRevision = graphLineage.lastOrNull()
	val graphAtLatestRevision = latestGraphRevision?.let {
		request.graphFor(day, it.graphRevision)
	}
	return if (appendProduct &&
		latestGraphRevision != null &&
		latestGraphRevision.graph == graphAtLatestRevision
	) {
		AmbientStepsDayImportPlan(
			day = day,
			productRevision = productRevision,
			graphRevision = latestGraphRevision.graphRevision,
			appendProduct = true,
			appendGraph = false,
			graph = latestGraphRevision.graph,
		)
	} else {
		planNewAmbientStepsGraph(day, request, lineage, graphLineage, productRevision, appendProduct)
	}
}

private fun planNewAmbientStepsGraph(
	day: PortableAmbientStepsDayV1,
	request: AmbientImportEnvelope,
	lineage: AuthenticatedImportedAmbientStepsLineage,
	graphLineage: List<AuthenticatedImportedAmbientStepsGraphRevision>,
	productRevision: Long,
	appendProduct: Boolean,
): AmbientStepsDayImportPlan {
	val latestGraphRevision = graphLineage.lastOrNull()
	if (!appendProduct &&
		productRevision != lineage.latest.header.importRevision
	) {
		blockAmbientStepsImport(PortableAmbientStepsImportBlockedReason.CORRECTION_CONFLICT)
	}
	if (graphLineage.size >= ImportedAmbientStepsDao.MAX_ARCHIVES_PER_DAY) {
		unverifiableAmbientStepsImport(PortableAmbientStepsImportUnverifiableReason.REVISION_OVERFLOW)
	}
	val graphRevision = try {
		Math.addExact(latestGraphRevision?.graphRevision ?: 0L, 1L)
	} catch (_: ArithmeticException) {
		unverifiableAmbientStepsImport(PortableAmbientStepsImportUnverifiableReason.REVISION_OVERFLOW)
	}
	val candidateGraph = request.graphFor(day, graphRevision)
	val existingGraph = latestGraphRevision?.graph
	authenticateAmbientStepsGraphSuccessor(request, existingGraph, candidateGraph)
	return AmbientStepsDayImportPlan(
		day = day,
		productRevision = productRevision,
		graphRevision = graphRevision,
		appendProduct = appendProduct,
		appendGraph = true,
		graph = candidateGraph,
	)
}

private fun authenticateAmbientStepsProductAppend(
	day: PortableAmbientStepsDayV1,
	request: AmbientImportEnvelope,
	lineage: AuthenticatedImportedAmbientStepsLineage,
) {
	val baseline = lineage.revisions.firstOrNull()?.day
	if (baseline != null && !day.hasSameStructuralAuthorityAs(baseline)) {
		blockAmbientStepsImport(PortableAmbientStepsImportBlockedReason.CORRECTION_CONFLICT)
	}
	val archiveCount = lineage.archiveDays.asSequence()
		.filter { it.dayIdentity == day.identity.value }
		.map { it.archiveIdentity }
		.distinct()
		.count()
	if (archiveCount >= ImportedAmbientStepsDao.MAX_ARCHIVES_PER_DAY) {
		ambientStepsImportOverflow()
	}
	if (!importedAmbientStepsLineageAdditionFits(
			existingArchiveMemberCount = lineage.archiveDays.size,
			existingReceiptCount = lineage.receipts.size,
			incomingArchiveMemberCount = request.archive.days.size,
		)
	) {
		ambientStepsImportOverflow()
	}
	val latestDurableTime = maxOf(
		lineage.revisions.lastOrNull()?.header?.receivedAtMs ?: 0L,
		lineage.receipts.maxOfOrNull { it.receivedAtMs } ?: 0L,
	)
	if (request.receipt.receivedAtMs < latestDurableTime) {
		blockAmbientStepsImport(PortableAmbientStepsImportBlockedReason.CORRECTION_CONFLICT)
	}
}

private fun authenticateAmbientStepsGraphSuccessor(
	request: AmbientImportEnvelope,
	existingGraph: PortableCountDomainGraphV2?,
	candidateGraph: PortableCountDomainGraphV2,
) {
	if (request.hasExplicitCountDomainGraphs &&
		!candidateGraph.hasCompletePortableOwnerLineages()
	) {
		blockAmbientStepsImport(PortableAmbientStepsImportBlockedReason.CORRECTION_CONFLICT)
	}
	if (request.hasExplicitCountDomainGraphs &&
		existingGraph != null &&
		existingGraph != candidateGraph &&
		!isAuthenticatedAmbientGraphSuccessor(existingGraph, candidateGraph)
	) {
		blockAmbientStepsImport(PortableAmbientStepsImportBlockedReason.CORRECTION_CONFLICT)
	}
}

internal suspend fun AppDatabase.insertAmbientStepsCountDomainGraph(
	plan: AmbientStepsDayImportPlan,
	request: AmbientImportEnvelope,
) {
	val graphDao = importedPortableStepsCountDomainDao()
	val stored = graphDao.graph(plan.graph.identity.value)
	if (stored == null) {
		graphDao.insertAuthenticatedGraph(
			plan.graph,
			ImportedPortableStepsCountDomainGraphEntity.SOURCE_AMBIENT_STEPS,
		)
	} else if (graphDao.authenticatedGraph(
			plan.graph.identity.value,
			ImportedPortableStepsCountDomainGraphEntity.SOURCE_AMBIENT_STEPS,
		) != plan.graph
	) {
		corruptAmbientStepsImport()
	}
	graphDao.insertBinding(
		ImportedPortableStepsCountDomainBindingEntity(
			productKind = ImportedPortableStepsCountDomainBindingEntity.PRODUCT_AMBIENT_DAY,
			productIdentity = plan.day.identity.value,
			productRevision = plan.graphRevision,
			graphIdentity = plan.graph.identity.value,
			sourceSchemaVersion = request.sourceSchemaVersion,
			sourceReceiptIdentity = ImportedAmbientStepsIdentity.receipt(
				request.receipt.jobId,
				request.receipt.archiveKey,
			),
			sourceArchiveIdentity = request.sourceArchiveIdentity.value,
			sourceArchiveContentChecksum = request.sourceArchiveContentChecksum.value,
		),
	)
}

internal suspend fun ImportedAmbientStepsDao.authenticateAmbientStepsCapacity(added: NewImportedAmbientStepsRows) {
	checkCapacity(archiveCount(), added.archives, ImportedAmbientStepsDao.MAX_GLOBAL_ARCHIVES)
	checkCapacity(receiptCount(), added.receipts, ImportedAmbientStepsDao.MAX_GLOBAL_RECEIPTS)
	checkCapacity(
		archiveDayCount(),
		added.archiveDays,
		ImportedAmbientStepsDao.MAX_GLOBAL_ARCHIVE_DAYS,
	)
	checkCapacity(
		dayRevisionCount(),
		added.dayRevisions,
		ImportedAmbientStepsDao.MAX_GLOBAL_DAY_REVISIONS,
	)
	checkCapacity(factCount(), added.facts, ImportedAmbientStepsDao.MAX_GLOBAL_FACTS)
	checkCapacity(gapCount(), added.gaps, ImportedAmbientStepsDao.MAX_GLOBAL_GAPS)
	checkCapacity(fenceCount(), 0L, ImportedAmbientStepsDao.MAX_GLOBAL_FENCES)
	checkCapacity(
		protectedIdentityCount(),
		0L,
		ImportedAmbientStepsDao.MAX_GLOBAL_PROTECTED_IDENTITIES,
	)
}

internal suspend fun AppDatabase.incrementAmbientStepsImportRevision(
	currentUpdatedAtMs: Long,
	changedAtMs: Long,
) {
	check(
		sourceEvidenceStateDao().incrementRevision(
			maxOf(currentUpdatedAtMs, changedAtMs),
		) == 1,
	) { "Ambient Steps import could not advance source-evidence revision" }
}

private fun checkCapacity(current: Long, added: Long, maximum: Long) {
	try {
		if (current < 0L || added < 0L || Math.addExact(current, added) > maximum) {
			ambientStepsImportOverflow()
		}
	} catch (_: ArithmeticException) {
		ambientStepsImportOverflow()
	}
}
