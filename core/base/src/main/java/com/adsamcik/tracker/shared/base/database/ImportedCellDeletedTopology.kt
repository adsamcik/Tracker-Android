package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.ImportedCellDeletedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellEntryDeletionReceiptEntity

private fun hasValidDeletedCellRunScopes(
	receipt: ImportedCellEntryDeletionReceiptEntity,
	runs: List<ImportedCellDeletedIdentityEntity>,
	scopes: List<ImportedCellDeletedIdentityEntity>,
	scopeByRun: Map<String?, List<ImportedCellDeletedIdentityEntity>>,
): Boolean {
	if (runs.any { run ->
			!run.hasExactDeletedCellScope(receipt, scopeByRun)
		}
	) {
		return false
	}
	if (runs.minOf { requireNotNull(it.runStartTimeMs) } != receipt.startTimeMs ||
		runs.maxOf { requireNotNull(it.runEndTimeMs) } != receipt.endTimeMs
	) {
		return false
	}
	if (runs.any { run ->
			run.contentChecksum == null || !run.includedInLatest
		} || scopes.any { !it.includedInLatest }
	) {
		return false
	}
	return true
}

private fun hasValidDeletedCellObservationOwners(
	markers: List<ImportedCellDeletedIdentityEntity>,
	observations: List<ImportedCellDeletedIdentityEntity>,
	runByIdentity: Map<String, ImportedCellDeletedIdentityEntity>,
): Boolean {
	if (observations.any { observation ->
			val parent = observation.runIdentity?.let(runByIdentity::get) ?: return@any true
			if (parent.entryIdentity != observation.entryIdentity) {
				return@any true
			}
			if (observation.contentChecksum == null) {
				return@any true
			}
			val ownerIdentity = observation.aggregateOwnerIdentity ?: return@any false
			val owner = markers.singleOrNull {
				it.protectedIdentity == ownerIdentity &&
					it.identityKind == ImportedCellDeletedIdentityEntity.OBSERVATION
			} ?: return@any true
			owner.runIdentity != observation.runIdentity ||
				owner.entryIdentity != observation.entryIdentity ||
				owner.aggregateOwnerIdentity != null
		}
	) {
		return false
	}
	return true
}

private fun hasExactDeletedCellContentChecksums(
	receipt: ImportedCellEntryDeletionReceiptEntity,
	runs: List<ImportedCellDeletedIdentityEntity>,
	observations: List<ImportedCellDeletedIdentityEntity>,
): Boolean {
	val latestRuns = runs.map { run ->
		val latestObservations = observations.filter {
			it.runIdentity == run.protectedIdentity && it.includedInLatest
		}.sortedBy(ImportedCellDeletedIdentityEntity::protectedIdentity)
		val expectedRunChecksum = deletedCellRunChecksum(run, latestObservations) ?: return false
		if (expectedRunChecksum != run.contentChecksum) {
			return false
		}
		run to latestObservations
	}
	val expectedEntryChecksum = deletedCellEntryChecksum(receipt, latestRuns) ?: return false
	return expectedEntryChecksum == receipt.deletedContentChecksum
}

private fun hasExactDeletedCellRunDeletions(
	receipt: ImportedCellEntryDeletionReceiptEntity,
	runDeletions: List<ImportedCellDeletionGenerationEntity>,
	runByIdentity: Map<String, ImportedCellDeletedIdentityEntity>,
): Boolean {
	val deletionByRun = runDeletions.associateBy(ImportedCellDeletionGenerationEntity::runIdentity)
	if (deletionByRun.size != receipt.expectedRunCount ||
		deletionByRun.keys != runByIdentity.keys ||
		runDeletions.any { marker ->
			marker.conflictsWithDeletedCellRun(receipt, runByIdentity)
		}
	) {
		return false
	}
	return true
}


internal fun hasValidDeletedCellTopology(
	deletion: ImportedCellEntryDeletionEntity,
	receipt: ImportedCellEntryDeletionReceiptEntity,
	markers: List<ImportedCellDeletedIdentityEntity>,
	runDeletions: List<ImportedCellDeletionGenerationEntity>,
): Boolean {
	if (deletion.deletedAtMs != receipt.deletedAtMs) {
		return false
	}
	val entry = markers.filter {
		it.identityKind == ImportedCellDeletedIdentityEntity.ENTRY
	}.singleOrNull()
	if (entry == null || entry.protectedIdentity != receipt.entryIdentity ||
		entry.entryIdentity != receipt.entryIdentity
	) {
		return false
	}
	val runs = markers.filter {
		it.identityKind == ImportedCellDeletedIdentityEntity.RUN
	}
	val scopes = markers.filter {
		it.identityKind == ImportedCellDeletedIdentityEntity.DELETION_SCOPE
	}
	val observations = markers.filter {
		it.identityKind == ImportedCellDeletedIdentityEntity.OBSERVATION
	}
	if (!hasExactDeletedCellMarkerCounts(receipt, markers, runs, scopes, observations)) {
		return false
	}
	val runByIdentity = runs.associateBy(ImportedCellDeletedIdentityEntity::protectedIdentity)
	val scopeByRun = scopes.groupBy(ImportedCellDeletedIdentityEntity::runIdentity)
	return hasValidDeletedCellRunScopes(receipt, runs, scopes, scopeByRun) &&
		hasValidDeletedCellObservationOwners(markers, observations, runByIdentity) &&
		hasExactDeletedCellContentChecksums(receipt, runs, observations) &&
		hasExactDeletedCellRunDeletions(receipt, runDeletions, runByIdentity)
}

@Suppress("ReturnCount") // Keep ordered missing-reference and rejection exits together.
private fun deletedCellRunChecksum(
	run: ImportedCellDeletedIdentityEntity,
	observations: List<ImportedCellDeletedIdentityEntity>,
): String? {
	val identity = run.runIdentity ?: return null
	val scope = run.deletionScopeDigest ?: return null
	val start = run.runStartTimeMs ?: return null
	val end = run.runEndTimeMs ?: return null
	val ordered = observations.sortedBy { it.observationOrdinal }
	if (ordered.map { it.observationOrdinal } != ordered.indices.toList()) {
		return null
	}
	return portableCellChecksum("tracker-portable-cell-run-v1") {
		writeCellString(identity)
		writeCellString(scope)
		writeLong(start)
		writeLong(end)
		writeCellString(run.captureCoverage)
		writeCellString(run.availability)
		writeCellString(run.acquisitionCompleteness)
		writeBoolean(requireNotNull(run.retentionLoss))
		writeCellString(run.subscriptionGrouping)
		writeInt(ordered.size)
		ordered.forEach { observation ->
			writeCellString(observation.protectedIdentity)
			writeCellString(requireNotNull(observation.contentChecksum))
		}
	}
}

private fun deletedCellEntryChecksum(
	receipt: ImportedCellEntryDeletionReceiptEntity,
	runs: List<Pair<ImportedCellDeletedIdentityEntity, List<ImportedCellDeletedIdentityEntity>>>,
): String? = portableCellChecksum("tracker-portable-cell-entry-v1") {
	writeCellString(CellCapturedPortableFormatV1.FORMAT)
	writeInt(CellCapturedPortableFormatV1.SCHEMA_VERSION)
	writeCellString(receipt.entryIdentity)
	writeCellString(receipt.sessionMode)
	writeLong(receipt.startTimeMs)
	writeLong(receipt.endTimeMs)
	writeCellString(receipt.subscriptionGrouping)
	writeInt(runs.size)
	runs.sortedWith(compareBy({ requireNotNull(it.first.runStartTimeMs) }, {
		requireNotNull(it.first.runIdentity)
	})).forEach { (run, _) ->
		writeCellString(requireNotNull(run.runIdentity))
		writeCellString(requireNotNull(run.contentChecksum))
	}
}

private fun portableCellChecksum(
	namespace: String,
	body: java.io.DataOutputStream.() -> Unit,
): String {
	val bytes = java.io.ByteArrayOutputStream().use { buffer ->
		java.io.DataOutputStream(buffer).use { output ->
			output.writeUTF(namespace)
			output.body()
		}
		buffer.toByteArray()
	}
	return java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
		.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun java.io.DataOutputStream.writeCellString(value: String?) {
	writeBoolean(value != null)
	if (value != null) {
		writeUTF(value)
	}
}

internal fun ImportedCellEntryDeletionEntity.hasExactImportedCellDeletionReceipt(
	receipt: ImportedCellEntryDeletionReceiptEntity,
): Boolean = entryIdentity == receipt.entryIdentity && collectedDataEpoch == receipt.collectedDataEpoch &&
	deletedImportRevision == receipt.deletedImportRevision && deletedAtMs == receipt.deletedAtMs

private fun hasExactDeletedCellMarkerCounts(
	receipt: ImportedCellEntryDeletionReceiptEntity, markers: List<ImportedCellDeletedIdentityEntity>,
	runs: List<ImportedCellDeletedIdentityEntity>, scopes: List<ImportedCellDeletedIdentityEntity>,
	observations: List<ImportedCellDeletedIdentityEntity>,
): Boolean = !(runs.size != receipt.expectedRunCount || scopes.size != receipt.expectedRunCount ||
		(receipt.expectedObservationCount > 0 && observations.isEmpty()) ||
		observations.size > receipt.expectedObservationCount ||
		runs.map { it.protectedIdentity }.distinct().size != runs.size ||
		scopes.map { it.protectedIdentity }.distinct().size != scopes.size ||
		markers.singleOrNull {
			it.identityKind == ImportedCellDeletedIdentityEntity.ENTRY
		}?.contentChecksum != receipt.deletedContentChecksum
)

private fun ImportedCellDeletedIdentityEntity.hasExactDeletedCellScope(
	receipt: ImportedCellEntryDeletionReceiptEntity,
	scopeByRun: Map<String?, List<ImportedCellDeletedIdentityEntity>>,
): Boolean = !(entryIdentity != receipt.entryIdentity || runIdentity != protectedIdentity ||
	deletionScopeDigest == null || scopeByRun[protectedIdentity].orEmpty().singleOrNull()?.let { scope ->
		scope.entryIdentity == receipt.entryIdentity && scope.protectedIdentity == deletionScopeDigest &&
			scope.deletionScopeDigest == deletionScopeDigest
	} != true)

private fun ImportedCellDeletionGenerationEntity.conflictsWithDeletedCellRun(
	receipt: ImportedCellEntryDeletionReceiptEntity, runByIdentity: Map<String, ImportedCellDeletedIdentityEntity>,
): Boolean {
	val run = runByIdentity[runIdentity] ?: return true
	return entryIdentity != receipt.entryIdentity || deletionScopeDigest != run.deletionScopeDigest ||
		collectedDataEpoch != receipt.collectedDataEpoch || generation != 1L || deletedAtMs != receipt.deletedAtMs
}
