package com.adsamcik.signalcollector.network

import android.content.Context
import androidx.core.content.edit
import com.adsamcik.signalcollector.signin.Signin
import com.adsamcik.signalcollector.utility.Preferences
import com.squareup.moshi.Moshi
import kotlinx.coroutines.experimental.launch
import retrofit2.Retrofit
import kotlin.coroutines.experimental.suspendCoroutine

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

    suspend fun refreshToken(context: Context, googleToken: String): JwtData? {
        return requestToken(context, googleToken)
    }

    fun getToken(context: Context): String? = Preferences.getPref(context).getString(tokenPreference, null)

    suspend fun requestToken(context: Context, googleToken: String): JwtData? = suspendCoroutine {
        val client = Network.client(googleToken)
        val networkInterface = Retrofit.Builder().client(client).build().create(NetworkInterface::class.java)

        val call = networkInterface.authenticate(googleToken)
        call.enqueue(object : retrofit2.Callback<JwtData> {
            override fun onFailure(call: retrofit2.Call<JwtData>?, t: Throwable?) {
                it.resume(null)
            }

            override fun onResponse(call: retrofit2.Call<JwtData>?, response: retrofit2.Response<JwtData>) {
                val data = response.body()
                val jAdapter = Moshi.Builder().build().adapter(JwtData::class.java)
                val serializedToken = jAdapter.toJson(data)
                Preferences.getPref(context).edit { putString(tokenPreference, serializedToken) }
                it.resume(data)
            }

        })
    }
}