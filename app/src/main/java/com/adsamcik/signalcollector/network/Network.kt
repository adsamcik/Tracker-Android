package com.adsamcik.signalcollector.network

import android.content.Context
import android.os.Build
import com.adsamcik.signalcollector.enums.CloudStatuses
import com.adsamcik.signalcollector.test.useMock
import com.adsamcik.signalcollector.utility.Preferences
import com.crashlytics.android.Crashlytics
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Network class that bridges custom Server implementation and the rest of the app
 * It contains some helpful methods that take care of providing authentication information, cookies and proper settings
 */
object Network {
    private const val TAG = "SignalsNetwork"
    const val URL_AUTHENTICATE = Server.URL_AUTHENTICATE
    const val URL_DATA_UPLOAD = Server.URL_DATA_UPLOAD
    const val URL_TILES = Server.URL_TILES
    const val URL_PERSONAL_TILES = Server.URL_PERSONAL_TILES
    const val URL_USER_STATS = Server.URL_USER_STATS
    const val URL_STATS = Server.URL_STATS
    const val URL_GENERAL_STATS = Server.URL_GENERAL_STATS
    const val URL_MAPS_AVAILABLE = Server.URL_MAPS_AVAILABLE
    const val URL_FEEDBACK = Server.URL_FEEDBACK
    const val URL_USER_INFO = Server.URL_USER_INFO
    const val URL_USER_PRICES = Server.URL_USER_PRICES
    const val URL_CHALLENGES_LIST = Server.URL_CHALLENGES_LIST
    const val URL_PRIVACY_POLICY = Server.URL_PRIVACY_POLICY

    const val URL_USER_UPDATE_MAP_PREFERENCE = Server.URL_USER_UPDATE_MAP_PREFERENCE
    const val URL_USER_UPDATE_PERSONAL_MAP_PREFERENCE = Server.URL_USER_UPDATE_PERSONAL_MAP_PREFERENCE

    @CloudStatuses.CloudStatus
    var cloudStatus = CloudStatuses.UNKNOWN

    private val spec: ConnectionSpec
        get() = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3)
                .cipherSuites(CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
                        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                        CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
                        CipherSuite.TLS_DHE_RSA_WITH_AES_256_CBC_SHA)
                .build()

    /**
     * Class prepares [OkHttpClient]
     * If userToken is provided class also handles signin cookies and authentication header
     */
    fun client(userToken: String?): OkHttpClient {
        return if (userToken == null) client().build()
        else
            client()
                    .authenticator { _, response ->
                        if (response.request().header("userToken") != null)
                            return@authenticator null
                        else {
                            response.request().newBuilder().header("userToken", userToken).build()
                        }
                    }
                    .build()
    }

    private fun client(): OkHttpClient.Builder {
        return OkHttpClient.Builder()
                .connectionSpecs(listOf(spec))
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .connectTimeout(60, TimeUnit.SECONDS)
    }

    /**
     * Builds simple GET request to given [url]
     */
    fun requestGET(url: String): Request = Request.Builder().url(url).build()

    /**
     * Builds basic POST request to given [url] with given [body]
     *
     * @param url URL for which request will be created
     * @param body Body that will be put into the created request
     * @return Request
     */
    fun requestPOST(url: String, body: RequestBody?): Request.Builder = Request
            .Builder()
            .url(url)
            .post(body ?: emptyRequestBody())

    fun emptyRequestBody() = RequestBody.create(null, byteArrayOf())!!

    /**
     * Registers device on the server
     * This provides server with user id and cloud message token
     */
    fun register(context: Context, userToken: String, token: String) {
        if (!useMock)
            register(context, userToken, "token", token, Preferences.PREF_SENT_TOKEN_TO_SERVER, Server.URL_TOKEN_REGISTRATION)
    }

    private fun register(context: Context, userToken: String, valueName: String, value: String, preferencesName: String, url: String) {
        val formBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("manufacturer", Build.MANUFACTURER)
                .addFormDataPart("model", Build.MODEL)
                .addFormDataPart(valueName, value)
                .build()
        val request = requestPOST(url, formBody).build()
        client(userToken).newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Crashlytics.log("Register $preferencesName")
                Crashlytics.logException(e)
            }

            @Throws(IOException::class)
            override fun onResponse(call: Call, response: Response) {
                Preferences.getPref(context).edit().putBoolean(preferencesName, true).apply()
                response.close()
            }
        })
    }

    /**
     * Generates verification string using custom hash function
     */
    fun generateVerificationString(uid: String, length: Long?): String =
            Server.generateVerificationString(uid, length)
}