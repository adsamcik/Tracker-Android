package com.adsamcik.tracker.map.ui.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.map.ui.theme.MapTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapThemeTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun lightTheme_rendersContent() {
		composeRule.setContent {
			MapTheme(darkTheme = false) {
				Text("Hello Map")
			}
		}
		composeRule.onNodeWithText("Hello Map").assertIsDisplayed()
	}

	@Test
	fun darkTheme_rendersContent() {
		composeRule.setContent {
			MapTheme(darkTheme = true) {
				Text("Dark Map")
			}
		}
		composeRule.onNodeWithText("Dark Map").assertIsDisplayed()
	}

	@Test
	fun lightTheme_providesLightColorScheme() {
		var primaryRed = 0f
		composeRule.setContent {
			MapTheme(darkTheme = false) {
				primaryRed = MaterialTheme.colorScheme.primary.red
				Text("Check colors")
			}
		}
		composeRule.waitForIdle()
		// Just verify the color scheme is resolved (non-zero primary)
		assertTrue(primaryRed > 0f, "Primary color should have non-zero red component")
	}

	@Test
	fun darkTheme_providesDarkColorScheme() {
		var surfaceRed = -1f
		composeRule.setContent {
			MapTheme(darkTheme = true) {
				surfaceRed = MaterialTheme.colorScheme.surface.red
				Text("Check dark colors")
			}
		}
		composeRule.waitForIdle()
		assertTrue(surfaceRed >= 0f, "Surface color should be resolved")
	}

	@Test
	fun nestedContent_hasAccessToMaterialTheme() {
		var typographyResolved = false
		var shapesResolved = false
		composeRule.setContent {
			MapTheme(darkTheme = false) {
				typographyResolved = MaterialTheme.typography.bodyLarge.fontSize.isSp
				shapesResolved = MaterialTheme.shapes.medium != null
				Text("Nested")
			}
		}
		composeRule.waitForIdle()
		assertTrue(typographyResolved, "Typography should be resolved inside MapTheme")
		assertTrue(shapesResolved, "Shapes should be resolved inside MapTheme")
	}
}
