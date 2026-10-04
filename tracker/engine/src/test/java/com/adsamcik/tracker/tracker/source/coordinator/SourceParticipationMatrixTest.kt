package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.preferences.tracking.SourceBalanceOverrides
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionFrequency
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionSettings
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.SourceDegradedReason
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.Test

/** Planning coverage only: these cases do not exercise Android owners or retained data. */
class SourceParticipationMatrixTest {
    private val factory = SemanticAcquisitionPlanFactory()
    private val resolver = SourcePlanResolver()
    private val sources = SourceKind.entries
    private val environment = SourcePlanEnvironment(LocationBackend.FRAMEWORK, true, setOf(1))
    private val context = PlanResolutionContext(emptyMap(), false, false, false)

    @Test
    fun `every source subset remains independent at every balance position`() {
        sources.size shouldBe 6
        forEachConfiguration { selected, level ->
            val desired = factory.create(settings(selected, level), 1, 100, environment)
            desired.plans.filterValues { it.enabled }.keys shouldBe selected
            desired.sourcePreferences.values.all { !it.focusRequested } shouldBe true
            desired.sourcePreferences.values.map { it.requestedLevel }.toSet() shouldBe setOf(level)
            val resolved = resolver.resolve(desired, emptyList(), context)
            resolved.applicablePlans shouldBe desired.plans
            resolved.fullyApplicable shouldBe true
        }
    }

    @Test
    fun `joining and leaving live focus never changes another source or enables an unchosen source`() {
        forEachConfiguration { selected, level ->
            val saved = settings(selected, level)
            val baseline = factory.create(saved, 1, 100, environment)
            sources.forEach { joining ->
                withClue("live source=$joining") {
                    val focused = factory.create(saved, 1, 100,
                        environment.copy(liveSources = setOf(joining)))
                    focused.plans.filterValues { it.enabled }.keys shouldBe selected
                    sources.forEach { source ->
                        val shouldFocus = source == joining && source in selected
                        focused.sourcePreferences.getValue(source).focusRequested shouldBe shouldFocus
                        val expectedLevel = if (shouldFocus) maxOf(level, 4) else level
                        focused.sourcePreferences.getValue(source).requestedLevel shouldBe expectedLevel
                        if (source != joining || joining !in selected) {
                            focused.plans.getValue(source) shouldBe baseline.plans.getValue(source)
                        }
                    }
                    resolver.resolve(focused, emptyList(), context).applicablePlans shouldBe focused.plans
                    factory.create(saved, 1, 100, environment) shouldBe baseline
                }
            }
        }
    }

    @Test
    fun `missing permission blocks only that selected source and keeps its saved choice`() {
        forEachConfiguration { selected, level ->
            val saved = settings(selected, level)
            val desired = factory.create(saved, 1, 100, environment)
            selected.forEach { unavailable ->
                withClue("permission missing=$unavailable") {
                    val resolved = resolver.resolve(desired, emptyList(), context.copy(
                        constraints = mapOf(unavailable to SourceConstraint(permissionGranted = false)),
                    ))
                    resolved.applicablePlans.filterValues { it.enabled }.keys shouldBe selected - unavailable
                    resolved.degradedReasons.getValue(unavailable) shouldBe
                        setOf(SourceDegradedReason.PERMISSION_MISSING)
                    sources.filter { it != unavailable }.forEach { sibling ->
                        resolved.applicablePlans.getValue(sibling) shouldBe desired.plans.getValue(sibling)
                        resolved.degradedReasons.getValue(sibling) shouldBe emptySet()
                    }
                    resolved.desired shouldBe desired
                    factory.create(saved, 1, 100, environment) shouldBe desired
                }
            }
        }
    }

    @Test
    fun `a source quality override never changes participation or sibling plans`() {
        forEachConfiguration { selected, level ->
            val baseline = factory.create(settings(selected, level), 1, 100, environment)
            sources.forEach { changed ->
                withClue("quality override=$changed") {
                    val overrideLevel = if (level == 5) 1 else 5
                    val component = TrackingSourceComponent.fromStableCode(changed.stableCode)
                    val overrides = SourceBalanceOverrides().with(component, overrideLevel)
                    val changedPlan = factory.create(settings(selected, level, overrides), 1, 100, environment)
                    changedPlan.plans.filterValues { it.enabled }.keys shouldBe selected
                    changedPlan.sourcePreferences.getValue(changed).requestedLevel shouldBe overrideLevel
                    sources.filter { it != changed }.forEach { sibling ->
                        changedPlan.plans.getValue(sibling) shouldBe baseline.plans.getValue(sibling)
                        changedPlan.sourcePreferences.getValue(sibling) shouldBe
                            baseline.sourcePreferences.getValue(sibling)
                    }
                    if (changed !in selected) {
                        changedPlan.plans.getValue(changed) shouldBe baseline.plans.getValue(changed)
                    }
                }
            }
        }
    }

    private fun forEachConfiguration(block: (Set<SourceKind>, Int) -> Unit) {
        (0 until (1 shl sources.size)).forEach { mask ->
            val selected = sources.filterIndexed { index, _ -> (mask and (1 shl index)) != 0 }.toSet()
            (1..5).forEach { level ->
                withClue("selected=$selected, balance=$level") { block(selected, level) }
            }
        }
    }

    private fun settings(
        selected: Set<SourceKind>,
        level: Int,
        overrides: SourceBalanceOverrides = SourceBalanceOverrides(),
    ) = TrackingParamsState(
        locationEnabled = SourceKind.LOCATION in selected,
        activityEnabled = SourceKind.ACTIVITY in selected,
        stepsEnabled = SourceKind.STEPS in selected,
        barometerEnabled = SourceKind.PRESSURE in selected,
        wifiEnabled = SourceKind.WIFI in selected,
        cellEnabled = SourceKind.CELL in selected,
        balanceProfileVersion = 1,
        overallBalanceLevel = level,
        sourceBalanceOverrides = overrides,
        locationProfileVersion = 1,
        locationBalanceLevel = overrides.location ?: level,
        // Adopted participation must not depend on obsolete frequency choices.
        sourceCollectionSettings = SourceCollectionSettings(
            location = SourceCollectionFrequency.OFF,
            activity = SourceCollectionFrequency.OFF,
            steps = SourceCollectionFrequency.OFF,
            pressure = SourceCollectionFrequency.OFF,
            wifi = SourceCollectionFrequency.OFF,
            cell = SourceCollectionFrequency.OFF,
        ),
    )
}
