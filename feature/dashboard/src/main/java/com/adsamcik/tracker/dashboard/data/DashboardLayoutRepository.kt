package com.adsamcik.tracker.dashboard.data

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persisted layout configuration for the idle dashboard.
 *
 * @param widgetOrder Ordered list of widget IDs (first = top of screen).
 * @param hiddenWidgets Set of widget IDs the user has hidden.
 */
@Immutable
data class DashboardLayout(
	val widgetOrder: List<String> = DashboardWidget.defaultOrder,
	val hiddenWidgets: Set<String> = emptySet(),
)

private val Context.dashboardLayoutDataStore by preferencesDataStore(
	name = "dashboard_layout"
)

/**
 * Repository for persisting the user's dashboard widget layout.
 *
 * Stores widget order and visibility in DataStore preferences,
 * following the same pattern as [FavoritesRepository].
 */
@Singleton
class DashboardLayoutRepository @Inject constructor(
	@ApplicationContext private val context: Context,
) {

	private val orderKey = stringPreferencesKey("widget_order")
	private val hiddenKey = stringSetPreferencesKey("hidden_widgets")

	/**
	 * Flow of the current dashboard layout.
	 * Emits the default layout if the user hasn't customized.
	 */
	val layout: Flow<DashboardLayout> =
		context.dashboardLayoutDataStore.data.map { prefs ->
			val storedOrder = prefs[orderKey]
			val storedHidden = prefs[hiddenKey]

			DashboardLayout(
				widgetOrder = normalizeWidgetOrder(
					storedOrder?.let(::deserializeOrder) ?: DashboardWidget.defaultOrder,
				),
				hiddenWidgets = storedHidden
					?.filter { DashboardWidget.fromId(it) != null }
					?.toSet()
					.orEmpty(),
			)
		}

	/**
	 * Persist a new widget order.
	 * @param widgetIds Ordered list of all widget IDs.
	 */
	suspend fun reorder(widgetIds: List<String>) {
		context.dashboardLayoutDataStore.edit { prefs ->
			prefs[orderKey] = serializeOrder(normalizeWidgetOrder(widgetIds))
		}
	}

	/**
	 * Toggle the visibility of a widget.
	 * If currently hidden, it becomes visible; if visible, it becomes hidden.
	 */
	suspend fun toggleVisibility(widgetId: String) {
		context.dashboardLayoutDataStore.edit { prefs ->
			val current = prefs[hiddenKey]?.toMutableSet() ?: mutableSetOf()
			if (widgetId in current) {
				current.remove(widgetId)
			} else {
				current.add(widgetId)
			}
			prefs[hiddenKey] = current.filter { DashboardWidget.fromId(it) != null }.toSet()
		}
	}

	/**
	 * Reset both order and visibility to defaults.
	 */
	suspend fun resetToDefault() {
		context.dashboardLayoutDataStore.edit { prefs ->
			prefs.remove(orderKey)
			prefs.remove(hiddenKey)
		}
	}

	companion object {
		private const val ORDER_SEPARATOR = ","

		internal fun serializeOrder(ids: List<String>): String =
			ids.joinToString(ORDER_SEPARATOR)

		internal fun deserializeOrder(raw: String): List<String> =
			raw.split(ORDER_SEPARATOR).filter { it.isNotBlank() }

		internal fun normalizeWidgetOrder(ids: List<String>): List<String> {
			val knownIds = DashboardWidget.defaultOrder
			val normalized = ids
				.distinct()
				.filter { it in knownIds }

			return normalized + knownIds.filterNot(normalized::contains)
		}
	}
}
