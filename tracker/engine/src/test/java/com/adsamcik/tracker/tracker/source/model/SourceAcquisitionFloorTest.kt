package com.adsamcik.tracker.tracker.source.model

import io.kotest.matchers.shouldBe
import org.junit.Test

class SourceAcquisitionFloorTest {
	@Test
	fun `all source qos and relevant purpose floors have an exact canonical round trip`() {
		val purposes = listOf(
			DirectSourceDemandPurpose.SESSION_CAPTURE,
			DirectSourceDemandPurpose.AMBIENT_PRODUCT,
			DirectSourceDemandPurpose.CONTROL_AUTOSTART,
			DirectSourceDemandPurpose.CONTROL_CONTINUATION,
		)
		SourceKind.entries.forEach { source ->
			(1..3).forEach { qos ->
				purposes.forEach { purpose ->
					val live = SourceDemandContractFactory.forQos(source, qos, purpose)
					SourceDemandContract.decode(
						source = source,
						floorSpec = live.encodeFloor(),
						maximumProviderItemAgeMs = live.maximumProviderItemAgeMs,
						targetPlanningLatencyMs = live.targetPlanningLatencyMs,
						requestedDeliveryLatencyMs = live.requestedDeliveryLatencyMs,
						adaptiveReductionAllowed = live.adaptiveReductionAllowed,
					) shouldBe live
				}
			}
		}
		SourceKind.entries.forEach { source ->
			purposes.filter { it != DirectSourceDemandPurpose.SESSION_CAPTURE }.forEach { purpose ->
				val disabledCapturePolicy = SourceDemandContractFactory.forQos(source, 0, purpose)
				SourceDemandContract.decode(
					source,
					disabledCapturePolicy.encodeFloor(),
					disabledCapturePolicy.maximumProviderItemAgeMs,
					disabledCapturePolicy.targetPlanningLatencyMs,
					disabledCapturePolicy.requestedDeliveryLatencyMs,
					disabledCapturePolicy.adaptiveReductionAllowed,
				) shouldBe disabledCapturePolicy
			}
		}
	}

	@Test
	fun `Activity capture and control retain orthogonal capabilities`() {
		(1..3).forEach { qos ->
			val capture = SourceDemandContractFactory.forQos(
				SourceKind.ACTIVITY,
				qos,
				DirectSourceDemandPurpose.SESSION_CAPTURE,
			).floor as ActivityAcquisitionFloor
			val autostart = SourceDemandContractFactory.forQos(
				SourceKind.ACTIVITY,
				qos,
				DirectSourceDemandPurpose.CONTROL_AUTOSTART,
			).floor as ActivityAcquisitionFloor
			val continuation = SourceDemandContractFactory.forQos(
				SourceKind.ACTIVITY,
				qos,
				DirectSourceDemandPurpose.CONTROL_CONTINUATION,
			).floor as ActivityAcquisitionFloor

			capture.requiredCapabilities shouldBe setOf(ActivityAcquisitionCapability.CLASSIFICATIONS)
			autostart.requiredCapabilities shouldBe setOf(ActivityAcquisitionCapability.TRANSITIONS)
			continuation.requiredCapabilities shouldBe setOf(ActivityAcquisitionCapability.CLASSIFICATIONS)
			capture.union(continuation).requiredCapabilities shouldBe
				setOf(ActivityAcquisitionCapability.CLASSIFICATIONS)
			val activityLatencyMs = when (qos) {
				1 -> 60_000L
				2 -> 30_000L
				else -> 5_000L
			}
			val transitions = ActivityPlan(
				1L, ActivityMode.TRANSITIONS_ONLY, activityLatencyMs, 75, setOf(0, 1),
			)
			val classifications = ActivityPlan(
				1L, ActivityMode.CONTINUOUS_RECOGNITION, activityLatencyMs, 55, setOf(0, 1),
			)
			val captureDemand = SourceDemandContractFactory.forQos(
				SourceKind.ACTIVITY, qos, DirectSourceDemandPurpose.SESSION_CAPTURE,
			).toDemand()
			val controlDemand = SourceDemandContractFactory.forQos(
				SourceKind.ACTIVITY, qos, DirectSourceDemandPurpose.CONTROL_AUTOSTART,
			).toDemand()
			val continuationDemand = SourceDemandContractFactory.forQos(
				SourceKind.ACTIVITY, qos, DirectSourceDemandPurpose.CONTROL_CONTINUATION,
			).toDemand()
			transitions.satisfies(captureDemand) shouldBe false
			transitions.satisfies(controlDemand) shouldBe true
			classifications.satisfies(captureDemand) shouldBe true
			classifications.satisfies(controlDemand) shouldBe false
			transitions.satisfies(continuationDemand) shouldBe false
			classifications.satisfies(continuationDemand) shouldBe true
		}
	}

	@Test
	fun `Wi-Fi broadcasts and Cell callbacks are opportunistic but still bound provider item age`() {
		(1..3).forEach { qos ->
			val wifi = SourceDemandContractFactory.forQos(
				SourceKind.WIFI,
				qos,
				DirectSourceDemandPurpose.SESSION_CAPTURE,
			)
			val cell = SourceDemandContractFactory.forQos(
				SourceKind.CELL,
				qos,
				DirectSourceDemandPurpose.SESSION_CAPTURE,
			)
			wifi.requestedDeliveryLatencyMs shouldBe null
			cell.requestedDeliveryLatencyMs shouldBe null
			(wifi.maximumProviderItemAgeMs > 0L) shouldBe true
			(cell.maximumProviderItemAgeMs > 0L) shouldBe true
		}
	}

	@Test
	fun `Steps provider reporting is independent from projection checkpoints`() {
		val contract = SourceDemandContractFactory.forQos(
			SourceKind.STEPS,
			3,
			DirectSourceDemandPurpose.SESSION_CAPTURE,
		)
		val demand = contract.toDemand()
		val original = StepsPlan(1L, true, 300_000L, 1_000L, false)
		val projectionOnly = original.copy(projectionCheckpointIntervalMs = 900_000L)
		val reportTooSlow = original.copy(maximumReportLatencyMs = 300_001L)

		original.satisfies(demand) shouldBe true
		projectionOnly.satisfies(demand) shouldBe true
		reportTooSlow.satisfies(demand) shouldBe false
		original.physicalConfigurationFingerprint() shouldBe
			projectionOnly.physicalConfigurationFingerprint()
		(original.physicalConfigurationFingerprint() == reportTooSlow.physicalConfigurationFingerprint()) shouldBe false
	}

	@Test
	fun `callback-only labels do not rotate physical provider registrations`() {
		val firstBackoff = RetryBackoff(1_000L, 60_000L)
		val secondBackoff = RetryBackoff(30_000L, 1_800_000L)
		val cached = WifiPlan(1L, WifiMode.CACHED_ONLY, 900_000L, 600_000L, 1_800_000L, firstBackoff)
		val broadcast = WifiPlan(2L, WifiMode.BROADCAST_DRIVEN, 1L, 1L, 1L, secondBackoff)
		val active = broadcast.copy(revision = 3L, mode = WifiMode.ACTIVE_ATTEMPTS)

		cached.physicalConfigurationFingerprint() shouldBe broadcast.physicalConfigurationFingerprint()
		(cached.physicalConfigurationFingerprint() == active.physicalConfigurationFingerprint()) shouldBe false

		val cellCallbacks = CellPlan(
			1L, CellMode.OBSERVE_CHANGES, 900_000L, 600_000L, setOf(1, 2), firstBackoff,
		)
		val relabelledCallbacks = cellCallbacks.copy(
			revision = 2L,
			minimumRefreshAttemptIntervalMs = 1L,
			maximumAcceptableCachedAgeMs = 1L,
			backoff = secondBackoff,
		)
		val sparseRefresh = relabelledCallbacks.copy(
			revision = 3L,
			mode = CellMode.OBSERVE_AND_SPARSE_REFRESH,
		)

		cellCallbacks.physicalConfigurationFingerprint() shouldBe
			relabelledCallbacks.physicalConfigurationFingerprint()
		(cellCallbacks.physicalConfigurationFingerprint() == sparseRefresh.physicalConfigurationFingerprint()) shouldBe false
	}

	@Test
	fun `projection and aggregation labels do not rotate sensor registrations`() {
		val pressure = PressurePlan(1L, true, 200_000, 10_000_000, 10_000L, false)
		val windowOnly = pressure.copy(revision = 2L, aggregationWindowMs = 60_000L, movementGatedBurst = true)
		val sampleChanged = pressure.copy(revision = 3L, hardwareSamplePeriodMicros = 1_000_000)

		pressure.physicalConfigurationFingerprint() shouldBe windowOnly.physicalConfigurationFingerprint()
		(pressure.physicalConfigurationFingerprint() == sampleChanged.physicalConfigurationFingerprint()) shouldBe false
	}

	@Test
	fun `cached Wi-Fi bootstrap state cannot satisfy a direct acquisition floor`() {
		val contract = SourceDemandContractFactory.forQos(
			SourceKind.WIFI,
			1,
			DirectSourceDemandPurpose.SESSION_CAPTURE,
		)
		val cached = WifiPlan(
			1L,
			WifiMode.CACHED_ONLY,
			900_000L,
			contract.maximumProviderItemAgeMs,
			1_800_000L,
			RetryBackoff(30_000L, 1_800_000L),
		)

		cached.satisfies(contract.toDemand()) shouldBe false
	}

	@Test
	fun `bounded Location probe is never treated as a durable acquisition floor`() {
		val contract = SourceDemandContractFactory.forQos(
			SourceKind.LOCATION,
			3,
			DirectSourceDemandPurpose.SESSION_CAPTURE,
		)
		val probe = LocationPlan(
			revision = 1L,
			backend = LocationBackend.FUSED,
			mode = LocationMode.PROBE,
			requestedIntervalMs = 1_000L,
			minimumUpdateIntervalMs = 500L,
			minimumDisplacementMeters = 0f,
			maximumBatchDelayMs = 2_000L,
			probeDurationMs = 10_000L,
			preciseLocationAvailable = true,
		)

		probe.satisfies(contract.toDemand()) shouldBe false
	}

	@Test
	fun `Location minimum update spacing cannot masquerade as requested delivery cadence`() {
		val contract = SourceDemandContractFactory.forQos(
			SourceKind.LOCATION,
			3,
			DirectSourceDemandPurpose.SESSION_CAPTURE,
		)
		val normalResponsive = LocationPlan(
			1L, LocationBackend.FUSED, LocationMode.HIGH_ACCURACY,
			1_000L, 500L, 2f, 2_000L, preciseLocationAvailable = true,
		)
		val slowRequestWithSmallMinimumSpacing = normalResponsive.copy(
			requestedIntervalMs = 60_000L,
			minimumUpdateIntervalMs = 500L,
			maximumBatchDelayMs = 60_000L,
		)

		normalResponsive.satisfies(contract.toDemand()) shouldBe true
		slowRequestWithSmallMinimumSpacing.satisfies(
			contract.copy(maximumProviderItemAgeMs = 120_000L).toDemand(),
		) shouldBe false
	}

	private fun SourceDemandContract.toDemand() = SourceDemand(
		source = source,
		maximumAgeMs = maximumProviderItemAgeMs,
		desiredLatencyMs = targetPlanningLatencyMs,
		quality = EvidenceQuality.ANY,
		reason = DemandReason.SESSION,
		acquisitionFloor = floor,
		requestedDeliveryLatencyMs = requestedDeliveryLatencyMs,
		adaptiveReductionAllowed = adaptiveReductionAllowed,
	)
}
