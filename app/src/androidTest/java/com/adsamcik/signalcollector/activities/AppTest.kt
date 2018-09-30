package com.adsamcik.signalcollector.activities

import android.content.Context
import android.util.MalformedJsonException
import androidx.test.InstrumentationRegistry.getInstrumentation
import androidx.test.runner.AndroidJUnit4
import androidx.test.uiautomator.UiDevice
import com.adsamcik.signalcollector.data.UploadStats
import com.adsamcik.signalcollector.device
import com.adsamcik.signalcollector.file.DataStore
import com.adsamcik.signalcollector.services.MessageListenerService
import com.adsamcik.signalcollector.utility.Preferences
import com.squareup.moshi.Moshi
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import java.util.*

@RunWith(AndroidJUnit4::class)
class AppTest {
    private var mDevice: UiDevice = device
    private var context: Context = getInstrumentation().targetContext

    @Test
    @Throws(MalformedJsonException::class, InterruptedException::class)
    fun notificationSavingTest() {
        val testFileName = DataStore.RECENT_UPLOADS_FILE
        val adapter = Moshi.Builder().build().adapter(UploadStats::class.java)

        val time = System.currentTimeMillis()
        val us = UploadStats(time, 2500, 10, 130, 1, 130, 2, 10654465)
        val usOld = UploadStats(20, 2500, 10, 130, 1, 130, 2, 10654465)
        val data = adapter.toJson(us)
        val dataOld = adapter.toJson(usOld)

        Preferences.getPref(context).edit().putLong(Preferences.PREF_OLDEST_RECENT_UPLOAD, 20).apply()
        Assert.assertEquals(true, DataStore.saveAppendableJsonArray(context, testFileName, adapter.toJson(us), false))
        Assert.assertEquals(true, DataStore.exists(context, testFileName))
        Assert.assertEquals("[$data", DataStore.loadString(context, testFileName))
        Assert.assertEquals("[$data]", DataStore.loadAppendableJsonArray(context, testFileName))
        //DataStore.removeOldRecentUploads();
        Assert.assertEquals(true, DataStore.saveAppendableJsonArray(context, testFileName, us, UploadStats::class.java, true))
        Assert.assertEquals("[$data,$data", DataStore.loadString(context, testFileName))
        Assert.assertEquals("[$data,$data]", DataStore.loadAppendableJsonArray(context, testFileName))

        Assert.assertEquals(true, DataStore.saveAppendableJsonArray(context, testFileName, adapter.toJson(usOld), true))
        Assert.assertEquals("[$data,$data,$dataOld", DataStore.loadString(context, testFileName))
        Assert.assertEquals("[$data,$data,$dataOld]", DataStore.loadAppendableJsonArray(context, testFileName))
        DataStore.removeOldRecentUploads(context)

        Assert.assertEquals("[$data,$data", DataStore.loadString(context, testFileName))
        Assert.assertEquals("[$data,$data]", DataStore.loadAppendableJsonArray(context, testFileName))

        DataStore.delete(context, testFileName)
        DataStore.delete(context, DataStore.RECENT_UPLOADS_FILE)

        val d = HashMap<String, String>(10)
        d[MessageListenerService.WIFI] = Integer.toString(us.wifi)
        d[MessageListenerService.NEW_WIFI] = Integer.toString(us.newWifi)
        d[MessageListenerService.CELL] = Integer.toString(us.cell)
        d[MessageListenerService.NEW_CELL] = Integer.toString(us.newCell)
        d[MessageListenerService.COLLECTIONS] = Integer.toString(us.collections)
        d[MessageListenerService.NEW_LOCATIONS] = Integer.toString(us.newLocations)
        d[MessageListenerService.UPLOAD_SIZE] = java.lang.Long.toString(us.uploadSize)

        MessageListenerService.parseAndSaveUploadReport(context, time, d)
        Assert.assertEquals("[$data", DataStore.loadString(context, DataStore.RECENT_UPLOADS_FILE))

        MessageListenerService.parseAndSaveUploadReport(context, time, d)
        Assert.assertEquals("[$data,$data", DataStore.loadString(context, DataStore.RECENT_UPLOADS_FILE))
        DataStore.delete(context, DataStore.RECENT_UPLOADS_FILE)
    }
}
