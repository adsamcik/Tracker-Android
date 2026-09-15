package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluation
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableWifiIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortableWifiOpaqueOwnershipVerifier
import com.adsamcik.tracker.stats.api.repository.PortableWifiOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.WifiHistoryEntry
import com.adsamcik.tracker.stats.api.repository.WifiHistoryOrigin

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
		val publicImported = imported.mapNotNull { evaluation ->
			val readable = evaluation as? ImportedWifiProductEvaluation.Readable
			val collision = readable?.collidingLocalLogicalTrackingId
			val ownershipConflict = readable != null && !ownership.tryInclude(readable.entry)
			if (ownershipConflict) {
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
		}
		return (live.map(ComposedWifiEntry::entry) + publicImported).sortedWith(
			compareByDescending<WifiHistoryEntry> { it.startTime.raw }
				.thenByDescending { it.endTime.raw }
				.thenBy { it.origin != WifiHistoryOrigin.LOCAL },
		).take(limit)
	}
}

internal class ImportedWifiHistoryCompositionFailure : RuntimeException(null, null, false, false)
