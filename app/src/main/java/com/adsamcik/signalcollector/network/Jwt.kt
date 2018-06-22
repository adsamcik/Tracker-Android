package com.adsamcik.signalcollector.network

import android.content.Context
import com.adsamcik.signalcollector.signin.Signin
import com.adsamcik.signalcollector.utility.Preferences
import kotlinx.coroutines.experimental.launch
import okhttp3.RequestBody
import kotlin.coroutines.experimental.suspendCoroutine
import com.adsamcik.signalcollector.signin.User
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import retrofit2.Retrofit
import java.io.IOException

object Jwt {
    const val tokenPreference = "jwtToken"

    suspend fun refreshToken(context: Context) = suspendCoroutine<String?> {
        launch {
            val user = Signin.getUserAsync(context)

            if (user == null) {
                it.resume(null)
                return@launch
            }


        }
    }

    fun refreshToken(context: Context, userToken: String): String? {

    }

    fun getToken(context: Context): String? = Preferences.getPref(context).getString(tokenPreference, null)

    fun requestToken(context: Context, user: User): JwtData {
        val client = Network.client(user.token)

        val request = Network.requestPOST(Network.URL_AUTHENTICATE, Network.emptyRequestBody())
        client.newCall(request.build()).enqueue(object :Callback {
            override fun onFailure(call: Call?, e: IOException?) {
                TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
            }

            override fun onResponse(call: Call?, response: Response?) {

            }

        })
    }

    class Token

}