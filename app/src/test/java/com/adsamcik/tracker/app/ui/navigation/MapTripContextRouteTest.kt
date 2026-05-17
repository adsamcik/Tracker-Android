package com.adsamcik.tracker.app.ui.navigation

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapTripContextRouteTest {

	@Test
	fun `typed navigation exposes map trip context arguments through saved state handle`() {
		val navController = NavHostController(ApplicationProvider.getApplicationContext<Context>()).apply {
			navigatorProvider.addNavigator(ComposeNavigator())
			setViewModelStore(ViewModelStore())
			graph = createGraph(startDestination = Map) {
				composable<Map> {}
				composable<MapTripContext> {}
			}
		}

		navController.navigate(
			MapTripContext(
				tripId = 42L,
				startMs = 1_700_000_000_000L,
				endMs = 1_700_000_900_000L,
			)
		)

		val entry = checkNotNull(navController.currentBackStackEntry)
		entry.toRoute<MapTripContext>() shouldBe MapTripContext(
			tripId = 42L,
			startMs = 1_700_000_000_000L,
			endMs = 1_700_000_900_000L,
		)
		entry.savedStateHandle.get<Long>("tripId") shouldBe 42L
		entry.savedStateHandle.get<Long>("startMs") shouldBe 1_700_000_000_000L
		entry.savedStateHandle.get<Long>("endMs") shouldBe 1_700_000_900_000L
	}
}
