package com.adsamcik.tracker.dashboard.data

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.adsamcik.tracker.dashboard.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Available metrics that can be pinned as favorites on the map overlay.
 * Each metric corresponds to a data point from the tracking pipeline.
 */
enum class DashboardMetric(val key: String, @StringRes val labelRes: Int) {
	SPEED("speed", R.string.dashboard_metric_speed),
	DISTANCE("distance", R.string.dashboard_metric_distance),
	DURATION("duration", R.string.dashboard_metric_duration),
	STEPS("steps", R.string.dashboard_metric_steps),
	ALTITUDE("altitude", R.string.dashboard_metric_altitude),
	ACTIVITY("activity", R.string.dashboard_metric_activity),
	ACCURACY("accuracy", R.string.dashboard_metric_accuracy),
	AVG_SPEED("avg_speed", R.string.dashboard_metric_avg_speed),
	WIFI_COUNT("wifi_count", R.string.dashboard_metric_wifi),
	CELL_COUNT("cell_count", R.string.dashboard_metric_cell),
	COORDINATES("coordinates", R.string.dashboard_metric_coordinates)
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
