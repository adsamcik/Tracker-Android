package com.adsamcik.signalcollector.network

import android.content.Context
import androidx.core.content.edit
import com.adsamcik.signalcollector.signin.Signin
import com.adsamcik.signalcollector.utility.Preferences
import kotlinx.coroutines.experimental.launch
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import kotlin.coroutines.experimental.suspendCoroutine

object Jwt {
    const val tokenPreference = "jwtToken"

    suspend fun refreshToken(context: Context) = suspendCoroutine<JwtData?> {
        launch {
            val user = Signin.getUserAsync(context)

            if (user == null) {
                it.resume(null)
                return@launch
            }

            it.resume(refreshToken(context, user.token))
        }
    }

    @Synchronized
    suspend fun refreshToken(context: Context, userToken: String): JwtData? {
        return requestToken(context, userToken)
    }

    fun getTokenLocal(context: Context): String? = Preferences.getPref(context).getString(tokenPreference, null)

    suspend fun getToken(context: Context): String? = getToken(context)
            ?: refreshToken(context)?.token

    suspend fun getToken(context: Context, userToken: String): String? = getToken(context)
            ?: refreshToken(context, userToken)?.token

    @Synchronized
    suspend fun requestToken(context: Context, userToken: String): JwtData? = suspendCoroutine {
        val client = Network.clientAuth(context)
        val networkInterface = Retrofit.Builder().client(client).baseUrl(Network.URL_BASE).addConverterFactory(MoshiConverterFactory.create()).build().create(NetworkInterface::class.java)

        val call = networkInterface.authenticate(userToken)
        call.enqueue(object : retrofit2.Callback<JwtData> {
            override fun onFailure(call: retrofit2.Call<JwtData>?, t: Throwable?) {
                it.resume(null)
            }

            override fun onResponse(call: retrofit2.Call<JwtData>?, response: retrofit2.Response<JwtData>) {
                val data = response.body()
                if (data != null)
                    Preferences.getPref(context).edit { putString(tokenPreference, data.token) }
                it.resume(data)
            }

        })
    }
}