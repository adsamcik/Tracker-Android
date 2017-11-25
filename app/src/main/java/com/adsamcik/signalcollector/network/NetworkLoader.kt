package com.adsamcik.signalcollector.network

import android.content.Context
import android.util.Pair
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.file.CacheStore
import com.adsamcik.signalcollector.signin.Signin
import com.adsamcik.signalcollector.test.useMock
import com.adsamcik.signalcollector.utility.Assist
import com.adsamcik.signalcollector.utility.Constants.MINUTE_IN_MILLISECONDS
import com.adsamcik.signalcollector.utility.Parser
import com.adsamcik.signalcollector.utility.Preferences
import com.google.firebase.crash.FirebaseCrash
import kotlinx.coroutines.experimental.launch
import okhttp3.*
import java.io.IOException
import kotlin.coroutines.experimental.suspendCoroutine

object NetworkLoader {
    /**
     * Loads json from the web and converts it to java object
     *
     * @param url                 URL
     * @param updateTimeInMinutes Update time in minutes (if last update was in less minutes, file will be loaded from cache)
     * @param context             Context
     * @param preferenceString    Name of the lastUpdate in sharedPreferences, also is used as file name + '.json'
     * @param tClass              Class of the type
     * @param callback            Callback which is called when the result is ready
     * @param <T>                 Value type
    </T> */
    fun <T> request(url: String, updateTimeInMinutes: Int, context: Context, preferenceString: String, tClass: Class<T>, callback: (Source, T?) -> Unit) {
        requestString(Network.client(context, null),
                Request.Builder().url(url).build(),
                updateTimeInMinutes,
                context,
                preferenceString, { src, value -> callback.invoke(src, Parser.tryFromJson(value, tClass)) })
    }

    /**
     * Loads json from the web and converts it to java object
     *
     * @param url                 URL
     * @param updateTimeInMinutes Update time in minutes (if last update was in less minutes, file will be loaded from cache)
     * @param context             Context
     * @param preferenceString    Name of the lastUpdate in sharedPreferences, also is used as file name + '.json'
     * @param tClass              Class of the type
     * @param callback            Callback which is called when the result is ready
     * @param <T>                 Value type
    </T> */
    fun <T> requestSigned(url: String, updateTimeInMinutes: Int, context: Context, preferenceString: String, tClass: Class<T>, callback: (Source, T?) -> Unit) {
        Signin.getUserAsync(context, { user ->
            if (user != null)
                requestString(Network.client(context, user.token),
                        Request.Builder().url(url).build(),
                        updateTimeInMinutes,
                        context,
                        preferenceString, { src, value -> callback.invoke(src, Parser.tryFromJson(value, tClass)) })
            else
                callback.invoke(Source.NO_DATA_FAILED_SIGNIN, null)
        })
    }

    /**
     * Loads json from the web and converts it to java object
     *
     * @param url                 URL
     * @param updateTimeInMinutes Update time in minutes (if last update was in less minutes, file will be loaded from cache)
     * @param context             Context
     * @param preferenceString    Name of the lastUpdate in sharedPreferences, also is used as file name + '.json'
     * @param tClass              Class of the type
     * @param <T>                 Value type
    </T> */
    suspend fun <T> requestSignedAsync(url: String, updateTimeInMinutes: Int, context: Context, preferenceString: String, tClass: Class<T>): Pair<Source, T?> = suspendCoroutine { cont ->
        launch {
            val user = Signin.getUserAsync(context)
            if (user != null) {
                if (useMock)
                    cont.resume(Pair(if (System.currentTimeMillis() % 2 == 0L) Source.CACHE else Source.NETWORK, tClass.newInstance()))
                else
                    requestString(Network.client(context, user.token),
                            Request.Builder().url(url).build(),
                            updateTimeInMinutes,
                            context,
                            preferenceString, { src, value -> cont.resume(Pair(src, Parser.tryFromJson(value, tClass))) })
            } else
                cont.resume(Pair(Source.NO_DATA_FAILED_SIGNIN, null))
        }
    }

    /**
     * Method which loads string from the web or cache
     *
     * @param url                 URL
     * @param updateTimeInMinutes Update time in minutes (if last update was in less minutes, file will be loaded from cache)
     * @param context             Context
     * @param preferenceString    Name of the lastUpdate in sharedPreferences, also is used as file name + '.json'
     */
    suspend fun requestStringSignedAsync(url: String, updateTimeInMinutes: Int, context: Context, preferenceString: String): Pair<Source, String?> = suspendCoroutine { cont ->
        launch {
            val user = Signin.getUserAsync(context)
            if (user != null)
                requestString(Network.client(context, user.token), Request.Builder().url(url).build(), updateTimeInMinutes, context, preferenceString, { source, string ->
                    cont.resume(Pair(source, string))
                })
            else
                cont.resume(Pair(Source.NO_DATA_FAILED_SIGNIN, null))
        }
    }

    /**
     * Method which loads string from the web or cache
     *
     * @param url                 URL
     * @param updateTimeInMinutes Update time in minutes (if last update was in less minutes, file will be loaded from cache)
     * @param context             Context
     * @param preferenceString    Name of the lastUpdate in sharedPreferences, also is used as file name + '.json'
     * @param callback            Callback which is called when the result is ready
     */
    fun requestStringSigned(url: String, updateTimeInMinutes: Int, context: Context, preferenceString: String, callback: (Source, String?) -> Unit) {
        Signin.getUserAsync(context, { user ->
            if (user != null)
                requestString(Network.client(context, user.token), Request.Builder().url(url).build(), updateTimeInMinutes, context, preferenceString, callback)
            else
                callback.invoke(Source.NO_DATA_FAILED_SIGNIN, null)
        })
    }

    private fun callbackNoData(context: Context, preferenceString: String, callback: (Source, String?) -> Unit, lastUpdate: Long, returnCode: Int) {
        when {
            returnCode == 403 -> callback.invoke(Source.NO_DATA_FAILED_SIGNIN, null)
            lastUpdate == -1L -> callback.invoke(Source.NO_DATA, null)
            returnCode < 0 -> callback.invoke(Source.CACHE_NO_INTERNET, CacheStore.loadString(context, preferenceString))
            else -> callback.invoke(Source.CACHE_INVALID_DATA, CacheStore.loadString(context, preferenceString))
        }
    }

    /**
     * Method to requestPOST string from server.
     *
     * @param request             requestPOST data
     * @param updateTimeInMinutes Update time in minutes (if last update was in less minutes, file will be loaded from cache)
     * @param ctx                 Context
     * @param preferenceString    Name of the lastUpdate in sharedPreferences, also is used as file name + '.json'
     * @param callback            Callback which is called when the result is ready
     */
    private fun requestString(client: OkHttpClient, request: Request, updateTimeInMinutes: Int, ctx: Context, preferenceString: String, callback: (Source, String?) -> Unit) {
        val context = ctx.applicationContext
        val lastUpdate = Preferences.getPref(context).getLong(preferenceString, -1)
        if (System.currentTimeMillis() - lastUpdate > updateTimeInMinutes * MINUTE_IN_MILLISECONDS || lastUpdate == -1L || !CacheStore.exists(context, preferenceString)) {
            if (!Assist.hasNetwork(context)) {
                callbackNoData(context, preferenceString, callback, lastUpdate, -1)
                return
            }

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    callbackNoData(context, preferenceString, callback, lastUpdate, -1)

                    FirebaseCrash.log("Load " + preferenceString)
                    FirebaseCrash.report(e)
                }

                @Throws(IOException::class)
                override fun onResponse(call: Call, response: Response) {
                    val returnCode = response.code()
                    if (!response.isSuccessful) {
                        callbackNoData(context, preferenceString, callback, lastUpdate, returnCode)
                        return
                    }

                    val body = response.body()
                    if (body == null) {
                        callbackNoData(context, preferenceString, callback, lastUpdate, returnCode)
                        return
                    }

                    val json = body.string()

                    if (json.isEmpty()) {
                        callbackNoData(context, preferenceString, callback, lastUpdate, returnCode)
                    } else {
                        Preferences.getPref(context).edit().putLong(preferenceString, System.currentTimeMillis()).apply()
                        CacheStore.saveString(context, preferenceString, json, false)
                        callback.invoke(Source.NETWORK, json)
                    }
                }
            })
        } else
            callback.invoke(Source.CACHE, CacheStore.loadString(context, preferenceString))
    }

    enum class Source {
        CACHE,
        NETWORK,
        CACHE_NO_INTERNET,
        CACHE_CONNECTION_FAILED,
        CACHE_INVALID_DATA,
        NO_DATA,
        NO_DATA_FAILED_SIGNIN;

        val success: Boolean
            get() = this.ordinal <= 1

        val dataAvailable: Boolean
            get() = this.ordinal <= 4

        fun toString(context: Context): String = when (this) {
            CACHE_CONNECTION_FAILED -> context.getString(R.string.error_connection_failed)
            CACHE_NO_INTERNET -> context.getString(R.string.error_no_internet)
            CACHE_INVALID_DATA -> context.getString(R.string.error_invalid_data)
            NO_DATA -> context.getString(R.string.error_no_data)
            NO_DATA_FAILED_SIGNIN -> context.getString(R.string.error_failed_signin)
            else -> "---"
        }
    }
}
