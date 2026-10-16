package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.model.SegmentSource

/** Real candidate rows with intentionally incomplete authority, unrelated to requested imports. */
internal suspend fun AppDatabase.insertUnrelatedNativePortableOwners(
	count: Int,
	sourceKind: Int,
	firstStartTimeMs: Long = 50_000L,
) {
	repeat(count) { index ->
		val logical = "unrelated-$sourceKind-logical-$index"
		val run = "unrelated-$sourceKind-run-$index"
		val segment = sessionSegmentDao().insert(SessionSegment(
			startTimeMs = firstStartTimeMs + index, endTimeMs = firstStartTimeMs + 1_000L + index, distanceM = 0f,
			steps = null, primaryActivity = null, activityConfidence = null, sampleCount = 0,
			source = SegmentSource.USER_CREATED, inferenceVersion = null, createdAt = 50_000L,
			logicalTrackingId = logical, serviceRunId = run,
		))
		sourceSessionDao().insertServiceRun(SourceServiceRunEntity(
			serviceRunId = run, logicalTrackingId = logical, state = "PREPARED",
			desiredPlanRevision = 1L, rolloutRevision = 1L, foregroundCapabilityFlags = 0L,
			startedAtMs = firstStartTimeMs, startedElapsedNanos = 1L, completedAtMs = null,
			completionReason = null, sessionSegmentId = segment,
		))
		sourceSessionDao().insertManifest(SessionManifestVersionEntity(
			logicalTrackingId = logical, manifestRevision = 1L, serviceRunId = run, sessionMode = "MANUAL",
			sourcePolicyRevision = 1L, acquisitionPlanRevision = 1L, rolloutRevision = 1L,
			startOrigin = "MANUAL", effectiveBootId = "unrelated-boot", effectiveElapsedRealtimeNanos = 1L,
			effectiveWallTimeMs = firstStartTimeMs, zoneId = "UTC", automationEpoch = null,
			changeReason = "test", manifestChecksum = "deliberately-unverifiable",
		))
		sourceSessionDao().insertManifestSources(listOf(SessionManifestSourceEntity(
			logicalTrackingId = logical, manifestRevision = 1L, sourceKind = sourceKind,
			purpose = "SESSION_CAPTURE", consentEpoch = 1L, persistenceEligible = true, qosCode = 1,
			outputDestination = if (sourceKind == 4) {
				SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE
			} else {
				null
			},
			writerOwner = if (sourceKind == 4) {
				SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS
			} else {
				null
			},
			writerOwnerGeneration = if (sourceKind == 4) {
				2L
			} else {
				null
			},
			writerProjectionId = if (sourceKind == 4) {
				SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID
			} else {
				null
			},
			writerProjectionVersion = if (sourceKind == 4) {
				SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION
			} else {
				null
			},
			writerBindingGeneration = if (sourceKind == 4) {
				SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION
			} else {
				null
			},
		)))
	}
}
