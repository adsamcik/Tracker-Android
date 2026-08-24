package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class RecentStepEvidenceTrackerTest {
	private var nowNanos = 1_000L
	private val subject = RecentStepEvidenceTracker { nowNanos }

	@Test
	fun `qualified durable handoff is recent only for its exact authorization identity`() {
		val registration = registration(authorizationRevision = 3L, effectiveElapsedNanos = 100L)
		subject.recordQualified(registration, payload(200L, 300L), observedElapsedNanos = 300L)

		assertTrue(subject.hasRecent(registration, maximumAgeNanos = 1_000L))
		assertFalse(subject.hasRecent(
			registration(authorizationRevision = 4L, effectiveElapsedNanos = 400L),
			maximumAgeNanos = 1_000L,
		))
	}

	@Test
	fun `window crossing control authorization boundary cannot corroborate`() {
		val controlJoinedAt = 250L
		val registration = registration(authorizationRevision = 4L, effectiveElapsedNanos = controlJoinedAt)

		subject.recordQualified(registration, payload(200L, 300L), observedElapsedNanos = 300L)
		assertFalse(subject.hasRecent(registration, maximumAgeNanos = 1_000L))

		subject.recordQualified(registration, payload(300L, 350L), observedElapsedNanos = 350L)
		assertTrue(subject.hasRecent(registration, maximumAgeNanos = 1_000L))
	}

	@Test
	fun `duplicate replay retains observed time and cannot make stale evidence fresh`() {
		val registration = registration(authorizationRevision = 3L, effectiveElapsedNanos = 100L)
		val oldWindow = payload(200L, 300L)
		subject.recordQualified(registration, oldWindow, observedElapsedNanos = 300L)
		nowNanos = 2_000L

		assertFalse(subject.hasRecent(registration, maximumAgeNanos = 1_000L))
		subject.recordQualified(registration, oldWindow, observedElapsedNanos = 300L)
		assertFalse(subject.hasRecent(registration, maximumAgeNanos = 1_000L))
	}

	@Test
	fun `baseline reset zero delta capture only and process recreation remain unknown`() {
		val control = registration(authorizationRevision = 3L, effectiveElapsedNanos = 100L)
		subject.recordQualified(control, payload(200L, 300L, delta = 0L), 300L)
		subject.recordQualified(control, payload(300L, 400L, reset = true), 400L)
		subject.recordQualified(
			registration(authorizationRevision = 3L, effectiveElapsedNanos = 100L, control = false),
			payload(400L, 500L),
			500L,
		)

		assertFalse(subject.hasRecent(control, maximumAgeNanos = 1_000L))
		assertFalse(RecentStepEvidenceTracker { nowNanos }
			.hasRecent(control, maximumAgeNanos = 1_000L))
	}

	private fun registration(
		authorizationRevision: Long,
		effectiveElapsedNanos: Long,
		control: Boolean = true,
	): SourceRegistration {
		val purpose = if (control) SourceBrokerPurpose.CONTROL_AUTOSTART else SourceBrokerPurpose.SESSION_CAPTURE
		val demand = SourceDemandEntity(
			demandId = "demand-$authorizationRevision-$purpose",
			consumerId = if (control) "app:automatic-start:steps" else "session:logical-1",
			sourceKind = SourceKind.STEPS.stableCode,
			purpose = purpose,
			logicalTrackingId = if (control) null else "logical-1",
			serviceRunId = if (control) null else "run-1",
			manifestRevision = if (control) null else 1L,
			lifecycleLeaseGeneration = if (control) null else 1L,
			sourcePolicyRevision = 2L,
			consentEpoch = 7L,
			persistenceEligible = !control,
			qosCode = 0,
			maximumAgeMs = 30_000L,
			desiredLatencyMs = 5_000L,
			requestedBootId = "boot-1",
			requestedElapsedRealtimeNanos = effectiveElapsedNanos,
			requestedAtMs = 10L,
			status = SourceDemandEntity.STATUS_ACTIVE,
			retireBootId = null,
			retireElapsedRealtimeNanos = null,
			retiredAtMs = null,
		)
		val authorization = requireNotNull(SourceBrokerAuthorization.rows(
			SourceKind.STEPS.stableCode,
			9L,
			authorizationRevision,
			listOf(demand),
			"boot-1",
			effectiveElapsedNanos,
			10L,
		).toAuthorizationSnapshotOrNull())
		return SourceRegistration(
			ownerScope = "source-broker:${SourceKind.STEPS.stableCode}",
			state = SourceRegistrationStateEntity(
				sourceKind = SourceKind.STEPS.stableCode,
				ownerScope = "source-broker:${SourceKind.STEPS.stableCode}",
				sourceInstanceId = "steps-1",
				clockDomainId = "boot-1",
				registrationGeneration = 9L,
				nextSequence = 0L,
				appliedRevision = 2L,
				collectedDataEpoch = 11L,
				updatedAtMs = 10L,
			),
			physicalConfigurationFingerprint = "steps-physical",
			authorization = authorization,
			requiresProviderAcceptance = false,
		)
	}

	private fun payload(
		windowStart: Long,
		windowEnd: Long,
		delta: Long = 1L,
		reset: Boolean = false,
	) = StepCounterWindowPayload(
		bootClockDomainId = "boot-1",
		firstCumulativeCount = 100L,
		lastCumulativeCount = 100L + delta,
		deltaCount = delta,
		windowStartElapsedRealtimeNanos = windowStart,
		windowEndElapsedRealtimeNanos = windowEnd,
		firstProviderSequence = 1L,
		lastProviderSequence = 2L,
		baselineReset = reset,
	)
}
