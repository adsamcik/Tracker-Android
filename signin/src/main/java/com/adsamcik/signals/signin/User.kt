package com.adsamcik.signals.signin

import com.adsamcik.signals.utilities.Constants.DAY_IN_MILLISECONDS
import com.adsamcik.signals.utilities.test.useMock
import com.google.gson.*
import java.lang.reflect.Type
import java.util.*

class User(val id: String, val token: String) {

    var wirelessPoints: Long = 0
        private set

    var networkInfo: NetworkInfo? = null
        private set
    var networkPreferences: NetworkPreferences? = null
        private set

    private var callbackList: MutableList<(User) -> Unit>? = null

    val isServerDataAvailable: Boolean
        get() = networkInfo != null && networkPreferences != null

    fun addWirelessPoints(value: Long) {
        wirelessPoints += value
    }

    fun deserializeServerData(json: String) {
        val gson = GsonBuilder().registerTypeAdapter(User::class.java, ServerUserDeserializer(this)).create()
        gson.fromJson(json, User::class.java)
    }

    internal fun setServerData(wirelessPoints: Long, networkInfo: NetworkInfo, networkPreferences: NetworkPreferences) {
        this.wirelessPoints = wirelessPoints
        this.networkInfo = networkInfo
        this.networkPreferences = networkPreferences

        if (callbackList != null) {
            for (cb in callbackList!!)
                cb.invoke(this)
            callbackList = null
        }
    }

    internal fun mockServerData() {
        if (useMock && !BuildConfig.DEBUG)
            throw RuntimeException("Cannot mock server data on production version")
        wirelessPoints = Math.abs(System.currentTimeMillis() * System.currentTimeMillis() % 64546)
        val networkPreferences = NetworkPreferences()
        networkPreferences.renewMap = true
        networkPreferences.renewPersonalMap = false

        val networkInfo = NetworkInfo()

        networkInfo.feedbackAccess = false
        networkInfo.mapAccessUntil = System.currentTimeMillis() + DAY_IN_MILLISECONDS
        networkInfo.personalMapAccessUntil = 0

        this.networkInfo = networkInfo
        this.networkPreferences = networkPreferences
    }

    fun addServerDataCallback(callback: (User) -> Unit) {
        if (isServerDataAvailable)
            callback.invoke(this)
        else {
            if (callbackList == null)
                callbackList = ArrayList()
            callbackList!!.add(callback)
        }
    }

    inner class NetworkInfo {
        var mapAccessUntil: Long = 0
        var personalMapAccessUntil: Long = 0
        var feedbackAccess: Boolean = false
        var uploadAccess: Boolean = false


        fun hasMapAccess(): Boolean = System.currentTimeMillis() < mapAccessUntil

        fun hasPersonalMapAccess(): Boolean = System.currentTimeMillis() < personalMapAccessUntil
    }

    inner class NetworkPreferences {
        var renewMap: Boolean = false
        var renewPersonalMap: Boolean = false
    }

    private inner class ServerUserDeserializer constructor(private val user: User) : JsonDeserializer<User> {

        @Throws(JsonParseException::class)
        override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): User {
            val `object` = json.asJsonObject
            val wirelessPoints = `object`.get("wirelessPoints").asLong
            val networkInfo = context.deserialize(`object`.get("networkInfo"), NetworkInfo::class.java) as NetworkInfo
            val networkPreferences = context.deserialize(`object`.get("networkPreferences"), NetworkPreferences::class.java) as NetworkPreferences
            user.setServerData(wirelessPoints, networkInfo, networkPreferences)
            return user
        }
    }

}
