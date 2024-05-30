package com.adsamcik.tracker.module

import android.Manifest.permission.POST_NOTIFICATIONS
import android.content.Context
import android.os.Build
import com.adsamcik.tracker.R
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.utils.module.FirstRun
import com.adsamcik.tracker.shared.utils.module.OnDoneListener
import com.adsamcik.tracker.shared.utils.permission.PermissionData
import com.adsamcik.tracker.shared.utils.permission.PermissionManager
import com.adsamcik.tracker.shared.utils.permission.PermissionRequest
import com.afollestad.materialdialogs.callbacks.onDismiss

/**
 * Prompts user for first run dialogs to ensures application is setup properly.
 */
@Suppress("unused")
class AppFirstRun : FirstRun() {
    override fun onFirstRun(context: Context, onDoneListener: OnDoneListener) {
        createDialog(context) {
            setTitle(R.string.first_run_app_title)
            setMessage(R.string.first_run_app_description)
            setPositiveButton(R.string.generic_ok) { dialog, _ ->
                enableNotifications(context, onDoneListener, false)
                dialog.dismiss()  // Manually dismiss dialog
            }
            setNegativeButton(R.string.skip_introduction) { dialog, _ ->
                enableNotifications(context, onDoneListener, true)
                dialog.dismiss()  // Manually dismiss dialog
            }
        }
    }

    private fun setReportingPreference(context: Context, value: Boolean) {
        Preferences.getPref(context).edit {
            setBoolean(
                com.adsamcik.tracker.shared.preferences.R.string.settings_error_reporting_key,
                value
            )
        }
    }

    private fun errorReporting(context: Context, onDoneListener: OnDoneListener) {
        createDialog(context) {
            setTitle(R.string.first_run_error_reporting_title)
            setMessage(R.string.first_run_error_reporting_description)
            setPositiveButton(com.adsamcik.tracker.shared.base.R.string.generic_yes) { dialog, _ ->
                setReportingPreference(context, true)
                dialog.dismiss()
            }
            setNegativeButton(com.adsamcik.tracker.shared.base.R.string.generic_no) { dialog, _ ->
                setReportingPreference(context, false)
                dialog.dismiss()
            }
            setOnDismissListener {
                onDoneListener(context, false)
            }
        }
    }

    private fun enableNotifications(
        context: Context,
        onDoneListener: OnDoneListener,
        isCloseRequested: Boolean
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            createDialog(context) {
                setTitle(R.string.first_run_notifications_title)
                setMessage(R.string.first_run_notifications_description)
                setPositiveButton(com.adsamcik.tracker.shared.base.R.string.generic_yes) { dialog, _ ->
                    PermissionManager.checkPermissionsWithRationaleDialog(
                        PermissionRequest.Builder(context)
                            .permission(PermissionData(POST_NOTIFICATIONS) { it.getString(R.string.first_run_notifications_description) })
                            .onResult { onDoneListener(context, isCloseRequested) }
                            .build()
                    )
                    dialog.dismiss()
                }
                setNegativeButton(com.adsamcik.tracker.shared.base.R.string.generic_no) { dialog, _ ->
                    dialog.dismiss()
                }
                setOnDismissListener {
                    if (isCloseRequested) {
                        onDoneListener(context, true)
                    } else {
                        errorReporting(context, onDoneListener)
                    }
                }
            }

        } else {
            onDoneListener(context, isCloseRequested)
        }
    }
}
