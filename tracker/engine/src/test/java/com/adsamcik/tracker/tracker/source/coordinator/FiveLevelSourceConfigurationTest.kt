package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.preferences.tracking.SourceBalanceOverrides
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionFrequency
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionSettings
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.source.model.ActivityPlan
import com.adsamcik.tracker.tracker.source.model.CellPlan
import com.adsamcik.tracker.tracker.source.model.DemandReason
import com.adsamcik.tracker.tracker.source.model.DirectSourceDemandPurpose
import com.adsamcik.tracker.tracker.source.model.EvidenceQuality
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.PressurePlan
import com.adsamcik.tracker.tracker.source.model.SourceDemand
import com.adsamcik.tracker.tracker.source.model.SourceDemandContractFactory
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import com.adsamcik.tracker.tracker.source.model.StepsPlan
import com.adsamcik.tracker.tracker.source.model.WifiMode
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import com.adsamcik.tracker.tracker.source.model.satisfies
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.Test

/** Acceptance sources only: execution is deferred to the final convergence batch. */
class FiveLevelSourceConfigurationTest {
    private val environment = SourcePlanEnvironment(LocationBackend.FUSED, true, setOf(1))
    private val factory = SemanticAcquisitionPlanFactory()
    private fun settings(level: Int, mask: Int = 63) = TrackingParamsState(
        balanceProfileVersion = 1, overallBalanceLevel = level, locationProfileVersion = 1, locationBalanceLevel = level,
        locationEnabled = mask and 1 != 0, activityEnabled = mask and 2 != 0,
        stepsEnabled = mask and 4 != 0, barometerEnabled = mask and 8 != 0,
        wifiEnabled = mask and 16 != 0, cellEnabled = mask and 32 != 0,
        sourceCollectionSettings = SourceCollectionSettings(SourceCollectionFrequency.OFF, SourceCollectionFrequency.OFF,
            SourceCollectionFrequency.OFF, SourceCollectionFrequency.OFF, SourceCollectionFrequency.OFF, SourceCollectionFrequency.OFF),
    )
    private fun selected(mask: Int) = listOf(SourceKind.LOCATION, SourceKind.ACTIVITY, SourceKind.STEPS,
        SourceKind.PRESSURE, SourceKind.WIFI, SourceKind.CELL).filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()

    @Test fun `every level and source subset preserves participation through live start and end`() {
        for (level in 1..5) for (mask in 0..63) {
            val configuration = settings(level, mask)
            val participants = selected(mask)
            val baseline = factory.create(configuration, 1, 0, environment)
            val live = factory.create(configuration, 1, 0, environment.copy(liveSources = SourceKind.entries.toSet()))
            baseline.plans.filterValues { it.enabled }.keys shouldBe participants
            live.plans.filterValues { it.enabled }.keys shouldBe participants
            SourceKind.entries.forEach { source ->
                baseline.sourcePreferences.getValue(source).requestedLevel shouldBe level
                live.sourcePreferences.getValue(source).requestedLevel shouldBe if (source in participants) maxOf(level, 4) else level
                live.sourcePreferences.getValue(source).focusRequested shouldBe (source in participants)
            }
            factory.create(configuration, 1, 0, environment) shouldBe baseline
            configuration.sourceCollectionSettings.location shouldBe SourceCollectionFrequency.OFF
        }
    }

    @Test fun `one actual participant boosts only itself while background siblings retain baseline`() {
        val configuration = settings(2).copy(ambientLocationEnabled = true, ambientWifiEnabled = true,
            ambientCellEnabled = true, ambientStepsEnabled = true)
        val baseline = factory.create(configuration, 1, 0, environment)
        val live = factory.create(configuration, 1, 0, environment.copy(liveSources = setOf(SourceKind.STEPS)))
        live.sourcePreferences.getValue(SourceKind.STEPS).requestedLevel shouldBe 4
        SourceKind.entries.filter { it != SourceKind.STEPS }.forEach {
            live.plans[it] shouldBe baseline.plans[it]
            live.sourcePreferences.getValue(it).focusRequested shouldBe false
        }
        val sessionless = configuration.copy(locationEnabled = false, stepsEnabled = false, wifiEnabled = false, cellEnabled = false,
            activityEnabled = false, barometerEnabled = false)
        factory.create(sessionless, 1, 0, environment.copy(liveSources = SourceKind.entries.toSet())).sourcePreferences.values
            .all { !it.focusRequested && it.requestedLevel == 2 } shouldBe true
    }

    @Test fun `overrides survive off sources and level five is never reduced by live preference`() {
        val configuration = settings(2, 4).copy(sourceBalanceOverrides = SourceBalanceOverrides(steps = 5, wifi = 1))
        val live = factory.create(configuration, 1, 0, environment.copy(liveSources = SourceKind.entries.toSet()))
        live.sourcePreferences.getValue(SourceKind.STEPS).requestedLevel shouldBe 5
        live.sourcePreferences.getValue(SourceKind.WIFI).requestedLevel shouldBe 1
        live.plans.getValue(SourceKind.WIFI).enabled shouldBe false
        configuration.sourceBalanceOverrides.wifi shouldBe 1
    }

    @Test fun `all profiles satisfy stable useful-data contracts without tightening radio age or Activity confidence`() {
        val ages = mapOf(SourceKind.LOCATION to 120_000L, SourceKind.ACTIVITY to 120_000L, SourceKind.STEPS to 300_000L,
            SourceKind.PRESSURE to 60_000L, SourceKind.WIFI to 600_000L, SourceKind.CELL to 600_000L)
        for (level in 1..5) {
            val desired = factory.create(settings(level), 1, 0, environment)
            for ((source, age) in ages) {
                val contract = SourceDemandContractFactory.forProfile(source, 1, level, DirectSourceDemandPurpose.SESSION_CAPTURE)
                contract.maximumProviderItemAgeMs shouldBe age
                desired.plans.getValue(source).satisfies(SourceDemand(source, age, contract.targetPlanningLatencyMs,
                    EvidenceQuality.ANY, DemandReason.SESSION, acquisitionFloor = contract.floor,
                    requestedDeliveryLatencyMs = contract.requestedDeliveryLatencyMs, adaptiveReductionAllowed = true)) shouldBe true
            }
            (desired.plans.getValue(SourceKind.ACTIVITY) as ActivityPlan).confidenceThresholdPercent shouldBe 65
            (desired.plans.getValue(SourceKind.WIFI) as WifiPlan).unchangedResultDedupeWindowMs shouldBe 600_000L
        }
        shouldThrow<IllegalArgumentException> {
            SourceDemandContractFactory.forProfile(SourceKind.STEPS, 1, 5, DirectSourceDemandPurpose.AMBIENT_PRODUCT)
        }
        shouldThrow<IllegalArgumentException> {
            SourceDemandContractFactory.forProfile(SourceKind.ACTIVITY, 1, 5, DirectSourceDemandPurpose.CONTROL_AUTOSTART)
        }
    }

    @Test fun `five cadence tables include distinct intermediate rates and finite radio spacing`() {
        fun plans(source: SourceKind) = (1..5).map { adaptiveSourcePlan(source, it, 1, environment) }
        plans(SourceKind.ACTIVITY).map { (it as ActivityPlan).desiredDetectionLatencyMs } shouldBe listOf(60_000L,45_000L,30_000L,15_000L,5_000L)
        plans(SourceKind.STEPS).map { (it as StepsPlan).maximumReportLatencyMs } shouldBe listOf(300_000L,120_000L,60_000L,15_000L,5_000L)
        plans(SourceKind.PRESSURE).map { (it as PressurePlan).hardwareSamplePeriodMicros } shouldBe listOf(1_000_000,500_000,200_000,100_000,50_000)
        plans(SourceKind.WIFI).map { (it as WifiPlan).mode } shouldBe listOf(WifiMode.BROADCAST_DRIVEN,WifiMode.BROADCAST_DRIVEN,WifiMode.BROADCAST_DRIVEN,WifiMode.ACTIVE_ATTEMPTS,WifiMode.ACTIVE_ATTEMPTS)
        (adaptiveSourcePlan(SourceKind.WIFI, 4, 1, environment) as WifiPlan).minimumAttemptIntervalMs shouldBe 120_000L
        (adaptiveSourcePlan(SourceKind.CELL, 4, 1, environment) as CellPlan).minimumRefreshAttemptIntervalMs shouldBe 300_000L
    }

    @Test fun `legacy custom settings and old QoS retain original bytes and contract meaning`() {
        val configuration = TrackingParamsState(minTimeSeconds = 17, minDistanceMeters = 43,
            activityEnabled = true, sourceCollectionSettings = SourceCollectionSettings(activity = SourceCollectionFrequency.RESPONSIVE))
        val baseline = factory.create(configuration, 1, 0, environment)
        val live = factory.create(configuration, 1, 0, environment.copy(liveSources = SourceKind.entries.toSet()))
        baseline shouldBe live
        (baseline.plans.getValue(SourceKind.LOCATION) as LocationPlan).requestedIntervalMs shouldBe 17_000L
        (baseline.plans.getValue(SourceKind.LOCATION) as LocationPlan).minimumDisplacementMeters shouldBe 43f
        val activity = baseline.plans.getValue(SourceKind.ACTIVITY) as ActivityPlan
        activity.confidenceThresholdPercent shouldBe 55
        SourcePlanCodec().encode(activity).formatVersion shouldBe 1
        SourceDemandContractFactory.forQos(SourceKind.ACTIVITY, 3, DirectSourceDemandPurpose.SESSION_CAPTURE)
            .maximumProviderItemAgeMs shouldBe 5_000L
        baseline.balanceProfileVersion shouldBe null
    }
}
