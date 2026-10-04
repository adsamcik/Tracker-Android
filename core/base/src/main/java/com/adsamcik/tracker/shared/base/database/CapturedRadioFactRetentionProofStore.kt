package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.CapturedRadioFactRetentionProofEntity

/**
 * Persists each independently qualified fact idempotently. Sibling facts may share one raw
 * event; a changed fact key or conflicting raw event identity rolls back retention.
 */
suspend fun AppDatabase.persistCapturedRadioFactRetentionProofs(
	proofs: List<CapturedRadioFactRetentionProofEntity>,
) {
	require(proofs.distinctBy { proof ->
		listOf(
			proof.sourceKind,
			proof.writerProjectionId,
			proof.writerProjectionVersion,
			proof.logicalFactId,
		)
	}.size == proofs.size)
	val dao = capturedRadioFactRetentionProofDao()
	for (proof in proofs) {
		require(proof.hasValidStoredIntegrity())
		val factCollision = dao.byFact(
			proof.sourceKind,
			proof.writerProjectionId,
			proof.writerProjectionVersion,
			proof.logicalFactId,
		)
		check(!dao.hasConflictingEventIdentity(
			proof.sourceKind, proof.sourceEventId, proof.sourceAdmissionOrdinal,
			proof.collectedDataEpoch, proof.walIntegrityIdentity,
		)) { "Captured radio retention event identity collision" }
		if (factCollision != null) {
			check(factCollision == proof) {
				"Captured radio retention proof identity collision"
			}
			continue
		}
		check(dao.insert(proof) != -1L) {
			"Captured radio retention proof changed during insertion"
		}
		check(dao.byFact(
			proof.sourceKind,
			proof.writerProjectionId,
			proof.writerProjectionVersion,
			proof.logicalFactId,
		) == proof) {
			"Captured radio retention proof did not round-trip exactly"
		}
	}
}
