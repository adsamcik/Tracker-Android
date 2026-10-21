package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity

/** Called by source erase after recording has been revoked, in the payload deletion transaction. */
suspend fun AppDatabase.fenceAmbientSensorConsentErasureInTransaction(
	sourceKind: Int,
	dataEpoch: Long,
	deletedAtMs: Long,
) {
	check(inTransaction())
	check(sourceEvidenceStateDao().get()?.collectedDataEpoch == dataEpoch)
	val purpose = SourceBrokerPurpose.AMBIENT_PRODUCT
	val consent = sourcePolicyDao().latestConsentEpoch(sourceKind, purpose) ?: return
	require(!consent.eligible && !consent.persistenceEligible) {
		"All-day recording must be stopped before erasing its data"
	}
	val dao = sourceDeletionFenceDao()
	val identity = SourceDeletionFenceEntity.sourceConsentIdentity(sourceKind, purpose, dataEpoch)
	val previous = dao.get(sourceKind, purpose, SourceDeletionFenceEntity.SCOPE_SOURCE_CONSENT_EPOCH, identity)
	if (previous != null && previous.fenceGeneration > consent.epoch) {
		return
	}
	dao.upsert(SourceDeletionFenceEntity.createSourceConsentEpoch(sourceKind, purpose, consent.epoch,
		dataEpoch, maxOf(deletedAtMs, previous?.deletedAtMs ?: 0L)))
}

/** Live replay checks this boundary; an explicit user file import does not become live capture. */
suspend fun AppDatabase.isAmbientSensorConsentErased(
	sourceKind: Int,
	consentEpoch: Long,
	dataEpoch: Long,
): Boolean {
	val purpose = SourceBrokerPurpose.AMBIENT_PRODUCT
	val fence = sourceDeletionFenceDao().get(sourceKind, purpose,
		SourceDeletionFenceEntity.SCOPE_SOURCE_CONSENT_EPOCH,
		SourceDeletionFenceEntity.sourceConsentIdentity(sourceKind, purpose, dataEpoch))
	return fence != null && consentEpoch < fence.fenceGeneration
}
