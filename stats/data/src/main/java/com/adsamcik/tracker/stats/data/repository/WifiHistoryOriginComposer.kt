package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluation
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableWifiIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortableWifiOpaqueOwnershipVerifier
import com.adsamcik.tracker.stats.api.repository.PortableWifiOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCause
import com.adsamcik.tracker.stats.api.repository.WifiHistoryEntry

/** Pure origin merge. Wall overlap and count equality never establish Wi-Fi ownership. */
internal object WifiHistoryOriginComposer {
	fun compose(
		live: List<ComposedWifiEntry>,
		imported: List<ImportedWifiProductEvaluation>,
		localPortableByLogicalId: Map<String, PortableCapturedWifiEntryV1>,
		limit: Int,
	): List<WifiHistoryEntry> = composeRows(
		live,
		imported,
		localPortableByLogicalId,
		limit,
	).map(OriginWifiRow::entry)

	fun composeSourceRecent(
		live: List<ComposedWifiEntry>,
		imported: List<ImportedWifiProductEvaluation>,
		localPortableByLogicalId: Map<String, PortableCapturedWifiEntryV1>,
		limit: Int,
	): WifiSourceRecentPage {
		val rows = composeRows(live, imported, localPortableByLogicalId, limit)
		val entries = ArrayList<WifiSourceRecentEntry>(rows.size)
		for (row in rows) {
			when (row) {
				is OriginWifiRow.Local -> entries += WifiSourceRecentEntry.Local(row.composed)
				is OriginWifiRow.Imported -> {
					val selection = row.entry.importedSelection
						?: return WifiSourceRecentPage.Failed(
							row.entry.causes.firstOrNull { it.isIntegrityFailure }
								?: WifiHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE,
						)
					if (selection != row.evaluation.candidate.selection) {
						return WifiSourceRecentPage.Failed(
							WifiHistoryCause.ORIGIN_IDENTITY_CONFLICT,
						)
					}
					entries += WifiSourceRecentEntry.Imported(
						entry = row.entry,
						recencyStartTimeMs = row.recencyStartTimeMs,
						recencyIdentity = row.stableTie,
						selection = selection,
					)
				}
			}
		}
		return WifiSourceRecentPage.Available(entries)
	}

	private fun composeRows(
		live: List<ComposedWifiEntry>,
		imported: List<ImportedWifiProductEvaluation>,
		localPortableByLogicalId: Map<String, PortableCapturedWifiEntryV1>,
		limit: Int,
	): List<OriginWifiRow> {
		require(limit > 0)
		if (localPortableByLogicalId.any { (logicalId, entry) ->
				PortableWifiOpaqueIdentity.derive(
					PortableWifiIdentityKind.LOGICAL_ENTRY,
					logicalId,
				) != entry.identity
			}
		) throw ImportedWifiHistoryCompositionFailure()
		val liveLogicalIds = live.mapTo(hashSetOf(), ComposedWifiEntry::logicalTrackingId)
		val ownership = PortableWifiOpaqueOwnershipVerifier.fromEntries(
			localPortableByLogicalId.values,
		) ?: throw ImportedWifiHistoryCompositionFailure()
		val importedRows = imported.mapNotNull { evaluation ->
			val readable = evaluation as? ImportedWifiProductEvaluation.Readable
			val collision = readable?.collidingLocalLogicalTrackingId
			val ownershipConflict = readable != null && !ownership.tryInclude(readable.entry)
			val entry = if (ownershipConflict) {
				evaluation.toPublicWifiEntry(originConflict = true)
			} else if (collision == null) {
				evaluation.toPublicWifiEntry()
			} else {
				val colliding = requireNotNull(readable)
				val local = localPortableByLogicalId[collision]
				?: throw ImportedWifiHistoryCompositionFailure()
				if (colliding.isReExportable && colliding.entry == local) {
					if (collision in liveLogicalIds) null else evaluation.toPublicWifiEntry()
				} else {
					evaluation.toPublicWifiEntry(originConflict = true)
				}
			}
			entry?.let {
				OriginWifiRow.Imported(
					evaluation = evaluation,
					entry = it,
					recencyStartTimeMs = evaluation.candidate.newestMemberStartTimeMs,
					stableTie = evaluation.candidate.newestMemberIdentity.value,
				)
			}
		}
		val localRows = live.map { composed ->
			OriginWifiRow.Local(composed)
		}
		return (localRows + importedRows).sortedWith(
			compareByDescending<OriginWifiRow> { it.recencyStartTimeMs }
				.thenBy(OriginWifiRow::originOrder)
				.thenByDescending(OriginWifiRow::stableTie),
		).take(limit)
	}
}

private sealed interface OriginWifiRow {
	val entry: WifiHistoryEntry
	val recencyStartTimeMs: Long
	val stableTie: String
	val originOrder: Int

	data class Local(val composed: ComposedWifiEntry) : OriginWifiRow {
		override val entry: WifiHistoryEntry = composed.entry
		override val recencyStartTimeMs: Long = composed.recencyStartTimeMs
		override val stableTie: String = composed.recencySegmentId.toString().padStart(20, '0')
		override val originOrder: Int = 0
	}

	data class Imported(
		val evaluation: ImportedWifiProductEvaluation,
		override val entry: WifiHistoryEntry,
		override val recencyStartTimeMs: Long,
		override val stableTie: String,
	) : OriginWifiRow {
		override val originOrder: Int = 1
	}
}

internal class ImportedWifiHistoryCompositionFailure : RuntimeException(null, null, false, false)
