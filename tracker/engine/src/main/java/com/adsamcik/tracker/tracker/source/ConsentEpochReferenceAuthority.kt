package com.adsamcik.tracker.tracker.source

import com.adsamcik.tracker.shared.base.database.dao.SourcePolicyDao
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity

/**
 * Validates an immutable policy-to-consent-epoch reference without coupling the epoch to the
 * policy revision that happens to reuse it.
 */
internal suspend fun SourcePolicyDao.hasAuthoritativeConsentReference(
	policy: SourcePolicyEntity,
	purpose: String,
	requirePersistenceEligible: Boolean,
): Boolean {
	val (referencedEpoch, policyPersistenceEligible) = when (purpose) {
		PURPOSE_SESSION_CAPTURE ->
			policy.captureConsentEpoch to policy.capturePersistenceEligible
		PURPOSE_CONTROL -> policy.controlConsentEpoch to policy.controlPersistenceEligible
		else -> return false
	}
	if (referencedEpoch == null ||
		(requirePersistenceEligible && !policyPersistenceEligible)
	) {
		return false
	}
	val referenced = consentEpoch(policy.sourceKind, purpose, referencedEpoch) ?: return false
	if (referenced.sourceKind != policy.sourceKind ||
		referenced.purpose != purpose ||
		referenced.epoch != referencedEpoch ||
		referenced.policyRevision > policy.policyRevision ||
		!referenced.eligible ||
		referenced.persistenceEligible != policyPersistenceEligible
	) {
		return false
	}
	val latest = latestConsentEpoch(policy.sourceKind, purpose) ?: return false
	return latest.sourceKind == policy.sourceKind &&
		latest.purpose == purpose &&
		latest.epoch == referencedEpoch &&
		latest.eligible &&
		latest.persistenceEligible == policyPersistenceEligible
}

private const val PURPOSE_SESSION_CAPTURE = "SESSION_CAPTURE"
private const val PURPOSE_CONTROL = "CONTROL"
