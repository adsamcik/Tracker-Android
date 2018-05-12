package com.adsamcik.signalcollector.signin

import android.support.v7.app.AppCompatActivity
import android.content.Context
import android.content.Intent
import com.adsamcik.signalcollector.file.DataStore
import com.adsamcik.signalcollector.network.Network
import com.adsamcik.signalcollector.test.MockSignInClient
import com.adsamcik.signalcollector.test.useMock
import com.adsamcik.signalcollector.utility.Preferences
import com.google.android.gms.auth.api.Auth
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.coroutines.experimental.suspendCoroutine

object Signin {
    private val statusLock = ReentrantLock()

    var user: User? = null
        private set

    private val onSignInInternal: (Context, User?) -> Unit = { context, user ->
        statusLock.lock()
        val status = when {
            this.user != null && user == null -> {
                Network.clearCookieJar(context)
                Preferences.getPref(context).edit().remove(Preferences.PREF_USER_ID).remove(Preferences.PREF_USER_DATA).remove(Preferences.PREF_USER_STATS).remove(Preferences.PREF_REGISTERED_USER).apply()
                DataStore.delete(context, Preferences.PREF_USER_DATA)
                DataStore.delete(context, Preferences.PREF_USER_STATS)
                SigninStatus.NOT_SIGNED
            }
            user == null -> SigninStatus.SIGNIN_FAILED
            user.isServerDataAvailable -> SigninStatus.SIGNED
            else -> SigninStatus.SIGNED_NO_DATA
        }

        this.user = user
        updateStatus(status)
        callOnSigninCallbacks()

        if (status == SigninStatus.SIGNED_NO_DATA) {
            listenForServerData(user!!)
        }

        statusLock.unlock()
    }

    private var client: ISignInClient = if (useMock)
        MockSignInClient()
    else
        GoogleSignInSignalsClient()

    private fun listenForServerData(user: User) {
        user.addServerDataCallback {
            if (this.user != it)
                return@addServerDataCallback
            statusLock.lock()
            status = SigninStatus.SIGNED
            updateStatus(status)
            callOnSigninCallbacks()
            statusLock.unlock()
        }
    }

    fun signin(activity: AppCompatActivity, callback: ((User?) -> Unit)?, silent: Boolean) {
        if (callback != null)
            onSignedCallbackList.add(callback)

        if (silent)
            client.signInSilent(activity, onSignInInternal)
        else
            client.signIn(activity, onSignInInternal)
    }

    fun signin(context: Context, callback: ((User?) -> Unit)?) {
        if (callback != null)
            onSignedCallbackList.add(callback)

        client.signInSilent(context, onSignInInternal)
    }

    private fun updateStatus(signinStatus: SigninStatus) {
        this.status = signinStatus
        onStateChangeCallback?.invoke(signinStatus, user)
    }

    fun onSignInFailed() {
        updateStatus(SigninStatus.SIGNIN_FAILED)
        callOnSigninCallbacks()
    }

    @Synchronized
    private fun callOnSigninCallbacks() {
        for (c in onSignedCallbackList)
            c.invoke(user)
        onSignedCallbackList.clear()
    }

    private fun signout(context: Context) {
        statusLock.lock()
        assert(status.success)
        client.signOut(context)
        statusLock.unlock()
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

    const val RC_SIGN_IN = 4654

    private val onSignedCallbackList = ArrayList<(User?) -> Unit>(2)

    var onStateChangeCallback: ((SigninStatus, User?) -> Unit)? = null
        set(value) {
            value?.invoke(status, user)
            field = value
        }

    val isSignedIn: Boolean
        get() = user != null

    var status: SigninStatus = SigninStatus.NOT_SIGNED

    fun signIn(activity: AppCompatActivity, callback: ((User?) -> Unit)?, silentOnly: Boolean) {
        statusLock.lock()
        when (status) {
            Signin.SigninStatus.NOT_SIGNED -> {
                addCallback(callback)
                if (silentOnly)
                    client.signInSilent(activity, onSignInInternal)
                else
                    client.signIn(activity, onSignInInternal)
            }
            Signin.SigninStatus.SIGNIN_IN_PROGRESS -> {
                addCallback(callback)
            }
            Signin.SigninStatus.SIGNED_NO_DATA,
            Signin.SigninStatus.SIGNED -> {
                callback?.invoke(user)
            }
            Signin.SigninStatus.SIGNIN_FAILED,
            Signin.SigninStatus.SILENT_SIGNIN_FAILED -> {
                if (!silentOnly)
                    client.signIn(activity, onSignInInternal)
            }
        }
        statusLock.unlock()
    }

    fun signIn(context: Context, callback: ((User?) -> Unit)?) {
        statusLock.lock()
        when (status) {
            Signin.SigninStatus.NOT_SIGNED -> {
                addCallback(callback)
                client.signInSilent(context, onSignInInternal)
            }
            Signin.SigninStatus.SIGNIN_IN_PROGRESS -> {
                addCallback(callback)
            }
            Signin.SigninStatus.SIGNED_NO_DATA,
            Signin.SigninStatus.SIGNED -> {
                callback?.invoke(user)
            }
            Signin.SigninStatus.SILENT_SIGNIN_FAILED,
            Signin.SigninStatus.SIGNIN_FAILED -> {
            }
        }
        statusLock.unlock()
    }

    fun addCallback(callback: ((User?) -> Unit)?) {
        if (callback != null)
            onSignedCallbackList.add(callback)
    }


    suspend fun signIn(activity: AppCompatActivity, silentOnly: Boolean): User? {
        return suspendCoroutine { cont ->
            signIn(activity, { cont.resume(it) }, silentOnly)
        }
    }

    fun signOut(context: Context) {
        signout(context)
    }

    /**
     * Returns user asynchronously using callback
     * User can be null if signin fails
     */
    fun getUserAsync(context: Context, callback: (User?) -> Unit) {
        if (user != null)
            callback.invoke(user)
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

    fun onSignResult(activity: AppCompatActivity, resultCode: Int, intent: Intent) {
        val result = Auth.GoogleSignInApi.getSignInResultFromIntent(intent)
        if (result.isSuccess) {
            client.onSignInResult(activity, resultCode, intent)
        } else {
            onSignInFailed()
        }

    }

}
