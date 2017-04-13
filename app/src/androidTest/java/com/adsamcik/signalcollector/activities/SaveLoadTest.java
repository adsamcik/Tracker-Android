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
import com.adsamcik.signalcollector.utility.DataStore;
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
	private static final String PACKAGE = "com.adsamcik.signalcollector";
	private static final int LAUNCH_TIMEOUT = 5000;
	private UiDevice mDevice;
	private Context context;

	@Before
	public void before() {
		// Initialize UiDevice instance
		mDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

		final String launcherPackage = getLauncherPackageName();
		assertThat(launcherPackage, notNullValue());

		// Start from the home screen
		if (!mDevice.getCurrentPackageName().equals(launcherPackage)) {
			mDevice.pressHome();
			mDevice.wait(Until.hasObject(By.pkg(launcherPackage).depth(0)), LAUNCH_TIMEOUT);
		}

		// Launch the blueprint app
		context = InstrumentationRegistry.getContext();
		final Intent intent = context.getPackageManager()
				.getLaunchIntentForPackage(PACKAGE);
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);    // Clear out any previous instances
		context.startActivity(intent);

		// Wait for the app to appear
		mDevice.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), LAUNCH_TIMEOUT);
	}

	@org.junit.Test
	public void RepeatedSaveTest() throws MalformedJsonException, InterruptedException {
		final String testFileName = DataStore.RECENT_UPLOADS_FILE;

		long time = System.currentTimeMillis();
		UploadStats us = new UploadStats(time, 2500, 10, 130, 1, 130, 2, 0, 10654465, 0);
		UploadStats usOld = new UploadStats(20, 2500, 10, 130, 1, 130, 2, 0, 10654465, 0);
		final String data = "{\"cell\":130,\"collections\":130,\"newCell\":1,\"newLocations\":2,\"newNoiseLocations\":0,\"newWifi\":10,\"noiseCollections\":0,\"time\":" + time + ",\"uploadSize\":10654465,\"wifi\":2500}";
		final String dataOld = "{\"cell\":130,\"collections\":130,\"newCell\":1,\"newLocations\":2,\"newNoiseLocations\":0,\"newWifi\":10,\"noiseCollections\":0,\"time\":20,\"uploadSize\":10654465,\"wifi\":2500}";

		Preferences.get().edit().putLong(Preferences.PREF_OLDEST_RECENT_UPLOAD, 20).apply();
		Gson gson = new Gson();
		Assert.assertEquals(true, DataStore.saveJsonArrayAppend(testFileName, gson.toJson(us), true, true));
		Assert.assertEquals(true, DataStore.exists(testFileName));
		Assert.assertEquals('[' + data, DataStore.loadString(testFileName));
		Assert.assertEquals('[' + data + ']', DataStore.loadJsonArrayAppend(testFileName));
		//DataStore.removeOldRecentUploads();
		Assert.assertEquals(true, DataStore.saveJsonArrayAppend(testFileName, gson.toJson(us)));
		Assert.assertEquals('[' + data + ',' + data, DataStore.loadString(testFileName));
		Assert.assertEquals('[' + data + ',' + data + ']', DataStore.loadJsonArrayAppend(testFileName));

		Assert.assertEquals(true, DataStore.saveJsonArrayAppend(testFileName, gson.toJson(usOld)));
		Assert.assertEquals('[' + data + ',' + data + ',' + dataOld, DataStore.loadString(testFileName));
		Assert.assertEquals('[' + data + ',' + data + ',' + dataOld + ']', DataStore.loadJsonArrayAppend(testFileName));
		DataStore.removeOldRecentUploads();
		Preferences.checkStatsDay(context);

		Assert.assertEquals('[' + data + ',' + data, DataStore.loadString(testFileName));
		Assert.assertEquals('[' + data + ',' + data + ']', DataStore.loadJsonArrayAppend(testFileName));

		Instrumentation mInstrumentation = InstrumentationRegistry.getInstrumentation();

		Intent intent = new Intent(Intent.ACTION_MAIN);
		intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		intent.setClassName(mInstrumentation.getTargetContext(), RecentUploadsActivity.class.getName());
		mInstrumentation.startActivitySync(intent);
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);    // Clear out any previous instances
		context.startActivity(intent);
		Thread.sleep(5000);
	}

	/**
	 * Uses package manager to find the package name of the device launcher. Usually this package
	 * is "com.android.launcher" but can be different at times. This is a generic solution which
	 * works on all platforms.`
	 */
	private String getLauncherPackageName() {
		// Create launcher Intent
		final Intent intent = new Intent(Intent.ACTION_MAIN);
		intent.addCategory(Intent.CATEGORY_HOME);

		// Use PackageManager to get the launcher package name
		PackageManager pm = InstrumentationRegistry.getContext().getPackageManager();
		ResolveInfo resolveInfo = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
		return resolveInfo.activityInfo.packageName;
	}
}
