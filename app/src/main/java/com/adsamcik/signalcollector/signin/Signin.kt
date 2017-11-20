package com.adsamcik.signalcollector.signin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.support.annotation.IntDef
import android.support.annotation.StringRes
import android.util.Log
import android.view.View
import android.widget.LinearLayout

import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.activities.MainActivity
import com.adsamcik.signalcollector.file.DataStore
import com.adsamcik.signalcollector.interfaces.IContextValueCallback
import com.adsamcik.signalcollector.interfaces.IValueCallback
import com.adsamcik.signalcollector.network.Network
import com.adsamcik.signalcollector.utility.Assist
import com.adsamcik.signalcollector.utility.Preferences
import com.adsamcik.signalcollector.utility.SnackMaker
import com.google.android.gms.auth.api.Auth
import com.google.android.gms.common.SignInButton
import java.lang.ref.WeakReference
import java.util.ArrayList
import kotlin.coroutines.experimental.suspendCoroutine

class Signin {

    @SigninStatus
    private var status = NOT_SIGNED

    private var signInButton: WeakReference<SignInButton>? = null
    private var signedInMenu: WeakReference<LinearLayout>? = null
    private var activityWeakReference: WeakReference<Activity>? = null

    var user: User? = null
        private set

    private val onSignedCallbackList = ArrayList<IValueCallback<User>>(2)

    private val onSignInInternal = IContextValueCallback<Context, User> { context, user ->
        this.user = user

        when {
            user == null -> updateStatus(SIGNIN_FAILED, context)
            user.isServerDataAvailable -> updateStatus(SIGNED, context)
            else -> updateStatus(SIGNED_NO_DATA, context)
        }

        callOnSigninCallbacks()
    }

    private var client: ISignInClient? = null

    private var activity: Activity?
        get() = if (activityWeakReference != null) activityWeakReference!!.get() else null
        set(activity) {
            activityWeakReference = WeakReference<Activity>(activity)
        }

    private fun initializeClient() {
        if (client == null) {
            client = if (Assist.isEmulator())
                MockSignInClient()
            else
                GoogleSignInSignalsClient()
        }
    }


    private constructor(activity: Activity, callback: IValueCallback<User>?, silent: Boolean) {
        if (callback != null)
            onSignedCallbackList.add(callback)

        instance = this

        this.activity = activity
        initializeClient()
        if (silent)
            client!!.signInSilent(activity, onSignInInternal)
        else
            client!!.signIn(activity, onSignInInternal)
    }

    private constructor(context: Context, callback: IValueCallback<User>?) {
        if (callback != null)
            onSignedCallbackList.add(callback)

        instance = this

        activityWeakReference = null
        initializeClient()
        client!!.signInSilent(context, onSignInInternal)
    }

    fun setButtons(signInButton: SignInButton, signedMenu: LinearLayout, context: Context) {
        this.signInButton = WeakReference(signInButton)
        this.signedInMenu = WeakReference(signedMenu)
        updateStatus(status, context)
    }

    private fun updateStatus(@SigninStatus signinStatus: Long, context: Context) {
        status = signinStatus
        if (signInButton != null && signedInMenu != null) {
            val signInButton = this.signInButton!!.get()
            val signedMenu = this.signedInMenu!!.get()
            if (signInButton != null && signedMenu != null) {
                when (status) {
                    SIGNED -> {
                        signedMenu.findViewById<View>(R.id.signed_in_server_menu).visibility = View.VISIBLE
                        signedMenu.visibility = View.VISIBLE
                        signedMenu.findViewById<View>(R.id.sign_out_button).setOnClickListener { v -> signout(context) }
                        signInButton.visibility = View.GONE
                    }
                    SIGNED_NO_DATA -> {
                        signedMenu.visibility = View.VISIBLE
                        signedMenu.findViewById<View>(R.id.sign_out_button).setOnClickListener { v -> signout(context) }
                        signInButton.visibility = View.GONE
                    }
                    SIGNIN_FAILED, SILENT_SIGNIN_FAILED, NOT_SIGNED -> {
                        signInButton.visibility = View.VISIBLE
                        signedMenu.visibility = View.GONE
                        signInButton.setOnClickListener { v ->
                            val a = activity
                            if (a != null) {
                                initializeClient()
                                client!!.signIn(a) { ctx, user ->
                                    this.user = user
                                    if (user != null) {
                                        if (user.isServerDataAvailable)
                                            updateStatus(SIGNED, ctx)
                                        else {
                                            updateStatus(SIGNED_NO_DATA, ctx)
                                            user.addServerDataCallback { value ->
                                                this.user = value
                                                updateStatus(SIGNED, ctx)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        signedMenu.findViewById<View>(R.id.signed_in_server_menu).visibility = View.GONE
                    }
                    SIGNIN_IN_PROGRESS -> {
                        signInButton.visibility = View.GONE
                        signedMenu.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun onSignInFailed(context: Context) {
        updateStatus(SIGNIN_FAILED, context)
        callOnSigninCallbacks()
    }

    @Synchronized private fun callOnSigninCallbacks() {
        for (c in onSignedCallbackList)
            c.callback(user)
        onSignedCallbackList.clear()
    }

    private fun onSignedOut(context: Context) {
        updateStatus(NOT_SIGNED, context)
        showSnackbar(R.string.signed_out_message)
    }

    private fun showSnackbar(@StringRes messageResId: Int) {
        val a = activity
        if (a != null && a is MainActivity)
            SnackMaker(a).showSnackbar(a.getString(messageResId))
    }

    private fun showSnackbar(message: String) {
        val a = activity
        if (a != null && a is MainActivity)
            SnackMaker(a).showSnackbar(message)
    }

    private fun signout(context: Context) {
        if (status == SIGNED || status == SIGNED_NO_DATA) {
            client!!.signOut(context)
            client = null
            user = null
            updateStatus(NOT_SIGNED, context)
            Network.clearCookieJar(context)
            Preferences.get(context).edit().remove(Preferences.PREF_USER_ID).remove(Preferences.PREF_USER_DATA).remove(Preferences.PREF_USER_STATS).remove(Preferences.PREF_REGISTERED_USER).apply()
            DataStore.delete(context, Preferences.PREF_USER_DATA)
            DataStore.delete(context, Preferences.PREF_USER_STATS)
            callOnSigninCallbacks()
        }
    }

    @IntDef(NOT_SIGNED, SIGNIN_IN_PROGRESS, SIGNED, SIGNED_NO_DATA, SILENT_SIGNIN_FAILED, SIGNIN_FAILED)
    @Retention(AnnotationRetention.SOURCE)
    annotation class SigninStatus

    companion object {
        const val NOT_SIGNED = 0L
        const val SIGNIN_IN_PROGRESS = 1L

        const val SIGNED = 2L
        const val SIGNED_NO_DATA = 3L

        const val SILENT_SIGNIN_FAILED = -1L
        const val SIGNIN_FAILED = -2L

        const val RC_SIGN_IN = 4654
        private const val ERROR_REQUEST_CODE = 3543

        private var instance: Signin? = null

        fun signin(activity: Activity, callback: IValueCallback<User>?, silentOnly: Boolean): Signin {
            if (instance == null)
                instance = Signin(activity, callback, silentOnly)
            else if (instance!!.activity == null || instance!!.status == SIGNIN_FAILED && !silentOnly) {
                if (callback != null)
                    instance!!.onSignedCallbackList.add(callback)
                instance!!.activity = activity
                if (!silentOnly && (instance!!.status == SILENT_SIGNIN_FAILED || instance!!.status == SIGNIN_FAILED))
                    instance!!.client!!.signIn(activity, instance!!.onSignInInternal)
            } else if (callback != null && instance!!.user != null)
                callback.callback(instance!!.user)

            return instance!!
        }

        private fun signin(context: Context, callback: IValueCallback<User>?): Signin? {
            if (instance == null)
            //instance is assigned in constructor to make it sooner available
                Signin(context, callback)
            else if (callback != null) {
                if (instance!!.user != null)
                    callback.callback(instance!!.user)
                else if (instance!!.status == SIGNIN_FAILED || instance!!.status == SILENT_SIGNIN_FAILED)
                    callback.callback(null)
                else
                    instance!!.onSignedCallbackList.add(callback)
            }

            return instance
        }

        fun getUserAsync(context: Context, callback: IValueCallback<User>) {
            if (instance!!.user != null)
                callback.callback(instance!!.user)
            else
                signin(context, callback)
        }

        suspend fun getUserAsync(context: Context): User? = suspendCoroutine { cont ->
            getUserAsync(context, IValueCallback { user -> cont.resume(user) })
        }

        fun getUserID(context: Context): String? =
                Preferences.get(context).getString(Preferences.PREF_USER_ID, null)

        fun removeOnSignedListeners() {
            if (instance == null)
                return
            instance!!.onSignedCallbackList.clear()
        }

        val isSignedIn: Boolean
            get() = instance != null && instance!!.user != null

        fun onSignResult(activity: Activity, resultCode: Int, intent: Intent) {
            val result = Auth.GoogleSignInApi.getSignInResultFromIntent(intent)
            if (result.isSuccess) {
                val acct = result.signInAccount!!
                instance!!.client!!.onSignInResult(activity, resultCode, intent)
            } else {
                SnackMaker(activity).showSnackbar(activity.getString(R.string.error_failed_signin))
                onSignedInFailed(activity, result.status.statusCode)
            }

        }

        private fun onSignedInFailed(context: Context, statusCode: Int) {
            val signin = signin(context, null)
            signin!!.onSignInFailed(context)
            signin.showSnackbar(context.getString(R.string.error_failed_signin, statusCode))
        }

        val isMock: Boolean
            get() {
                if (instance == null)
                    throw RuntimeException("Cannot ask if is mock before signing in")

                return instance!!.client is MockSignInClient
            }

        fun onSignOut(context: Context) {
            instance!!.onSignedOut(context)
        }
    }
}
