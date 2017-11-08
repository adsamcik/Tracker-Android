package com.adsamcik.signalcollector.activities;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.Until;
import android.util.MalformedJsonException;

import com.adsamcik.signalcollector.data.UploadStats;
import com.adsamcik.signalcollector.file.DataStore;
import com.adsamcik.signalcollector.utility.Preferences;
import com.google.gson.Gson;

import org.junit.Assert;
import org.junit.Before;
import org.junit.runner.RunWith;

import static org.hamcrest.core.IsNull.notNullValue;
import static org.junit.Assert.assertThat;

@RunWith(AndroidJUnit4.class)
public class SaveLoadTest {
	private static final String TAG = "SignalsSaveLoadTest";
	private Context context = InstrumentationRegistry.getTargetContext();

	@org.junit.Test
	public void RepeatedSaveTest() throws MalformedJsonException, InterruptedException {
		final String testFileName = DataStore.RECENT_UPLOADS_FILE;

		DataStore.delete(context, testFileName);

		long time = System.currentTimeMillis();
		UploadStats us = new UploadStats(time, 2500, 10, 130, 1, 130, 2, 0, 10654465, 0);
		UploadStats usOld = new UploadStats(20, 2500, 10, 130, 1, 130, 2, 0, 10654465, 0);
		final String data = "{\"cell\":130,\"collections\":130,\"newCell\":1,\"newLocations\":2,\"newNoiseLocations\":0,\"newWifi\":10,\"noiseCollections\":0,\"time\":" + time + ",\"uploadSize\":10654465,\"wifi\":2500}";
		final String dataOld = "{\"cell\":130,\"collections\":130,\"newCell\":1,\"newLocations\":2,\"newNoiseLocations\":0,\"newWifi\":10,\"noiseCollections\":0,\"time\":20,\"uploadSize\":10654465,\"wifi\":2500}";

		Preferences.get(context).edit().putLong(Preferences.PREF_OLDEST_RECENT_UPLOAD, 20).apply();
		Gson gson = new Gson();
		Assert.assertEquals(true, DataStore.saveAppendableJsonArray(context, testFileName, gson.toJson(us), false));
		Assert.assertEquals(true, DataStore.exists(context, testFileName));
		Assert.assertEquals('[' + data, DataStore.loadString(context, testFileName));
		Assert.assertEquals('[' + data + ']', DataStore.loadAppendableJsonArray(context, testFileName));
		//DataStore.removeOldRecentUploads();
		Assert.assertEquals(true, DataStore.saveAppendableJsonArray(context, testFileName, gson.toJson(us), true));
		Assert.assertEquals('[' + data + ',' + data, DataStore.loadString(context, testFileName));
		Assert.assertEquals('[' + data + ',' + data + ']', DataStore.loadAppendableJsonArray(context, testFileName));

		Assert.assertEquals(true, DataStore.saveAppendableJsonArray(context, testFileName, gson.toJson(usOld), true));
		Assert.assertEquals('[' + data + ',' + data + ',' + dataOld, DataStore.loadString(context, testFileName));
		Assert.assertEquals('[' + data + ',' + data + ',' + dataOld + ']', DataStore.loadAppendableJsonArray(context, testFileName));
		DataStore.removeOldRecentUploads(context);
		Preferences.checkStatsDay(context);

		Assert.assertEquals('[' + data + ',' + data, DataStore.loadString(context, testFileName));
		Assert.assertEquals('[' + data + ',' + data + ']', DataStore.loadAppendableJsonArray(context, testFileName));

		Instrumentation mInstrumentation = InstrumentationRegistry.getInstrumentation();

		Intent intent = new Intent(Intent.ACTION_MAIN);
		intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		intent.setClassName(mInstrumentation.getTargetContext(), UploadReportsActivity.class.getName());
		mInstrumentation.startActivitySync(intent);
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);    // Clear out any previous instances
		context.startActivity(intent);
		Thread.sleep(5000);
	}
}
