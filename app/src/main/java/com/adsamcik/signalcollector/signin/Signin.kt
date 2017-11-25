package com.adsamcik.signalcollector.signin

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.adsamcik.signalcollector.file.DataStore
import com.adsamcik.signalcollector.network.Network
import com.adsamcik.signalcollector.test.MockSignInClient
import com.adsamcik.signalcollector.test.useMock
import com.adsamcik.signalcollector.utility.Preferences
import com.google.android.gms.auth.api.Auth
import java.util.*
import kotlin.coroutines.experimental.suspendCoroutine

class Signin {
    private var silentFailed: Boolean = false

    var user: User? = null
        private set

    private val onSignedCallbackList = ArrayList<(User?) -> Unit>(2)
    var onStateChangeCallback: ((SigninStatus, User?) -> Unit)? = null
        set(value) {
            value?.invoke(status, user)
        }

    private val onSignInInternal: (Context, User?) -> Unit = { context, user ->
        when {
            this.user != null && user == null -> {
                updateStatus(SigninStatus.NOT_SIGNED)
                signout(context)
            }
            user == null -> updateStatus(SigninStatus.SIGNIN_FAILED)
            user.isServerDataAvailable -> updateStatus(SigninStatus.SIGNED)
            else -> updateStatus(SigninStatus.SIGNED_NO_DATA)
        }

        this.user = user
        callOnSigninCallbacks()
    }

    private var client: ISignInClient? = null

    private fun initializeClient() {
        if (client == null) {
            client = if (useMock)
                MockSignInClient()
            else
                GoogleSignInSignalsClient()
        }
    }


    private constructor(activity: Activity, callback: ((User?) -> Unit)?, silent: Boolean) {
        if (callback != null)
            onSignedCallbackList.add(callback)

        instance = this
        initializeClient()
        if (silent)
            client!!.signInSilent(activity, onSignInInternal)
        else
            client!!.signIn(activity, onSignInInternal)
    }

    private constructor(context: Context, callback: ((User?) -> Unit)?) {
        if (callback != null)
            onSignedCallbackList.add(callback)

        instance = this

        initializeClient()
        client!!.signInSilent(context, onSignInInternal)
    }

    private fun updateStatus(signinStatus: SigninStatus) {
        when {
            signinStatus == SigninStatus.NOT_SIGNED -> client = null
            signinStatus.failed -> {
                client = null
                silentFailed = true
            }
        }
        onStateChangeCallback?.invoke(signinStatus, user)
    }

    private fun onSignInFailed() {
        updateStatus(SigninStatus.SIGNIN_FAILED)
        callOnSigninCallbacks()
    }

    @Synchronized private fun callOnSigninCallbacks() {
        for (c in onSignedCallbackList)
            c.invoke(user)
        onSignedCallbackList.clear()
    }

    private fun signout(context: Context) {
        assert(status.success)
        client!!.signOut(context)
        client = null
        user = null
        updateStatus(SigninStatus.NOT_SIGNED)
        Network.clearCookieJar(context)
        Preferences.getPref(context).edit().remove(Preferences.PREF_USER_ID).remove(Preferences.PREF_USER_DATA).remove(Preferences.PREF_USER_STATS).remove(Preferences.PREF_REGISTERED_USER).apply()
        DataStore.delete(context, Preferences.PREF_USER_DATA)
        DataStore.delete(context, Preferences.PREF_USER_STATS)
        callOnSigninCallbacks()
    }

    enum class SigninStatus(val value: Int) {
        NOT_SIGNED(0),
        SIGNIN_IN_PROGRESS(1),
        SIGNED(2),
        SIGNED_NO_DATA(3),
        SILENT_SIGNIN_FAILED(4),
        SIGNIN_FAILED(5);


        val failed: Boolean
            get() = value == SILENT_SIGNIN_FAILED.ordinal || value == SIGNIN_FAILED.ordinal

        val success: Boolean
            get() = value == SIGNED_NO_DATA.ordinal || value == SIGNED.ordinal
    }

    companion object {
        const val RC_SIGN_IN = 4654

        private var instance: Signin? = null

        val isSignedIn: Boolean
            get() = instance?.user != null

        val status: SigninStatus
            get() = when {
                instance == null -> SigninStatus.NOT_SIGNED
                instance!!.silentFailed -> SigninStatus.SILENT_SIGNIN_FAILED
                instance!!.user == null -> SigninStatus.SIGNIN_IN_PROGRESS
                instance!!.user!!.isServerDataAvailable -> SigninStatus.SIGNED
                else -> SigninStatus.SIGNED_NO_DATA
            }

        fun signIn(activity: Activity, callback: ((User?) -> Unit)?, silentOnly: Boolean): Signin {
            if (instance == null)
                instance = Signin(activity, callback, silentOnly)
            else if (status.failed && !silentOnly) {
                if (callback != null)
                    instance!!.onSignedCallbackList.add(callback)
                instance!!.client!!.signIn(activity, instance!!.onSignInInternal)
            } else if (instance?.user != null)
                callback?.invoke(instance!!.user)

            return instance!!
        }

        fun signIn(context: Context, callback: ((User?) -> Unit)?): Signin? {
            if (instance == null)
            //instance is assigned in constructor to make it sooner available
                Signin(context, callback)
            else if (callback != null) {
                if (instance!!.user != null)
                    callback.invoke(instance!!.user)
                else if (status.failed)
                    callback.invoke(null)
                else
                    instance!!.onSignedCallbackList.add(callback)
            }

            return instance
        }

        suspend fun signIn(activity: Activity, silentOnly: Boolean): User? =
                suspendCoroutine { cont ->
                    if (instance == null)
                        instance = Signin(activity, { cont.resume(it) }, silentOnly)
                    else if (status.failed && !silentOnly) {
                        instance!!.client!!.signIn(activity, { context, value ->
                            instance!!.onSignInInternal.invoke(context, value)
                            cont.resume(value)
                        })
                    }

                    cont.resume(instance!!.user)
                }

        fun signOut(context: Context) {
            instance!!.signout(context)
        }

        fun getUserAsync(context: Context, callback: (User?) -> Unit) {
            if (instance?.user != null)
                callback.invoke(instance!!.user)
            else
                signIn(context, callback)
        }

        suspend fun getUserAsync(context: Context): User? = suspendCoroutine { cont ->
            getUserAsync(context, { user -> cont.resume(user) })
        }

        fun getUserID(context: Context): String? =
                Preferences.getPref(context).getString(Preferences.PREF_USER_ID, null)

        fun removeOnSignedListeners() {
            instance?.onSignedCallbackList?.clear()
        }

        fun onSignResult(activity: Activity, resultCode: Int, intent: Intent) {
            val result = Auth.GoogleSignInApi.getSignInResultFromIntent(intent)
            if (result.isSuccess) {
                instance!!.client!!.onSignInResult(activity, resultCode, intent)
            } else {
                onSignedInFailed(activity)
            }

        }

        private fun onSignedInFailed(context: Context) {
            val signin = signIn(context, null)
            signin!!.onSignInFailed()
        }
    }
}
