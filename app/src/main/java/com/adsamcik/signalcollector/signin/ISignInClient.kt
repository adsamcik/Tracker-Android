package com.adsamcik.signalcollector.signin

import android.support.v7.app.AppCompatActivity
import android.content.Context
import android.content.Intent

internal interface ISignInClient {
    fun signIn(activity: AppCompatActivity, userValueCallback: (Context, User?) -> Unit)

    fun signInSilent(context: Context, userValueCallback: (Context, User?) -> Unit)

    fun signOut(context: Context)

    fun onSignInResult(activity: AppCompatActivity, resultCode: Int, data: Intent)
}
