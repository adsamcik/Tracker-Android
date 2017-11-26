package com.adsamcik.signalcollector.signin

import android.app.Activity
import android.content.Context
import android.content.Intent

internal interface ISignInClient {
    fun signIn(activity: Activity, userValueCallback: (Context, User?) -> Unit)

    fun signInSilent(context: Context, userValueCallback: (Context, User?) -> Unit)

    fun signOut(context: Context)

    fun onSignInResult(activity: Activity, resultCode: Int, data: Intent)
}
