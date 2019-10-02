package com.adsamcik.tracker.tracker.module

import android.content.Context
import com.adsamcik.tracker.common.module.FirstRun
import com.adsamcik.tracker.common.module.OnDoneListener
import com.adsamcik.tracker.common.preference.Preferences
import com.adsamcik.tracker.tracker.R
import com.afollestad.materialdialogs.list.listItemsMultiChoice
import com.afollestad.materialdialogs.list.listItemsSingleChoice

@Suppress("unused")
class TrackerFirstRun : FirstRun() {
	override fun onFirstRun(context: Context, onDoneListener: OnDoneListener) {
		autoTrackingOptions(context, onDoneListener)
	}

	private fun autoTrackingOptions(context: Context, onDoneListener: OnDoneListener) {
		createDialog(context) {
			title(R.string.settings_auto_tracking_category)
			listItemsSingleChoice(
					R.array.tracking_options_titles,
					waitForPositiveButton = true,
					selection = { dialog, index, _ ->
						Preferences.getPref(dialog.context).edit {
							setInt(
									com.adsamcik.tracker.common.R.string.settings_tracking_activity_key,
									index
							)
						}
					}
			)
			positiveButton {

				whatToTrackOptions(it.context, onDoneListener)
			}
		}
	}

	private fun getTrackingListResources(): List<Triple<Int, Int, Int>> {
		return listOf(
				Triple(
						R.string.settings_activity_enabled_key,
						R.string.settings_activity_enabled_default,
						R.string.settings_activity_enabled_title
				),
				Triple(
						R.string.settings_location_enabled_key,
						R.string.settings_location_enabled_default,
						R.string.settings_location_enabled_title
				),
				Triple(
						R.string.settings_wifi_network_enabled_key,
						R.string.settings_wifi_network_enabled_default,
						R.string.settings_wifi_network_enabled_title
				),
				Triple(
						R.string.settings_wifi_location_count_enabled_key,
						R.string.settings_wifi_location_count_enabled_default,
						R.string.settings_wifi_location_count_enabled_title
				),
				Triple(
						R.string.settings_cell_enabled_key,
						R.string.settings_cell_enabled_default,
						R.string.settings_cell_enabled_title
				)
		)
	}

	private fun whatToTrackOptions(context: Context, onDoneListener: OnDoneListener) {
		val list = getTrackingListResources()

		val resources = context.resources
		val selection = list
				.asSequence()
				.mapIndexed { index, triple ->
					(resources.getString(triple.second) == "true") to index
				}
				.filter { it.first }
				.map { it.second }
				.toList()
				.toIntArray()

		val titleList = list.map { resources.getString(it.third) }

		createDialog(context) {
			title(R.string.settings_auto_tracking_category)
			listItemsMultiChoice(
					items = titleList,
					waitForPositiveButton = true,
					initialSelection = selection,
					allowEmptySelection = true,
					selection = { dialog, indices, _ ->
						Preferences.getPref(dialog.context).edit {
							list.forEachIndexed { index, triple ->
								setBoolean(triple.first, indices.contains(index))
							}

							val anyWifi = list.mapIndexedNotNull { index, triple ->
								if (triple.first == R.string.settings_wifi_location_count_enabled_key ||
										triple.first == R.string.settings_wifi_location_count_enabled_key) {
									index
								} else {
									null
								}
							}.any { indices.contains(it) }

							if (anyWifi) {
								setBoolean(R.string.settings_wifi_enabled_key, true)
							}
						}
					}
			)
			positiveButton {
				onDoneListener(it.context)
			}
		}
	}
}
