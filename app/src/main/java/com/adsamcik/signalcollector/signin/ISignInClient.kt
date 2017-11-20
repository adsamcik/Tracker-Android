package com.adsamcik.signalcollector.signin

import android.app.Activity
import android.content.Context
import android.content.Intent

import com.adsamcik.signalcollector.interfaces.IContextValueCallback

internal interface ISignInClient {

    fun signIn(activity: Activity, userValueCallback: IContextValueCallback<Context, User>)

    fun signInSilent(context: Context, userValueCallback: IContextValueCallback<Context, User>)

    fun signOut(context: Context)

    fun onSignInResult(activity: Activity, resultCode: Int, data: Intent)

    companion object {
        val RC_SIGN_IN = 4654
    }
}
