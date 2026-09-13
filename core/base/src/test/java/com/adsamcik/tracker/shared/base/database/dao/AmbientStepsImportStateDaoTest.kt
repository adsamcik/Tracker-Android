package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportCursorEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportAuthorityTransitionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportAuthorityTransitionIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportGapEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportGapEffectIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportGapEffectRevisionEntity
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
			expectedUpdatedAtMs = 5_000L,
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
			expectedUpdatedAtMs = 5_000L,
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
			expectedUpdatedAtMs = 7_000L,
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
		dao.insertGapEffectRevision(gapEffect(gap, 1L, DECLARE, 8_000L)) shouldBe 1L
		dao.beginNextSegmentExact(
			registrationGeneration = 7L,
			expectedContinuitySegmentGeneration = 1L,
			expectedLastGapSequence = 0L,
			expectedCursorRevision = 2L,
			expectedImportedThroughTimeMs = 6_000L,
			expectedObservedAtMs = 7_000L,
			expectedUpdatedAtMs = 7_000L,
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
	fun `late fact retracts a gap and fact retraction restores it by revision`() = runTest {
		val gap = gap()
		dao.insertCursor(
			cursor().copy(importedThroughTimeMs = 6_000L, lastObservedAtMs = 7_000L, updatedAtMs = 7_000L),
		) shouldBe 1L
		dao.insertGap(gap) shouldBe 1L
		val declared = gapEffect(gap, 1L, DECLARE, 9_000L)
		val retracted = gapEffect(gap, 2L, RETRACT, 10_000L)
		val restored = gapEffect(gap, 3L, DECLARE, 11_000L)

		dao.insertGapEffectRevision(declared) shouldBe 1L
		dao.effectiveGaps(7L) shouldContainExactly listOf(gap)
		dao.insertGapEffectRevision(retracted) shouldBe 2L
		dao.effectiveGaps(7L) shouldContainExactly emptyList()
		beginGapSegment() shouldBe 0
		dao.insertGapEffectRevision(restored) shouldBe 3L
		dao.effectiveGaps(7L) shouldContainExactly listOf(gap)
		beginGapSegment() shouldBe 1
		dao.gapEffectRevisions(gap.gapId) shouldContainExactly listOf(declared, retracted, restored)
	}

	private suspend fun beginGapSegment(): Int = dao.beginNextSegmentExact(
		registrationGeneration = 7L,
		expectedContinuitySegmentGeneration = 1L,
		expectedLastGapSequence = 0L,
		expectedCursorRevision = 1L,
		expectedImportedThroughTimeMs = 6_000L,
		expectedObservedAtMs = 7_000L,
		expectedUpdatedAtMs = 7_000L,
		newLastGapSequence = 1L,
		newContinuitySegmentGeneration = 2L,
		newSegmentStartTimeMs = 8_000L,
		newObservedAtMs = 9_000L,
		newBootId = "boot-a",
		newZoneId = "UTC",
		newCursorRevision = 2L,
		updatedAtMs = 9_000L,
	)

	@Test
	fun `same registration rotates authority at one exact non-gap boundary`() = runTest {
		val initial = cursor().copy(importedThroughTimeMs = 6_000L, lastObservedAtMs = 7_000L, updatedAtMs = 7_000L)
		dao.insertCursor(initial) shouldBe 1L
		val transition = authorityTransition()
		dao.insertAuthorityTransition(transition) shouldBe 1L

		dao.rotateAuthorityExact(
			registrationGeneration = 7L,
			expectedContinuitySegmentGeneration = 1L,
			expectedLastGapSequence = 0L,
			expectedAuthorityTransitionSequence = 0L,
			expectedCursorRevision = 1L,
			expectedObservedAtMs = 7_000L,
			expectedUpdatedAtMs = 7_000L,
			newAuthorityTransitionSequence = 1L,
			newContinuitySegmentGeneration = 2L,
			newAuthorizationRevision = 4L,
			newAuthorizationFingerprint = "b".repeat(64),
			newAuthorizationEffectiveBootId = "boot-a",
			newAuthorizationEffectiveElapsedRealtimeNanos = 6_000_000_000L,
			newAuthorizationEffectiveWallTimeMs = 5_001L,
			newSourcePolicyRevision = 8L,
			newAmbientConsentEpoch = 9L,
			effectiveBoundaryTimeMs = 6_000L,
			newObservedAtMs = 8_000L,
			newCursorRevision = 2L,
			updatedAtMs = 8_000L,
		) shouldBe 1

		val rotated = requireNotNull(dao.cursor(7L))
		rotated.continuitySegmentGeneration shouldBe 2L
		rotated.lastGapSequence shouldBe 0L
		rotated.authorityTransitionSequence shouldBe 1L
		rotated.eligibleFromTimeMs shouldBe 6_000L
		rotated.segmentStartTimeMs shouldBe 6_000L
		rotated.importedThroughTimeMs shouldBe 6_000L
		rotated.authorizationRevision shouldBe 4L
		rotated.sourcePolicyRevision shouldBe 8L
		rotated.ambientConsentEpoch shouldBe 9L
		dao.authorityTransitions(7L) shouldContainExactly listOf(transition)
	}

	@Test
	fun `same high water requires a strictly newer observation and monotonic update time`() = runTest {
		dao.insertCursor(cursor()) shouldBe 1L
		suspend fun advance(observedAtMs: Long, updatedAtMs: Long): Int =
			dao.advanceExact(
				registrationGeneration = 7L,
				expectedContinuitySegmentGeneration = 1L,
				expectedLastGapSequence = 0L,
				expectedCursorRevision = 1L,
				expectedImportedThroughTimeMs = 4_000L,
				expectedObservedAtMs = 5_000L,
				expectedUpdatedAtMs = 5_000L,
				expectedBootId = "boot-a",
				expectedZoneId = "UTC",
				newImportedThroughTimeMs = 4_000L,
				newObservedAtMs = observedAtMs,
				newCursorRevision = 2L,
				updatedAtMs = updatedAtMs,
			)

		advance(observedAtMs = 5_000L, updatedAtMs = 5_000L) shouldBe 0
		advance(observedAtMs = 6_000L, updatedAtMs = 4_999L) shouldBe 0
		advance(observedAtMs = 6_000L, updatedAtMs = 6_000L) shouldBe 1
	}

	@Test
	fun `retired cursor cannot advance and full clear removes cursor and gaps`() = runTest {
		dao.insertCursor(cursor())
		dao.insertGap(gap())
		dao.insertGapEffectRevision(gapEffect(gap(), 1L, DECLARE, 8_000L))
		dao.insertAuthorityTransition(authorityTransition())
		dao.retireExact(
			registrationGeneration = 7L,
			expectedCursorRevision = 1L,
			expectedImportedThroughTimeMs = 4_000L,
			expectedUpdatedAtMs = 5_000L,
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
			expectedUpdatedAtMs = 8_000L,
			expectedBootId = "boot-a",
			expectedZoneId = "UTC",
			newImportedThroughTimeMs = 6_000L,
			newObservedAtMs = 8_000L,
			newCursorRevision = 3L,
			updatedAtMs = 8_000L,
		) shouldBe 0

		dao.deleteAllAuthorityTransitions()
		dao.deleteAllGapEffectRevisions()
		dao.deleteAllGaps()
		dao.deleteAllCursors()
		dao.countGaps() shouldBe 0L
		dao.countAuthorityTransitions() shouldBe 0L
		dao.countGapEffectRevisions() shouldBe 0L
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
		authorityTransitionSequence = 0L,
		cursorRevision = 1L,
		status = AmbientStepsImportCursorEntity.STATUS_ACTIVE,
		updatedAtMs = 5_000L,
	)

	private fun authorityTransition(): AmbientStepsImportAuthorityTransitionEntity =
		AmbientStepsImportAuthorityTransitionEntity(
			transitionId = AmbientStepsImportAuthorityTransitionIntegrity.transitionId(
				registrationGeneration = 7L,
				transitionSequence = 1L,
				sourceInstanceId = "ambient-instance",
				collectedDataEpoch = 6L,
			),
			registrationGeneration = 7L,
			transitionSequence = 1L,
			provider = PROVIDER,
			sourceInstanceId = "ambient-instance",
			collectedDataEpoch = 6L,
			fromContinuitySegmentGeneration = 1L,
			toContinuitySegmentGeneration = 2L,
			fromAuthorizationRevision = 3L,
			fromAuthorizationFingerprint = "a".repeat(64),
			fromSourcePolicyRevision = 4L,
			fromAmbientConsentEpoch = 5L,
			toAuthorizationRevision = 4L,
			toAuthorizationFingerprint = "b".repeat(64),
			toAuthorizationEffectiveBootId = "boot-a",
			toAuthorizationEffectiveElapsedRealtimeNanos = 6_000_000_000L,
			toAuthorizationEffectiveWallTimeMs = 5_001L,
			toSourcePolicyRevision = 8L,
			toAmbientConsentEpoch = 9L,
			registrationAcceptedAtMs = 1_001L,
			effectiveBoundaryTimeMs = 6_000L,
			recordedAtMs = 8_000L,
		)

	private fun gapEffect(
		gap: AmbientStepsImportGapEntity,
		revision: Long,
		operation: String,
		recordedAtMs: Long,
	): AmbientStepsImportGapEffectRevisionEntity {
		val mutationId = AmbientStepsImportGapEffectIntegrity.mutationId(
			gap.gapId,
			revision,
			operation,
		)
		return AmbientStepsImportGapEffectRevisionEntity(
			gapId = gap.gapId,
			semanticRevision = revision,
			mutationId = mutationId,
			operation = operation,
			effectChecksum = AmbientStepsImportGapEffectIntegrity.effectChecksum(
				gap.gapId,
				revision,
				mutationId,
				operation,
				recordedAtMs,
			),
			recordedAtMs = recordedAtMs,
		)
	}

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
		const val DECLARE = AmbientStepsImportGapEffectRevisionEntity.OPERATION_DECLARE
		const val RETRACT = AmbientStepsImportGapEffectRevisionEntity.OPERATION_RETRACT
	}
}
