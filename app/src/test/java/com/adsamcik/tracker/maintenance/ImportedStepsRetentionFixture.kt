package com.adsamcik.tracker.maintenance

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsAdmissionRows
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsCaptureCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsCompletenessV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsDeletionScopeDigest
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsEntryV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsFactCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsFactV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsManifestV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsOpaqueIdentity
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsProviderCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsRunV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsSessionMode
import org.junit.Assert.assertEquals

/** Direct authenticated storage fixture; it does not exercise or authorize production import. */
internal suspend fun seedExpiredImportedSteps(
	database: AppDatabase,
	epoch: Long,
): Pair<PortableStepsEntryV1, Long> = database.withTransaction {
	val run = PortableStepsRunV1(
		fixtureIdentity('2'), PortableStepsDeletionScopeDigest.derive("foreign-entry", "foreign-run"),
		1L, 2L, "Europe/Prague", listOf(PortableStepsManifestV1(1L, 1L, 1L, 1L)),
		PortableStepsCompletenessV1(PortableStepsCaptureCoverage.WHOLE_RUN, PortableStepsProviderCoverage.COMPLETE,
			appDrainComplete = true, stopComplete = true, hasUnresolvedProviderRange = false),
		listOf(PortableStepsFactV1.create(fixtureIdentity('3'), 1L, 1L, 2L, 0L, PortableStepsFactCoverage.COVERED, 5L)),
	)
	val entry = PortableStepsEntryV1.create(fixtureIdentity('1'), PortableStepsSessionMode.MANUAL, 1L, 2L, listOf(run))
	val metadata = ImportedStepsAdmissionRows.entry(entry, epoch, 7L)
	database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = epoch))
	database.importedStepsDao().insertEntry(metadata)
	val segmentId = database.sessionSegmentDao().insert(SessionSegment(
		startTimeMs = 1L, endTimeMs = 2L, distanceM = 0f, steps = null, primaryActivity = null,
		activityConfidence = null, sampleCount = 0, source = SegmentSource.PORTABLE_STEPS_IMPORT,
		inferenceVersion = null, createdAt = 2L, logicalTrackingId = entry.identity.value, serviceRunId = run.identity.value,
	))
	database.importedStepsDao().insertRun(ImportedStepsAdmissionRows.run(metadata, run, segmentId))
	ImportedStepsAdmissionRows.manifests(run).forEach { database.importedStepsDao().insertManifest(it) }
	run.facts.forEach { database.stepFactRevisionDao().insert(ImportedStepsAdmissionRows.fact(metadata, run, it, 2L)) }
	entry to segmentId
}

private fun fixtureIdentity(value: Char) = PortableStepsOpaqueIdentity("sha256:${value.toString().repeat(64)}")

/** Both worker entry points must remove exact membership and retain the original deletion scope. */
internal suspend fun assertExpiredImportedStepsRemoved(database: AppDatabase, entry: PortableStepsEntryV1, segmentId: Long) {
	assertEquals(null, database.importedStepsDao().entry(entry.identity.value))
	assertEquals(null, database.sessionSegmentDao().getById(segmentId))
	assertEquals(null, database.sourceSessionDao().serviceRun(entry.runs.single().identity.value))
	assertEquals(1L, database.sourceDeletionFenceDao().get(
		SourceDestinationOwnerEntity.SOURCE_STEPS, StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
		SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN, entry.runs.single().deletionScopeDigest.value,
	)?.fenceGeneration)
}
