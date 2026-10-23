package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.tracker.source.control.CollectionMotionPolicy
import com.adsamcik.tracker.tracker.source.control.CollectionMotionState
import com.adsamcik.tracker.tracker.source.control.LocationCollectionStrategy
import com.adsamcik.tracker.tracker.source.control.MotionPolicyReason
import com.adsamcik.tracker.tracker.source.control.acquisitionProfile
import com.adsamcik.tracker.tracker.source.model.AcquisitionPlanRevision
import com.adsamcik.tracker.tracker.source.model.DemandReason
import com.adsamcik.tracker.tracker.source.model.DirectSourceDemandPurpose
import com.adsamcik.tracker.tracker.source.model.EvidenceQuality
import com.adsamcik.tracker.tracker.source.model.LocationAcquisitionFloor
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.LocationFloorMode
import com.adsamcik.tracker.tracker.source.model.LocationMode
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.RetryBackoff
import com.adsamcik.tracker.tracker.source.model.SourceDemand
import com.adsamcik.tracker.tracker.source.model.SourceDemandContract
import com.adsamcik.tracker.tracker.source.model.SourceDemandContractFactory
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.WifiMode
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import io.kotest.matchers.shouldBe
import org.junit.Test

class AllDayLocationPlanResolverTest {
	@Test
	fun `all day Balanced remains high accuracy while unknown and fresh movement restores its chosen cadence`() {
		val desired = adaptiveLocationOnly(3)
		val demand = allDayLocationDemand(3)
		val context = PlanResolutionContext(emptyMap(), false, false, false,
			locationProfileLevel = 3, allDayLocation = true)
		val subject = SourcePlanResolver()
		val unknown = subject.resolve(desired, listOf(demand), context)
		(unknown.applicablePlans.getValue(SourceKind.LOCATION) as LocationPlan).also {
			it.enabled shouldBe true
			it.mode shouldBe LocationMode.HIGH_ACCURACY
			it.requestedIntervalMs shouldBe 30_000L
			it.minimumUpdateIntervalMs shouldBe 15_000L
			it.maximumBatchDelayMs shouldBe 60_000L
		}
		val moving = context.copy(motionProfile = context.motionProfile.copy(moving = true))
		subject.resolve(desired, listOf(demand), moving).applicablePlans shouldBe desired.plans
		subject.resolve(desired, listOf(demand), context.copy(allDayLocation = false))
			.applicablePlans shouldBe desired.plans
	}

	@Test
	fun `all day stationary rates preserve Balanced map context and delivery bounds`() {
		// Wi-Fi needs both bracketing Location fixes within 30 seconds, including finite scan windows.
		val intervals = listOf(120_000L, 90_000L, 30_000L, 30_000L, 30_000L)
		for (level in 1..5) {
			val desired = adaptiveLocationOnly(level)
			val context = PlanResolutionContext(emptyMap(), false, false, false,
				motionProfile = stationaryPolicy().acquisitionProfile,
				locationProfileLevel = level, allDayLocation = true)
			val actual = SourcePlanResolver().resolve(desired, listOf(allDayLocationDemand(level)), context)
				.applicablePlans.getValue(SourceKind.LOCATION) as LocationPlan
			actual.enabled shouldBe true
			actual.mode shouldBe if (level == 1) {
				LocationMode.LOW_POWER
			} else {
				LocationMode.BALANCED
			}
			actual.requestedIntervalMs shouldBe intervals[level - 1]
			actual.minimumUpdateIntervalMs shouldBe intervals[level - 1] / 2L
			actual.maximumBatchDelayMs shouldBe minOf(intervals[level - 1] * 2L, 120_000L)
			val moving = context.copy(motionProfile = context.motionProfile.copy(stationary = false, moving = true))
			SourcePlanResolver().resolve(desired, listOf(allDayLocationDemand(level)), moving)
				.applicablePlans shouldBe desired.plans
		}
	}

	@Test
	fun `all day Balanced reduces under moderate battery pressure or saver while live focus retains quality`() {
		val desired = adaptiveLocationOnly(3)
		val demand = allDayLocationDemand(3)
		val context = PlanResolutionContext(emptyMap(), false, false, false,
			locationProfileLevel = 3, allDayLocation = true)
		val subject = SourcePlanResolver()
		val moderate = context.copy(batteryPressure = com.adsamcik.tracker.tracker.policy.BatteryPressure.MODERATE)
		for (reduced in listOf(moderate, context.copy(powerSaver = true))) {
			val actual = subject.resolve(desired, listOf(demand), reduced)
				.applicablePlans.getValue(SourceKind.LOCATION) as LocationPlan
			actual.mode shouldBe LocationMode.BALANCED
			actual.requestedIntervalMs shouldBe 30_000L
			subject.resolve(desired, listOf(demand), reduced.copy(locationLiveFocus = true))
				.applicablePlans shouldBe desired.plans
		}
		val critical = subject.resolve(desired, listOf(demand), context.copy(
			batteryPressure = com.adsamcik.tracker.tracker.policy.BatteryPressure.STRONG))
			.applicablePlans.getValue(SourceKind.LOCATION) as LocationPlan
		critical.mode shouldBe LocationMode.LOW_POWER
		critical.requestedIntervalMs shouldBe 60_000L
	}

	@Test
	fun `all day reductions preserve direct floors and do not alter custom or nonadaptive requests`() {
		val desired = adaptiveLocationOnly(3)
		val context = PlanResolutionContext(emptyMap(), false, false, false,
			motionProfile = stationaryPolicy().acquisitionProfile, locationProfileLevel = 3, allDayLocation = true)
		val subject = SourcePlanResolver()
		val floor = SourceDemandContract(LocationAcquisitionFloor(LocationFloorMode.HIGH_ACCURACY),
			120_000L, 10_000L, 50_000L, true)
		val actual = subject.resolve(desired, listOf(directDemand(floor)), context)
			.applicablePlans.getValue(SourceKind.LOCATION) as LocationPlan
		actual.mode shouldBe LocationMode.HIGH_ACCURACY
		actual.requestedIntervalMs shouldBe 30_000L
		actual.maximumBatchDelayMs shouldBe 50_000L
		val fixed = allDayLocationDemand(3).copy(adaptiveReductionAllowed = false)
		subject.resolve(desired, listOf(fixed), context).applicablePlans shouldBe desired.plans
		val custom = plan()
		subject.resolve(custom, emptyList(), context).applicablePlans shouldBe
			subject.resolve(custom, emptyList(), context.copy(allDayLocation = false)).applicablePlans
		val live = context.copy(locationLiveFocus = true)
		subject.resolve(desired, listOf(allDayLocationDemand(3)), live).applicablePlans shouldBe
			subject.resolve(desired, listOf(allDayLocationDemand(3)), live.copy(allDayLocation = false)).applicablePlans
	}

	private fun adaptiveLocationOnly(level: Int) = AcquisitionPlanRevision(2L, "all-day-test", 100L,
		mapOf(SourceKind.LOCATION to adaptiveLocationPlan(level, 2L, LocationBackend.FUSED, true)))

	private fun allDayLocationDemand(level: Int) = directDemand(SourceDemandContractFactory.forProfile(
		SourceKind.LOCATION, 1, level, DirectSourceDemandPurpose.AMBIENT_PRODUCT))

	private fun stationaryPolicy() = CollectionMotionPolicy(
		motionState = CollectionMotionState.STATIONARY,
		confidencePercent = 90,
		reason = MotionPolicyReason.STATIONARY_CONFIRMED,
		locationStrategy = LocationCollectionStrategy.PASSIVE_WHILE_STATIONARY,
		expensiveNetworkScansAllowed = false,
		continuousPressureAllowed = false,
		lowLatencyStepReporting = false,
	)

	private fun plan(): AcquisitionPlanRevision {
		val revision = 2L
		val location = LocationPlan(
			revision = revision,
			backend = LocationBackend.FUSED,
			mode = LocationMode.HIGH_ACCURACY,
			requestedIntervalMs = 1_000,
			minimumUpdateIntervalMs = 500,
			minimumDisplacementMeters = 2f,
			maximumBatchDelayMs = 2_000,
			preciseLocationAvailable = true,
		)
		val wifi = WifiPlan(
			revision = revision,
			mode = WifiMode.ACTIVE_ATTEMPTS,
			minimumAttemptIntervalMs = 60_000,
			maximumAcceptableResultAgeMs = 60_000,
			unchangedResultDedupeWindowMs = 30_000,
			backoff = RetryBackoff(1_000, 60_000),
		)
		return AcquisitionPlanRevision(revision, "test", 100, mapOf(location.source to location, wifi.source to wifi))
	}

	private fun directDemand(contract: SourceDemandContract) = SourceDemand(
		source = contract.source,
		maximumAgeMs = contract.maximumProviderItemAgeMs,
		desiredLatencyMs = contract.targetPlanningLatencyMs,
		quality = EvidenceQuality.HIGH,
		reason = DemandReason.SESSION,
		acquisitionFloor = contract.floor,
		requestedDeliveryLatencyMs = contract.requestedDeliveryLatencyMs,
		adaptiveReductionAllowed = contract.adaptiveReductionAllowed,
	)
}
