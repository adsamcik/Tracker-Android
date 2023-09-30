package com.adsamcik.tracker.tracker.module

import android.Manifest
import android.annotation.SuppressLint
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
                    .permission(
                        PermissionData(
                            Manifest.permission.ACCESS_BACKGROUND_LOCATION
                        ) { it.getString(com.adsamcik.tracker.shared.utils.R.string.permission_rationale_background_location) }
                    )
                    .onResult(callback)
                    .build()
            )
        }
    }

    private fun backgroundLocation(context: Context, onDoneListener: OnDoneListener) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requestBackgroundLocation(context) {
                onDoneListener(context, false)
            }
        } else {
            onDoneListener(context, false)
        }

    }

    private fun autoTrackingOptions(context: Context, onDoneListener: OnDoneListener) {
        val itemsArray = context.resources.getStringArray(R.array.auto_tracking_options_values)

        createDialog(context) {
            setTitle(R.string.first_run_automatic_tracking_title)
            setMessage(R.string.first_run_automatic_tracking_description)
            setSingleChoiceItems(itemsArray, -1) { dialog, which ->
                // Handle item selection
                Preferences.getPref(context).edit {
                    setInt(
                        com.adsamcik.tracker.shared.preferences.R.string.settings_tracking_activity_key,
                        which
                    )
                }

                if (which > 0) {
                    PermissionManager.checkActivityPermissions(context) {
                        if (!it.isSuccess) {
                            Preferences.getPref(context).edit {
                                setInt(
                                    com.adsamcik.tracker.shared.preferences.R.string.settings_tracking_activity_key,
                                    0
                                )
                            }
                        }
                        whatToTrackOptions(context, onDoneListener)
                    }
                } else {
                    whatToTrackOptions(context, onDoneListener)
                }
            }
            setPositiveButton(com.adsamcik.tracker.shared.base.R.string.generic_done) { dialog, _ ->
                dialog.dismiss()
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
            .mapIndexed { index, triple ->
                (resources.getString(triple.second) == "true") to index
            }
            .filter { it.first }
            .map { it.second }
            .toIntArray()

        val titleList = list.map { resources.getString(it.third) }
        val titleListCharSequence = titleList.toTypedArray()
        val initialSelectionBooleanArray = BooleanArray(titleList.size) { it in selection.toList() }

        createDialog(context) {
            setTitle(R.string.settings_auto_tracking_category)
            setMultiChoiceItems(titleListCharSequence, initialSelectionBooleanArray) { _, index, isChecked ->
                // Handle item selected/deselected
            }
            setPositiveButton(R.string.generic_ok) { dialog, _ ->
                val selectedIndices = initialSelectionBooleanArray.mapIndexed { idx, isChecked ->
                    if (isChecked) idx else null
                }.filterNotNull().toIntArray()

                Preferences.getPref(context).edit {
                    list.forEachIndexed { index, triple ->
                        setBoolean(resources.getString(triple.first), selectedIndices.contains(index))
                    }

                    val anyWifi = list.mapIndexedNotNull { index, triple ->
                        if (triple.first == R.string.settings_wifi_location_count_enabled_key ||
                            triple.first == R.string.settings_wifi_location_count_enabled_key
                        ) {
                            index
                        } else {
                            null
                        }
                    }.any { selectedIndices.contains(it) }

                    if (anyWifi) {
                        setBoolean(resources.getString(R.string.settings_wifi_enabled_key), true)
                    }
                }

                trackingPermissionRequest(context) {
                    if (it.checkPermissionGranted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                        backgroundLocation(context, onDoneListener)
                    } else {
                        onDoneListener(context, false)
                    }
                }
            }
            setNegativeButton(R.string.generic_cancel) { dialog, _ ->
                // Handle Cancel button click
            }
        }
    }
}
