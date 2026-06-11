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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
import java.util.Locale

@ExtendWith(RobolectricExtension::class)
@Config(sdk = [34])
class FormatHelpersTest {

	private lateinit var context: Context

	@BeforeEach
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

	@Nested
	inner class Routing {

		@ParameterizedTest(name = "{0} with {1}m contains \"{2}\"")
		@CsvSource(
			delimiter = '|',
			value = [
				"Metric|500|m",
				"Metric|1500|km",
				"Imperial|500|ft",
				"Imperial|5000|mi",
				"AncientRoman|500|pace",
				"AncientRoman|2000|roman mile",
				"Sailing|500|cable",
				"Sailing|5|fathom",
				"Flying|500|FL",
				"Flying|100|ft"
			]
		)
		fun `routes to correct format function`(systemName: String, meters: Int, expectedUnit: String) {
			injectLengthSystem(LengthSystem.valueOf(systemName))
			val result = FormatHelpers.formatDistance(context, meters, 2)
			result shouldContain expectedUnit
		}
	}

	@Nested
	inner class AllSystemsProduce {

		@ParameterizedTest(name = "{0} produces output for typical value")
		@EnumSource(LengthSystem::class)
		fun `all systems produce non-empty output`(system: LengthSystem) {
			injectLengthSystem(system)
			FormatHelpers.formatDistance(context, 1000, 2).shouldNotBeEmpty()
		}

		@ParameterizedTest(name = "{0} handles zero meters")
		@EnumSource(LengthSystem::class)
		fun `all systems handle zero meters`(system: LengthSystem) {
			injectLengthSystem(system)
			FormatHelpers.formatDistance(context, 0, 2).shouldNotBeEmpty()
		}
	}

	@Nested
	inner class MetricFormatting {

		@Test
		fun `below threshold returns meters`() {
			injectLengthSystem(LengthSystem.Metric)
			FormatHelpers.formatDistance(context, 500, 2) shouldBe "500 m"
		}

		@Test
		fun `above threshold returns kilometers`() {
			injectLengthSystem(LengthSystem.Metric)
			FormatHelpers.formatDistance(context, 1500, 2) shouldBe "1.5 km"
		}
	}

	@Nested
	inner class ImperialConversion {

		@Test
		fun `converts meters to feet`() {
			injectLengthSystem(LengthSystem.Imperial)
			val result = FormatHelpers.formatDistance(context, 1000, 2)
			result shouldContain "3,280.84"
			result shouldContain "ft"
		}

		@Test
		fun `converts large distance to miles`() {
			injectLengthSystem(LengthSystem.Imperial)
			val result = FormatHelpers.formatDistance(context, 5000, 2)
			result shouldContain "mi"
		}
	}

	@Nested
	inner class FlyingConversion {

		@Test
		fun `high altitude shows flight level`() {
			injectLengthSystem(LengthSystem.Flying)
			val result = FormatHelpers.formatDistance(context, 10000, 2)
			result shouldContain "FL"
		}

		@Test
		fun `low altitude shows feet`() {
			injectLengthSystem(LengthSystem.Flying)
			val result = FormatHelpers.formatDistance(context, 100, 2)
			result shouldContain "ft"
		}
	}
}
