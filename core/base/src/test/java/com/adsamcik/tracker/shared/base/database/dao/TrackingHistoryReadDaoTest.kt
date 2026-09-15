package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.model.SegmentSource
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
class TrackingHistoryReadDaoTest {
	private lateinit var database: AppDatabase
	private lateinit var history: TrackingHistoryReadDao

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		history = database.trackingHistoryReadDao()
	}

	@After
	fun tearDown() = database.close()

	@Test
	@Suppress("LongMethod")
	fun exactIntentModeAddsFactlessManifestSeedsWithoutChangingOrdinaryCandidates() = runTest {
		insertExactStepsRun(
			runId = "evidence-run",
			logicalId = "logical-evidence",
			manifestRevision = 1L,
			segmentId = 41L,
			startTimeMs = 1_000L,
			steps = 7,
		)
		insertExactStepsRun(
			runId = "older-factless-run",
			logicalId = "logical-older-factless",
			manifestRevision = 1L,
			segmentId = 42L,
			startTimeMs = 2_000L,
			steps = null,
		)
		insertExactStepsRun(
			runId = "newer-factless-run-1",
			logicalId = "logical-newer-factless",
			manifestRevision = 1L,
			segmentId = 43L,
			startTimeMs = 3_000L,
			steps = null,
		)
		insertExactStepsRun(
			runId = "newer-factless-run-2",
			logicalId = "logical-newer-factless",
			manifestRevision = 2L,
			segmentId = 44L,
			startTimeMs = 5_000L,
			steps = null,
		)

		history.recentEntryCandidatePage(
			limit = 10,
			stepsSourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			capturePurpose = SessionManifestPurposeCode.SESSION_CAPTURE,
			includeExactStepsOnlyIntent = false,
			beforeStartTimeMs = null,
			beforeSegmentId = null,
		) shouldBe listOf(
			RecentHistoryEntryCandidate(
				logicalTrackingId = "logical-evidence",
				legacySegmentId = null,
				sortStartTimeMs = 1_000L,
				sortSegmentId = 41L,
			),
		)

		val newestIntent = history.recentEntryCandidatePage(
			limit = 1,
			stepsSourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			capturePurpose = SessionManifestPurposeCode.SESSION_CAPTURE,
			includeExactStepsOnlyIntent = true,
			beforeStartTimeMs = null,
			beforeSegmentId = null,
		).single()
		newestIntent shouldBe RecentHistoryEntryCandidate(
			logicalTrackingId = "logical-newer-factless",
			legacySegmentId = null,
			sortStartTimeMs = 5_000L,
			sortSegmentId = 44L,
		)

		history.recentEntryCandidatePage(
			limit = 1,
			stepsSourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			capturePurpose = SessionManifestPurposeCode.SESSION_CAPTURE,
			includeExactStepsOnlyIntent = true,
			beforeStartTimeMs = newestIntent.sortStartTimeMs,
			beforeSegmentId = newestIntent.sortSegmentId,
		).single() shouldBe RecentHistoryEntryCandidate(
			logicalTrackingId = "logical-older-factless",
			legacySegmentId = null,
			sortStartTimeMs = 2_000L,
			sortSegmentId = 42L,
		)
	}

	@Suppress("LongMethod")
	private suspend fun insertExactStepsRun(
		runId: String,
		logicalId: String,
		manifestRevision: Long,
		segmentId: Long,
		startTimeMs: Long,
		steps: Int?,
	) {
		database.sourceSessionDao().insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = runId,
				logicalTrackingId = logicalId,
				state = "FINALIZED",
				desiredPlanRevision = manifestRevision,
				rolloutRevision = 1L,
				foregroundCapabilityFlags = 0L,
				startedAtMs = startTimeMs,
				startedElapsedNanos = startTimeMs,
				completedAtMs = startTimeMs + 500L,
				completionReason = "STOPPED",
				sessionSegmentId = segmentId,
			),
		)
		database.sourceSessionDao().insertManifest(
			SessionManifestVersionEntity(
				logicalTrackingId = logicalId,
				manifestRevision = manifestRevision,
				serviceRunId = runId,
				sessionMode = "MANUAL",
				sourcePolicyRevision = manifestRevision,
				acquisitionPlanRevision = manifestRevision,
				rolloutRevision = 1L,
				startOrigin = "MANUAL_FOREGROUND_START",
				effectiveBootId = "boot",
				effectiveElapsedRealtimeNanos = startTimeMs,
				effectiveWallTimeMs = startTimeMs,
				zoneId = "UTC",
				automationEpoch = null,
				changeReason = "TEST",
				manifestChecksum = "checksum-$logicalId-$manifestRevision",
			),
		)
		database.sourceSessionDao().insertManifestSources(
			listOf(
				SessionManifestSourceEntity(
					logicalTrackingId = logicalId,
					manifestRevision = manifestRevision,
					sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
					purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
					consentEpoch = 1L,
					persistenceEligible = true,
					qosCode = 1,
					outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
					writerOwner = SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL,
					writerOwnerGeneration = 1L,
				),
			),
		)
		database.sessionSegmentDao().insert(
			SessionSegment(
				id = segmentId,
				startTimeMs = startTimeMs,
				endTimeMs = startTimeMs + 500L,
				distanceM = 0f,
				steps = steps,
				primaryActivity = null,
				activityConfidence = null,
				sampleCount = 0,
				source = SegmentSource.USER_CREATED,
				inferenceVersion = "test",
				createdAt = startTimeMs + 500L,
				logicalTrackingId = logicalId,
				serviceRunId = runId,
			),
		)
	}
}
