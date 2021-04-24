package com.adsamcik.tracker.tracker.utility

import android.os.Build
import android.telephony.TelephonyManager

object TelephonyUtils {
    fun getPhoneCount(telephonyManager: TelephonyManager) = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
            telephonyManager.activeModemCount
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
            @Suppress("DEPRECATION") telephonyManager.phoneCount
        }
        else -> {
            1
        }
    }
}