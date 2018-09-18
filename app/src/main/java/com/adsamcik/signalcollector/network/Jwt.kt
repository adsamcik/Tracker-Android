package com.adsamcik.signalcollector.network

import android.content.Context
import androidx.core.content.edit
import com.adsamcik.signalcollector.signin.Signin
import com.adsamcik.signalcollector.utility.Constants
import com.adsamcik.signalcollector.utility.Preferences
import kotlinx.coroutines.experimental.Dispatchers
import kotlinx.coroutines.experimental.GlobalScope
import kotlinx.coroutines.experimental.launch
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import kotlin.coroutines.experimental.suspendCoroutine

object Jwt {
    const val tokenPreference = "jwtToken"

    var lastRefresh = 0L
    var hasToken = false

    suspend fun refreshToken(context: Context) = suspendCoroutine<JwtData?> {
        GlobalScope.launch(Dispatchers.Default, CoroutineStart.DEFAULT, null, {
            val user = Signin.getUserAsync(context)

            if (user == null) {
                it.resume(null)
                return@launch
            }

            it.resume(refreshToken(context, user.token))
        })
    }

    @Synchronized
    suspend fun refreshToken(context: Context, userToken: String): JwtData? {
        return requestToken(context, userToken)
    }

    fun getTokenLocal(context: Context): String? = Preferences.getPref(context).getString(tokenPreference, null)

    suspend fun getToken(context: Context): String? = getTokenLocal(context)
            ?: refreshToken(context)?.token

    suspend fun getToken(context: Context, userToken: String): String? = getTokenLocal(context)
            ?: refreshToken(context, userToken)?.token

    @Synchronized
    suspend fun requestToken(context: Context, userToken: String): JwtData? = suspendCoroutine {

        if (hasToken) {
            if (System.currentTimeMillis() - lastRefresh < Constants.MINUTE_IN_MILLISECONDS) {
                it.resume(JwtData(getTokenLocal(context)!!))
                return@suspendCoroutine
            }
        } else {
            if (System.currentTimeMillis() - lastRefresh < Constants.SECOND_IN_MILLISECONDS) {
                val token = getTokenLocal(context)
                if (token != null)
                    it.resume(JwtData(token))
                else
                    it.resume(null)
                return@suspendCoroutine
            }
        }

        val client = Network.clientAuth(context)
        val networkInterface = Retrofit.Builder().client(client).baseUrl(Network.URL_BASE).addConverterFactory(MoshiConverterFactory.create()).build().create(NetworkInterface::class.java)

        val call = networkInterface.authenticate(userToken)
        call.enqueue(object : retrofit2.Callback<JwtData> {
            override fun onFailure(call: retrofit2.Call<JwtData>?, t: Throwable?) {
                lastRefresh = System.currentTimeMillis()
                hasToken = false
                it.resume(null)
            }

            override fun onResponse(call: retrofit2.Call<JwtData>?, response: retrofit2.Response<JwtData>) {
                val data = response.body()
                if (data != null)
                    Preferences.getPref(context).edit { putString(tokenPreference, data.token) }
                lastRefresh = System.currentTimeMillis()
                hasToken = true
                it.resume(data)
            }

        })
    }
}