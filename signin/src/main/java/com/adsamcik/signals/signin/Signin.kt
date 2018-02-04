package com.adsamcik.signals.signin

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.adsamcik.signals.network.Network
import com.adsamcik.signals.utilities.Preferences
import com.adsamcik.signals.utilities.storage.CacheStore
import com.adsamcik.signals.utilities.test.useMock
import com.google.android.gms.auth.api.Auth
import java.util.*
import kotlin.coroutines.experimental.suspendCoroutine

class Signin {
    var user: User? = null
        private set

    private val onSignInInternal: (Context, User?) -> Unit = { context, user ->
        val status = when {
            this.user != null && user == null -> {
                Network.clearCookieJar(context)
                Preferences.getPref(context).edit().remove(Preferences.PREF_USER_ID).remove(Preferences.PREF_USER_DATA).remove(Preferences.PREF_USER_STATS).remove(Preferences.PREF_REGISTERED_USER).apply()
                CacheStore.delete(context, Preferences.PREF_USER_DATA)
                CacheStore.delete(context, Preferences.PREF_USER_STATS)
                SigninStatus.NOT_SIGNED
            }
            user == null -> SigninStatus.SIGNIN_FAILED
            user.isServerDataAvailable -> SigninStatus.SIGNED
            else -> SigninStatus.SIGNED_NO_DATA
        }

        this.user = user
        updateStatus(status)
        callOnSigninCallbacks()
    }

    private var client: ISignInClient = if (useMock)
        MockSignInClient()
    else
        GoogleSignInSignalsClient()

    init {
        instance = this
    }

    private constructor(activity: Activity, callback: ((User?) -> Unit)?, silent: Boolean) {
        if (callback != null)
            onSignedCallbackList.add(callback)

        if (silent)
            client.signInSilent(activity, onSignInInternal)
        else
            client.signIn(activity, onSignInInternal)
    }

    private constructor(context: Context, callback: ((User?) -> Unit)?) {
        if (callback != null)
            onSignedCallbackList.add(callback)

        client.signInSilent(context, onSignInInternal)
    }

    private fun updateStatus(signinStatus: SigninStatus) {
        when {
            signinStatus == SigninStatus.NOT_SIGNED -> instance = null
            signinStatus.failed -> {
                instance = null
                silentFailed = true
            }
            signinStatus.success -> silentFailed = false
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
        client.signOut(context)
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

        private var silentFailed: Boolean = false
        private var instance: Signin? = null

        private val onSignedCallbackList = ArrayList<(User?) -> Unit>(2)

        var onStateChangeCallback: ((SigninStatus, User?) -> Unit)? = null
            set(value) {
                value?.invoke(status, instance?.user)
                field = value
            }

        val isSignedIn: Boolean
            get() = instance?.user != null

        val status: SigninStatus
            get() = when {
                instance == null && silentFailed -> SigninStatus.SILENT_SIGNIN_FAILED
                instance == null -> SigninStatus.NOT_SIGNED
                instance!!.user == null -> SigninStatus.SIGNIN_IN_PROGRESS
                instance!!.user!!.isServerDataAvailable -> SigninStatus.SIGNED
                else -> SigninStatus.SIGNED_NO_DATA
            }

        fun signIn(activity: Activity, callback: ((User?) -> Unit)?, silentOnly: Boolean): Signin {
            if (instance == null)
                instance = Signin(activity, callback, silentOnly)
            else if (status.failed && !silentOnly) {
                if (callback != null)
                    onSignedCallbackList.add(callback)
                instance!!.client.signIn(activity, instance!!.onSignInInternal)
            } else if (instance!!.user != null)
                callback?.invoke(instance!!.user)

            return instance!!
        }

        fun signIn(context: Context, callback: ((User?) -> Unit)?): Signin? {
            if (instance == null && !silentFailed)
                instance = Signin(context, callback)
            else if (callback != null) {
                when {
                    instance?.user != null -> callback.invoke(instance!!.user)
                    status.failed -> callback.invoke(null)
                    else -> onSignedCallbackList.add(callback)
                }
            }

            return instance
        }

        suspend fun signIn(activity: Activity, silentOnly: Boolean): User? {
            return suspendCoroutine { cont ->
                if (instance == null)
                    Signin(activity, {
                        cont.resume(it)
                    }, silentOnly)
                else if (status.failed && !silentOnly) {
                    instance!!.client.signIn(activity, { context, value ->
                        instance!!.onSignInInternal.invoke(context, value)
                        cont.resume(value)
                    })
                }
            }
        }

        fun signOut(context: Context) {
            instance?.signout(context)
        }

        /**
         * Returns user asynchronously using callback
         * User can be null if signin fails
         */
        fun getUserAsync(context: Context, callback: (User?) -> Unit) {
            if (instance?.user != null)
                callback.invoke(instance!!.user)
            else
                signIn(context, callback)
        }

        /**
         * Returns user asynchronously using Kotlin's coroutines
         * User can be null if signin fails
         */
        suspend fun getUserAsync(context: Context): User? = suspendCoroutine { cont ->
            getUserAsync(context, { user -> cont.resume(user) })
        }

        fun getUserID(context: Context): String? =
                Preferences.getPref(context).getString(Preferences.PREF_USER_ID, null)

        fun removeOnSignedListeners() {
            onSignedCallbackList.clear()
        }

        fun onSignResult(activity: Activity, resultCode: Int, intent: Intent) {
            val result = Auth.GoogleSignInApi.getSignInResultFromIntent(intent)
            if (result.isSuccess) {
                instance!!.client.onSignInResult(activity, resultCode, intent)
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
