package com.adsamcik.signalcollector.extensions

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.PowerManager
import android.support.v4.app.Fragment
import android.telephony.TelephonyManager
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager


inline fun <reified T : Any> Activity.startActivity(
        requestCode: Int = -1,
        options: Bundle? = null,
        noinline init: Intent.() -> Unit = {}) {
    val intent = newIntent<T>(this)
    intent.init()
    startActivityForResult(intent, requestCode, options)
}

inline fun <reified T : Any> Fragment.startActivity(
        options: Bundle? = null,
        noinline init: Intent.() -> Unit = {}) {
    context!!.startActivity<T>(options, init)
}

inline fun <reified T : Any> Context.startActivity(
        options: Bundle? = null,
        noinline init: Intent.() -> Unit = {}) {

    val intent = newIntent<T>(this)
    intent.init()
    startActivity(intent, options)
}

inline fun <reified T : Any> Context.stopService() {
    val intent = newIntent<T>(this)
    stopService(intent)
}

inline fun <reified T : Any> newIntent(context: Context): Intent =
        Intent(context, T::class.java)

inline fun android.support.v4.app.FragmentManager.transaction(func: android.support.v4.app.FragmentTransaction.() -> android.support.v4.app.FragmentTransaction) {
    beginTransaction().func().commit()
}

inline fun android.support.v4.app.FragmentManager.transactionStateLoss(func: android.support.v4.app.FragmentTransaction.() -> android.support.v4.app.FragmentTransaction) {
    beginTransaction().func().commitAllowingStateLoss()
}

inline fun <reified T : Any> Context.getSystemServiceTyped(serviceName: String): T = getSystemService(serviceName) as T

val Context.telephonyManager get() = getSystemServiceTyped<TelephonyManager>(Context.TELEPHONY_SERVICE)

val Context.connectivityManager get() = getSystemServiceTyped<ConnectivityManager>(Context.CONNECTIVITY_SERVICE)

val Context.locationManager get() = getSystemServiceTyped<LocationManager>(Context.LOCATION_SERVICE)

val Context.inputMethodManager get() = getSystemServiceTyped<InputMethodManager>(Context.INPUT_METHOD_SERVICE)

val Context.windowManager get() = getSystemServiceTyped<WindowManager>(Context.WINDOW_SERVICE)

val Context.powerManager get() = getSystemServiceTyped<PowerManager>(Context.POWER_SERVICE)