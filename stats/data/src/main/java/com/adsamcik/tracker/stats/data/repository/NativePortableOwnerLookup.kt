package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Resolves only exact requested keys. The caller then authenticates complete source membership. */
internal suspend fun AppDatabase.nativePortableOwnerSeeds(
	sourceKind: Int,
	identities: Sequence<String>,
	maximumOwners: Int,
): List<SessionSegment> {
	val owners = linkedSetOf<String>()
	for (batch in identities.chunked(256)) {
		currentCoroutineContext().ensureActive()
		owners += nativePortableOwnershipDao().ownerLogicalIds(sourceKind, batch.distinct(), maximumOwners + 1)
		if (owners.size > maximumOwners) throw NativePortableOwnerBudgetExceeded()
	}
	if (owners.isEmpty()) return emptyList()
	val seeds = nativePortableOwnershipDao().ownerSeeds(owners.toList(), maximumOwners + 1)
	if (seeds.size != owners.size || seeds.map { it.logicalTrackingId }.toSet() != owners) {
		throw NativePortableOwnerMembershipInvalid()
	}
	return seeds
}

internal class NativePortableOwnerBudgetExceeded : RuntimeException(null, null, false, false)
internal class NativePortableOwnerMembershipInvalid : RuntimeException(null, null, false, false)
