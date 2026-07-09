package com.adsamcik.tracker.shared.preferences.local

import android.content.Context
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsAccess
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsState
import com.adsamcik.tracker.shared.preferences.type.LengthSystem
import com.adsamcik.tracker.shared.preferences.type.SpeedFormat
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FormatHelpersTest {

	private lateinit var context: Context

	@Before
	fun setUp() {
		Locale.setDefault(Locale.US)
		context = RuntimeEnvironment.getApplication()
		resetSettingsSingleton(null)
	}

	private fun injectLengthSystem(system: LengthSystem) {
		val state = TrackerSettingsState(
			autoUnitSwitch = false,
			lengthSystem = system,
			speedFormat = SpeedFormat.Hour,
		)
		resetSettingsSingleton(MutableStateFlow(state))
	}

	private fun resetSettingsSingleton(value: Any?) {
		val field = TrackerSettingsAccess.javaClass.getDeclaredField("stateFlow")
		field.isAccessible = true
		field.set(TrackerSettingsAccess, value)
	}

	// region Routing

	private data class RoutingCase(val systemName: String, val meters: Int, val expectedUnit: String)

	private val routingCases = listOf(
		RoutingCase("Metric", 500, "m"),
		RoutingCase("Metric", 1500, "km"),
		RoutingCase("Imperial", 500, "ft"),
		RoutingCase("Imperial", 5000, "mi"),
		RoutingCase("AncientRoman", 500, "pace"),
		RoutingCase("AncientRoman", 2000, "roman mile"),
		RoutingCase("Sailing", 500, "cable"),
		RoutingCase("Sailing", 5, "fathom"),
		RoutingCase("Flying", 500, "FL"),
		RoutingCase("Flying", 100, "ft"),
	)

	@Test
	fun `routes to correct format function`() {
		routingCases.forEach { (systemName, meters, expectedUnit) ->
			injectLengthSystem(LengthSystem.valueOf(systemName))
			val result = FormatHelpers.formatDistance(context, meters, 2)
			result shouldContain expectedUnit
		}
	}
	// endregion

	// region AllSystemsProduce

	@Test
	fun `all systems produce non-empty output`() {
		LengthSystem.entries.forEach { system ->
			injectLengthSystem(system)
			FormatHelpers.formatDistance(context, 1000, 2).shouldNotBeEmpty()
		}
	}

	@Test
	fun `all systems handle zero meters`() {
		LengthSystem.entries.forEach { system ->
			injectLengthSystem(system)
			FormatHelpers.formatDistance(context, 0, 2).shouldNotBeEmpty()
		}
	}
	// endregion

	// region MetricFormatting

	@Test
	fun `metric below threshold returns meters`() {
		injectLengthSystem(LengthSystem.Metric)
		FormatHelpers.formatDistance(context, 500, 2) shouldBe "500 m"
	}

	@Test
	fun `metric above threshold returns kilometers`() {
		injectLengthSystem(LengthSystem.Metric)
		FormatHelpers.formatDistance(context, 1500, 2) shouldBe "1.5 km"
	}
	// endregion

	// region ImperialConversion

	@Test
	fun `imperial converts meters to feet`() {
		injectLengthSystem(LengthSystem.Imperial)
		val result = FormatHelpers.formatDistance(context, 1000, 2)
		result shouldContain "3,280.84"
		result shouldContain "ft"
	}

	@Test
	fun `imperial converts large distance to miles`() {
		injectLengthSystem(LengthSystem.Imperial)
		val result = FormatHelpers.formatDistance(context, 5000, 2)
		result shouldContain "mi"
	}
	// endregion

	// region FlyingConversion

	@Test
	fun `flying high altitude shows flight level`() {
		injectLengthSystem(LengthSystem.Flying)
		val result = FormatHelpers.formatDistance(context, 10000, 2)
		result shouldContain "FL"
	}

	@Test
	fun `flying low altitude shows feet`() {
		injectLengthSystem(LengthSystem.Flying)
		val result = FormatHelpers.formatDistance(context, 100, 2)
		result shouldContain "ft"
	}
	// endregion
}
