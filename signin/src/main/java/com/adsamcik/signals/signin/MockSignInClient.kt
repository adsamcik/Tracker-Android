package com.adsamcik.signals.signin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.adsamcik.signals.signin.signin.ISignInClient
import com.adsamcik.signals.signin.signin.User
import com.adsamcik.signalcollector.utility.Preferences


class MockSignInClient : ISignInClient {
    private var u: User? = null
    private var userValueCallback: ((Context, User?) -> Unit)? = null

    override fun signIn(activity: Activity, userValueCallback: (Context, User?) -> Unit) {
        signInSilent(activity, userValueCallback)
    }

    override fun signInSilent(context: Context, userValueCallback: (Context, User?) -> Unit) {
        if (u != null) {
            userValueCallback.invoke(context, u)
            return
        }

        val state = (System.currentTimeMillis() % 4).toInt()
        Log.d("MockSigninSignals", "State " + state)
        if (state == 2) {
            userValueCallback.invoke(context, null)
            return
        }

        val user = User("MOCKED", "BLEH")
        Preferences.getPref(context).edit().putString(Preferences.PREF_USER_ID, user.id).apply()
        when (state) {
            0 -> user.mockServerData()
            1 -> {
                //server data received later on
                if (Looper.myLooper() == null)
                    Looper.prepare()
                Handler().postDelayed({ user.mockServerData() }, 2000 + System.currentTimeMillis() % 6000)
            }
            3 -> {
            }
        }//no server data received

        userValueCallback.invoke(context, user)
        u = user
        this.userValueCallback = userValueCallback
    }

    override fun signOut(context: Context) {
        userValueCallback!!.invoke(context, null)
        u = null
    }

    override fun onSignInResult(activity: Activity, resultCode: Int, data: Intent) {
        //do nothing
    }

}
