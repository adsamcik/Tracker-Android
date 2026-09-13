package com.adsamcik.tracker.shared.base.database.data

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.Test

class AmbientStepsImportStateEntitiesTest {
	@Test
	fun `cursor rounds privacy authority forward and cannot claim pre-consent progress`() {
		AmbientStepsImportCursorEntity.privacyFloorTimeMs(1_500L, 2_100L) shouldBe 3_000L
		val cursor = cursor()

		cursor.eligibleFromTimeMs shouldBe 2_000L
		listOf<() -> Unit>(
			{ cursor.copy(eligibleFromTimeMs = 1_000L) },
			{ cursor.copy(segmentStartTimeMs = 1_000L) },
			{ cursor.copy(importedThroughTimeMs = 1_000L) },
			{ cursor.copy(lastObservedBootId = "boot-b") },
			{ cursor.copy(lastObservedZoneId = "not a zone") },
			{ cursor.copy(authorizationFingerprint = "not-a-digest") },
		).forEach { invalid -> shouldThrow<IllegalArgumentException> { invalid() } }
	}

	@Test
	fun `gap identity freezes exact discontinuity authority`() {
		val gap = processGap()

		gap.gapEndTimeMs shouldBe 6_000L
		listOf<() -> Unit>(
			{ gap.copy(gapId = "process:4000:6000") },
			{ gap.copy(gapEndTimeMs = gap.gapStartTimeMs) },
			{
				gap.copy(
					reason = AmbientStepsImportGapEntity.REASON_ZONE_CHANGED,
					gapId = gapId(reason = AmbientStepsImportGapEntity.REASON_ZONE_CHANGED),
				)
			},
			{
				gap.copy(
					reason = AmbientStepsImportGapEntity.REASON_BOOT_CHANGED,
					previousClockDomainId = "boot-before",
					gapId = gapId(
						reason = AmbientStepsImportGapEntity.REASON_BOOT_CHANGED,
						previousClockDomainId = "boot-before",
					),
				)
			},
		).forEach { invalid -> shouldThrow<IllegalArgumentException> { invalid() } }
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

	private fun processGap(): AmbientStepsImportGapEntity = AmbientStepsImportGapEntity(
		gapId = gapId(),
		registrationGeneration = 7L,
		gapSequence = 1L,
		provider = PROVIDER,
		sourceInstanceId = "ambient-instance",
		reason = AmbientStepsImportGapEntity.REASON_PROCESS_ABSENCE,
		gapStartTimeMs = 4_000L,
		gapEndTimeMs = 6_000L,
		predecessorRegistrationGeneration = null,
		predecessorProvider = null,
		previousClockDomainId = "boot-a",
		nextClockDomainId = "boot-a",
		previousZoneId = "UTC",
		nextZoneId = "UTC",
		collectedDataEpoch = 6L,
		recordedAtMs = 7_000L,
	)

	private fun gapId(
		reason: String = AmbientStepsImportGapEntity.REASON_PROCESS_ABSENCE,
		previousClockDomainId: String = "boot-a",
	): String = AmbientStepsImportGapIntegrity.gapId(
		registrationGeneration = 7L,
		gapSequence = 1L,
		provider = PROVIDER,
		sourceInstanceId = "ambient-instance",
		reason = reason,
		gapStartTimeMs = 4_000L,
		gapEndTimeMs = 6_000L,
		predecessorRegistrationGeneration = null,
		predecessorProvider = null,
		previousClockDomainId = previousClockDomainId,
		nextClockDomainId = "boot-a",
		previousZoneId = "UTC",
		nextZoneId = "UTC",
		collectedDataEpoch = 6L,
	)

	private companion object {
		const val PROVIDER = AmbientStepsImportCursorEntity.PROVIDER_HEALTH_CONNECT_MOBILE_STEPS
	}
}
