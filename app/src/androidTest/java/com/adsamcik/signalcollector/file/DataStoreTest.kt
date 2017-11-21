package com.adsamcik.signalcollector.file

import android.os.Build
import android.support.test.InstrumentationRegistry
import android.support.test.runner.AndroidJUnit4
import android.util.Log
import com.adsamcik.signalcollector.BuildConfig
import com.adsamcik.signalcollector.data.RawData
import com.adsamcik.signalcollector.signin.Signin
import com.adsamcik.signalcollector.utility.Constants
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataStoreTest {
    private val gson = Gson()
    private val appContext = InstrumentationRegistry.getTargetContext()

    @Before
    fun clearAll() {
        DataStore.clearAll(appContext)
    }

    @Test
    @Throws(Exception::class)
    fun saveArraySigned() {
        Signin.signin(appContext, null)

        if (!Signin.isSignedIn) {
            Log.w("SignalsTest", "Please sign in before doing this test")
            return
        }

        val fileHeader = "\"model\":\"" + Build.MODEL +
                "\",\"manufacturer\":\"" + Build.MANUFACTURER +
                "\",\"api\":" + Build.VERSION.SDK_INT +
                ",\"version\":" + BuildConfig.VERSION_CODE + "," +
                "\"data\":"

        val rawData = arrayOf(RawData(System.currentTimeMillis()),
                RawData(System.currentTimeMillis() + Constants.MINUTE_IN_MILLISECONDS))

        assertEquals(DataStore.SaveStatus.SAVE_SUCCESS, DataStore.saveData(appContext, rawData))
        assertEquals(DataStore.PREF_DATA_FILE_INDEX, DataStore.currentDataFile!!.preference)
        val loadedData = DataStore.loadAppendableJsonArray(appContext, DataStore.currentDataFile!!.file.name)
        val firstComma = loadedData!!.indexOf(',')
        assertEquals(fileHeader + gson.toJson(rawData), loadedData.substring(firstComma + 1))
    }
}