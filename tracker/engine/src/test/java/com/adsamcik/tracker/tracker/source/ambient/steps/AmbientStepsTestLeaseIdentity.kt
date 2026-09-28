package com.adsamcik.tracker.tracker.source.ambient.steps

import com.adsamcik.tracker.tracker.api.AmbientReconciliationIdentity
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.runtime.LiveAmbientRetentionSnapshot

internal fun LiveAmbientRetentionSnapshot.stepsTestLeaseIdentity() =
	grants.getValue(SourceKind.STEPS).let { grant ->
		AmbientReconciliationIdentity(
			source = AmbientTrackingSource.STEPS,
			policyRevision = grant.sourcePolicyRevision,
			consentEpoch = grant.ambientConsentEpoch,
			collectedDataEpoch = grant.collectedDataEpoch,
			rolloutRevision = 1L,
			ownerCasToken = "ambient-steps-test-owner",
			executionRevision = 1L,
			retainedFromMs = grant.retainedFromMs,
			retentionPolicyId = grant.opaquePolicyId,
			retentionApprovalRevision = grant.approvalRevision,
		)
	}
