package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationArbiter
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationOwner
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationResult
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationSnapshot
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationStatus
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.tracker.api.AutomaticTrackingOperationalAvailability
import com.adsamcik.tracker.tracker.api.CurrentTrackingPurposeAvailability
import com.adsamcik.tracker.tracker.api.CurrentTrackingPurposeAvailabilityReader
import com.adsamcik.tracker.tracker.api.TrackingPurpose
import com.adsamcik.tracker.tracker.api.TrackingPurposeAuthorityRevision
import com.adsamcik.tracker.tracker.api.TrackingPurposeAuthorityVector
import com.adsamcik.tracker.tracker.api.TrackingPurposeAvailabilitySnapshot
import com.adsamcik.tracker.tracker.api.TrackingPurposeLeaseIdentity
import com.adsamcik.tracker.tracker.api.TrackingSource
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationProjectionLane
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AutomaticStartTransitionMonitorTest {
	private val arbiter = mockk<ActivityRegistrationArbiter>()
	private val broker = mockk<SourceBroker>()
	private val activityProjectionLane = mockk<ActivityAutomationProjectionLane>()
	private val currentPurposeAvailability =
		MutableStateFlow(currentAvailability(readyAutomaticControl()))
	private val currentPurposeReader = object : CurrentTrackingPurposeAvailabilityReader {
		override val availability = currentPurposeAvailability
		override val authorityRevision = MutableStateFlow(
			TrackingPurposeAuthorityRevision(
				policyRevision = 1L,
				collectedDataEpoch = 0L,
				rolloutRevision = 0L,
			),
		)
	}
	private val subject = AutomaticStartTransitionMonitor(
		arbiter = arbiter,
		sourceBroker = broker,
		clockDomainProvider = BootClockDomainProvider { "boot:test" },
		activityProjectionLane = activityProjectionLane,
		currentPurposeAvailabilityReader = currentPurposeReader,
	)

	@Test
	fun `disabled transition API cannot fall back to continuous recognition control`() = runTest {
		coEvery { broker.replaceAutomaticControlDemand(any(), any(), false, any(), any(), any(), any(), any()) } returns null
		coEvery { arbiter.clearDemand(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR) } returns cleared()

		val result = subject.reconcile(
			enabled = true,
			useTransitionApi = false,
			continuousIntervalSeconds = 5,
			transitions = setOf(walkingEnter()),
			controlAvailability = readyAutomaticControl(),
		)

		coVerify(exactly = 1) {
			broker.replaceAutomaticControlDemand(
				consumerId = "app:automatic-start:activity",
				source = SourceKind.ACTIVITY,
				enabled = false,
				bootId = "boot:test",
				elapsedRealtimeNanos = any(),
				wallTimeMs = any(),
				maximumAgeMs = any(),
				desiredLatencyMs = 5_000L,
			)
		}
		coVerify(exactly = 1) {
			arbiter.clearDemand(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR)
		}
		coVerify(exactly = 0) { arbiter.setDemand(any(), any()) }
		coVerify(exactly = 0) { activityProjectionLane.ensureRegisteredAtLiveTail() }
		result.status shouldBe ActivityRegistrationStatus.BLOCKED
	}

	@Test
	fun `empty transition set clears automatic control instead of registering sampling`() = runTest {
		coEvery { broker.replaceAutomaticControlDemand(any(), any(), false, any(), any(), any(), any(), any()) } returns null
		coEvery { arbiter.clearDemand(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR) } returns cleared()

		val result = subject.reconcile(
			enabled = true,
			useTransitionApi = true,
			continuousIntervalSeconds = 30,
			transitions = emptySet(),
			controlAvailability = readyAutomaticControl(),
		)

		coVerify(exactly = 1) {
			broker.replaceAutomaticControlDemand(any(), SourceKind.ACTIVITY, false, any(), any(), any(), any(), any())
		}
		coVerify(exactly = 0) { arbiter.setDemand(any(), any()) }
		coVerify(exactly = 0) { activityProjectionLane.ensureRegisteredAtLiveTail() }
		result.status shouldBe ActivityRegistrationStatus.BLOCKED
	}

	@Test
	fun `unapproved control retention policy clears demand without registering Activity control`() =
		runTest {
			coEvery {
				broker.replaceAutomaticControlDemand(
					any(),
					any(),
					false,
					any(),
					any(),
					any(),
					any(),
					any(),
				)
			} returns null
			coEvery {
				arbiter.clearDemand(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR)
			} returns cleared()

			val result = subject.reconcile(
				enabled = true,
				useTransitionApi = true,
				continuousIntervalSeconds = 30,
				transitions = setOf(walkingEnter()),
			)

			coVerify(exactly = 1) {
				broker.replaceAutomaticControlDemand(
					any(),
					SourceKind.ACTIVITY,
					false,
					any(),
					any(),
					any(),
					any(),
					any(),
				)
			}
			coVerify(exactly = 1) {
				arbiter.clearDemand(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR)
			}
			coVerify(exactly = 0) { activityProjectionLane.ensureRegisteredAtLiveTail() }
			coVerify(exactly = 0) { arbiter.setDemand(any(), any()) }
			result.status shouldBe ActivityRegistrationStatus.BLOCKED
		}

	@Test
	fun `collected data epoch mismatch clears demand without touching provider readiness`() = runTest {
		val publishedReady = readyAutomaticControl(
			collectedDataEpoch = 3L,
			rolloutRevision = 9L,
		)
		currentPurposeAvailability.value = currentAvailability(
			publishedReady,
			currentCollectedDataEpoch = 4L,
			currentRolloutRevision = 9L,
		)
		coEvery {
			broker.replaceAutomaticControlDemand(
				any(),
				any(),
				false,
				any(),
				any(),
				any(),
				any(),
				any(),
			)
		} returns null
		coEvery {
			arbiter.clearDemand(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR)
		} returns cleared()

		subject.reconcile(
			enabled = true,
			useTransitionApi = true,
			continuousIntervalSeconds = 30,
			transitions = setOf(walkingEnter()),
			controlAvailability = publishedReady,
		)

		coVerify(exactly = 1) {
			broker.replaceAutomaticControlDemand(
				any(),
				SourceKind.ACTIVITY,
				false,
				any(),
				any(),
				any(),
				any(),
				any(),
			)
		}
		coVerify(exactly = 0) { activityProjectionLane.ensureRegisteredAtLiveTail() }
		coVerify(exactly = 0) { arbiter.setDemand(any(), any()) }
	}

	@Test
	fun `fresh authority rejection wins even while projected ready has not emitted yet`() = runTest {
		val publishedReady = readyAutomaticControl(
			collectedDataEpoch = 3L,
			rolloutRevision = 9L,
		)
		val staleProjection = object : CurrentTrackingPurposeAvailabilityReader {
			override val availability = MutableStateFlow(currentAvailability(publishedReady))
			override val authorityRevision = MutableStateFlow(
				TrackingPurposeAuthorityRevision(
					policyRevision = 1L,
					collectedDataEpoch = 4L,
					rolloutRevision = 9L,
				),
			)

			override suspend fun isCurrent(identity: TrackingPurposeLeaseIdentity): Boolean = false
		}
		val monitor = AutomaticStartTransitionMonitor(
			arbiter = arbiter,
			sourceBroker = broker,
			clockDomainProvider = BootClockDomainProvider { "boot:test" },
			activityProjectionLane = activityProjectionLane,
			currentPurposeAvailabilityReader = staleProjection,
		)
		coEvery {
			broker.replaceAutomaticControlDemand(
				any(),
				any(),
				false,
				any(),
				any(),
				any(),
				any(),
				any(),
			)
		} returns null
		coEvery {
			arbiter.clearDemand(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR)
		} returns cleared()

		monitor.reconcile(
			enabled = true,
			useTransitionApi = true,
			continuousIntervalSeconds = 30,
			transitions = setOf(walkingEnter()),
			controlAvailability = publishedReady,
		)

		coVerify(exactly = 1) {
			broker.replaceAutomaticControlDemand(
				any(),
				SourceKind.ACTIVITY,
				false,
				any(),
				any(),
				any(),
				any(),
				any(),
			)
		}
		coVerify(exactly = 0) { activityProjectionLane.ensureRegisteredAtLiveTail() }
		coVerify(exactly = 0) { arbiter.setDemand(any(), any()) }
	}

	@Test
	fun `rollout-contained automatic demand clears the provider owner`() = runTest {
		coEvery { activityProjectionLane.ensureRegisteredAtLiveTail() } returns Unit
		coEvery { broker.replaceAutomaticControlDemand(any(), any(), true, any(), any(), any(), any(), any()) } returns null
		coEvery { arbiter.clearDemand(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR) } returns cleared()

		val result = subject.reconcile(
			enabled = true,
			useTransitionApi = true,
			continuousIntervalSeconds = 30,
			transitions = setOf(walkingEnter()),
			controlAvailability = readyAutomaticControl(),
		)

		coVerify(exactly = 1) { arbiter.clearDemand(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR) }
		coVerify(exactly = 0) { arbiter.setDemand(any(), any()) }
		coVerify(exactly = 1) { activityProjectionLane.ensureRegisteredAtLiveTail() }
		result.status shouldBe ActivityRegistrationStatus.BLOCKED
	}

	@Test
	fun `reachable automatic capture keeps explicit transition control`() = runTest {
		coEvery { broker.replaceAutomaticControlDemand(any(), any(), true, any(), any(), any(), any(), any()) } returns
			mockk<SourceDemandEntity>()
		coEvery { activityProjectionLane.ensureRegisteredAtLiveTail() } returns Unit
		coEvery { arbiter.setDemand(any(), any()) } returns cleared()
		val transition = walkingEnter()

		subject.reconcile(
			enabled = true,
			useTransitionApi = true,
			continuousIntervalSeconds = 30,
			transitions = setOf(transition),
			controlAvailability = readyAutomaticControl(),
		)

		coVerifyOrder {
			activityProjectionLane.ensureRegisteredAtLiveTail()
			broker.replaceAutomaticControlDemand(
				any(),
				SourceKind.ACTIVITY,
				true,
				any(),
				any(),
				any(),
				any(),
				any(),
			)
			arbiter.setDemand(
				ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
				match { it.continuousRecognitionIntervalSeconds == null && it.transitions == setOf(transition) },
			)
		}
	}

	@Test
	fun `projection registration failure keeps provider unreachable for retry`() = runTest {
		coEvery { broker.replaceAutomaticControlDemand(any(), any(), true, any(), any(), any(), any(), any()) } returns
			mockk<SourceDemandEntity>()
		coEvery { activityProjectionLane.ensureRegisteredAtLiveTail() } throws
			IllegalStateException("projection storage unavailable")

		shouldThrow<IllegalStateException> {
			subject.reconcile(
				enabled = true,
				useTransitionApi = true,
				continuousIntervalSeconds = 30,
				transitions = setOf(walkingEnter()),
				controlAvailability = readyAutomaticControl(),
			)
		}

		coVerify(exactly = 1) { activityProjectionLane.ensureRegisteredAtLiveTail() }
		coVerify(exactly = 0) {
			broker.replaceAutomaticControlDemand(any(), any(), any(), any(), any(), any(), any(), any())
		}
		coVerify(exactly = 0) { arbiter.setDemand(any(), any()) }
	}

	private fun walkingEnter() = ActivityTransitionData(
		DetectedActivityType.WALKING,
		ActivityTransitionType.ENTER,
	)

	private fun cleared() = ActivityRegistrationResult(
		status = ActivityRegistrationStatus.APPLIED,
		snapshot = ActivityRegistrationSnapshot(
			active = false,
			identity = null,
			owners = emptySet(),
			continuousRecognitionIntervalSeconds = null,
			transitions = emptySet(),
		),
	)

	private fun readyAutomaticControl(
		collectedDataEpoch: Long = 0L,
		rolloutRevision: Long = 0L,
	) = AutomaticTrackingOperationalAvailability.Ready(
		TrackingPurposeLeaseIdentity(
			source = TrackingSource.ACTIVITY,
			purpose = TrackingPurpose.CONTROL,
			policyRevision = 1L,
			consentEpoch = 1L,
			collectedDataEpoch = collectedDataEpoch,
			rolloutRevision = rolloutRevision,
			executionRevision = 1L,
			ownerCasToken = "transition-monitor-test",
		),
	)

	private fun currentAvailability(
		publishedReady: AutomaticTrackingOperationalAvailability.Ready,
		currentCollectedDataEpoch: Long = publishedReady.identity.collectedDataEpoch,
		currentRolloutRevision: Long = publishedReady.identity.rolloutRevision,
	): CurrentTrackingPurposeAvailability = CurrentTrackingPurposeAvailability(
		published = TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT.copy(
			automaticControl = publishedReady,
		),
		currentAuthorities = mapOf(
			publishedReady.identity.sourcePurpose to TrackingPurposeAuthorityVector(
				sourcePurpose = publishedReady.identity.sourcePurpose,
				policyRevision = publishedReady.identity.policyRevision,
				consentEpoch = publishedReady.identity.consentEpoch,
				collectedDataEpoch = currentCollectedDataEpoch,
				rolloutRevision = currentRolloutRevision,
				executionRevision = publishedReady.identity.executionRevision,
			),
		),
	)
}
