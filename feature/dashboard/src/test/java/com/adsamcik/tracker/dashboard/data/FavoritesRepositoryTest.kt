package com.adsamcik.tracker.dashboard.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FavoritesRepositoryTest {

	private lateinit var repo: FavoritesRepository

	@Before
	fun setup() = runTest {
		val context = ApplicationProvider.getApplicationContext<Application>()
		repo = FavoritesRepository(context)
		// Reset DataStore to known state for test isolation
		repo.setFavorites(repo.defaultFavorites)
	}

	// region Default favorites
	@Test
	fun `default favorites contains four metrics`() = runTest {
		val defaults = repo.favoritesFlow.first()
		defaults shouldBe repo.defaultFavorites
		defaults.size shouldBe 4
	}

	@Test
	fun `default favorites include expected metrics`() {
		repo.defaultFavorites shouldContain DashboardMetric.DURATION
		repo.defaultFavorites shouldContain DashboardMetric.DISTANCE
		repo.defaultFavorites shouldContain DashboardMetric.SPEED
		repo.defaultFavorites shouldContain DashboardMetric.ACTIVITY
	}
	// endregion

	// region setFavorites
	@Test
	fun `setFavorites persists custom set`() = runTest {
		val custom = setOf(DashboardMetric.STEPS, DashboardMetric.ALTITUDE)
		repo.setFavorites(custom)

		repo.favoritesFlow.first() shouldBe custom
	}

	@Test
	fun `setFavorites with empty set persists empty`() = runTest {
		repo.setFavorites(emptySet())
		repo.favoritesFlow.first() shouldBe emptySet()
	}
	// endregion

	// region addFavorite
	@Test
	fun `addFavorite adds to default set`() = runTest {
		repo.addFavorite(DashboardMetric.WIFI_COUNT)

		val result = repo.favoritesFlow.first()
		result shouldContain DashboardMetric.WIFI_COUNT
		// Defaults should still be present
		result shouldContain DashboardMetric.DURATION
	}

	@Test
	fun `addFavorite is idempotent`() = runTest {
		repo.addFavorite(DashboardMetric.STEPS)
		repo.addFavorite(DashboardMetric.STEPS)

		val result = repo.favoritesFlow.first()
		result.count { it == DashboardMetric.STEPS } shouldBe 1
	}
	// endregion

	// region removeFavorite
	@Test
	fun `removeFavorite removes from default set`() = runTest {
		repo.removeFavorite(DashboardMetric.SPEED)

		val result = repo.favoritesFlow.first()
		result shouldNotContain DashboardMetric.SPEED
		// Other defaults should remain
		result shouldContain DashboardMetric.DURATION
	}

	@Test
	fun `removeFavorite for absent metric is no-op`() = runTest {
		repo.removeFavorite(DashboardMetric.COORDINATES)

		val result = repo.favoritesFlow.first()
		result shouldBe repo.defaultFavorites
	}
	// endregion

	// region DashboardMetric enum
	@Test
	fun `all metrics have unique keys`() {
		val keys = DashboardMetric.entries.map { it.key }
		keys.size shouldBe keys.toSet().size
	}

	@Test
	fun `all metrics have non-zero labelRes`() {
		DashboardMetric.entries.forEach { metric ->
			(metric.labelRes != 0) shouldBe true
		}
	}
	// endregion
}
