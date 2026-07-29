package com.adsamcik.tracker.stats.engine.research

import com.adsamcik.tracker.stats.api.research.ResearchAcquisitionApplyOutcome
import com.adsamcik.tracker.stats.api.research.ResearchAcquisitionConfiguration
import com.adsamcik.tracker.stats.api.research.ResearchAcquisitionMode
import com.adsamcik.tracker.stats.api.research.ResearchAcquisitionRecord
import com.adsamcik.tracker.stats.api.research.ResearchClockDomain
import com.adsamcik.tracker.stats.api.research.ResearchControlDigestKind
import com.adsamcik.tracker.stats.api.research.ResearchControlDigestRecord
import com.adsamcik.tracker.stats.api.research.ResearchControlEventKind
import com.adsamcik.tracker.stats.api.research.ResearchControlEventRecord
import com.adsamcik.tracker.stats.api.research.ResearchEvidenceEnvelope
import com.adsamcik.tracker.stats.api.research.ResearchEvidenceRecord
import com.adsamcik.tracker.stats.api.research.ResearchGapRecord
import com.adsamcik.tracker.stats.api.research.ResearchHorizontalEstimatorDecision
import com.adsamcik.tracker.stats.api.research.ResearchHorizontalEstimatorRecord
import com.adsamcik.tracker.stats.api.research.ResearchPrivacyClass
import com.adsamcik.tracker.stats.api.research.ResearchTrackingLifecycleRecord
import com.adsamcik.tracker.stats.api.research.ResearchTrackingLifecycleTransition
import com.adsamcik.tracker.stats.api.research.ResearchTrackingMode
import com.adsamcik.tracker.stats.api.research.ResearchTrackingStopCause
import com.adsamcik.tracker.stats.api.research.ResearchTraceIdentity
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ResearchControlTraceReplayTest {
	private val identity = ResearchTraceIdentity("control-trace", "run-1", "session-1")
	private val domain = ResearchClockDomain("boot-a", ResearchClockDomain.Kind.ANDROID_ELAPSED_REALTIME, "boot-a")

	@Test
	fun `replay reconstructs a typed logical lifecycle and stable trace digest`() {
		val evidence = listOf(
			envelope(0, lifecycle(ResearchTrackingLifecycleTransition.STARTED, 0)),
			envelope(
				1,
				ResearchControlEventRecord(
					logicalTrackingId = "logical-1",
					eventEpochMs = 100,
					eventElapsedNanos = 100,
					kind = ResearchControlEventKind.POLICY_STATE_CHANGED,
					payload = mapOf("tier" to "ACTIVE", "motion" to "MOVING"),
				),
			),
			envelope(
				2,
				ResearchAcquisitionRecord(
					logicalTrackingId = "logical-1",
					requestId = "request-1",
					eventEpochMs = 200,
					eventElapsedNanos = 200,
					desired = ResearchAcquisitionConfiguration(ResearchAcquisitionMode.BALANCED, intervalMs = 10_000),
					applied = ResearchAcquisitionConfiguration(ResearchAcquisitionMode.PASSIVE, intervalMs = 0),
					outcome = ResearchAcquisitionApplyOutcome.APPLIED,
				),
			),
			envelope(
				3,
				ResearchHorizontalEstimatorRecord(
					logicalTrackingId = "logical-1",
					estimatorVersion = "enu-kf-v1",
					decision = ResearchHorizontalEstimatorDecision.UPDATED,
					eventEpochMs = 300,
					sourceElapsedNanos = 300,
					measurementEastM = 2.0,
					measurementNorthM = 3.0,
					measurementCovariance = listOf(1.0, 0.0, 0.0, 1.0),
					innovation = listOf(0.1, 0.2),
					normalizedInnovationSquared = 0.3,
					accepted = true,
				),
			),
			envelope(4, ResearchGapRecord(400, 500, domain.id, "provider", "logical-1", 400, 500)),
			envelope(5, lifecycle(ResearchTrackingLifecycleTransition.PAUSED, 500)),
			envelope(6, lifecycle(ResearchTrackingLifecycleTransition.RESUMED, 600)),
			envelope(7, lifecycle(ResearchTrackingLifecycleTransition.STOP_CANDIDATE, 700)),
			envelope(
				8,
				lifecycle(
					ResearchTrackingLifecycleTransition.STOPPED,
					800,
					stopCause = ResearchTrackingStopCause.USER_REQUEST,
				),
			),
			envelope(
				9,
				ResearchControlDigestRecord(
					kind = ResearchControlDigestKind.LEGACY_V1_REPLAY,
					digestAlgorithm = "sha-256",
					digest = "legacy-output",
					logicalTrackingId = "logical-1",
					envelopeCount = 9,
					firstSequence = 0,
					lastSequence = 8,
				),
			),
		)

		val first = ResearchControlTraceReplay.replay(evidence)
		val second = ResearchControlTraceReplay.replay(evidence)
		assertEquals(first, second)
		assertEquals(10, first.accepted.size)
		assertTrue(first.rejected.isEmpty())
		assertEquals(ResearchControlLifecycleState.STOPPED, first.states.getValue("logical-1").lifecycleState)
		assertEquals(1L, first.states.getValue("logical-1").observedGapCount)
		assertEquals(ResearchAcquisitionMode.PASSIVE, first.states.getValue("logical-1").lastAcquisition?.applied?.mode)
		assertEquals(ResearchHorizontalEstimatorDecision.UPDATED, first.states.getValue("logical-1").lastEstimator?.decision)
		assertEquals(listOf(ResearchControlDigestKind.LEGACY_V1_REPLAY), first.legacyV1ReplayDigests.map { it.kind })
		assertEquals(9L, first.replayDigest.entryCount)
		assertTrue(first.replayDigest.value.matches(Regex("[0-9a-f]{16}")))
	}

	@Test
	fun `automatic lifecycle evidence cannot finish an explicit user run`() {
		val result = ResearchControlTraceReplay.replay(
			listOf(
				envelope(0, lifecycle(ResearchTrackingLifecycleTransition.STARTED, 0)),
				envelope(
					1,
					lifecycle(
						ResearchTrackingLifecycleTransition.STOPPED,
						100,
						stopCause = ResearchTrackingStopCause.AUTOMATIC_ACTIVITY,
					),
				),
			),
		)
		assertEquals(1, result.accepted.size)
		assertEquals(ResearchControlReplayRejection.AUTOMATIC_STOP_OF_USER_TRACKING, result.rejected.single().reason)
		assertEquals(ResearchControlLifecycleState.ACTIVE, result.states.getValue("logical-1").lifecycleState)
	}

	@Test
	fun `same-domain elapsed time is never silently reordered and payload maps hash canonically`() {
		val outOfOrder = ResearchControlTraceReplay.replay(
			listOf(
				envelope(0, controlEvent(100, mapOf("a" to "1", "b" to "2"))),
				envelope(1, controlEvent(99, mapOf("b" to "2", "a" to "1"))),
			),
		)
		assertEquals(ResearchControlReplayRejection.OUT_OF_ORDER_ELAPSED_TIME, outOfOrder.rejected.single().reason)

		val first = ResearchControlTraceReplay.replay(listOf(envelope(0, controlEvent(100, linkedMapOf("b" to "2", "a" to "1")))))
		val second = ResearchControlTraceReplay.replay(listOf(envelope(0, controlEvent(100, linkedMapOf("a" to "1", "b" to "2")))))
		assertEquals(first.replayDigest, second.replayDigest)
	}

	private fun lifecycle(
		transition: ResearchTrackingLifecycleTransition,
		elapsed: Long,
		stopCause: ResearchTrackingStopCause? = null,
	): ResearchTrackingLifecycleRecord = ResearchTrackingLifecycleRecord(
		logicalTrackingId = "logical-1",
		transition = transition,
		trackingMode = ResearchTrackingMode.USER_INITIATED,
		eventEpochMs = elapsed,
		eventElapsedNanos = elapsed,
		stopCause = stopCause,
	)

	private fun controlEvent(elapsed: Long, payload: Map<String, String>): ResearchControlEventRecord = ResearchControlEventRecord(
		logicalTrackingId = "logical-1",
		eventEpochMs = elapsed,
		eventElapsedNanos = elapsed,
		kind = ResearchControlEventKind.POLICY_INPUT,
		payload = payload,
	)

	private fun envelope(sequence: Long, record: ResearchEvidenceRecord): ResearchEvidenceEnvelope = ResearchEvidenceEnvelope(
		identity = identity,
		sequence = sequence,
		clockDomain = domain,
		privacyClass = ResearchPrivacyClass.ENCRYPTED_RESEARCH,
		record = record,
	)
}
