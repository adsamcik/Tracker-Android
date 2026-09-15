package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.ImportedCellProductEvaluation
import com.adsamcik.tracker.shared.base.database.PortableCapturedCellEntryV1
import com.adsamcik.tracker.shared.base.database.PortableCellDigest
import com.adsamcik.tracker.shared.base.database.PortableCellOpaqueIdentity
import com.adsamcik.tracker.shared.base.database.PortableCellOpaqueOwnershipVerifier
import com.adsamcik.tracker.stats.api.repository.CellHistoryCause
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntry
import com.adsamcik.tracker.stats.api.repository.CellHistoryOrigin

/** Pure bounded merge. Only exact authenticated full-v1 equality suppresses an imported origin. */
internal object CellHistoryOriginComposer {
	fun compose(
		live: List<CellHistoryEntry>,
		visibleLocalEntryIdentities: Set<String>,
		localCollisionIdentities: Set<String>,
		imported: List<ImportedCellProductEvaluation>,
		localPortableEntriesByIdentity: Map<String, PortableCapturedCellEntryV1>,
		limit: Int,
	): List<CellHistoryEntry> {
		require(limit > 0)
		val ownership = PortableCellOpaqueOwnershipVerifier.fromEntries(
			localPortableEntriesByIdentity.values,
		) ?: throw ImportedCellHistoryCompositionFailure()
		val publicImported = imported.mapNotNull { evaluation ->
			if (!evaluation.candidate.hasSafePublicShell()) {
				throw ImportedCellHistoryCompositionFailure()
			}
			val collidesWithLocalEntry = evaluation.candidate.identity in localCollisionIdentities
			val exactLocal = localPortableEntriesByIdentity[evaluation.candidate.identity]
			val ownershipConflict = when (evaluation) {
				is ImportedCellProductEvaluation.Readable -> !ownership.tryInclude(evaluation.entry)
				is ImportedCellProductEvaluation.Unverifiable -> false
			}
			if (!ownershipConflict && collidesWithLocalEntry &&
				evaluation is ImportedCellProductEvaluation.Readable &&
				evaluation.isReExportable && exactLocal == evaluation.entry &&
				evaluation.candidate.identity in visibleLocalEntryIdentities
			) {
				null
			} else {
				val originConflict = ownershipConflict || (
					collidesWithLocalEntry && when (evaluation) {
						is ImportedCellProductEvaluation.Readable -> exactLocal != evaluation.entry
						is ImportedCellProductEvaluation.Unverifiable -> true
					}
				)
				try {
					evaluation.toPublicCellEntry(
						overrideFailure = CellHistoryCause.ORIGIN_IDENTITY_CONFLICT
							.takeIf { originConflict },
					)
				} catch (_: ArithmeticException) {
					evaluation.toPublicCellEntry(
						overrideFailure = CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE,
					)
				} catch (_: RuntimeException) {
					evaluation.toPublicCellEntry(
						overrideFailure = CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE,
					)
				}
			}
		}
		return (live + publicImported).sortedWith(
			compareByDescending<CellHistoryEntry> { it.startTime.raw }
				.thenByDescending { it.endTime.raw }
				.thenBy { it.origin != CellHistoryOrigin.Local },
		).take(limit)
	}
}

internal class ImportedCellHistoryCompositionFailure : RuntimeException(null, null, false, false)

private fun com.adsamcik.tracker.shared.base.database.dao.ImportedCellHistoryCandidate
	.hasSafePublicShell(): Boolean = try {
	PortableCellOpaqueIdentity(identity)
	PortableCellDigest(contentChecksum)
	importRevision > 0L && startTimeMs >= 0L && endTimeMs >= startTimeMs && receivedAtMs >= 0L
} catch (_: IllegalArgumentException) {
	false
}
