package com.adsamcik.signalcollector.signin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.adsamcik.signalcollector.interfaces.IContextValueCallback
import com.adsamcik.signalcollector.utility.Preferences


class MockSignInClient : ISignInClient {
    private var u: User? = null

    override fun signIn(activity: Activity, userValueCallback: IContextValueCallback<Context, User>) {
        signInSilent(activity, userValueCallback)
    }

    override fun signInSilent(context: Context, userValueCallback: IContextValueCallback<Context, User>) {
        if (u != null) {
            userValueCallback.callback(context, u)
            return
        }

        val left = (System.currentTimeMillis() % 4).toInt()
        Log.d("MockSigninSignals", "State " + left)
        if (left == 2) {
            userValueCallback.callback(context, null)
            return
        }

        val user = User("MOCKED", "BLEH")
        Preferences.get(context).edit().putString(Preferences.PREF_USER_ID, user.id).apply()
        when (left) {
            0 -> user.mockServerData()
            1 -> {
                //server data received later on
                Looper.prepare()
                Handler().postDelayed({ user.mockServerData() }, 2000 + System.currentTimeMillis() % 6000)
            }
            3 -> {
            }
        }//no server data received

        userValueCallback.callback(context, user)
        u = user
    }

    override fun signOut(context: Context) {
        u = null
    }

    override fun onSignInResult(activity: Activity, resultCode: Int, data: Intent) {
        //do nothing
    }

}
