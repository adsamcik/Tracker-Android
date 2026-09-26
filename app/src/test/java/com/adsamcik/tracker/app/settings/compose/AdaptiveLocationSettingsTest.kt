package com.adsamcik.tracker.app.settings.compose

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.app.settings.TrackingSettingsUiState
import com.adsamcik.tracker.app.settings.tracking.AdaptiveLocationSettings
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdaptiveLocationSettingsTest {
	@get:Rule val compose = createComposeRule()

	@Test fun enablingRequiresDisclosureBeforeSavedIntentCallback() {
		var enabled = false
		compose.setContent {
			AppTheme {
				AdaptiveLocationSettings(
					TrackingSettingsUiState(), { enabled = it }, {}, {}, {}, {}, {},
				)
			}
		}
		compose.onNodeWithTag("ambientSource-location").assertIsOff().performClick()
		enabled shouldBe false
		compose.onNodeWithTag("confirmAdaptiveLocation").performClick()
		enabled shouldBe true
	}

	@Test fun existingPassiveIntentDoesNotAdoptAdaptiveOnOpeningSettings() {
		var changes = 0
		compose.setContent {
			AppTheme {
				AdaptiveLocationSettings(
					TrackingSettingsUiState(ambientLocationEnabled = true),
					{ changes++ }, { changes++ }, { changes++ }, { changes++ }, {}, {},
				)
			}
		}
		compose.onNodeWithTag("locationMode-passive").assertIsSelected()
		changes shouldBe 0
	}

	@Test fun exactlyFiveLevelsMakeExplicitProfileSelection() {
		var selected: Int? = null
		compose.setContent {
			AppTheme {
				AdaptiveLocationSettings(TrackingSettingsUiState(), {}, {}, { selected = it }, {}, {}, {})
			}
		}
		compose.onNodeWithTag("locationBalance").performClick()
		(1..5).forEach { compose.onNodeWithTag("locationBalance-$it").assertExists() }
		compose.onNodeWithTag("locationBalance-3").performClick()
		selected shouldBe 3
	}
	@Test fun captureOnlyAdoptedProfileExposesOptionalMotionWithoutEnablingBackground() {
		var assistance = false
		var backgroundChanges = 0
		compose.setContent {
			AppTheme {
				AdaptiveLocationSettings(TrackingSettingsUiState(locationProfileVersion = 1, locationBalanceLevel = 3),
					{ backgroundChanges++ }, {}, {}, { assistance = it }, {}, {})
			}
		}
		compose.onNodeWithTag("locationMotionAssistance").assertIsOff().performClick()
		assistance shouldBe true
		backgroundChanges shouldBe 0
		compose.onNodeWithTag("ambientSource-location").assertIsOff()
	}

}
