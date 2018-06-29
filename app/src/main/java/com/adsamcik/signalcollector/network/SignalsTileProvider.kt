package com.adsamcik.signalcollector.network

import android.content.Context
import com.crashlytics.android.Crashlytics
import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileProvider
import retrofit2.Retrofit
import java.io.IOException

class SignalsTileProvider(context: Context, userToken: String, private val maxZoom: Int) : TileProvider {

    private val client = Retrofit.Builder().client(Network.clientAuth(context, userToken)).build().create(NetworkInterface::class.java)

    private var type: String? = null
    private var personal: Boolean = false

    fun setType(type: String) {
        this.type = type
        this.personal = false
    }

    fun setTypePersonal() {
        this.personal = true
    }

    override fun getTile(x: Int, y: Int, z: Int): Tile {
        val tileImage = getTileImage(x, y, z)
        return if (tileImage != null) Tile(halfSize, halfSize, tileImage) else TileProvider.NO_TILE
    }

    private fun canTileExist(z: Int): Boolean = z <= maxZoom

    private fun getTileImage(x: Int, y: Int, z: Int): ByteArray? {
        if (!canTileExist(z))
            return null


        val call =
                if (personal) {
                    client.personalMapTile(z, x, y)
                } else {
                    client.mapTile(z, x, y, type!!)
                }

        try {
            val r = call.execute()
            if (r.isSuccessful) {
                return r.body()
            }
        } catch (e: IOException) {
            Crashlytics.logException(e)
        }
        return null
    }

    companion object {
        private const val size = 256
        private const val halfSize = size / 2
    }
}
