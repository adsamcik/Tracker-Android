package com.adsamcik.signalcollector.signin


import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.network.Network
import com.adsamcik.signalcollector.network.NetworkLoader
import com.adsamcik.signalcollector.utility.Preferences
import com.google.android.gms.auth.api.Auth
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.OnCompleteListener
import com.google.firebase.iid.FirebaseInstanceId

class GoogleSignInSignalsClient : ISignInClient {
    private var client: GoogleSignInClient? = null
    private var user: User? = null

    private var userValueCallback: ((Context, User?) -> Unit)? = null

    private fun getOptions(context: Context): GoogleSignInOptions {
        return GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(context.getString(R.string.server_client_id))
                .requestId()
                .build()
    }

    private fun silentSignInInternal(onCompleteListener: OnCompleteListener<GoogleSignInAccount>) {
        if (client == null)
            throw RuntimeException("Client is null")

        val task = client!!.silentSignIn()
        if (task.isSuccessful) {
            onCompleteListener.onComplete(task)
        } else {
            task.addOnCompleteListener(onCompleteListener)
        }
    }

    override fun signIn(activity: AppCompatActivity, userValueCallback: (Context, User?) -> Unit) {
        client = GoogleSignIn.getClient(activity, getOptions(activity))
        this.userValueCallback = userValueCallback

        val onCompleteListener = OnCompleteListener<GoogleSignInAccount> { task ->
            try {
                user = resolveUser(activity, task.getResult(ApiException::class.java)!!)
                userValueCallback.invoke(activity, user!!)
            } catch (e: ApiException) {
                val signInIntent = client!!.signInIntent
                activity.startActivityForResult(signInIntent, Signin.RC_SIGN_IN)
            }
        }
        silentSignInInternal(onCompleteListener)
    }

    override fun signInSilent(context: Context, userValueCallback: (Context, User?) -> Unit) {
        client = GoogleSignIn.getClient(context, getOptions(context))
        this.userValueCallback = userValueCallback
        val onCompleteListener = OnCompleteListener<GoogleSignInAccount> { task ->
            try {
                user = resolveUser(context, task.getResult(ApiException::class.java)!!)
            } catch (e: ApiException) {
                userValueCallback.invoke(context, null)
            }
        }
        silentSignInInternal(onCompleteListener)
    }

    override fun signOut(context: Context) {
        client!!.signOut().addOnCompleteListener {
            Preferences.getPref(context).edit().remove(Preferences.PREF_USER_ID).apply()
            userValueCallback?.invoke(context, null)
        }
    }

    private fun resolveUser(context: Context, account: GoogleSignInAccount): User {
        val user = User(account.id!!, account.idToken!!)

        Preferences.getPref(context).edit().putString(Preferences.PREF_USER_ID, user.id).apply()

        userValueCallback?.invoke(context, user)


        //todo uncomment this when server is ready
        //SharedPreferences sp = Preferences.getPref(context);
        //if (!sp.getBoolean(Preferences.PREF_SENT_TOKEN_TO_SERVER, false)) {
        FirebaseInstanceId.getInstance().instanceId.continueWith {
            if (it.isSuccessful) {
                val result = it.result!!
                Network.register(context, user.token, result.token)
            }
        }
        //}

        NetworkLoader.requestSigned(Network.URL_USER_INFO, user.token, 10, context, Preferences.PREF_USER_DATA, UserData::class.java) { state, value ->
            if (state.dataAvailable && value != null) {
                user.setData(value)
            } else
                user.setData(null)
        }

        return user
    }

    override fun onSignInResult(activity: AppCompatActivity, resultCode: Int, data: Intent) {
        val result = Auth.GoogleSignInApi.getSignInResultFromIntent(data)
        if (result.isSuccess) {
            val acct = result.signInAccount!!
            user = resolveUser(activity, acct)
        } else
            userValueCallback?.invoke(activity, null)
    }
}
