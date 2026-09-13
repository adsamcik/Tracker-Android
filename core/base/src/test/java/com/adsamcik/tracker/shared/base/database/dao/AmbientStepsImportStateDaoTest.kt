package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportCursorEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportGapEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportGapIntegrity
import io.kotest.matchers.collections.shouldContainExactly
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
class AmbientStepsImportStateDaoTest {
	private lateinit var database: AppDatabase
	private lateinit var dao: AmbientStepsImportStateDao

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dao = database.ambientStepsImportStateDao()
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `progress and segment transitions are monotonic compare and set`() = runTest {
		val initial = cursor()
		dao.insertCursor(initial) shouldBe 1L
		dao.insertCursor(initial) shouldBe -1L

		dao.advanceExact(
			registrationGeneration = 7L,
			expectedContinuitySegmentGeneration = 1L,
			expectedLastGapSequence = 0L,
			expectedCursorRevision = 1L,
			expectedImportedThroughTimeMs = 4_000L,
			expectedObservedAtMs = 5_000L,
			expectedBootId = "boot-a",
			expectedZoneId = "UTC",
			newImportedThroughTimeMs = 6_000L,
			newObservedAtMs = 7_000L,
			newCursorRevision = 2L,
			updatedAtMs = 7_000L,
		) shouldBe 1

		// A stale or regressing importer cannot move the accepted high-water.
		dao.advanceExact(
			registrationGeneration = 7L,
			expectedContinuitySegmentGeneration = 1L,
			expectedLastGapSequence = 0L,
			expectedCursorRevision = 1L,
			expectedImportedThroughTimeMs = 4_000L,
			expectedObservedAtMs = 5_000L,
			expectedBootId = "boot-a",
			expectedZoneId = "UTC",
			newImportedThroughTimeMs = 8_000L,
			newObservedAtMs = 9_000L,
			newCursorRevision = 2L,
			updatedAtMs = 9_000L,
		) shouldBe 0
		dao.advanceExact(
			registrationGeneration = 7L,
			expectedContinuitySegmentGeneration = 1L,
			expectedLastGapSequence = 0L,
			expectedCursorRevision = 2L,
			expectedImportedThroughTimeMs = 6_000L,
			expectedObservedAtMs = 7_000L,
			expectedBootId = "boot-a",
			expectedZoneId = "UTC",
			newImportedThroughTimeMs = 5_000L,
			newObservedAtMs = 9_000L,
			newCursorRevision = 3L,
			updatedAtMs = 9_000L,
		) shouldBe 0

		val gap = gap()
		dao.insertGap(gap) shouldBe 1L
		dao.insertGap(gap) shouldBe -1L
		dao.beginNextSegmentExact(
			registrationGeneration = 7L,
			expectedContinuitySegmentGeneration = 1L,
			expectedLastGapSequence = 0L,
			expectedCursorRevision = 2L,
			expectedImportedThroughTimeMs = 6_000L,
			newLastGapSequence = 1L,
			newContinuitySegmentGeneration = 2L,
			newSegmentStartTimeMs = 8_000L,
			newObservedAtMs = 9_000L,
			newBootId = "boot-a",
			newZoneId = "UTC",
			newCursorRevision = 3L,
			updatedAtMs = 9_000L,
		) shouldBe 1

		val next = requireNotNull(dao.cursor(7L))
		next.continuitySegmentGeneration shouldBe 2L
		next.lastGapSequence shouldBe 1L
		next.segmentStartTimeMs shouldBe 8_000L
		next.importedThroughTimeMs shouldBe 8_000L
		dao.gaps(7L) shouldContainExactly listOf(gap)
	}

	@Test
	fun `retired cursor cannot advance and full clear removes cursor and gaps`() = runTest {
		dao.insertCursor(cursor())
		dao.insertGap(gap())
		dao.retireExact(
			registrationGeneration = 7L,
			expectedCursorRevision = 1L,
			expectedImportedThroughTimeMs = 4_000L,
			newCursorRevision = 2L,
			updatedAtMs = 8_000L,
		) shouldBe 1
		dao.advanceExact(
			registrationGeneration = 7L,
			expectedContinuitySegmentGeneration = 1L,
			expectedLastGapSequence = 0L,
			expectedCursorRevision = 2L,
			expectedImportedThroughTimeMs = 4_000L,
			expectedObservedAtMs = 5_000L,
			expectedBootId = "boot-a",
			expectedZoneId = "UTC",
			newImportedThroughTimeMs = 6_000L,
			newObservedAtMs = 8_000L,
			newCursorRevision = 3L,
			updatedAtMs = 8_000L,
		) shouldBe 0

		dao.deleteAllGaps()
		dao.deleteAllCursors()
		dao.countGaps() shouldBe 0L
		dao.countCursors() shouldBe 0L
	}

	private fun cursor() = AmbientStepsImportCursorEntity(
		registrationGeneration = 7L,
		provider = PROVIDER,
		sourceInstanceId = "ambient-instance",
		registrationClockDomainId = "boot-a",
		registrationAcceptedAtMs = 1_001L,
		registrationAcceptedElapsedRealtimeNanos = 2_000L,
		authorizationRevision = 3L,
		authorizationFingerprint = "a".repeat(64),
		authorizationEffectiveBootId = "boot-a",
		authorizationEffectiveElapsedRealtimeNanos = 2_000L,
		authorizationEffectiveWallTimeMs = 1_500L,
		sourcePolicyRevision = 4L,
		ambientConsentEpoch = 5L,
		collectedDataEpoch = 6L,
		eligibleFromTimeMs = 2_000L,
		continuitySegmentGeneration = 1L,
		segmentStartTimeMs = 2_000L,
		importedThroughTimeMs = 4_000L,
		lastObservedAtMs = 5_000L,
		lastObservedBootId = "boot-a",
		lastObservedZoneId = "UTC",
		lastGapSequence = 0L,
		cursorRevision = 1L,
		status = AmbientStepsImportCursorEntity.STATUS_ACTIVE,
		updatedAtMs = 5_000L,
	)

	private fun gap(): AmbientStepsImportGapEntity {
		val id = AmbientStepsImportGapIntegrity.gapId(
			registrationGeneration = 7L,
			gapSequence = 1L,
			provider = PROVIDER,
			sourceInstanceId = "ambient-instance",
			reason = AmbientStepsImportGapEntity.REASON_PROCESS_ABSENCE,
			gapStartTimeMs = 6_000L,
			gapEndTimeMs = 8_000L,
			predecessorRegistrationGeneration = null,
			predecessorProvider = null,
			previousClockDomainId = "boot-a",
			nextClockDomainId = "boot-a",
			previousZoneId = "UTC",
			nextZoneId = "UTC",
			collectedDataEpoch = 6L,
		)
		return AmbientStepsImportGapEntity(
			gapId = id,
			registrationGeneration = 7L,
			gapSequence = 1L,
			provider = PROVIDER,
			sourceInstanceId = "ambient-instance",
			reason = AmbientStepsImportGapEntity.REASON_PROCESS_ABSENCE,
			gapStartTimeMs = 6_000L,
			gapEndTimeMs = 8_000L,
			predecessorRegistrationGeneration = null,
			predecessorProvider = null,
			previousClockDomainId = "boot-a",
			nextClockDomainId = "boot-a",
			previousZoneId = "UTC",
			nextZoneId = "UTC",
			collectedDataEpoch = 6L,
			recordedAtMs = 9_000L,
		)
	}

	private companion object {
		const val PROVIDER = AmbientStepsImportCursorEntity.PROVIDER_LOCAL_RECORDING_STEPS
	}
}
