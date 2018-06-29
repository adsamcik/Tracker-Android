package com.adsamcik.signalcollector.signin

import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity

/**
 * Interface for the sign-in clientAuth.
 */
internal interface ISignInClient {
    /**
     * Attempts to sign-in the user. Sign-in does not have to be silent.
     *
     * @param activity Activity.
     * @param userValueCallback Callback that needs to be called once the user sign-in either fails or succeeds.
     */
    fun signIn(activity: AppCompatActivity, userValueCallback: (Context, User?) -> Unit)

    /**
     * Attempts to sign-in the user silently.
     *
     * @param context Context.
     * @param userValueCallback Callback that needs to be called once the user sign-in either fails or succeeds.
     */
    fun signInSilent(context: Context, userValueCallback: (Context, User?) -> Unit)

    /**
     * Signs the user out.
     */
    fun signOut(context: Context)

    /**
     * Called if the clientAuth requires [AppCompatActivity.onActivityResult] for some reason. Looking at you Google.
     */
    fun onSignInResult(activity: AppCompatActivity, resultCode: Int, data: Intent)
}
