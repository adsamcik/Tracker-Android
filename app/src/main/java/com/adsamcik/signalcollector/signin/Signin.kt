package com.adsamcik.signalcollector.signin

import android.content.Context
import android.content.Intent
import android.support.v7.app.AppCompatActivity
import com.adsamcik.signalcollector.file.DataStore
import com.adsamcik.signalcollector.network.Network
import com.adsamcik.signalcollector.test.MockSignInClient
import com.adsamcik.signalcollector.test.useMock
import com.adsamcik.signalcollector.utility.Preferences
import com.google.android.gms.auth.api.Auth
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.coroutines.experimental.suspendCoroutine

/**
 * Singleton that manages user
 *
 * todo Improve so that Signin is always called only from UserActivity. This should make sure that there are fewer breaking points in Signin.
 */
object Signin {
    private val statusLock = ReentrantLock()

    /**
     * Provides instance object for the current user. If user is not signed in, null is returned.
     */
    //todo Rework to LiveData so changes are properly reflected in the UI on each user sign-in or sign-out.
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

    private fun updateStatus(signinStatus: SigninStatus) {
        this.status = signinStatus
        onStateChangeCallback?.invoke(signinStatus, user)
    }

    /**
     * Needs to be called when Sign in fails
     */
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

    /**
     * Enum with Signin states
     */
    enum class SigninStatus(val value: Int) {
        NOT_SIGNED(0) {
            override val failed: Boolean get() = false
            override val success: Boolean get() = false
        },
        SIGNIN_IN_PROGRESS(1) {
            override val failed: Boolean get() = false
            override val success: Boolean get() = false
        },
        SIGNED(2) {
            override val failed: Boolean get() = false
            override val success: Boolean get() = true
        },
        SIGNED_NO_DATA(3) {
            override val failed: Boolean get() = false
            override val success: Boolean get() = true
        },
        SILENT_SIGNIN_FAILED(4) {
            override val failed: Boolean get() = true
            override val success: Boolean get() = false
        },
        SIGNIN_FAILED(5) {
            override val failed: Boolean get() = true
            override val success: Boolean get() = false
        };


        /**
         * Returns true if sign-in failed.
         * State [NOT_SIGNED] will return false.
         */
        abstract val failed: Boolean

        /**
         * Returns true if user is signed-in.
         */
        abstract val success: Boolean
    }

    /**
     * Request code when Google request for result is called.
     */
    const val RC_SIGN_IN = 4654

    private val onSignedCallbackList = ArrayList<(User?) -> Unit>(2)

    /**
     * On state changed callback.
     * todo Replace this with live data.
     */
    var onStateChangeCallback: ((SigninStatus, User?) -> Unit)? = null
        set(value) {
            value?.invoke(status, user)
            field = value
        }

    /**
     * Returns true if user is signed-in
     */
    val isSignedIn: Boolean
        get() = status.success

    var status: SigninStatus = SigninStatus.NOT_SIGNED

    /**
     * Attempts to sign in the user. Based on [silentOnly] it is decided whether user should see any window or not.
     *
     * @param activity Activity (needs to implement proper callbacks).
     * @param callback Callback that is called once final state is decided.
     * @param silentOnly Should the sign-in be done silently.
     */
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

    /**
     * Attempts to sign-in the user silently.
     *
     * @param context Context
     * @param callback Callback that is called once final state is decided.
     */
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

    private fun addCallback(callback: ((User?) -> Unit)?) {
        if (callback != null)
            onSignedCallbackList.add(callback)
    }


    /**
     * Signs the user in. Uses suspend function so no callback is required.
     *
     * @param activity Activity
     * @param silentOnly
     */
    suspend fun signIn(activity: AppCompatActivity, silentOnly: Boolean): User? {
        return suspendCoroutine { cont ->
            signIn(activity, { cont.resume(it) }, silentOnly)
        }
    }

    /**
     * Signs the user out
     */
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

    /**
     * Returns user id from persistent storage.
     * This might not reflect actual users state, because updating persistent storage can take some extra milliseconds.
     */
    fun getUserID(context: Context): String? =
            Preferences.getPref(context).getString(Preferences.PREF_USER_ID, null)

    /**
     * Dangerous method. Will no longer be needed once callbacks are migrated to liveData.
     */
    fun removeOnSignedListeners() {
        onSignedCallbackList.clear()
    }

    /**
     * Needs to be called when [AppCompatActivity.onActivityResult] is called in activity that initialized this.
     */
    fun onSignResult(activity: AppCompatActivity, resultCode: Int, intent: Intent) {
        val result = Auth.GoogleSignInApi.getSignInResultFromIntent(intent)
        if (result.isSuccess) {
            client.onSignInResult(activity, resultCode, intent)
        } else {
            onSignInFailed()
        }

    }

}
