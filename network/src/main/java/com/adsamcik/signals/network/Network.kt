package com.adsamcik.signals.network

import android.content.Context
import android.os.Build
import com.adsamcik.signals.utilities.Preferences
import com.adsamcik.signals.utilities.test.useMock
import com.crashlytics.android.Crashlytics
import com.franmontiel.persistentcookiejar.PersistentCookieJar
import com.franmontiel.persistentcookiejar.cache.SetCookieCache
import com.franmontiel.persistentcookiejar.persistence.SharedPrefsCookiePersistor
import okhttp3.*
import java.io.IOException
import java.util.*

object Network {
    private const val TAG = "SignalsNetwork"
    val URL_DATA_UPLOAD = Server.URL_DATA_UPLOAD
    val URL_TILES = Server.URL_TILES
    val URL_PERSONAL_TILES = Server.URL_PERSONAL_TILES
    val URL_USER_STATS = Server.URL_USER_STATS
    val URL_STATS = Server.URL_STATS
    val URL_GENERAL_STATS = Server.URL_GENERAL_STATS
    val URL_MAPS_AVAILABLE = Server.URL_MAPS_AVAILABLE
    val URL_FEEDBACK = Server.URL_FEEDBACK
    val URL_USER_INFO = Server.URL_USER_INFO
    val URL_USER_PRICES = Server.URL_USER_PRICES
    val URL_CHALLENGES_LIST = Server.URL_CHALLENGES_LIST

    val URL_USER_UPDATE_MAP_PREFERENCE = Server.URL_USER_UPDATE_MAP_PREFERENCE
    val URL_USER_UPDATE_PERSONAL_MAP_PREFERENCE = Server.URL_USER_UPDATE_PERSONAL_MAP_PREFERENCE

    @CloudStatus
    var cloudStatus = CloudStatus.UNKNOWN

    private var cookieJar: PersistentCookieJar? = null

    private val spec: ConnectionSpec
        get() = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TlsVersion.TLS_1_2)
                .cipherSuites(CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
                        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                        CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
                        CipherSuite.TLS_DHE_RSA_WITH_AES_256_CBC_SHA)
                .build()

    private fun getCookieJar(context: Context): CookieJar {
        if (cookieJar == null)
            cookieJar = PersistentCookieJar(SetCookieCache(), SharedPrefsCookiePersistor(context))
        return cookieJar!!
    }

    fun clearCookieJar(context: Context) {
        if (cookieJar == null)
            getCookieJar(context)
        cookieJar!!.clear()
    }

    fun client(context: Context, userToken: String?): OkHttpClient {
        return if (userToken == null) client(context)
        else
            OkHttpClient.Builder()
                    .connectionSpecs(Collections.singletonList(spec))
                    .cookieJar(getCookieJar(context))
                    .authenticator({ _, response ->
                        if (response.request().header("userToken") != null)
                            return@authenticator null
                        else {
                            response.request().newBuilder().header("userToken", userToken).build()
                        }
                    }).build()
    }

    private fun client(context: Context): OkHttpClient {
        return OkHttpClient.Builder()
                .connectionSpecs(listOf(spec))
                .cookieJar(getCookieJar(context))
                .build()
    }

    fun requestGET(url: String): Request = Request.Builder().url(url).build()

    fun requestPOST(url: String, body: RequestBody): Request =
            Request.Builder().url(url).post(body).build()

    fun generateAuthBody(userToken: String): MultipartBody.Builder {
        return MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("userToken", userToken)
                .addFormDataPart("manufacturer", Build.MANUFACTURER)
                .addFormDataPart("model", Build.MODEL)
    }

    fun register(context: Context, userToken: String, token: String) {
        if (!useMock)
            register(context, userToken, "token", token, Preferences.PREF_SENT_TOKEN_TO_SERVER, Server.URL_TOKEN_REGISTRATION)
    }

    private fun register(context: Context, userToken: String, valueName: String, value: String, preferencesName: String, url: String) {
        val formBody = generateAuthBody(userToken)
                .addFormDataPart(valueName, value)
                .build()
        val request = requestPOST(url, formBody)
        client(context, userToken).newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Crashlytics.log("Register " + preferencesName)
                Crashlytics.logException(e)
            }

            @Throws(IOException::class)
            override fun onResponse(call: Call, response: Response) {
                Preferences.getPref(context).edit().putBoolean(preferencesName, true).apply()
                response.close()
            }
        })
    }

    fun generateVerificationString(uid: String, length: Long?): String =
            Server.generateVerificationString(uid, length)
}