package com.adsamcik.signalcollector.network

import com.adsamcik.signalcollector.data.Stat
import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.http.*

interface NetworkInterface {
    @POST(Network.URL_AUTHENTICATE)
    fun authenticate(@Query("token") token: String): Call<JwtData>

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