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

sealed interface AmbientRadioReportPreparation {
	data class Prepared(
		val result: AmbientSourceReconciliationResult,
		val evidence: AmbientRadioReconciliationEvidence,
	) : AmbientRadioReportPreparation

	data class Rejected(
		val evidence: AmbientRadioReconciliationEvidence?,
		val reason: AmbientRadioReportPreparationRejection,
	) : AmbientRadioReportPreparation
}

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
	if (lease.identity.source != evidence.source || availability.source != evidence.source) {
		return AmbientRadioReportPreparation.Rejected(
			evidence,
			AmbientRadioReportPreparationRejection.SOURCE_MISMATCH,
		)
	}
	val actualPolicyRevision = evidence.policyRevision
	val actualConsentEpoch = evidence.ambientConsentEpoch
	val actualCollectedDataEpoch = evidence.collectedDataEpoch
	if (actualPolicyRevision == null || actualConsentEpoch == null ||
		actualCollectedDataEpoch == null ||
		lease.identity.policyRevision != actualPolicyRevision ||
		lease.identity.consentEpoch != actualConsentEpoch ||
		lease.identity.collectedDataEpoch != actualCollectedDataEpoch ||
		lease.identity.rolloutRevision != evidence.rolloutRevision ||
		lease.identity.ownerCasToken != evidence.ownerCasToken ||
		evidence.authorityReconciliationAttempt != evidence.reconciliationAttempt
	) {
		return AmbientRadioReportPreparation.Rejected(
			evidence,
			AmbientRadioReportPreparationRejection.STALE_AUTHORITY,
		)
	}
	if (availability.isOperational &&
		(
			evidence.executionGeneration == null ||
				evidence.providerKey == null ||
				lease.identity.executionRevision != evidence.executionGeneration
			)
	) {
		return AmbientRadioReportPreparation.Rejected(
			evidence,
			AmbientRadioReportPreparationRejection.OPERATIONAL_PROVIDER_UNSETTLED,
		)
	}
	if (availability.state == AmbientSourceOperationalState.WAITING) {
		return AmbientRadioReportPreparation.Rejected(
			evidence,
			AmbientRadioReportPreparationRejection.NOT_RECONCILED,
		)
	}
	if (
		availability.operationalIdentity != null &&
		availability.operationalIdentity != lease.purposeLeaseIdentity ||
		availability.lastIdentity != null &&
		availability.lastIdentity != lease.purposeLeaseIdentity
	) {
		return AmbientRadioReportPreparation.Rejected(
			evidence,
			AmbientRadioReportPreparationRejection.STALE_AUTHORITY,
		)
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

private fun com.adsamcik.tracker.tracker.source.model.SourceKind.toAmbientTrackingSource():
	AmbientTrackingSource = when (this) {
	com.adsamcik.tracker.tracker.source.model.SourceKind.WIFI -> AmbientTrackingSource.WIFI
	com.adsamcik.tracker.tracker.source.model.SourceKind.CELL -> AmbientTrackingSource.CELL
	else -> error("Ambient radio evidence cannot represent $this")
}
