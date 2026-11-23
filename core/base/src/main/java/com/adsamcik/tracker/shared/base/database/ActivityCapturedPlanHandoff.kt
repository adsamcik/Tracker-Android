package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedPlanIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity

/** Checks an already applied Activity plan against its same-session replacement in the caller's Room snapshot. */
suspend fun AppDatabase.hasCompatibleCapturedActivityAcquisition(
	manifest: SessionManifestVersionEntity,
	configurationRevision: Long,
	sourceInstanceId: String,
	registrationGeneration: Long,
	authorizationRevision: Long,
): Boolean {
	if (manifest.acquisitionPlanRevision == configurationRevision) {
		return true
	}
	val plan = activityCapturedFactDao().registrationPlanBinding(sourceInstanceId, registrationGeneration)
		?.takeIf { it.configurationRevision == configurationRevision } ?: return false
	val currentGrant = sourceBrokerDao().authorizationRevision(SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
		registrationGeneration, authorizationRevision).singleOrNull {
		it.purpose == SourceBrokerPurpose.SESSION_CAPTURE && it.persistenceEligible
	}
	val originalGrant = sourceBrokerDao().originalActivityPlanCaptureGrant(registrationGeneration,
		authorizationRevision, manifest.logicalTrackingId, manifest.serviceRunId, configurationRevision)
	val originalManifest = originalGrant?.manifestRevision?.let {
		sourceSessionDao().manifestByServiceRunRevision(manifest.serviceRunId, it)
	}
	val sources = originalManifest?.let {
		sourceSessionDao().manifestSources(it.logicalTrackingId, it.manifestRevision)
	}.orEmpty()
	val desired = sourcePlanStateDao().desiredPlans(manifest.acquisitionPlanRevision)
		.singleOrNull { it.sourceKind == SourceDestinationOwnerEntity.SOURCE_ACTIVITY }
	return originalManifest != null && SessionManifestIntegrity.verify(originalManifest, sources) &&
		ActivityCapturedPlanIntegrity.hasCompatibleManifestPlan(plan, manifest, desired, currentGrant,
			originalManifest, originalGrant)
}
