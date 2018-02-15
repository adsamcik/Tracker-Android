package com.adsamcik.signalcollector.utility

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle


inline fun <reified T : Any> Activity.launchActivity(
        requestCode: Int = -1,
        options: Bundle? = null,
        noinline init: Intent.() -> Unit = {}) {

    val intent = newIntent<T>(this)
    intent.init()
    startActivityForResult(intent, requestCode, options)
}

inline fun <reified T : Any> Context.launchActivity(
        options: Bundle? = null,
        noinline init: Intent.() -> Unit = {}) {

    val intent = newIntent<T>(this)
    intent.init()
    startActivity(intent, options)
}

inline fun <reified T : Any> newIntent(context: Context): Intent =
        Intent(context, T::class.java)

inline fun android.support.v4.app.FragmentManager.transaction(func: android.support.v4.app.FragmentTransaction.() -> android.support.v4.app.FragmentTransaction) {
    beginTransaction().func().commit()
}

inline fun android.app.FragmentManager.transaction(func: android.app.FragmentTransaction.() -> android.app.FragmentTransaction) {
    beginTransaction().func().commit()
}