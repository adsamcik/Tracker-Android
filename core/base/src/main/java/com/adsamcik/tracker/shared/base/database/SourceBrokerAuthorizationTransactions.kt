package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull

/**
 * Rotates the callback authorization of every same-boot physical registration at one observed-time
 * boundary. The caller must already own the Room transaction that changes the corresponding
 * policy, consent, manifest, or demand authority.
 */
suspend fun AppDatabase.rotateCurrentSourceAuthorizationInTransaction(
	sourceKind: Int,
	bootId: String,
	elapsedRealtimeNanos: Long,
	wallTimeMs: Long,
) {
	require(sourceKind > 0)
	require(bootId.isNotBlank())
	require(elapsedRealtimeNanos >= 0L)
	require(wallTimeMs >= 0L)
	val dao = sourceBrokerDao()
	val demands = dao.authorizationDemands(sourceKind)
	val fingerprint = SourceBrokerAuthorization.fingerprint(demands)
	dao.currentPhysicalRegistrations(sourceKind)
		.filter { registration -> registration.clockDomainId == bootId }
		.forEach { registration ->
			val current = dao.latestAuthorization(sourceKind, registration.registrationGeneration)
				.toAuthorizationSnapshotOrNull()
			if (current?.authorizationFingerprint == fingerprint) return@forEach
			val revision = dao.maximumAuthorizationRevision(sourceKind) + 1L
			check(revision > 0L) { "Source authorization revision exhausted for $sourceKind" }
			dao.insertAuthorizations(
				SourceBrokerAuthorization.rows(
					sourceKind = sourceKind,
					registrationGeneration = registration.registrationGeneration,
					authorizationRevision = revision,
					demands = demands,
					effectiveBootId = bootId,
					effectiveElapsedRealtimeNanos = elapsedRealtimeNanos,
					effectiveWallTimeMs = wallTimeMs,
				),
			)
		}
}

/**
 * Immediately closes the selected policy purposes without waiting for Android provider teardown.
 * RETIRING demands continue to keep the physical callback barrier alive, but are excluded from the
 * new authorization revision and therefore cannot admit observations at or after this boundary.
 */
suspend fun AppDatabase.fenceSourcePurposesInTransaction(
	sourceKind: Int,
	purposes: Collection<String>,
	bootId: String,
	elapsedRealtimeNanos: Long,
	wallTimeMs: Long,
): Int {
	if (purposes.isEmpty()) return 0
	val updated = sourceBrokerDao().markSourcePurposesRetiring(
		sourceKind = sourceKind,
		purposes = purposes,
		bootId = bootId,
		elapsedRealtimeNanos = elapsedRealtimeNanos,
		wallTimeMs = wallTimeMs,
	)
	rotateCurrentSourceAuthorizationInTransaction(
		sourceKind = sourceKind,
		bootId = bootId,
		elapsedRealtimeNanos = elapsedRealtimeNanos,
		wallTimeMs = wallTimeMs,
	)
	return updated
}
