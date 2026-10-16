package com.adsamcik.tracker.tracker.source.ambient

import com.adsamcik.tracker.tracker.api.AmbientReconciliationLease
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalAvailability
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalState
import com.adsamcik.tracker.tracker.api.AmbientSourceReconciliationReport
import com.adsamcik.tracker.tracker.api.AmbientSourceReconciliationResult
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.runtime.AmbientRadioReconciliationAuthority
import com.adsamcik.tracker.tracker.source.runtime.SourceProviderKey

/**
 * Exact source-owner evidence returned to the parent CAS publisher.
 *
 * The parent-owned lease token remains opaque. Provider identity, writer execution generation,
 * authority revisions, and the owner-local attempt are retained separately rather than being
 * encoded into a success-shaped global availability value.
 */
data class AmbientRadioReconciliationEvidence(
	val source: AmbientTrackingSource,
	val policyRevision: Long?,
	val ambientConsentEpoch: Long?,
	val collectedDataEpoch: Long?,
	val retainedFromMs: Long? = null,
	val rolloutRevision: Long,
	val executionGeneration: Long?,
	val authorityRevision: Long?,
	val ownerCasToken: String?,
	val reconciliationAttempt: Long,
	val authorityReconciliationAttempt: Long?,
	val demandId: String?,
	val providerKey: SourceProviderKey?,
) {
	init {
		require(policyRevision == null || policyRevision > 0L)
		require(ambientConsentEpoch == null || ambientConsentEpoch >= 0L)
		require(collectedDataEpoch == null || collectedDataEpoch >= 0L)
		require(retainedFromMs == null || retainedFromMs >= 0L)
		require(rolloutRevision >= 0L)
		require(executionGeneration == null || executionGeneration > 0L)
		require(authorityRevision == null || authorityRevision > 0L)
		require(ownerCasToken == null || ownerCasToken.isNotBlank())
		require(reconciliationAttempt > 0L)
		require(authorityReconciliationAttempt == null || authorityReconciliationAttempt > 0L)
		require(demandId == null || demandId.isNotBlank())
		require((executionGeneration == null) == (authorityRevision == null))
		require((ownerCasToken == null) == (authorityReconciliationAttempt == null))
	}

	/** Creates reconciliation evidence from the exact broker authority and provider registration. */
	companion object {
		internal fun from(
			authority: AmbientRadioReconciliationAuthority,
			reconciliationAttempt: Long,
			demandId: String?,
			sourceInstanceId: SourceInstanceId?,
			registrationGeneration: Long?,
		): AmbientRadioReconciliationEvidence =
			AmbientRadioReconciliationEvidence(
				source = authority.source.toAmbientTrackingSource(),
				policyRevision = authority.policyRevision,
				ambientConsentEpoch = authority.ambientConsentEpoch,
				collectedDataEpoch = authority.collectedDataEpoch,
				retainedFromMs = authority.retainedFromMs,
				rolloutRevision = authority.rolloutRevision,
				executionGeneration = authority.executionGeneration,
				authorityRevision = authority.authorityRevision,
				ownerCasToken = authority.ownerCasToken,
				reconciliationAttempt = reconciliationAttempt,
				authorityReconciliationAttempt = authority.reconciliationAttempt,
				demandId = demandId,
				providerKey = if (sourceInstanceId != null && registrationGeneration != null) {
					SourceProviderKey(sourceInstanceId, registrationGeneration)
				} else {
					null
				},
			)
	}
}

/** Admission result for publishing an all-day radio reconciliation under its current purpose lease. */
sealed interface AmbientRadioReportPreparation {
	/** The reconciled provider outcome and evidence are ready for guarded status publication. */
	data class Prepared(
		val result: AmbientSourceReconciliationResult,
		val evidence: AmbientRadioReconciliationEvidence,
	) : AmbientRadioReportPreparation

	/**
	 * The outcome cannot be published for this lease; optional evidence identifies the rejected attempt.
	 */
	data class Rejected(
		val evidence: AmbientRadioReconciliationEvidence?,
		val reason: AmbientRadioReportPreparationRejection,
	) : AmbientRadioReportPreparation
}

/** Explains which source, authority, attempt or provider-settlement check prevented publication. */
enum class AmbientRadioReportPreparationRejection {
	SOURCE_MISMATCH,
	STALE_AUTHORITY,
	NOT_RECONCILED,
	OPERATIONAL_PROVIDER_UNSETTLED,
}

internal fun prepareAmbientRadioReport(
	lease: AmbientReconciliationLease,
	evidence: AmbientRadioReconciliationEvidence,
	availability: AmbientSourceOperationalAvailability,
): AmbientRadioReportPreparation {
	ambientRadioReportRejection(lease, evidence, availability)?.let { reason ->
		return AmbientRadioReportPreparation.Rejected(evidence, reason)
	}
	val report = AmbientSourceReconciliationReport(
		identity = lease.identity,
		availability = availability,
	)
	val result = if (availability.isOperational) {
		AmbientSourceReconciliationResult.Reconciled(report)
	} else {
		AmbientSourceReconciliationResult.Unavailable(report)
	}
	return AmbientRadioReportPreparation.Prepared(result, evidence)
}

private fun ambientRadioReportRejection(
	lease: AmbientReconciliationLease,
	evidence: AmbientRadioReconciliationEvidence,
	availability: AmbientSourceOperationalAvailability,
): AmbientRadioReportPreparationRejection? {
	if (lease.identity.source != evidence.source || availability.source != evidence.source) {
		return AmbientRadioReportPreparationRejection.SOURCE_MISMATCH
	}
	if (!evidence.matchesLeaseAuthority(lease)) {
		return AmbientRadioReportPreparationRejection.STALE_AUTHORITY
	}
	return availability.operationalReportRejection(lease, evidence)
}

// Exact immutable source-policy, consent, data, retained floor, rollout and attempt tuple.
@Suppress("ComplexCondition", "CyclomaticComplexMethod")
private fun AmbientRadioReconciliationEvidence.matchesLeaseAuthority(lease: AmbientReconciliationLease): Boolean =
	policyRevision != null && ambientConsentEpoch != null && collectedDataEpoch != null &&
	lease.identity.policyRevision == policyRevision && lease.identity.consentEpoch == ambientConsentEpoch &&
	lease.identity.collectedDataEpoch == collectedDataEpoch && lease.identity.retainedFromMs == retainedFromMs &&
	lease.identity.rolloutRevision == rolloutRevision && lease.identity.ownerCasToken == ownerCasToken &&
	authorityReconciliationAttempt == reconciliationAttempt

private fun AmbientSourceOperationalAvailability.operationalReportRejection(
	lease: AmbientReconciliationLease,
	evidence: AmbientRadioReconciliationEvidence,
): AmbientRadioReportPreparationRejection? {
	if (isOperational && (evidence.executionGeneration == null || evidence.providerKey == null ||
		lease.identity.executionRevision != evidence.executionGeneration)
	) {
		return AmbientRadioReportPreparationRejection.OPERATIONAL_PROVIDER_UNSETTLED
	}
	if (state == AmbientSourceOperationalState.WAITING) {
		return AmbientRadioReportPreparationRejection.NOT_RECONCILED
	}
	return if (operationalIdentity != null && operationalIdentity != lease.purposeLeaseIdentity ||
		lastIdentity != null && lastIdentity != lease.purposeLeaseIdentity
	) {
		AmbientRadioReportPreparationRejection.STALE_AUTHORITY
	} else {
		null
	}
}

private fun com.adsamcik.tracker.tracker.source.model.SourceKind.toAmbientTrackingSource():
	AmbientTrackingSource = when (this) {
	com.adsamcik.tracker.tracker.source.model.SourceKind.WIFI -> AmbientTrackingSource.WIFI
	com.adsamcik.tracker.tracker.source.model.SourceKind.CELL -> AmbientTrackingSource.CELL
	else -> error("Ambient radio evidence cannot represent $this")
}
