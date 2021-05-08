package com.adsamcik.tracker.tracker.module

import android.Manifest
import android.content.Context
import android.os.Build
import com.adsamcik.tracker.shared.base.extension.telephonyManager
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.utils.module.FirstRun
import com.adsamcik.tracker.shared.utils.module.OnDoneListener
import com.adsamcik.tracker.shared.utils.permission.PermissionData
import com.adsamcik.tracker.shared.utils.permission.PermissionManager
import com.adsamcik.tracker.shared.utils.permission.PermissionRequest
import com.adsamcik.tracker.shared.utils.permission.PermissionResultCallback

import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.utility.TelephonyUtils
import com.afollestad.materialdialogs.list.listItemsMultiChoice
import com.afollestad.materialdialogs.list.listItemsSingleChoice

/**
 * First run for tracker component.
 */
class TrackerFirstRun : FirstRun() {
    override fun onFirstRun(context: Context, onDoneListener: OnDoneListener) {
        autoTrackingOptions(context, onDoneListener)
    }

    private fun requestBackgroundLocation(context: Context, callback: PermissionResultCallback) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            PermissionManager.checkPermissions(
                PermissionRequest
                    .with(context)
                    .permissions(listOf(
                        PermissionData(
                            Manifest.permission.ACCESS_BACKGROUND_LOCATION
                        ) { context -> context.getString(com.adsamcik.tracker.shared.utils.R.string.permission_rationale_background_location) }
                    ))
                    .onResult(callback)
                    .build()
            )
        }
    }

    private fun backgroundLocation(context: Context, onDoneListener: OnDoneListener) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requestBackgroundLocation(context) { }
        }
        onDoneListener(context, false)
    }

    private fun autoTrackingOptions(context: Context, onDoneListener: OnDoneListener) {
        createDialog(context) {
            title(R.string.first_run_automatic_tracking_title)
            message(R.string.first_run_automatic_tracking_description)
            listItemsSingleChoice(
                R.array.auto_tracking_options_values,
                waitForPositiveButton = true,
                selection = { dialog, index, _ ->
                    Preferences.getPref(dialog.context).edit {
                        setInt(
                            com.adsamcik.tracker.shared.preferences.R.string.settings_tracking_activity_key,
                            index
                        )
                    }

                    if (index > 0) {
                        PermissionManager.checkActivityPermissions(dialog.context) {
                            if (!it.isSuccess) {
                                Preferences.getPref(dialog.context).edit {
                                    setInt(
                                        com.adsamcik.tracker.shared.preferences.R.string.settings_tracking_activity_key,
                                        0
                                    )
                                }
                            }

                            whatToTrackOptions(dialog.context, onDoneListener)
                        }
                    } else {
                        whatToTrackOptions(dialog.context, onDoneListener)
                    }
                }
            )
            positiveButton(res = com.adsamcik.tracker.shared.base.R.string.generic_done)
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

    private fun trackingPermissionRequest(context: Context, onResult: PermissionResultCallback) {
        val preferences = Preferences.getPref(context)
        val permissionsList = mutableListOf<PermissionData>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            preferences.getBooleanRes(
                R.string.settings_activity_enabled_key,
                R.string.settings_activity_enabled_default
            )
        ) {
            permissionsList.add(PermissionData(Manifest.permission.ACTIVITY_RECOGNITION) {
                it.getString(
                    R.string.permission_rationale_activity
                )
            })
        }

        if (TelephonyUtils.getPhoneCount(context.telephonyManager) > 1 &&
            preferences.getBooleanRes(
                R.string.settings_cell_enabled_key,
                R.string.settings_cell_enabled_default
            )
        ) {
            permissionsList.add(PermissionData(Manifest.permission.READ_PHONE_STATE) {
                it.getString(R.string.permission_rationale_phone_state)
            })
        }

        if (preferences.getBooleanRes(
                R.string.settings_location_enabled_key,
                R.string.settings_location_enabled_default
            )
        ) {
            permissionsList.add(PermissionData(Manifest.permission.ACCESS_FINE_LOCATION) {
                it.getString(R.string.permission_rationale_location)
            })
        }

        PermissionManager.checkPermissions(
            PermissionRequest
                .with(context)
                .permissions(permissionsList)
                .onResult(onResult)
                .build()
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
                                triple.first == R.string.settings_wifi_location_count_enabled_key
                            ) {
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
                trackingPermissionRequest(context) {
                    if (it.checkPermissionGranted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                        backgroundLocation(context, onDoneListener)
                    } else {
                        onDoneListener(context, false)
                    }
                }
            }
        }
    }
}
