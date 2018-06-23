package com.adsamcik.signalcollector.utility

import android.content.Context
import com.adsamcik.signalcollector.file.DataStore
import com.crashlytics.android.Crashlytics
import com.squareup.moshi.Moshi
import java.io.IOException
import java.io.InputStreamReader
import java.util.*

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
                Crashlytics.logException(e)
            }

        }
        return null
    }

    /**
     * Tries to parse TSV file to an array of string arrays
     *
     * @param context Context used for file lookup
     * @param fileName File's name
     * @return Parsed array of string arrays
     */
    fun parseTSVFromFile(context: Context, fileName: String): ArrayList<Array<String>>? {
        if (DataStore.exists(context, fileName)) {
            val items = ArrayList<Array<String>>()
            try {
                context.openFileInput(fileName).use { fis ->
                    val isr = InputStreamReader(fis)

                    isr.forEachLine {
                        val parsedLine = parseLine(it)
                        if (parsedLine != null)
                            items.add(parsedLine)
                    }
                    return items
                }
            } catch (e: IOException) {
                Crashlytics.logException(e)
            }

        }

        return null
    }

    private fun parseLine(line: String): Array<String>? =
            if (line.isEmpty()) null else line.split("\t".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()


}
