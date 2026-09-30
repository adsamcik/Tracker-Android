package com.adsamcik.tracker.tracker.source.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.Test

class SourceEventTest {
	@Test
	fun `v6 radio callback can retain an authenticated denied snapshot`() {
		candidate(SourceKind.WIFI, wifiPayload(), payloadVersion = 6, authorizationRevision = 2L,
			eligibilityFingerprint = "denied-revision").registrationPurposeEligibilityMask shouldBe 0L
		candidate(SourceKind.CELL, cellPayload(), payloadVersion = 6, authorizationRevision = 2L,
			eligibilityFingerprint = "denied-revision").registrationPurposeEligibilityMask shouldBe 0L
	}

	@Test
	fun `legacy and unstamped candidates cannot claim a denied revision fingerprint`() {
		shouldThrow<IllegalArgumentException> {
			candidate(SourceKind.WIFI, wifiPayload(), payloadVersion = 5, authorizationRevision = 2L,
				eligibilityFingerprint = "denied-revision")
		}
		shouldThrow<IllegalArgumentException> {
			candidate(SourceKind.WIFI, wifiPayload(), payloadVersion = 6,
				eligibilityFingerprint = "denied-revision")
		}
		shouldThrow<IllegalArgumentException> {
			candidate(SourceKind.ACTIVITY, activityPayload(), payloadVersion = 6, authorizationRevision = 2L,
				eligibilityFingerprint = "denied-revision")
		}
		candidate(SourceKind.WIFI, wifiPayload(), payloadVersion = 6)
			.registrationEligibilityFingerprint shouldBe null
	}

	@Test
	fun `candidate requires payload and declared source to match`() {
		shouldThrow<IllegalArgumentException> {
			candidate(source = SourceKind.CELL, payload = activityPayload())
		}
	}

	@Test
	fun `causal plan attribution requires a revision`() {
		shouldThrow<IllegalArgumentException> {
			candidate(
				source = SourceKind.ACTIVITY,
				payload = activityPayload(),
				planAttribution = PlanAttribution.CAPTURED_REGISTRATION,
				configRevision = null,
			)
		}
	}

	@Test
	fun `receive time attribution permits an unknown revision`() {
		candidate(
			source = SourceKind.ACTIVITY,
			payload = activityPayload(),
			planAttribution = PlanAttribution.RECEIVE_TIME_ONLY,
			configRevision = null,
		).configRevision shouldBe null
	}

	private fun activityPayload() = ActivityTransitionPayload(
		activityType = 3,
		transitionType = 1,
		providerElapsedRealtimeNanos = 10L,
	)

	private fun wifiPayload() = WifiResultSnapshotPayload(emptyList(), 100L, null, "UTC")
	private fun cellPayload() = CellSnapshotPayload(null, emptyList(), CellRefreshOutcome.CALLBACK, "UTC")

	private fun candidate(
		source: SourceKind,
		payload: SourcePayload,
		planAttribution: PlanAttribution = PlanAttribution.RECEIVE_TIME_ONLY,
		configRevision: Long? = null,
		payloadVersion: Int = 1,
		authorizationRevision: Long? = null,
		eligibilityFingerprint: String? = null,
	) = SourceEvidenceCandidate(
		providerDedupKey = null,
		logicalTrackingId = null,
		serviceRunId = null,
		source = source,
		sourceInstanceId = SourceInstanceId("instance"),
		registrationGeneration = 1L,
		authorizationRevision = authorizationRevision,
		registrationEligibilityFingerprint = eligibilityFingerprint,
		sourceSequence = 1L,
		configRevision = configRevision,
		planAttribution = planAttribution,
		clockDomainId = "boot",
		observedElapsedRealtimeNanos = 10L,
		receivedElapsedRealtimeNanos = 11L,
		wallTimeMs = 100L,
		wallTimeUncertaintyMs = 1L,
		capturedCollectedDataEpoch = 2L,
		acquiredAtMs = 100L,
		quality = SourceQuality(),
		payloadVersion = payloadVersion,
		payload = payload,
	)
}

