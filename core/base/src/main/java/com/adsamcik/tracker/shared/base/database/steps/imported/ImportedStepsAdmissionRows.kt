package com.adsamcik.tracker.shared.base.database.steps.imported

import com.adsamcik.tracker.shared.base.database.data.ImportedStepsEntryEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedStepsManifestEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedStepsRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsEntryV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsFactV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsRunV1

/**
 * Origin-specific row conversion only. Caller must own the admission transaction, current epoch,
 * sole owner, deletion/retention checks and real day repair. These helpers grant no authority.
 */
object ImportedStepsAdmissionRows {
	/** Preserves original whole-entry checksum with the local historical admission-owner receipt. */
	fun entry(portable: PortableStepsEntryV1, epoch: Long, ownerGeneration: Long) = ImportedStepsEntryEntity(
		portable.identity.value, portable.contentChecksum.value, portable.sessionMode.name,
		portable.startTimeMs, portable.endTimeMs, epoch, ownerGeneration,
	)

	/** Binds the exact physical presentation row and authenticates this member independently. */
	fun run(entry: ImportedStepsEntryEntity, portable: PortableStepsRunV1, segmentId: Long): ImportedStepsRunEntity {
		val run = ImportedStepsRunEntity(
			portable.identity.value, entry.identity, portable.deletionScopeDigest.value,
			portable.startTimeMs, portable.endTimeMs, portable.storedZoneId,
			portable.completeness.captureCoverage.name, portable.completeness.providerCoverage.name,
			portable.completeness.appDrainComplete, portable.completeness.stopComplete,
			portable.completeness.hasUnresolvedProviderRange, segmentId,
		)
		return run.copy(retainedChecksum = ImportedStepsRetainedIntegrity.runChecksum(entry, run, portable))
	}

	/** Exact foreign manifest values, not locally granted capture authority. */
	fun manifests(run: PortableStepsRunV1): List<ImportedStepsManifestEntity> = run.manifests.map {
		ImportedStepsManifestEntity(run.identity.value, it.revision, it.effectiveWallTimeMs,
			it.originSourcePolicyRevision, it.captureConsentEpoch)
	}

	/** Portable shape deliberately omits all provider/boot/elapsed/cumulative/runtime evidence. */
	@Suppress("LongMethod")
	fun fact(
		entry: ImportedStepsEntryEntity, run: PortableStepsRunV1, portable: PortableStepsFactV1, appliedAtMs: Long,
	): StepFactRevisionEntity {
		val manifest = run.manifests.single { it.revision == portable.manifestRevision }
		val fact = StepFactRevisionEntity(
			logicalFactId = portable.identity.value,
			semanticRevision = 1L,
			mutationId = StepFactRevisionIntegrity.portableImportMutationId(portable.identity.value),
			stepIntervalId = null, sourceEventId = null, sourceAdmissionOrdinal = null,
			originKind = StepFactRevisionEntity.ORIGIN_PORTABLE_IMPORT,
			originIdentity = portable.identity.value,
			writerProjectionId = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
			writerProjectionVersion = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
			writerBindingGeneration = StepFactRevisionIntegrity.PORTABLE_IMPORT_BINDING_GENERATION,
			operation = StepFactRevisionEntity.OPERATION_UPSERT,
			intervalStartTimeMs = portable.intervalStartTimeMs,
			intervalEndTimeMs = portable.intervalEndTimeMs,
			intervalStartElapsedRealtimeNanos = null, intervalEndElapsedRealtimeNanos = null,
			clockDomainId = null, bootClockDomainId = null,
			cumulativeStepCountStart = null, cumulativeStepCountEnd = null,
			wallTimeUncertaintyMs = portable.wallTimeUncertaintyMs,
			coverageKind = portable.coverage.name,
			effectiveStepCount = portable.stepCount,
			logicalTrackingId = entry.identity, serviceRunId = run.identity.value,
			purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			manifestRevision = portable.manifestRevision,
			sourcePolicyRevision = manifest.originSourcePolicyRevision,
			captureConsentEpoch = manifest.captureConsentEpoch,
			collectedDataEpoch = entry.collectedDataEpoch, scopeDeletionGeneration = 0L,
			effectChecksum = "pending", appliedAtMs = appliedAtMs,
		)
		return fact.copy(effectChecksum = StepFactRevisionIntegrity.portableImportEffectChecksum(fact))
	}
}
