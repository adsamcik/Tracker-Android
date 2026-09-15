package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluation
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableWifiIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortableWifiOpaqueOwnershipVerifier
import com.adsamcik.tracker.stats.api.repository.PortableWifiOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.WifiHistoryEntry

/** Pure origin merge. Wall overlap and count equality never establish Wi-Fi ownership. */
internal object WifiHistoryOriginComposer {
	fun compose(
		live: List<ComposedWifiEntry>,
		imported: List<ImportedWifiProductEvaluation>,
		localPortableByLogicalId: Map<String, PortableCapturedWifiEntryV1>,
		limit: Int,
	): List<WifiHistoryEntry> {
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
				OriginWifiRow(
					entry = it,
					recencyStartTimeMs = evaluation.candidate.newestMemberStartTimeMs,
					stableTie = evaluation.candidate.newestMemberIdentity.value,
					originOrder = 1,
				)
			}
		}
		val localRows = live.map { composed ->
			OriginWifiRow(
				entry = composed.entry,
				recencyStartTimeMs = composed.recencyStartTimeMs,
				stableTie = composed.recencySegmentId.toString().padStart(20, '0'),
				originOrder = 0,
			)
		}
		return (localRows + importedRows).sortedWith(
			compareByDescending<OriginWifiRow> { it.recencyStartTimeMs }
				.thenBy(OriginWifiRow::originOrder)
				.thenByDescending(OriginWifiRow::stableTie),
		).take(limit).map(OriginWifiRow::entry)
	}
}

private data class OriginWifiRow(
	val entry: WifiHistoryEntry,
	val recencyStartTimeMs: Long,
	val stableTie: String,
	val originOrder: Int,
)

internal class ImportedWifiHistoryCompositionFailure : RuntimeException(null, null, false, false)
