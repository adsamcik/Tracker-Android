package com.adsamcik.tracker.module

import android.Manifest
import android.content.Context
import android.os.Build
import com.adsamcik.tracker.R
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.utils.module.FirstRun
import com.adsamcik.tracker.shared.utils.module.OnDoneListener
import com.adsamcik.tracker.shared.utils.permission.PermissionData
import com.adsamcik.tracker.shared.utils.permission.PermissionManager
import com.adsamcik.tracker.shared.utils.permission.PermissionRequest
import com.adsamcik.tracker.shared.utils.permission.PermissionResultCallback
import com.afollestad.materialdialogs.callbacks.onDismiss

/**
 * Prompts user for first run dialogs to ensures application is setup properly.
 */
class AppFirstRun : FirstRun() {
    override fun onFirstRun(context: Context, onDoneListener: OnDoneListener) {
        createDialog(context) {
            title(res = R.string.first_run_app_title)
            message(res = R.string.first_run_app_description)
            positiveButton { errorReporting(context, onDoneListener) }
            negativeButton(res = R.string.skip_introduction) {
                onDoneListener.invoke(context, true)
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
            title(res = R.string.first_run_error_reporting_title)
            message(res = R.string.first_run_error_reporting_description) {}
            positiveButton(res = com.adsamcik.tracker.shared.base.R.string.generic_yes) {
                setReportingPreference(it.context, true)
            }
            negativeButton(res = com.adsamcik.tracker.shared.base.R.string.generic_no) {
                setReportingPreference(it.context, false)
            }

            onDismiss { onDoneListener(context, false) }
        }
    }
}
