package com.adsamcik.signalcollector.network

import android.content.Context
import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileProvider
import com.google.firebase.crash.FirebaseCrash
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.*

class SignalsTileProvider(context: Context, private val maxZoom: Int) : TileProvider {

    private val client: OkHttpClient = Network.client(context, null)

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


        val url = if (personal) String.format(Locale.ENGLISH, Network.URL_PERSONAL_TILES, z, x, y) else String.format(Locale.ENGLISH, Network.URL_TILES, z, x, y, type)
        val c = client.newCall(Request.Builder().url(url).build())
        var r: Response? = null

        try {
            r = c.execute()
            val body = r!!.body()
            if (body != null)
                return body.bytes()
        } catch (e: IOException) {
            FirebaseCrash.report(e)
        } finally {
            if (r != null)
                r.close()
        }
        return null
    }

    companion object {
        private val size = 256
        private val halfSize = size / 2
    }
}
