package com.adsamcik.signalcollector.network

import android.content.Context
import android.os.Build
import com.adsamcik.signalcollector.enums.CloudStatuses
import com.adsamcik.signalcollector.extensions.addBearer
import com.adsamcik.signalcollector.extensions.hasAuthorizationToken
import com.adsamcik.signalcollector.test.useMock
import com.adsamcik.signalcollector.utility.Preferences
import com.crashlytics.android.Crashlytics
import kotlinx.coroutines.experimental.runBlocking
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Network class that bridges custom Server implementation and the rest of the app
 * It contains some helpful methods that take care of providing authentication information, cookies and proper settings
 */
object Network {
    private const val TAG = "SignalsNetwork"
    const val URL_BASE = Server.URL_WEB
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
        get() = ConnectionSpec.Builder(ConnectionSpec.CLEARTEXT)
                //.tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3)
                /*.cipherSuites(CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
                        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                        CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
                        CipherSuite.TLS_DHE_RSA_WITH_AES_256_CBC_SHA)*/
                .build()

    var client: OkHttpClient? = null
    var userToken: String? = null
    var triedAuth: Boolean = false


    /**
     * Class prepares [OkHttpClient]
     * If userToken is provided class also handles signin cookies and authentication header
     */
    @Synchronized
    fun clientAuth(context: Context, userToken: String? = null): OkHttpClient {
        if (Network.userToken != userToken || client == null) {
            client = client()
                    .authenticator { _, response -> authenticate(context, response) }
                    .build()
            this.userToken = userToken
            triedAuth = false
        }
        return client!!
    }

    private fun authenticate(context: Context, response: Response): Request? {
        val tokenRejected = response.request().header("Authorization") != null
        var tokenCoroutine: String? = null
        runBlocking {
            tokenCoroutine = if (tokenRejected) {
                if (userToken == null)
                    Jwt.refreshToken(context)?.token
                else
                    Jwt.refreshToken(context, userToken!!)?.token
            } else
                Jwt.getToken(context)
        }
        val request = response.request()
        val token = tokenCoroutine
        return if (token != null && !request.hasAuthorizationToken(token)) {
            triedAuth = true
            request.newBuilder().removeHeader("Authorization").addBearer(token).build()
        } else
            null
    }

    private fun client(): OkHttpClient.Builder {
        return OkHttpClient.Builder()
                .connectionSpecs(listOf(spec, ConnectionSpec.CLEARTEXT))
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .connectTimeout(60, TimeUnit.SECONDS)
    }

    /**
     * Builds simple GET request to given [url]
     */
    fun requestGET(url: String): Request = Request.Builder()
            .url(url)
            .build()

    /**
     * Builds simple GET request to given [url]
     */
    fun requestGET(context: Context, url: String): Request = Request.Builder()
            .addBearer(Jwt.getTokenLocal(context))
            .url(url)
            .build()

    /**
     * Builds basic POST request to given [url] with given [body]
     *
     * @param context Context required for getting bearer
     * @param url URL for which request will be created
     * @param body Body that will be put into the created request
     * @return Request
     */
    fun requestPOST(context: Context, url: String, body: RequestBody?): Request.Builder = Request
            .Builder()
            .url(url)
            .addBearer(Jwt.getTokenLocal(context))
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
        val request = requestPOST(context, url, formBody).build()
        clientAuth(context, userToken).newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Crashlytics.log("Register $preferencesName")
                Crashlytics.logException(e)
            }

            @Throws(IOException::class)
            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    Preferences.getPref(context).edit().putBoolean(preferencesName, true).apply()
                }
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
