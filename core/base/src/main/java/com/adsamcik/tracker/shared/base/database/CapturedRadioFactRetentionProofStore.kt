package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.CapturedRadioFactRetentionProofEntity

/**
 * Persists an exact proof set idempotently. A primary-key or event collision with different
 * authority is corruption and must roll back the enclosing retention transaction.
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
	require(proofs.distinctBy { it.sourceKind to it.sourceEventId }.size == proofs.size)
	val dao = capturedRadioFactRetentionProofDao()
	for (proof in proofs) {
		require(proof.hasValidStoredIntegrity())
		val factCollision = dao.byFact(
			proof.sourceKind,
			proof.writerProjectionId,
			proof.writerProjectionVersion,
			proof.logicalFactId,
		)
		val eventCollision = dao.byEvent(proof.sourceKind, proof.sourceEventId)
		if (factCollision != null || eventCollision != null) {
			check(factCollision == proof && eventCollision == proof) {
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
		) == proof && dao.byEvent(proof.sourceKind, proof.sourceEventId) == proof) {
			"Captured radio retention proof did not round-trip exactly"
		}
	}
}
