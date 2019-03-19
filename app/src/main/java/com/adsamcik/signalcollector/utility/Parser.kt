package com.adsamcik.signalcollector.utility

import android.util.Log
import com.adsamcik.signalcollector.BuildConfig
import com.crashlytics.android.Crashlytics
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import java.io.IOException



/**
 * Utility tool for parsing different types of files to objects
 */
object Parser {

    /**
     * Tries to parse json to object
     *
     * @param json   json
     * @param tClass object class
     * @param <T>    object type
     * @return object if successful, null otherwise
    </T> */
    fun <T> tryFromJson(json: String?, tClass: Class<T>): T? {
        if (json != null && !json.isEmpty()) {
            val moshi = Moshi.Builder().build()
            try {
                return moshi.adapter(tClass).fromJson(json)
            } catch (e: IOException) {
                if (BuildConfig.DEBUG)
                    Log.e("FATAL EXCEPTION", json)
                else
                    Crashlytics.logException(e)
            } catch (e: JsonDataException) {
                if (BuildConfig.DEBUG)
                    Log.e("FATAL EXCEPTION", "type ${tClass.name} data $json")
                else
                    Crashlytics.logException(e)
            }

        }
        return null
    }

    private fun parseLine(line: String): Array<String>? =
            if (line.isEmpty()) null else line.split("\t".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()


}
