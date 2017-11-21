package com.adsamcik.signalcollector.signin

import com.adsamcik.signalcollector.BuildConfig
import com.adsamcik.signalcollector.interfaces.INonNullValueCallback
import com.adsamcik.signalcollector.utility.Assist
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

    private var callbackList: MutableList<INonNullValueCallback<User>>? = null

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
                cb.callback(this)
            callbackList = null
        }
    }

    internal fun mockServerData() {
        if (!Assist.isEmulator && !BuildConfig.DEBUG)
            throw RuntimeException("Cannot mock server data on production version")
        wirelessPoints = Math.abs(System.currentTimeMillis() * System.currentTimeMillis() % 64546)
        networkPreferences = NetworkPreferences()
        networkPreferences!!.renewMap = true
        networkPreferences!!.renewPersonalMap = false

        networkInfo = NetworkInfo()

        networkInfo!!.feedbackAccess = false
        networkInfo!!.mapAccessUntil = System.currentTimeMillis()
        networkInfo!!.personalMapAccessUntil = 0

    }

    fun addServerDataCallback(callback: INonNullValueCallback<User>) {
        if (isServerDataAvailable)
            callback.callback(this)
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
            val networkInfo = context.deserialize<User.NetworkInfo>(`object`.get("networkInfo"), User.NetworkInfo::class.java)
            val networkPreferences = context.deserialize<User.NetworkPreferences>(`object`.get("networkPreferences"), User.NetworkPreferences::class.java)
            user.setServerData(wirelessPoints, networkInfo, networkPreferences)
            return user
        }
    }

}
