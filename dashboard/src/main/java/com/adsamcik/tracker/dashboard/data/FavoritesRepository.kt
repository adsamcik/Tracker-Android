package com.adsamcik.tracker.dashboard.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Available metrics that can be pinned as favorites on the map overlay.
 * Each metric corresponds to a data point from the tracking pipeline.
 */
enum class DashboardMetric(val key: String, val defaultLabel: String) {
	SPEED("speed", "Speed"),
	DISTANCE("distance", "Distance"),
	DURATION("duration", "Duration"),
	STEPS("steps", "Steps"),
	ALTITUDE("altitude", "Altitude"),
	ACTIVITY("activity", "Activity"),
	ACCURACY("accuracy", "Accuracy"),
	AVG_SPEED("avg_speed", "Avg Speed"),
	WIFI_COUNT("wifi_count", "WiFi"),
	CELL_COUNT("cell_count", "Cell"),
	COORDINATES("coordinates", "Coordinates")
}

private val Context.dashboardPrefsDataStore by preferencesDataStore(
	name = "dashboard_preferences"
)

/**
 * Repository for managing user-configurable metric favorites.
 *
 * Favorites are the metrics displayed as glass cards overlaid on the
 * MapLibre hero during active tracking. Users can customize which
 * metrics they see at a glance.
 *
 * Default favorites: Duration, Distance, Speed, Activity
 */
class FavoritesRepository(private val context: Context) {

	private val favoritesKey = stringSetPreferencesKey("favorite_metrics")

	val defaultFavorites: Set<DashboardMetric> = setOf(
		DashboardMetric.DURATION,
		DashboardMetric.DISTANCE,
		DashboardMetric.SPEED,
		DashboardMetric.ACTIVITY
	)

	/**
	 * Flow of currently selected favorite metrics.
	 * Emits the default set if the user hasn't customized.
	 */
	val favoritesFlow: Flow<Set<DashboardMetric>> =
		context.dashboardPrefsDataStore.data.map { prefs ->
			val stored = prefs[favoritesKey]
			if (stored != null) {
				stored.mapNotNull { key ->
					DashboardMetric.entries.find { it.key == key }
				}.toSet()
			} else {
				defaultFavorites
			}
		}

	/**
	 * Update the set of favorite metrics.
	 */
	suspend fun setFavorites(metrics: Set<DashboardMetric>) {
		context.dashboardPrefsDataStore.edit { prefs ->
			prefs[favoritesKey] = metrics.map { it.key }.toSet()
		}
	}

	/**
	 * Add a single metric to favorites.
	 */
	suspend fun addFavorite(metric: DashboardMetric) {
		context.dashboardPrefsDataStore.edit { prefs ->
			val current = prefs[favoritesKey]?.toMutableSet()
				?: defaultFavorites.map { it.key }.toMutableSet()
			current.add(metric.key)
			prefs[favoritesKey] = current
		}
	}

	/**
	 * Remove a single metric from favorites.
	 */
	suspend fun removeFavorite(metric: DashboardMetric) {
		context.dashboardPrefsDataStore.edit { prefs ->
			val current = prefs[favoritesKey]?.toMutableSet()
				?: defaultFavorites.map { it.key }.toMutableSet()
			current.remove(metric.key)
			prefs[favoritesKey] = current
		}
	}
}
