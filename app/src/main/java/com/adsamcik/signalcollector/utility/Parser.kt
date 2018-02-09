package com.adsamcik.signalcollector.utility

import android.content.Context
import com.adsamcik.signalcollector.file.DataStore
import com.crashlytics.android.Crashlytics
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.io.IOException
import java.io.InputStreamReader
import java.util.*

object Parser {

    /**
     * Tries to parse json to object
     *
     * @param json   json
     * @param tClass object class
     * @param <T>    object type
     * @return object if success, nul otherwise
    </T> */
    fun <T> tryFromJson(json: String?, tClass: Class<T>): T? {
        if (json != null && !json.isEmpty()) {
            try {
                return Gson().fromJson(json, tClass)
            } catch (e: JsonSyntaxException) {
                Crashlytics.logException(e)
            }

        }
        return null
    }


    fun parseTSVFromFile(context: Context, fileName: String): ArrayList<Array<String>>? {
        if (DataStore.exists(context, fileName)) {
            val items = ArrayList<Array<String>>()
            try {
                context.openFileInput(fileName).use { fis ->
                    val isr = InputStreamReader(fis)

                    isr.buffered().useLines { line ->
                        val parsedLine = parseLine(line.toString())
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
