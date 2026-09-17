package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.AmbientStepsNativeReplayFootprintEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsNativeReplayFootprintIntegrity

/** Preserves value-free native portable identities before the common collected-data cascade. */
internal suspend fun AppDatabase.preserveAmbientStepsNativeReplayFootprintsForFullClearInCurrentTransaction(
	oldCollectedDataEpoch: Long,
	newCollectedDataEpoch: Long,
	protectedAtMs: Long,
) {
	require(newCollectedDataEpoch > oldCollectedDataEpoch)
	check(
		sourceEvidenceStateDao().get()?.collectedDataEpoch == oldCollectedDataEpoch,
	) { "Ambient Steps native replay protection observed a stale collected-data epoch" }
	val existing = authenticatedNativeReplayFootprints(oldCollectedDataEpoch)
	val existingIdentities = existing.mapTo(mutableSetOf()) {
		it.protectedIdentity
	}
	val current = AmbientStepsPortableLocalOriginReader(this)
		.readAllForFullClearInTransaction()
		.filterNot { it.identity in existingIdentities }
	replaceAmbientStepsNativeReplayFootprintsInCurrentTransaction(
		existing = existing,
		owners = current,
		newCollectedDataEpoch = newCollectedDataEpoch,
		protectedAtMs = protectedAtMs,
	)
}

/** Installs native replay protection before source deletion removes structural payload. */
internal suspend fun AppDatabase.installAmbientStepsNativeReplayFootprintsInCurrentTransaction(
	owners: List<AmbientStepsPortableLocalOwner>,
	expectedCollectedDataEpoch: Long,
	protectedAtMs: Long,
) {
	require(expectedCollectedDataEpoch >= 0L)
	require(protectedAtMs >= 0L)
	val existing = authenticatedNativeReplayFootprints(expectedCollectedDataEpoch)
	replaceAmbientStepsNativeReplayFootprintsInCurrentTransaction(
		existing = existing,
		owners = owners,
		newCollectedDataEpoch = expectedCollectedDataEpoch,
		protectedAtMs = protectedAtMs,
	)
}

internal suspend fun AppDatabase.authenticatedNativeReplayFootprints(
	expectedCollectedDataEpoch: Long,
): List<AmbientStepsNativeReplayFootprintEntity> {
	val dao = ambientStepsFactRevisionDao()
	val existingCount = dao.nativeReplayFootprintCount()
	if (existingCount > MAX_NATIVE_REPLAY_FOOTPRINTS) {
		throw AmbientStepsMaintenanceLimitExceeded(
			"Ambient Steps native replay footprint bound exceeded",
		)
	}
	val existing = dao.nativeReplayFootprintPage(MAX_NATIVE_REPLAY_FOOTPRINTS + 1)
	check(existing.size.toLong() == existingCount)
	check(existing.all {
		AmbientStepsNativeReplayFootprintIntegrity.isAuthentic(it) &&
			it.collectedDataEpoch == expectedCollectedDataEpoch
	})
	return existing
}

private suspend fun AppDatabase.replaceAmbientStepsNativeReplayFootprintsInCurrentTransaction(
	existing: List<AmbientStepsNativeReplayFootprintEntity>,
	owners: List<AmbientStepsPortableLocalOwner>,
	newCollectedDataEpoch: Long,
	protectedAtMs: Long,
) {
	val ownerRows = owners.map { owner ->
		when (owner.kind) {
			AmbientStepsPortableLocalOwnerKind.DAY ->
				AmbientStepsNativeReplayFootprintIntegrity.create(
					protectedIdentity = owner.identity,
					identityKind = AmbientStepsNativeReplayFootprintEntity.KIND_DAY,
					ownerDayIdentity = owner.identity,
					collectedDataEpoch = newCollectedDataEpoch,
					protectedAtMs = protectedAtMs,
				)
			AmbientStepsPortableLocalOwnerKind.FACT,
			AmbientStepsPortableLocalOwnerKind.GAP,
			-> AmbientStepsNativeReplayFootprintIntegrity.create(
				protectedIdentity = owner.identity,
				identityKind = when (owner.kind) {
					AmbientStepsPortableLocalOwnerKind.FACT ->
						AmbientStepsNativeReplayFootprintEntity.KIND_FACT
					AmbientStepsPortableLocalOwnerKind.GAP ->
						AmbientStepsNativeReplayFootprintEntity.KIND_GAP
					AmbientStepsPortableLocalOwnerKind.DAY -> error("Handled above")
				},
				ownerDayIdentity = checkNotNull(owner.ownerDayIdentity) {
					"Ambient Steps native replay footprint is missing exact day ownership"
				},
				collectedDataEpoch = newCollectedDataEpoch,
				protectedAtMs = protectedAtMs,
			)
		}
	}
	val reepoched = existing.map { value ->
		AmbientStepsNativeReplayFootprintIntegrity.create(
			protectedIdentity = value.protectedIdentity,
			identityKind = value.identityKind,
			ownerDayIdentity = value.ownerDayIdentity,
			collectedDataEpoch = newCollectedDataEpoch,
			protectedAtMs = maxOf(value.protectedAtMs, protectedAtMs),
		)
	}
	val merged = (reepoched + ownerRows)
		.associateBy(AmbientStepsNativeReplayFootprintEntity::protectedIdentity)
		.values
		.toList()
	if (merged.size > MAX_NATIVE_REPLAY_FOOTPRINTS) {
		throw AmbientStepsMaintenanceLimitExceeded(
			"Ambient Steps native replay footprint bound exceeded",
		)
	}
	if (merged.isNotEmpty()) {
		ambientStepsFactRevisionDao().replaceNativeReplayFootprints(merged)
	}
}
