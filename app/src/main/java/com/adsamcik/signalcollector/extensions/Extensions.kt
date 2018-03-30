package com.adsamcik.signalcollector.extensions

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.support.v4.app.Fragment


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

inline fun <reified T : Any> Context.getSystemServiceTyped(serviceName: String): T = getSystemService(serviceName) as T

inline fun <reified T : Any> newIntent(context: Context): Intent =
        Intent(context, T::class.java)

inline fun android.support.v4.app.FragmentManager.transaction(func: android.support.v4.app.FragmentTransaction.() -> android.support.v4.app.FragmentTransaction) {
    beginTransaction().func().commit()
}

inline fun android.support.v4.app.FragmentManager.transactionStateLoss(func: android.support.v4.app.FragmentTransaction.() -> android.support.v4.app.FragmentTransaction) {
    beginTransaction().func().commitAllowingStateLoss()
}