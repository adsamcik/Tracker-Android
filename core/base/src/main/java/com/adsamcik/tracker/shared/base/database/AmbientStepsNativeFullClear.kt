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
	val dao = ambientStepsFactRevisionDao()
	val existingCount = dao.nativeReplayFootprintCount()
	check(existingCount <= MAX_NATIVE_REPLAY_FOOTPRINTS)
	val existing = dao.nativeReplayFootprintPage(MAX_NATIVE_REPLAY_FOOTPRINTS + 1)
	check(existing.size.toLong() == existingCount)
	check(existing.all(AmbientStepsNativeReplayFootprintIntegrity::isAuthentic))
	val current = AmbientStepsPortableLocalOriginReader(this)
		.readAllForFullClearInTransaction()
	val currentRows = current.mapNotNull { owner ->
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
			-> null
		}
	}
	val childRows = current.filter {
		it.kind != AmbientStepsPortableLocalOwnerKind.DAY
	}.map { owner ->
		val ownerDay = checkNotNull(owner.ownerDayIdentity) {
			"Ambient Steps native replay footprint is missing exact day ownership"
		}
		AmbientStepsNativeReplayFootprintIntegrity.create(
			protectedIdentity = owner.identity,
			identityKind = when (owner.kind) {
				AmbientStepsPortableLocalOwnerKind.FACT ->
					AmbientStepsNativeReplayFootprintEntity.KIND_FACT
				AmbientStepsPortableLocalOwnerKind.GAP ->
					AmbientStepsNativeReplayFootprintEntity.KIND_GAP
				AmbientStepsPortableLocalOwnerKind.DAY -> error("Handled above")
			},
			ownerDayIdentity = ownerDay,
			collectedDataEpoch = newCollectedDataEpoch,
			protectedAtMs = protectedAtMs,
		)
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
	val merged = (reepoched + currentRows + childRows)
		.associateBy(AmbientStepsNativeReplayFootprintEntity::protectedIdentity)
		.values
		.toList()
	check(merged.size <= MAX_NATIVE_REPLAY_FOOTPRINTS)
	if (merged.isNotEmpty()) dao.replaceNativeReplayFootprints(merged)
}

private const val MAX_NATIVE_REPLAY_FOOTPRINTS = 65_536
