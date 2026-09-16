package com.adsamcik.tracker.app.tracking

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.ActivityCapturedPortableIntegrity
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.CellCapturedPortableFormatV1
import com.adsamcik.tracker.shared.base.database.PortableActivityCaptureCoverage
import com.adsamcik.tracker.shared.base.database.PortableActivityDeletionScopeDigest
import com.adsamcik.tracker.shared.base.database.PortableActivityDigest
import com.adsamcik.tracker.shared.base.database.PortableActivityEntryV1
import com.adsamcik.tracker.shared.base.database.PortableActivityFragmentV1
import com.adsamcik.tracker.shared.base.database.PortableActivityOpaqueIdentity
import com.adsamcik.tracker.shared.base.database.PortableActivityRunV1
import com.adsamcik.tracker.shared.base.database.PortableActivitySessionMode
import com.adsamcik.tracker.shared.base.database.PortableActivityWindowCoverage
import com.adsamcik.tracker.shared.base.database.PortableActivityWindowV1
import com.adsamcik.tracker.shared.base.database.PortableActivityZoneEpochV1
import com.adsamcik.tracker.shared.base.database.PortableCapturedCellEntryV1
import com.adsamcik.tracker.shared.base.database.PortableCapturedCellObservationV1
import com.adsamcik.tracker.shared.base.database.PortableCapturedCellRunV1
import com.adsamcik.tracker.shared.base.database.PortableCellAcquisitionCompleteness
import com.adsamcik.tracker.shared.base.database.PortableCellCaptureCoverage
import com.adsamcik.tracker.shared.base.database.PortableCellChildCompleteness
import com.adsamcik.tracker.shared.base.database.PortableCellDeletionScopeDigest
import com.adsamcik.tracker.shared.base.database.PortableCellDigest
import com.adsamcik.tracker.shared.base.database.PortableCellIdentityKind
import com.adsamcik.tracker.shared.base.database.PortableCellOpaqueIdentity
import com.adsamcik.tracker.shared.base.database.PortableCellRunAvailability
import com.adsamcik.tracker.shared.base.database.PortableCellSessionMode
import com.adsamcik.tracker.shared.base.database.PortableCellSubscriptionGrouping
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsAdmissionRows
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiEntryV1
import com.adsamcik.tracker.stats.api.repository.PortablePressureAvailability
import com.adsamcik.tracker.stats.api.repository.PortablePressureCoverage
import com.adsamcik.tracker.stats.api.repository.PortablePressureEntryV1
import com.adsamcik.tracker.stats.api.repository.PortablePressureIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortablePressureOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PortablePressureRunV1
import com.adsamcik.tracker.stats.api.repository.PortablePressureSensorAccuracy
import com.adsamcik.tracker.stats.api.repository.PortablePressureWindowClosure
import com.adsamcik.tracker.stats.api.repository.PortablePressureWindowQualification
import com.adsamcik.tracker.stats.api.repository.PortablePressureWindowV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsCaptureCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsCompletenessV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsDeletionScopeDigest
import com.adsamcik.tracker.stats.api.repository.PortableStepsEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsFactCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsFactV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsManifestV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PortableStepsProviderCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsRunV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsSessionMode
import com.adsamcik.tracker.stats.api.repository.PortableWifiAcquisitionCompleteness
import com.adsamcik.tracker.stats.api.repository.PortableWifiAvailability
import com.adsamcik.tracker.stats.api.repository.PortableWifiCaptureCoverage
import com.adsamcik.tracker.stats.api.repository.PortableWifiDeletionScopeDigest
import com.adsamcik.tracker.stats.api.repository.PortableWifiIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortableWifiIntegrity
import com.adsamcik.tracker.stats.api.repository.PortableWifiOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PortableWifiResultCompleteness
import com.adsamcik.tracker.stats.api.repository.PortableWifiRunAvailability
import com.adsamcik.tracker.stats.api.repository.PortableWifiSessionMode
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

internal const val FIVE_SOURCE_NATIVE_STEPS_SEGMENT_ID = 910_001L

internal data class WifiAssemblyEntry(
	val entry: PortableCapturedWifiEntryV1,
	val newestRunIdentity: PortableWifiOpaqueIdentity,
)

internal suspend fun seedExactNativeStepsMembership(
	database: AppDatabase,
	startMs: Long,
) =
	database.withTransaction {
		val logicalId = "five-source-native-steps"
		val runId = "five-source-native-steps-run"
		val endMs = Math.addExact(startMs, 100L)
		val source = SessionManifestSourceEntity(
			logicalTrackingId = logicalId,
			manifestRevision = 1L,
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
			consentEpoch = 1L,
			persistenceEligible = true,
			qosCode = 1,
			outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
			writerOwner = SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL,
			writerOwnerGeneration = 1L,
		)
		val unsignedManifest = SessionManifestVersionEntity(
			logicalTrackingId = logicalId,
			manifestRevision = 1L,
			serviceRunId = runId,
			sessionMode = "MANUAL",
			sourcePolicyRevision = 1L,
			acquisitionPlanRevision = 1L,
			rolloutRevision = 1L,
			startOrigin = "MANUAL_FOREGROUND_START",
			effectiveBootId = "five-source-boot",
			effectiveElapsedRealtimeNanos = 1_000L,
			effectiveWallTimeMs = startMs,
			zoneId = "UTC",
			automationEpoch = null,
			changeReason = "FIVE_SOURCE_ASSEMBLY_TEST",
			manifestChecksum = "",
		)
		sourceSessionDao().insertSession(
			LogicalTrackingSessionEntity(
				logicalTrackingId = logicalId,
				state = "FINALIZED",
				lifecycleRevision = 1L,
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				startOrigin = "MANUAL_FOREGROUND_START",
				clockDomainId = "five-source-clock",
				startedAtMs = startMs,
				startedElapsedNanos = 1_000L,
				cutoffAtMs = endMs,
				cutoffElapsedNanos = 1_100L,
				completedAtMs = endMs,
				finalAdmissionOrdinal = null,
				failureCode = null,
				sessionMode = "MANUAL",
				currentManifestRevision = 1L,
				currentIntentRevision = 1L,
				currentServiceRunId = runId,
				lifecycleLeaseGeneration = 1L,
				lifecycleBootId = "five-source-boot",
			),
		)
		sourceSessionDao().insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = runId,
				logicalTrackingId = logicalId,
				state = "FINALIZED",
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				foregroundCapabilityFlags = 0L,
				startedAtMs = startMs,
				startedElapsedNanos = 1_000L,
				completedAtMs = endMs,
				completionReason = "STOPPED",
				bootId = "five-source-boot",
				leaseGeneration = 1L,
				startOrigin = "MANUAL_FOREGROUND_START",
				runRevision = 1L,
				startDeliveryToken = "five-source-native-steps-delivery",
				startCommandGeneration = 1L,
				preparedManifestRevision = 1L,
				preparedIntentRevision = 1L,
				startIsUserInitiated = true,
				sessionSegmentId = FIVE_SOURCE_NATIVE_STEPS_SEGMENT_ID,
			),
		)
		sourceSessionDao().insertManifest(
			unsignedManifest.copy(
				manifestChecksum = SessionManifestIntegrity.compute(
					unsignedManifest,
					listOf(source),
				),
			),
		)
		sourceSessionDao().insertManifestSources(listOf(source))
		sourcePolicyDao().insertPolicies(
			listOf(
				SourcePolicyEntity(
					policyRevision = 1L,
					sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
					enabled = true,
					qosCode = 1,
					locationMinTimeSeconds = null,
					locationMinDistanceMeters = null,
					locationRequiredAccuracyMeters = null,
					capturePersistenceEligible = true,
					controlPersistenceEligible = false,
					ambientPersistenceEligible = false,
					captureConsentEpoch = 1L,
					controlConsentEpoch = null,
					ambientConsentEpoch = null,
					effectiveBootId = "five-source-boot",
					effectiveElapsedRealtimeNanos = 1_000L,
					effectiveWallTimeMs = startMs,
					changeReason = "FIVE_SOURCE_ASSEMBLY_TEST",
				),
			),
		)
		sourcePolicyDao().insertConsentEpochs(
			listOf(
				SourceConsentEpochEntity(
					sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
					purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
					epoch = 1L,
					eligible = true,
					persistenceEligible = true,
					policyRevision = 1L,
					effectiveBootId = "five-source-boot",
					effectiveElapsedRealtimeNanos = 1_000L,
					effectiveWallTimeMs = startMs,
					changeReason = "FIVE_SOURCE_ASSEMBLY_TEST",
				),
			),
		)
		sessionSegmentDao().insert(
			SessionSegment(
				id = FIVE_SOURCE_NATIVE_STEPS_SEGMENT_ID,
				startTimeMs = startMs,
				endTimeMs = endMs,
				distanceM = 0f,
				steps = 12,
				primaryActivity = null,
				activityConfidence = null,
				sampleCount = 0,
				source = SegmentSource.USER_CREATED,
				inferenceVersion = "five-source-test",
				createdAt = endMs,
				logicalTrackingId = logicalId,
				serviceRunId = runId,
			),
		)
	}

internal fun wifiAssemblyEntry(
	logicalId: String,
	olderStartMs: Long,
	newestStartMs: Long,
	semanticRevision: Long = 1L,
): WifiAssemblyEntry {
	val runs = listOf(olderStartMs, newestStartMs).mapIndexed { index, startMs ->
		val observation = PortableWifiIntegrity.createObservation(
			identity = PortableWifiOpaqueIdentity.derive(
				PortableWifiIdentityKind.OBSERVATION,
				"$logicalId-observation-$index",
			),
			semanticRevision = semanticRevision,
			supersedesSemanticRevision = semanticRevision.takeIf { it > 1L }?.minus(1L),
			aggregateOwnerIdentity = null,
			aggregateOwnerSemanticRevision = null,
			coverageStartTimeMs = startMs + 10L,
			observedTimeMs = startMs + 20L,
			latestPossibleTimeMs = startMs + 21L,
			wallTimeUncertaintyMs = 1L,
			storedZoneId = "UTC",
			availability = PortableWifiAvailability.AVAILABLE,
			resultCompleteness = PortableWifiResultCompleteness.COMPLETE,
			submittedResultCount = 1,
			acceptedResultCount = 1,
			staleResultCount = 0,
			clockUnverifiableResultCount = 0,
			malformedResultCount = 0,
			observationCount = 1,
			twoPointFourGhzCount = 1,
			fiveGhzCount = 0,
			sixGhzCount = 0,
			otherBandCount = 0,
			strongestSignalDbm = -50,
			weakestSignalDbm = -50,
			meanSignalDbm = -50.0,
			sourceQualityFlags = 0L,
			sourceQualityConfidence = 1f,
		)
		PortableWifiIntegrity.createRun(
			identity = PortableWifiOpaqueIdentity.derive(
				PortableWifiIdentityKind.PHYSICAL_RUN,
				"$logicalId-run-$index",
			),
			deletionScopeDigest = PortableWifiDeletionScopeDigest(
				PortableWifiOpaqueIdentity.derive(
					PortableWifiIdentityKind.LOGICAL_ENTRY,
					"$logicalId-scope-$index",
				).value,
			),
			startTimeMs = startMs,
			endTimeMs = startMs + 100L,
			storedZoneIds = listOf("UTC"),
			captureCoverage = PortableWifiCaptureCoverage.WHOLE_RUN,
			availability = PortableWifiRunAvailability.RETAINED,
			acquisitionCompleteness = PortableWifiAcquisitionCompleteness.COMPLETE,
			hasUnresolvedProviderRange = false,
			retentionLoss = false,
			observations = listOf(observation),
		)
	}
	val ordered = runs.sortedWith(com.adsamcik.tracker.stats.api.repository.PORTABLE_WIFI_RUN_ORDER)
	return WifiAssemblyEntry(
		entry = PortableWifiIntegrity.createEntry(
			identity = PortableWifiOpaqueIdentity.derive(
				PortableWifiIdentityKind.LOGICAL_ENTRY,
				logicalId,
			),
			sessionMode = PortableWifiSessionMode.MANUAL,
			startTimeMs = ordered.first().startTimeMs,
			endTimeMs = ordered.maxOf { it.endTimeMs },
			runs = ordered,
		),
		newestRunIdentity = ordered.maxWith(
			compareBy({ it.startTimeMs }, { it.identity.value }),
		).identity,
	)
}

internal fun cellAssemblyEntry(
	logicalId: String,
	olderStartMs: Long,
	newestStartMs: Long,
): PortableCapturedCellEntryV1 {
	val runs = listOf(olderStartMs, newestStartMs).mapIndexed { index, startMs ->
		cellRun(logicalId, "$logicalId-run-$index", startMs)
	}.sortedWith(compareBy({ it.startTimeMs }, { it.identity.value }))
	val identity = PortableCellOpaqueIdentity.derive(
		PortableCellIdentityKind.LOGICAL_ENTRY,
		logicalId,
	)
	val startMs = runs.first().startTimeMs
	val endMs = runs.maxOf { it.endTimeMs }
	val checksum = cellDigest("tracker-portable-cell-entry-v1") {
		writeCellString(CellCapturedPortableFormatV1.FORMAT)
		writeInt(CellCapturedPortableFormatV1.SCHEMA_VERSION)
		writeCellString(identity.value)
		writeCellString(PortableCellSessionMode.MANUAL.name)
		writeLong(startMs)
		writeLong(endMs)
		writeCellString(PortableCellSubscriptionGrouping.UNKNOWN.name)
		writeInt(runs.size)
		runs.forEach { run ->
			writeCellString(run.identity.value)
			writeCellString(run.contentChecksum.value)
		}
	}
	return PortableCapturedCellEntryV1(
		identity = identity,
		contentChecksum = PortableCellDigest(checksum),
		sessionMode = PortableCellSessionMode.MANUAL,
		startTimeMs = startMs,
		endTimeMs = endMs,
		subscriptionGrouping = PortableCellSubscriptionGrouping.UNKNOWN,
		runs = runs,
	)
}

private fun cellRun(
	logicalId: String,
	runLocalId: String,
	startMs: Long,
): PortableCapturedCellRunV1 {
	val observation = cellObservation("$runLocalId-observation", startMs)
	val identity = PortableCellOpaqueIdentity.derive(
		PortableCellIdentityKind.PHYSICAL_RUN,
		runLocalId,
	)
	val scope = PortableCellDeletionScopeDigest.derive(logicalId, runLocalId)
	val checksum = cellDigest("tracker-portable-cell-run-v1") {
		writeCellString(identity.value)
		writeCellString(scope.value)
		writeLong(startMs)
		writeLong(startMs + 100L)
		writeCellString(PortableCellCaptureCoverage.WHOLE_RUN.name)
		writeCellString(PortableCellRunAvailability.RETAINED.name)
		writeCellString(PortableCellAcquisitionCompleteness.COMPLETE.name)
		writeBoolean(false)
		writeCellString(PortableCellSubscriptionGrouping.UNKNOWN.name)
		writeInt(1)
		writeCellString(observation.identity.value)
		writeCellString(observation.contentChecksum.value)
	}
	return PortableCapturedCellRunV1(
		identity = identity,
		deletionScopeDigest = scope,
		contentChecksum = PortableCellDigest(checksum),
		startTimeMs = startMs,
		endTimeMs = startMs + 100L,
		captureCoverage = PortableCellCaptureCoverage.WHOLE_RUN,
		availability = PortableCellRunAvailability.RETAINED,
		acquisitionCompleteness = PortableCellAcquisitionCompleteness.COMPLETE,
		retentionLoss = false,
		subscriptionGrouping = PortableCellSubscriptionGrouping.UNKNOWN,
		observations = listOf(observation),
	)
}

private fun cellObservation(
	localId: String,
	startMs: Long,
): PortableCapturedCellObservationV1 {
	val identity = PortableCellOpaqueIdentity.derive(
		PortableCellIdentityKind.OBSERVATION,
		localId,
	)
	val coverageStartMs = startMs + 10L
	val observedMs = startMs + 20L
	val checksum = cellDigest("tracker-portable-cell-observation-v1") {
		writeCellString(identity.value)
		writeLong(1L)
		writeNullableLong(null)
		writeCellString(null)
		writeNullableLong(null)
		writeLong(coverageStartMs)
		writeLong(observedMs)
		writeLong(observedMs + 1L)
		writeLong(1L)
		writeCellString("UTC")
		writeCellString(PortableCellChildCompleteness.COMPLETE.name)
		writeCellString(PortableCellSubscriptionGrouping.UNKNOWN.name)
		listOf(
			1, 1, 0, 0, 0, 0, 0, 0,
			1, 1, 0, 0, 0, 0, 1, 0,
			0, 0, 0, 0, 1, 0, 0, 1,
		).forEach(::writeInt)
		writeBoolean(false)
		writeLong(0L)
		writeNullableDouble(1.0)
	}
	return PortableCapturedCellObservationV1(
		identity = identity,
		semanticRevision = 1L,
		supersedesSemanticRevision = null,
		aggregateOwnerIdentity = null,
		aggregateOwnerSemanticRevision = null,
		contentChecksum = PortableCellDigest(checksum),
		coverageStartTimeMs = coverageStartMs,
		observedTimeMs = observedMs,
		latestPossibleTimeMs = observedMs + 1L,
		wallTimeUncertaintyMs = 1L,
		storedZoneId = "UTC",
		childCompleteness = PortableCellChildCompleteness.COMPLETE,
		subscriptionGrouping = PortableCellSubscriptionGrouping.UNKNOWN,
		submittedChildCount = 1,
		acceptedChildCount = 1,
		staleChildCount = 0,
		futureTimeChildCount = 0,
		missingTimeChildCount = 0,
		clockUnverifiableChildCount = 0,
		authorityMismatchChildCount = 0,
		unsupportedTechnologyChildCount = 0,
		observationCount = 1,
		registeredObservationCount = 1,
		gsmCount = 0,
		cdmaCount = 0,
		wcdmaCount = 0,
		tdscdmaCount = 0,
		lteCount = 1,
		nrCount = 0,
		qualityUnknownCount = 0,
		qualityNoneOrUnknownCount = 0,
		qualityPoorCount = 0,
		qualityModerateCount = 0,
		qualityGoodCount = 1,
		qualityGreatCount = 0,
		weakObservationCount = 0,
		knownQualityObservationCount = 1,
		allKnownQualityIsWeak = false,
		qualityFlags = 0L,
		qualityConfidence = 1.0,
	)
}

internal fun activityAssemblyEntry(
	logicalId: Char,
	olderStartMs: Long,
	newestStartMs: Long,
): PortableActivityEntryV1 {
	val runs = listOf(olderStartMs, newestStartMs).mapIndexed { index, startMs ->
		activityRun(logicalId, index, startMs)
	}.sortedWith(
		compareBy<PortableActivityRunV1>({ it.startTimeMs }, { it.endTimeMs }, { it.identity.value }),
	)
	val identity = PortableActivityOpaqueIdentity(logicalId.toString().repeat(64))
	return PortableActivityEntryV1(
		identity = identity,
		contentChecksum = ActivityCapturedPortableIntegrity.entryChecksum(
			identity,
			PortableActivitySessionMode.MANUAL,
			runs.first().startTimeMs,
			runs.maxOf { it.endTimeMs },
			runs,
		),
		sessionMode = PortableActivitySessionMode.MANUAL,
		startTimeMs = runs.first().startTimeMs,
		endTimeMs = runs.maxOf { it.endTimeMs },
		runs = runs,
	)
}

private fun activityRun(
	logicalId: Char,
	index: Int,
	startMs: Long,
): PortableActivityRunV1 {
	val identity = PortableActivityOpaqueIdentity(
		(if (index == 0) 'b' else 'c').toString().repeat(64),
	)
	val scope = PortableActivityDeletionScopeDigest(
		(if (index == 0) 'd' else 'e').toString().repeat(64),
	)
	val zones = listOf(PortableActivityZoneEpochV1(startMs, "UTC"))
	val windowIdentity = PortableActivityOpaqueIdentity(
		(if (index == 0) '4' else '5').toString().repeat(64),
	)
	val fragment = PortableActivityFragmentV1.Band(
		startOffsetNanos = 0L,
		endOffsetNanos = 100L,
		activity = "WALKING",
		mechanism = "TRANSITION",
		refinedTransitionActivity = null,
		confidenceKind = "TRANSITION_SIGNAL",
		confidenceMinimumPercent = null,
		confidenceMaximumPercent = null,
		confidenceObservationCount = null,
		startWallTimeMs = startMs + 10L,
		startWallTimeUncertaintyMs = 0L,
		startBoundaryKind = "EXACT_PROVIDER_OBSERVATION",
		endWallTimeMs = startMs + 11L,
		endWallTimeUncertaintyMs = 0L,
		endBoundaryKind = "SAME_CLOCK_EXTRAPOLATION",
		wallTimeContinuity = "SAME_ANCHOR",
	)
	val checksum = ActivityCapturedPortableIntegrity.windowChecksum(
		windowIdentity,
		0L,
		100L,
		"UTC",
		PortableActivityWindowCoverage.COMPLETE,
		100L,
		0L,
		0L,
		0L,
		listOf(fragment),
	)
	val window = PortableActivityWindowV1(
		identity = windowIdentity,
		contentChecksum = checksum,
		startOffsetNanos = 0L,
		endOffsetNanos = 100L,
		storedZoneId = "UTC",
		coverage = PortableActivityWindowCoverage.COMPLETE,
		knownActiveDurationNanos = 100L,
		knownInactiveDurationNanos = 0L,
		unknownActivityDurationNanos = 0L,
		unobservedDurationNanos = 0L,
		fragments = listOf(fragment),
	)
	return PortableActivityRunV1(
		identity = identity,
		deletionScopeDigest = scope,
		contentChecksum = ActivityCapturedPortableIntegrity.runChecksum(
			identity,
			scope,
			startMs,
			startMs + 100L,
			PortableActivityCaptureCoverage.WHOLE_RUN,
			zones,
			listOf(window),
		),
		startTimeMs = startMs,
		endTimeMs = startMs + 100L,
		captureCoverage = PortableActivityCaptureCoverage.WHOLE_RUN,
		zoneEpochs = zones,
		windows = listOf(window),
	)
}

internal fun pressureAssemblyEntry(
	logicalId: String,
	olderStartMs: Long,
	newestStartMs: Long,
): PortablePressureEntryV1 {
	val runs = listOf(olderStartMs, newestStartMs).mapIndexed { index, startMs ->
		PortablePressureRunV1(
			identity = PortablePressureOpaqueIdentity.derive(
				PortablePressureIdentityKind.PHYSICAL_RUN,
				"$logicalId-run-$index",
			),
			startTimeMs = startMs,
			endTimeMs = startMs + 100L,
			capturedForWholeRun = true,
			availability = PortablePressureAvailability.RETAINED,
			coverage = PortablePressureCoverage.COMPLETE,
			retentionLoss = false,
			windows = listOf(pressureWindow("$logicalId-window-$index", startMs)),
		)
	}
	return PortablePressureEntryV1.create(
		identity = PortablePressureOpaqueIdentity.derive(
			PortablePressureIdentityKind.LOGICAL_ENTRY,
			logicalId,
		),
		startTimeMs = runs.minOf { it.startTimeMs },
		endTimeMs = runs.maxOf { it.endTimeMs },
		runs = runs,
	)
}

internal fun pressureRecencyCollisionEntry(
	logicalId: String,
	olderStartMs: Long,
	newestStartMs: Long,
): PortablePressureEntryV1 {
	val runs = listOf(olderStartMs, newestStartMs).mapIndexed { index, startMs ->
		PortablePressureRunV1(
			identity = PortablePressureOpaqueIdentity.derive(
				PortablePressureIdentityKind.PHYSICAL_RUN,
				"$logicalId-run-$index",
			),
			startTimeMs = startMs,
			endTimeMs = startMs + 100L,
			capturedForWholeRun = true,
			availability = PortablePressureAvailability.NO_RETAINED_OBSERVATION,
			coverage = PortablePressureCoverage.PARTIAL,
			retentionLoss = true,
			windows = emptyList(),
		)
	}
	return PortablePressureEntryV1.create(
		identity = PortablePressureOpaqueIdentity.derive(
			PortablePressureIdentityKind.LOGICAL_ENTRY,
			logicalId,
		),
		startTimeMs = runs.minOf { it.startTimeMs },
		endTimeMs = runs.maxOf { it.endTimeMs },
		runs = runs,
	)
}

internal fun rewritePressureNewestRunIdentityForCollision(
	database: AppDatabase,
	entry: PortablePressureEntryV1,
	collidingRun: PortablePressureRunV1,
) {
	val originalNewest = entry.runs.maxWith(
		compareBy({ it.startTimeMs }, { it.identity.value }),
	)
	require(originalNewest.windows.isEmpty() && collidingRun.windows.isEmpty())
	val corrected = PortablePressureEntryV1.create(
		identity = entry.identity,
		startTimeMs = entry.startTimeMs,
		endTimeMs = entry.endTimeMs,
		runs = entry.runs.map { run ->
			if (run.identity == originalNewest.identity) {
				run.copy(identity = collidingRun.identity)
			} else {
				run
			}
		},
	)
	val sqlite = database.openHelper.writableDatabase
	sqlite.execSQL(
		"UPDATE imported_pressure_run SET identity = ? " +
			"WHERE entry_identity = ? AND identity = ?",
		arrayOf(
			collidingRun.identity.value,
			entry.identity.value,
			originalNewest.identity.value,
		),
	)
	sqlite.execSQL(
		"UPDATE imported_pressure_entry_revision SET content_checksum = ? WHERE identity = ?",
		arrayOf(corrected.contentChecksum.value, entry.identity.value),
	)
	sqlite.execSQL(
		"UPDATE imported_pressure_receipt SET entry_content_checksum = ? " +
			"WHERE entry_identity = ?",
		arrayOf(corrected.contentChecksum.value, entry.identity.value),
	)
}

private fun pressureWindow(
	localId: String,
	startMs: Long,
) = PortablePressureWindowV1.create(
	identity = PortablePressureOpaqueIdentity.derive(
		PortablePressureIdentityKind.WINDOW,
		localId,
	),
	intervalStartTimeMs = startMs + 10L,
	intervalEndTimeMs = startMs + 60L,
	wallTimeUncertaintyMs = 0L,
	observedDurationNanos = 50_000_000L,
	sampleCount = 2,
	expectedSampleCount = 2,
	meanHectopascals = 1_001.0,
	sumSquaredDeviations = 2.0,
	minimumHectopascals = 1_000f,
	maximumHectopascals = 1_002f,
	firstHectopascals = 1_000f,
	latestHectopascals = 1_002f,
	slopeHectopascalsPerSecond = 40.0,
	rSquared = 1.0,
	sensorAccuracy = PortablePressureSensorAccuracy.HIGH,
	effectiveSamplePeriodMicros = 50_000,
	effectiveMaximumReportLatencyMicros = 0,
	targetWindowDurationNanos = 100_000_000L,
	maximumInterSampleGapNanos = 50_000_000L,
	closure = PortablePressureWindowClosure.TARGET_ELAPSED,
	qualification = PortablePressureWindowQualification.COMPLETE,
	sourceQualityFlags = 0L,
	sourceQualityConfidence = 1f,
	zoneId = "UTC",
)

internal fun importedStepsAssemblyEntry(startMs: Long): PortableStepsEntryV1 {
	val endMs = Math.addExact(startMs, 100L)
	val run = PortableStepsRunV1(
		identity = stepsIdentity('2'),
		deletionScopeDigest = PortableStepsDeletionScopeDigest("3".repeat(64)),
		startTimeMs = startMs,
		endTimeMs = endMs,
		storedZoneId = "UTC",
		manifests = listOf(PortableStepsManifestV1(1L, startMs, 7L, 8L)),
		completeness = PortableStepsCompletenessV1(
			PortableStepsCaptureCoverage.WHOLE_RUN,
			PortableStepsProviderCoverage.COMPLETE,
			appDrainComplete = true,
			stopComplete = true,
			hasUnresolvedProviderRange = false,
		),
		facts = listOf(
			PortableStepsFactV1.create(
				identity = stepsIdentity('4'),
				semanticRevision = 1L,
				intervalStartTimeMs = startMs,
				intervalEndTimeMs = endMs,
				wallTimeUncertaintyMs = 0L,
				coverage = PortableStepsFactCoverage.COVERED,
				effectiveStepCount = 5L,
			),
		),
	)
	return PortableStepsEntryV1.create(
		identity = stepsIdentity('1'),
		sessionMode = PortableStepsSessionMode.MANUAL,
		startTimeMs = run.startTimeMs,
		endTimeMs = run.endTimeMs,
		runs = listOf(run),
	)
}

internal suspend fun seedImportedSteps(
	database: AppDatabase,
	entry: PortableStepsEntryV1,
	segmentId: Long,
	collectedDataEpoch: Long,
) = database.withTransaction {
	val storedEntry = ImportedStepsAdmissionRows.entry(
		entry,
		epoch = collectedDataEpoch,
		ownerGeneration = 1L,
	)
	importedStepsDao().insertEntry(storedEntry)
	entry.runs.forEachIndexed { index, run ->
		val physicalId = segmentId + index
		sessionSegmentDao().insert(
			SessionSegment(
				id = physicalId,
				startTimeMs = run.startTimeMs,
				endTimeMs = run.endTimeMs,
				distanceM = 0f,
				steps = null,
				primaryActivity = null,
				activityConfidence = null,
				sampleCount = 0,
				source = SegmentSource.PORTABLE_STEPS_IMPORT,
				inferenceVersion = null,
				createdAt = run.endTimeMs,
				logicalTrackingId = storedEntry.identity,
				serviceRunId = run.identity.value,
			),
		)
		importedStepsDao().insertRun(
			ImportedStepsAdmissionRows.run(storedEntry, run, physicalId),
		)
		ImportedStepsAdmissionRows.manifests(run).forEach {
			importedStepsDao().insertManifest(it)
		}
		run.facts.forEach {
			stepFactRevisionDao().insert(
				ImportedStepsAdmissionRows.fact(storedEntry, run, it, run.endTimeMs),
			)
		}
	}
}

internal suspend fun seedCellOriginClaim(
	database: AppDatabase,
	logicalId: String,
	startMs: Long,
) {
	val endMs = Math.addExact(startMs, 1L)
	database.sourceSessionDao().insertSession(
		LogicalTrackingSessionEntity(
			logicalTrackingId = logicalId,
			state = "FINALIZED",
			lifecycleRevision = 1L,
			desiredPlanRevision = 1L,
			rolloutRevision = 1L,
			startOrigin = "MANUAL",
			clockDomainId = "five-source-cell-conflict-boot",
			startedAtMs = startMs,
			startedElapsedNanos = 1L,
			cutoffAtMs = endMs,
			cutoffElapsedNanos = 2L,
			completedAtMs = endMs,
			finalAdmissionOrdinal = 0L,
			failureCode = null,
		),
	)
}

private fun stepsIdentity(value: Char) =
	PortableStepsOpaqueIdentity("sha256:${value.toString().repeat(64)}")

private fun cellDigest(
	namespace: String,
	body: DataOutputStream.() -> Unit,
): String {
	val bytes = ByteArrayOutputStream().use { buffer ->
		DataOutputStream(buffer).use { output ->
			output.writeUTF(namespace)
			output.body()
		}
		buffer.toByteArray()
	}
	return MessageDigest.getInstance("SHA-256").digest(bytes)
		.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun DataOutputStream.writeCellString(value: String?) {
	writeBoolean(value != null)
	if (value != null) writeUTF(value)
}

private fun DataOutputStream.writeNullableLong(value: Long?) {
	writeBoolean(value != null)
	if (value != null) writeLong(value)
}

private fun DataOutputStream.writeNullableDouble(value: Double?) {
	writeBoolean(value != null)
	if (value != null) writeDouble(value)
}
