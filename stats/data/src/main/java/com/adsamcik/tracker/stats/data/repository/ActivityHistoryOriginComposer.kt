package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.ImportedActivityProductEvaluation
import com.adsamcik.tracker.shared.base.database.ImportedActivityProductFailure
import com.adsamcik.tracker.shared.base.database.PortableActivityEntryV1
import com.adsamcik.tracker.shared.base.database.PortableActivityIdentityKind
import com.adsamcik.tracker.shared.base.database.PortableActivityOpaqueIdentity
import com.adsamcik.tracker.shared.base.database.PortableActivityOpaqueOwnershipVerifier
import com.adsamcik.tracker.shared.base.database.RetainedImportedActivityIdentity
import com.adsamcik.tracker.shared.base.database.dao.ImportedActivityHistoryCandidate
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntry
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryOrigin

/** Pure local/imported merge. Only exact full-v1 equality may suppress an imported origin. */
internal object ActivityHistoryOriginComposer {
	fun compose(
		live: List<ActivityHistoryEntry>,
		liveLogicalTrackingIds: Set<String>,
		imported: List<ImportedActivityProductEvaluation>,
		localPortableEntriesByIdentity: Map<String, PortableActivityEntryV1>,
		localProtectedIdentitiesByEntry:
			Map<PortableActivityOpaqueIdentity, List<RetainedImportedActivityIdentity>> = emptyMap(),
		limit: Int,
	): List<ActivityHistoryEntry> {
		require(limit > 0)
		val publicImported = composeImported(
			liveLogicalTrackingIds,
			imported,
			localPortableEntriesByIdentity,
			localProtectedIdentitiesByEntry,
		).mapNotNull(ComposedImportedActivityEntry::entry)
		return (live + publicImported).sortedWith(
			compareByDescending<ActivityHistoryEntry> { it.startTime.raw }
				.thenByDescending { it.endTime.raw }
				.thenBy { it.origin != ActivityHistoryOrigin.LOCAL },
		).take(limit)
	}

	fun composeImported(
		liveLogicalTrackingIds: Set<String>,
		imported: List<ImportedActivityProductEvaluation>,
		localPortableEntriesByIdentity: Map<String, PortableActivityEntryV1>,
		localProtectedIdentitiesByEntry:
			Map<PortableActivityOpaqueIdentity, List<RetainedImportedActivityIdentity>> = emptyMap(),
	): List<ComposedImportedActivityEntry> {
		val localIdentities = liveLogicalTrackingIds.mapTo(hashSetOf()) { logicalId ->
			PortableActivityOpaqueIdentity.derive(
				PortableActivityIdentityKind.LOGICAL_ENTRY,
				logicalId,
			).value
		}
		localIdentities += localPortableEntriesByIdentity.keys
		localIdentities += localProtectedIdentitiesByEntry.keys.map { it.value }
		val ownership = PortableActivityOpaqueOwnershipVerifier.fromEntries(
			localPortableEntriesByIdentity.values,
		) ?: throw ImportedActivityHistoryCompositionFailure()
		if (localProtectedIdentitiesByEntry.any { (entry, identities) ->
			!ownership.tryInclude(entry, identities)
		}) throw ImportedActivityHistoryCompositionFailure()
		return imported.map { evaluation ->
			if (!evaluation.candidate.hasSafePublicShell()) {
				throw ImportedActivityHistoryCompositionFailure()
			}
			val collision = evaluation.candidate.identity in localIdentities
			val exactLocal = localPortableEntriesByIdentity[evaluation.candidate.identity]
			val ownershipConflict = when (evaluation) {
				is ImportedActivityProductEvaluation.Readable -> !ownership.tryInclude(evaluation.entry)
				is ImportedActivityProductEvaluation.Retained -> !ownership.tryInclude(
					PortableActivityOpaqueIdentity(evaluation.candidate.identity),
					evaluation.protectedIdentities,
				)
				is ImportedActivityProductEvaluation.Unverifiable -> false
			}
			if (!ownershipConflict && collision &&
				evaluation is ImportedActivityProductEvaluation.Readable &&
				evaluation.isReExportable && exactLocal == evaluation.entry
			) {
				ComposedImportedActivityEntry(evaluation, null)
			} else {
				val originConflict = ownershipConflict || (
					collision && when (evaluation) {
						is ImportedActivityProductEvaluation.Readable -> exactLocal != evaluation.entry
						is ImportedActivityProductEvaluation.Retained -> true
						is ImportedActivityProductEvaluation.Unverifiable -> true
					}
				)
				val entry = try {
					evaluation.toPublicActivityEntry(originConflict = originConflict)
				} catch (_: ArithmeticException) {
					ImportedActivityProductEvaluation.Unverifiable(
						evaluation.candidate,
						ImportedActivityProductFailure.VALUE_OVERFLOW,
					).toPublicActivityEntry()
				} catch (_: RuntimeException) {
					ImportedActivityProductEvaluation.Unverifiable(
						evaluation.candidate,
						ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
					).toPublicActivityEntry()
				}
				ComposedImportedActivityEntry(evaluation, entry)
			}
		}
	}
}

internal data class ComposedImportedActivityEntry(
	val evaluation: ImportedActivityProductEvaluation,
	val entry: ActivityHistoryEntry?,
)

internal class ImportedActivityHistoryCompositionFailure : RuntimeException(null, null, false, false)

private fun ImportedActivityHistoryCandidate.hasSafePublicShell(): Boolean = try {
	PortableActivityOpaqueIdentity(identity)
	importRevision > 0L && startTimeMs >= 0L && endTimeMs >= startTimeMs && receivedAtMs >= 0L
} catch (_: IllegalArgumentException) {
	false
}
