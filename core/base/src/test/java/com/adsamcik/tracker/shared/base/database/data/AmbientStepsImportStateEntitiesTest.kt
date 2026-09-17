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

	@Test
	fun `unobserved zone authority is confined to the initial cursor and explicit initial gap`() {
		val initial = cursor().copy(
			segmentStartTimeMs = 2_000L,
			importedThroughTimeMs = 2_000L,
			lastObservedAtMs = 2_000L,
			lastObservedZoneId = AmbientStepsImportGapEntity.ZONE_AUTHORITY_UNOBSERVED,
			updatedAtMs = 2_000L,
		)
		val gapId = AmbientStepsImportGapIntegrity.gapId(
			registrationGeneration = 7L,
			gapSequence = 1L,
			provider = PROVIDER,
			sourceInstanceId = "ambient-instance",
			reason = AmbientStepsImportGapEntity.REASON_INITIAL_ZONE_AUTHORITY_UNOBSERVED,
			gapStartTimeMs = 2_000L,
			gapEndTimeMs = 5_000L,
			predecessorRegistrationGeneration = null,
			predecessorProvider = null,
			previousClockDomainId = "boot-a",
			nextClockDomainId = "boot-a",
			previousZoneId = AmbientStepsImportGapEntity.ZONE_AUTHORITY_UNOBSERVED,
			nextZoneId = "Europe/Prague",
			collectedDataEpoch = 6L,
		)

		AmbientStepsImportGapEntity(
			gapId = gapId,
			registrationGeneration = 7L,
			gapSequence = 1L,
			provider = PROVIDER,
			sourceInstanceId = "ambient-instance",
			reason = AmbientStepsImportGapEntity.REASON_INITIAL_ZONE_AUTHORITY_UNOBSERVED,
			gapStartTimeMs = 2_000L,
			gapEndTimeMs = 5_000L,
			predecessorRegistrationGeneration = null,
			predecessorProvider = null,
			previousClockDomainId = "boot-a",
			nextClockDomainId = "boot-a",
			previousZoneId = AmbientStepsImportGapEntity.ZONE_AUTHORITY_UNOBSERVED,
			nextZoneId = "Europe/Prague",
			collectedDataEpoch = 6L,
			recordedAtMs = 5_000L,
		).gapEndTimeMs shouldBe 5_000L

		shouldThrow<IllegalArgumentException> {
			initial.copy(importedThroughTimeMs = 3_000L, lastObservedAtMs = 3_000L, updatedAtMs = 3_000L)
		}
		shouldThrow<IllegalArgumentException> {
			processGap().copy(
				previousZoneId = AmbientStepsImportGapEntity.ZONE_AUTHORITY_UNOBSERVED,
			)
		}
	}

	@Test
	fun `authority transition is an exact non-gap privacy split`() {
		val transition = authorityTransition()

		transition.effectiveBoundaryTimeMs shouldBe 6_000L
		listOf<() -> Unit>(
			{ transition.copy(transitionId = "wrong") },
			{ transition.copy(fromSourcePolicyRevision = 6L) },
			{ transition.copy(toAmbientConsentEpoch = 10L) },
			{ transition.copy(effectiveBoundaryTimeMs = 7_000L) },
			{ transition.copy(toContinuitySegmentGeneration = 3L) },
			{ transition.copy(toAuthorizationRevision = 3L) },
			{ transition.copy(effectiveBoundaryTimeMs = 5_000L) },
			{
				transition.copy(
					toAuthorizationFingerprint = transition.fromAuthorizationFingerprint,
					toSourcePolicyRevision = transition.fromSourcePolicyRevision,
					toAmbientConsentEpoch = transition.fromAmbientConsentEpoch,
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
		authorityTransitionSequence = 0L,
		cursorRevision = 1L,
		status = AmbientStepsImportCursorEntity.STATUS_ACTIVE,
		updatedAtMs = 5_000L,
		retentionScope = "LIVE_AMBIENT",
		retentionPolicyId = "test-retention",
		retentionApprovalRevision = 1L,
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

	private fun authorityTransition(): AmbientStepsImportAuthorityTransitionEntity =
		AmbientStepsImportAuthorityTransitionEntity(
			transitionId = AmbientStepsImportAuthorityTransitionIntegrity.transitionId(
				7L, 1L, PROVIDER, "ambient-instance", 6L, 1L, 2L,
				3L, "a".repeat(64), 4L, 5L, 4L, "b".repeat(64),
				"boot-a", 6_000_000_000L, 5_001L, 8L, 9L, 1_001L, 6_000L,
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
