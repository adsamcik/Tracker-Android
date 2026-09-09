package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsAdmissionRows
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedRead
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedReader
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
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportedStepsMixedRetentionTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `mixed identical wall ranges prune exact expired facts and retain both origin suffixes`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = EPOCH))
		val live = seedLive()
		val imported = seedImported()
		val importedRun = imported.runs.single()
		val originalImported = importedRun.facts.map { latest(it.identity.value).shouldNotBeNull() }
		live.map { it.intervalStartTimeMs to it.intervalEndTimeMs } shouldBe
			originalImported.map { it.intervalStartTimeMs to it.intervalEndTimeMs }
		StepFactRevisionIntegrity.hasValidCanonicalLiveWalRunTimeline(live, LIVE_ENTRY, LIVE_RUN) shouldBe true
		database.sourceSessionDao().serviceRun(importedRun.identity.value) shouldBe null

		database.sourceEvidenceStateDao().updateLifecycle(EPOCH, 25L, 100L)
		database.markAuthenticatedStepsRunsAffectedByRetentionFloor(25L, EPOCH, 100L) shouldBe 2
		database.pruneAuthenticatedStepsFactsAffectedByRetentionFloor(25L, EPOCH, 100L) shouldBe 2
		latest(live.first().logicalFactId) shouldBe null
		latest(originalImported.first().logicalFactId) shouldBe null
		live.drop(1).map { latest(it.logicalFactId) } shouldBe live.drop(1)
		originalImported.drop(1).map { latest(it.logicalFactId) } shouldBe originalImported.drop(1)
		database.stepFactRevisionDao().countAll() shouldBe 4L

		val liveMarker = StepFactRevisionIntegrity.retentionTruncationFence(LIVE_ENTRY, LIVE_RUN, EPOCH, 100L)
		val importMarker = SourceDeletionFenceEntity.createForOriginalRunDigest(
			SourceDestinationOwnerEntity.SOURCE_STEPS, StepFactRevisionIntegrity.RETENTION_TRUNCATION_PURPOSE,
			importedRun.deletionScopeDigest.value, 1L, EPOCH, 100L,
		)
		fence(liveMarker.purpose, liveMarker.scopeIdentityDigest) shouldBe liveMarker
		fence(importMarker.purpose, importMarker.scopeIdentityDigest) shouldBe importMarker
		assertNoCaptureDeletionOrRehashedImportScope(imported)
		val read = database.withTransaction {
			ImportedStepsRetainedReader(database).readEntriesInTransaction(listOf(imported.identity.value))
		} as ImportedStepsRetainedRead.Ready
		val retained = read.entries.single()
		retained.portable shouldBe null
		retained.metadata.contentChecksum shouldBe imported.contentChecksum.value
		retained.retentionTruncatedRunIds shouldBe setOf(importedRun.identity.value)
		retained.factsByRun.values.flatten().map { it.effectiveStepCount } shouldBe listOf(0L, null)
		database.sourceSessionDao().serviceRun(importedRun.identity.value) shouldBe null
		database.sourceSessionDao().serviceRun(LIVE_RUN).shouldNotBeNull()
		database.markAuthenticatedStepsRunsAffectedByRetentionFloor(25L, EPOCH, 101L) shouldBe 0
		database.pruneAuthenticatedStepsFactsAffectedByRetentionFloor(25L, EPOCH, 101L) shouldBe 0
		database.sourceDeletionFenceDao().countAll() shouldBe 2L
		fence(liveMarker.purpose, liveMarker.scopeIdentityDigest) shouldBe liveMarker
		fence(importMarker.purpose, importMarker.scopeIdentityDigest) shouldBe importMarker
	}

	private suspend fun assertNoCaptureDeletionOrRehashedImportScope(entry: PortableStepsEntryV1) {
		val run = entry.runs.single()
		val capture = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE
		fence(capture, PortableStepsDeletionScopeDigest.derive(LIVE_ENTRY, LIVE_RUN).value) shouldBe null
		fence(capture, run.deletionScopeDigest.value) shouldBe null
		val incorrectlyRehashed = StepFactRevisionIntegrity.retentionTruncationIdentity(
			entry.identity.value, run.identity.value,
		)
		fence(StepFactRevisionIntegrity.RETENTION_TRUNCATION_PURPOSE, incorrectlyRehashed) shouldBe null
	}

	private suspend fun latest(identity: String) = database.stepFactRevisionDao().latest(
		SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
		SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION, identity,
	)

	private suspend fun fence(purpose: String, digest: String) = database.sourceDeletionFenceDao().get(
		SourceDestinationOwnerEntity.SOURCE_STEPS, purpose, SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN, digest,
	)

	private suspend fun seedLive(): List<StepFactRevisionEntity> {
		database.sourceSessionDao().insertServiceRun(SourceServiceRunEntity(
			LIVE_RUN, LIVE_ENTRY, "PREPARED", 1L, 1L, 0L, 10L, 10_000_000L, null, null,
			bootId = "mixed-live-boot",
		))
		return listOf(liveFact(1L, 10L, 20L, 100L, 102L), liveFact(2L, 20L, 30L, 102L, 102L),
			liveFact(3L, 30L, 40L, 102L, 105L)).also { facts ->
			facts.forEach { database.stepFactRevisionDao().insert(it) }
		}
	}

	private fun liveFact(
		ordinal: Long, start: Long, end: Long, counterStart: Long, counterEnd: Long,
	): StepFactRevisionEntity {
		val event = "mixed-live-$ordinal"
		val identity = "${SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID}:$event"
		val fact = StepFactRevisionEntity(
			logicalFactId = identity, semanticRevision = 1L, mutationId = "$identity:1:UPSERT",
			stepIntervalId = null, sourceEventId = event, sourceAdmissionOrdinal = ordinal,
			originKind = StepFactRevisionEntity.ORIGIN_LIVE_WAL, originIdentity = event,
			writerProjectionId = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
			writerProjectionVersion = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
			writerBindingGeneration = SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION,
			operation = StepFactRevisionEntity.OPERATION_UPSERT, intervalStartTimeMs = start, intervalEndTimeMs = end,
			intervalStartElapsedRealtimeNanos = start * 1_000_000L, intervalEndElapsedRealtimeNanos = end * 1_000_000L,
			clockDomainId = "mixed-live-boot", bootClockDomainId = "mixed-live-boot",
			cumulativeStepCountStart = counterStart, cumulativeStepCountEnd = counterEnd, wallTimeUncertaintyMs = 0L,
			coverageKind = StepFactRevisionEntity.COVERAGE_COVERED, effectiveStepCount = counterEnd - counterStart,
			logicalTrackingId = LIVE_ENTRY, serviceRunId = LIVE_RUN, purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			manifestRevision = 1L, sourcePolicyRevision = 1L, captureConsentEpoch = 1L, collectedDataEpoch = EPOCH,
			scopeDeletionGeneration = 0L, effectChecksum = "unsigned", appliedAtMs = end,
		)
		return fact.copy(effectChecksum = StepFactRevisionIntegrity.liveWalEffectChecksum(fact))
	}

	private suspend fun seedImported(): PortableStepsEntryV1 {
		val run = PortableStepsRunV1(identity('2'), PortableStepsDeletionScopeDigest.derive("foreign-entry", "foreign-run"),
			10L, 40L, "Europe/Prague", listOf(PortableStepsManifestV1(1L, 10L, 5L, 3L)),
			PortableStepsCompletenessV1(PortableStepsCaptureCoverage.WHOLE_RUN, PortableStepsProviderCoverage.PARTIAL,
				appDrainComplete = true, stopComplete = true, hasUnresolvedProviderRange = true),
			listOf(
				PortableStepsFactV1.create(identity('3'), 1L, 10L, 20L, 0L, PortableStepsFactCoverage.COVERED, 2L),
				PortableStepsFactV1.create(identity('4'), 1L, 20L, 30L, 0L, PortableStepsFactCoverage.COVERED, 0L),
				PortableStepsFactV1.create(identity('5'), 1L, 30L, 40L, 0L, PortableStepsFactCoverage.PARTIAL, null),
			))
		val entry = PortableStepsEntryV1.create(identity('1'), PortableStepsSessionMode.MANUAL, 10L, 40L, listOf(run))
		val metadata = ImportedStepsAdmissionRows.entry(entry, EPOCH, 7L)
		database.withTransaction {
			database.importedStepsDao().insertEntry(metadata)
			val segmentId = database.sessionSegmentDao().insert(SessionSegment(
				startTimeMs = 10L, endTimeMs = 40L, distanceM = 0f, steps = null, primaryActivity = null,
				activityConfidence = null, sampleCount = 0, source = SegmentSource.PORTABLE_STEPS_IMPORT,
				inferenceVersion = null, createdAt = 100L, logicalTrackingId = metadata.identity,
				serviceRunId = run.identity.value,
			))
			database.importedStepsDao().insertRun(ImportedStepsAdmissionRows.run(metadata, run, segmentId))
			ImportedStepsAdmissionRows.manifests(run).forEach { database.importedStepsDao().insertManifest(it) }
			run.facts.forEach { database.stepFactRevisionDao().insert(ImportedStepsAdmissionRows.fact(metadata, run, it, 100L)) }
		}
		return entry
	}

	private fun identity(value: Char) = PortableStepsOpaqueIdentity("sha256:${value.toString().repeat(64)}")

	private companion object {
		const val EPOCH = 1L
		const val LIVE_ENTRY = "mixed-live-entry"
		const val LIVE_RUN = "mixed-live-run"
	}
}
