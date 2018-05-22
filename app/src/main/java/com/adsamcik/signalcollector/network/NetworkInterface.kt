package com.adsamcik.signalcollector.network

import com.adsamcik.signalcollector.data.Stat
import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.http.*


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


interface NetworkInterface {
    @POST(Network.URL_AUTHENTICATE)
    fun authenticate(@Field("user") user: String): Call<JwtData>

    @POST(Network.URL_DATA_UPLOAD)
    fun dataUpload(@Body file: RequestBody): Call<Boolean>

    @GET(Network.URL_TILES)
    fun mapTile(@Path("zoom") zoom: Int, @Path("x") x: Int, @Path("y") y: Int, @Path("type") type: String): Call<ByteArray?>

    @GET(Network.URL_PERSONAL_TILES)
    fun personalMapTile(@Path("zoom") zoom: Int, @Path("x") x: Int, @Path("y") y: Int): Call<ByteArray?>

    @GET(Network.URL_USER_STATS)
    fun userStats(): Call<Stat>

    @GET(Network.URL_STATS)
    fun stats(): Call<Stat>

    @GET(Network.URL_GENERAL_STATS)
    fun generalStats(): Call<Stat>

    @GET(Network.URL_USER_PRICES)
    fun userPrices(): Call<Prices>
}