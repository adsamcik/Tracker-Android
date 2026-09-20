package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.data.ImportedStepsEntryEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedStepsRunEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainOwnerFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedIntegrity
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedRead
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedReader
import com.adsamcik.tracker.shared.base.database.steps.imported.RetainedImportedStepsEntry

/**
 * Checks intrinsically valid audit rows against exact parent membership in bounded SQL batches.
 * Complete retained receipts are authenticated once per entry after the raw audit, not per fact page.
 */
internal suspend fun AppDatabase.authenticateImportedStepsRetentionFacts(facts: List<StepFactRevisionEntity>) {
	if (facts.isEmpty()) {
		return
	}
	check(facts.all(StepFactRevisionIntegrity::hasValidPortableImportFact)) {
		"Imported retention encountered invalid intrinsic fact state"
	}
	val runIds = facts.map { requireNotNull(it.serviceRunId) }.distinct()
	val entryIds = facts.map { requireNotNull(it.logicalTrackingId) }.distinct()
	val dao = importedStepsDao()
	val runs = dao.runsForIdentities(runIds, runIds.size + 1).associateBy { it.identity }
	val entries = dao.entries(entryIds, entryIds.size + 1).associateBy { it.identity }
	check(runs.keys == runIds.toSet() && entries.keys == entryIds.toSet() && facts.all { fact ->
		runs[fact.serviceRunId]?.entryIdentity == fact.logicalTrackingId &&
			entries[fact.logicalTrackingId]?.collectedDataEpoch == fact.collectedDataEpoch
	}) { "Imported retention audit does not match exact parent authority" }
}

/** Full hierarchy, receipts and global revision lineage pass once for each entry before age selection. */
internal suspend fun AppDatabase.authenticateImportedStepsRetentionEntries() {
	visitImportedRetentionEntries { /* The shared reader authenticates the complete entry before visiting. */ }
}

/** Marks loss before a pending signal can defer physical pruning; this is not capture deletion. */
internal suspend fun AppDatabase.markImportedStepsRetentionFloor(beforeMs: Long, markedAtMs: Long): Int {
	var inserted = 0
	visitImportedRetentionEntries { entry ->
		entry.runs.filter { run -> entry.crossesFloor(run, beforeMs) }.forEach { run ->
			if (installImportedRetentionFence(entry, run, markedAtMs, StepFactRevisionIntegrity.RETENTION_TRUNCATION_PURPOSE)) {
				inserted++
			}
		}
	}
	return inserted
}

/** Removes only authenticated expired revisions, keeping a verifiable, explicitly partial suffix. */
internal suspend fun AppDatabase.pruneImportedStepsRetentionFloor(beforeMs: Long, markedAtMs: Long): Int {
	var deleted = 0
	visitImportedRetentionEntries { entry ->
		var prunedEntry = false
		var authenticatedGraph: AuthenticatedImportedPortableGraphBinding? = null
		for (run in entry.runs) {
			if (entry.crossesFloor(run, beforeMs)) {
				installImportedRetentionFence(entry, run, markedAtMs, StepFactRevisionIntegrity.RETENTION_TRUNCATION_PURPOSE)
			}
			val expired = entry.factsByRun.getValue(run.identity).filter { requireNotNull(it.intervalEndTimeMs) < beforeMs }
			if (expired.isEmpty()) {
				continue
			}
			authenticatedGraph = fenceImportedPortableSessionFacts(
				entry = entry,
				runIdentity = run.identity,
				factIdentities = expired.mapTo(linkedSetOf()) { it.logicalFactId },
				fenceKind = ImportedPortableStepsCountDomainOwnerFenceEntity.FENCE_RETENTION,
				collectedDataEpoch = entry.metadata.collectedDataEpoch,
				fencedAtMs = markedAtMs,
			)
			deleted += deleteImportedRetentionFacts(expired)
			val removedIds = expired.mapTo(hashSetOf()) { it.logicalFactId }
			val portable = entry.portableRunsById.getValue(run.identity)
			val retained = portable.copy(facts = portable.facts.filterNot { it.identity.value in removedIds })
			val checksum = ImportedStepsRetainedIntegrity.runChecksum(entry.metadata, run, retained)
			check(importedStepsDao().updateRetainedChecksum(
				run.identity, requireNotNull(run.retainedChecksum), requireNotNull(run.sessionSegmentId), checksum,
			) == 1) { "Imported retention receipt changed during pruning" }
			prunedEntry = true
		}
		if (prunedEntry) {
			refreshImportedLegacySessionCountDomainBinding(
				entry.metadata.identity,
				requireNotNull(authenticatedGraph),
			)
		}
	}
	return deleted
}

/**
 * Removes expired imported presentation members and only their authenticated Steps payload together.
 *
 * The timestamp selects age, never ownership. Original opaque scope fences survive payload removal
 * and reject later re-import. Sibling runs, unrelated sources and redacted revisions are untouched.
 * Caller retains its startup-generation guard around this transaction, as for ordinary trip pruning.
 */
suspend fun AppDatabase.pruneImportedStepsSegmentsBefore(beforeMs: Long, markedAtMs: Long): Int = withTransaction {
	require(markedAtMs >= 0L)
	var deleted = 0
	visitImportedRetentionEntries { entry ->
		val expiredRuns = entry.runs.filter { it.endTimeMs < beforeMs }
		var authenticatedGraph: AuthenticatedImportedPortableGraphBinding? = null
		for (run in expiredRuns) {
			installImportedRetentionFence(entry, run, markedAtMs, StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE)
			authenticatedGraph = fenceImportedPortableSessionRun(
				entry = entry,
				runIdentity = run.identity,
				fenceKind = ImportedPortableStepsCountDomainOwnerFenceEntity.FENCE_RETENTION,
				collectedDataEpoch = entry.metadata.collectedDataEpoch,
				fencedAtMs = markedAtMs,
			)
			deleteImportedRetentionFacts(entry.factsByRun.getValue(run.identity))
			val segmentId = requireNotNull(run.sessionSegmentId)
			check(importedStepsDao().deleteRunExact(
				run.identity, entry.metadata.identity, segmentId, requireNotNull(run.retainedChecksum),
			) == 1) { "Imported trip retention membership changed" }
			check(sessionSegmentDao().deleteExact(segmentId, entry.metadata.identity, run.identity) == 1) {
				"Imported trip retention presentation binding changed"
			}
			deleted++
		}
		if (expiredRuns.isNotEmpty()) {
			if (importedStepsDao().deleteEntryIfEmpty(entry.metadata.identity) == 1) {
				removeImportedPortableSessionGraph(entry.metadata.identity)
			} else {
				refreshImportedLegacySessionCountDomainBinding(
					entry.metadata.identity,
					requireNotNull(authenticatedGraph),
				)
			}
		}
	}
	if (deleted > 0) {
		check(sourceEvidenceStateDao().incrementRevision(markedAtMs) == 1) {
			"Imported trip retention requires initialized source evidence"
		}
		enqueueAllStepsGoalRepairs()
	}
	deleted
}

private fun RetainedImportedStepsEntry.crossesFloor(run: ImportedStepsRunEntity, beforeMs: Long): Boolean =
	run.startTimeMs < beforeMs || factsByRun.getValue(run.identity).any {
		requireNotNull(it.intervalStartTimeMs) < beforeMs
	}

private suspend fun AppDatabase.installImportedRetentionFence(
	entry: RetainedImportedStepsEntry,
	run: ImportedStepsRunEntity,
	markedAtMs: Long,
	purpose: String,
): Boolean {
	val expected = SourceDeletionFenceEntity.createForOriginalRunDigest(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		purpose = purpose,
		scopeIdentityDigest = run.deletionScopeDigest,
		fenceGeneration = 1L,
		collectedDataEpoch = entry.metadata.collectedDataEpoch,
		deletedAtMs = markedAtMs,
	)
	val dao = sourceDeletionFenceDao()
	if (dao.insertIfAbsent(expected) != IMPORTED_INSERT_IGNORED) {
		return true
	}
	val existing = dao.get(expected.sourceKind, purpose, expected.scopeKind, expected.scopeIdentityDigest)
	check(existing != null && existing.collectedDataEpoch == expected.collectedDataEpoch &&
		existing.fenceGeneration == 1L
	) {
		"Imported retention encountered conflicting original scope authority"
	}
	return false
}

private suspend fun AppDatabase.deleteImportedRetentionFacts(facts: List<StepFactRevisionEntity>): Int {
	var deleted = 0
	facts.groupBy { Triple(it.writerProjectionId, it.writerProjectionVersion, it.semanticRevision) }
		.forEach { (writer, members) ->
			members.chunked(IMPORTED_FACT_DELETE_BATCH).forEach { batch ->
				val count = stepFactRevisionDao().deleteAuthenticatedUpsertRevisions(
					writer.first, writer.second, writer.third, batch.map { it.logicalFactId },
				)
				check(count == batch.size) { "Imported retention fact identities changed" }
				deleted += count
			}
		}
	return deleted
}

private suspend fun AppDatabase.visitImportedRetentionEntries(visit: suspend (RetainedImportedStepsEntry) -> Unit) {
	var before: ImportedStepsEntryEntity? = null
	while (true) {
		val page = importedStepsDao().entryPage(
			before?.startTimeMs, before?.identity, ImportedStepsRetainedReader.MAX_ENTRY_BATCH,
		)
		if (page.isEmpty()) {
			break
		}
		val entries = readImportedRetentionEntries(page.map { it.identity })
		entries.forEach { visit(it) }
		val last = page.last()
		check(last.identity != before?.identity) { "Imported retention entry page did not advance" }
		before = last
		if (page.size < ImportedStepsRetainedReader.MAX_ENTRY_BATCH) {
			break
		}
	}
}

/** The shared reader owns bounded dependency splitting; destructive callers require every entry. */
private suspend fun AppDatabase.readImportedRetentionEntries(
	identities: List<String>,
): List<RetainedImportedStepsEntry> {
	val result = mutableListOf<RetainedImportedStepsEntry>()
	for (batch in identities.chunked(ImportedStepsRetainedReader.MAX_ENTRY_BATCH)) {
		when (val read = ImportedStepsRetainedReader(this).readEntriesForRetentionInTransaction(batch)) {
			is ImportedStepsRetainedRead.Ready -> {
				check(read.unverifiableEntries.isEmpty() && read.entries.size == batch.size) {
					"Imported retention requires every selected entry to authenticate"
				}
				result += read.entries
			}
			is ImportedStepsRetainedRead.Unverifiable -> {
				error("Imported retention encountered unverifiable entry authority")
			}
		}
	}
	return result
}

private const val IMPORTED_INSERT_IGNORED = -1L
private const val IMPORTED_FACT_DELETE_BATCH = 256
