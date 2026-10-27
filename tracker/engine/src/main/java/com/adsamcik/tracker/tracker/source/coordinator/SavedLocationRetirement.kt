package com.adsamcik.tracker.tracker.source.coordinator

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.SourceRunAdmissionHighWaterRow
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProviderPurposeScope
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.runtime.ProviderCoverage
import com.adsamcik.tracker.tracker.source.runtime.ProviderFlushOutcome
import com.adsamcik.tracker.tracker.source.runtime.RegistrationRemovalOutcome
import com.adsamcik.tracker.tracker.source.runtime.SessionCutoff
import com.adsamcik.tracker.tracker.source.runtime.SourceStopAck
import com.adsamcik.tracker.tracker.source.runtime.SourceStopStatus

/** Reuses the acknowledged predecessor drain when a later End no longer owns that Location listener. */
internal suspend fun savedLocationRetirementAcknowledgement(
	database: AppDatabase,
	registration: ProviderRegistrationGenerationEntity,
	highWater: SourceRunAdmissionHighWaterRow,
	cutoff: SessionCutoff,
	serviceRunId: String,
): SourceStopAck? = database.withTransaction {
	val dao = database.sourceSessionDao()
	val run = dao.serviceRun(serviceRunId) ?: return@withTransaction null
	if (!registration.hasSavedLocationRetirement(run, cutoff)) {
		return@withTransaction null
	}
	val raw = dao.rawSourceCompletenessForServiceRun(serviceRunId, SourceKind.LOCATION.stableCode,
		MAX_RUN_RETIREMENT_RECEIPTS + 1)
	if (raw.size > MAX_RUN_RETIREMENT_RECEIPTS) {
		return@withTransaction null
	}
	val saved = raw.map { it.validatedOrNull() ?: return@withTransaction null }.singleOrNull {
		it.logicalTrackingId == cutoff.logicalTrackingId && it.sourceInstanceId == registration.sourceInstanceId &&
			it.registrationGeneration == registration.registrationGeneration
	} ?: return@withTransaction null
	if (!saved.hasCleanSavedLocationDrain(highWater)) {
		return@withTransaction null
	}
	SourceStopAck(
		source = SourceKind.LOCATION,
		sourceInstanceId = SourceInstanceId(registration.sourceInstanceId),
		registrationGeneration = registration.registrationGeneration,
		appliedRevision = null,
		// Replay uses the saved WAL coordinate; it does not claim a new provider callback fence.
		callbackEntryBarrierSequence = highWater.lastSourceSequence,
		lastDurablyAdmittedSequence = saved.lastSourceSequence,
		lastAdmissionOrdinal = saved.lastAdmissionOrdinal,
		failedAdmissionCount = 0L,
		unresolvedSequenceStart = null,
		unresolvedSequenceEndInclusive = null,
		registrationRemovalOutcome = RegistrationRemovalOutcome.REMOVED,
		// The original flush result was not retained here. No further flush is requested on replay.
		providerFlushOutcome = ProviderFlushOutcome.NOT_REQUESTED,
		providerCoverage = ProviderCoverage.valueOf(saved.providerCoverage),
		appDrainComplete = saved.appDrainComplete,
		status = SourceStopStatus.COMPLETE,
		logicalTrackingId = saved.logicalTrackingId,
		serviceRunId = saved.serviceRunId,
	)
}

// One exact physical predecessor, its run clock and the already-observed End cutoff.
private fun ProviderRegistrationGenerationEntity.hasSavedLocationRetirement(
	run: SourceServiceRunEntity,
	cutoff: SessionCutoff,
): Boolean {
	val accepted = acceptedElapsedRealtimeNanos ?: return false
	val retired = retiredElapsedRealtimeNanos ?: return false
	val chronological = reservedElapsedRealtimeNanos <= accepted && accepted <= retired &&
		retired in run.startedElapsedNanos..cutoff.elapsedRealtimeNanos
	return isRetiredLocationReconfiguration() && clockDomainId == run.bootId &&
		run.logicalTrackingId == cutoff.logicalTrackingId && chronological
}

private fun ProviderRegistrationGenerationEntity.isRetiredLocationReconfiguration(): Boolean =
	sourceKind == SourceKind.LOCATION.stableCode && status == ProviderRegistrationGenerationEntity.STATUS_RETIRED &&
		failureCode == "RECONFIGURE" && SourceProviderPurposeScope.isCanonicalOwnerScope(sourceKind, ownerScope)

// Partial or stale saved evidence must never turn into a clean retirement.
private fun SourceSessionCompletenessEntity.hasCleanSavedLocationDrain(
	highWater: SourceRunAdmissionHighWaterRow,
): Boolean = hasNoSavedLocationLoss() &&
	(lastAdmissionOrdinal ?: 0L) == highWater.lastAdmissionOrdinal &&
	(lastSourceSequence ?: 0L) == highWater.lastSourceSequence

private fun SourceSessionCompletenessEntity.hasNoSavedLocationLoss(): Boolean =
	appDrainComplete && stopStatus == SourceStopStatus.COMPLETE.name &&
		unresolvedSequenceStart == null && unresolvedSequenceEnd == null
