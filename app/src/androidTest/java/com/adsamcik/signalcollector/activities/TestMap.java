package com.adsamcik.signalcollector.activities;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Environment;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.BySelector;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject2;
import android.support.test.uiautomator.Until;

import org.junit.Assert;
import org.junit.Before;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.Random;

import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;


@RunWith(AndroidJUnit4.class)
public class TestMap {

	private static final String PACKAGE = "com.adsamcik.signalcollector";
	private static final int LAUNCH_TIMEOUT = 5000;
	private UiDevice mDevice;

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
		Context context = InstrumentationRegistry.getContext();
		final Intent intent = context.getPackageManager()
				.getLaunchIntentForPackage(PACKAGE);
		assert intent != null;
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);    // Clear out any previous instances
		context.startActivity(intent);

		// Wait for the app to appear
		mDevice.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), LAUNCH_TIMEOUT);
	}

	@org.junit.Test
	public void StabilityTest() throws InterruptedException {
		/*
		mDevice.findObject(By.res(PACKAGE, "action_map")).click();
		Thread.sleep(5000);

		String[] items = new String[]{"action_tracker", "action_map", "action_stats", "action_settings"};
		Random random = new Random(System.currentTimeMillis());

		for (int i = 0; i < 15; i++) {
			UiObject2 obj = mDevice.findObject(By.res(PACKAGE, items[(int) (random.nextDouble() * items.length)]));
			Assert.assertNotNull(obj);
			obj.click();
			Thread.sleep(750);
		}

		mDevice.findObject(By.res(PACKAGE, "action_map")).click();
		Thread.sleep(2000);

		for (int i = 0; i < 5; i++) {
			mDevice.pressHome();
			Thread.sleep(1000);
			Context context = InstrumentationRegistry.getContext();
			Intent intent = context.getPackageManager()
					.getLaunchIntentForPackage(PACKAGE);
			assert intent != null;
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); // You need this if starting
			intent.setAction(Intent.ACTION_MAIN);
			intent.addCategory(Intent.CATEGORY_LAUNCHER);
			context.startActivity(intent);

			// Wait for the app to appear
			mDevice.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), LAUNCH_TIMEOUT);

			Assert.assertEquals(mDevice.getCurrentPackageName(), PACKAGE);

		}
		*/
	}

	private UiObject2 waitForObject(BySelector selector) throws InterruptedException {
		UiObject2 object = null;
		int timeout = 30000;
		int delay = 1000;
		long time = System.currentTimeMillis();
		while (object == null) {
			object = mDevice.findObject(selector);
			Thread.sleep(delay);
			if (System.currentTimeMillis() - timeout > time) {
				fail();
			}
		}
		return object;
	}

	private void takeScreenshot(String name) throws Exception {
		String dir = String.format("%s/%s", Environment.getExternalStorageDirectory().getPath(), "test-screenshots");
		File theDir = new File(dir);
		if (!theDir.exists()) {
			if (!theDir.mkdir())
				throw new Exception();
		}
		mDevice.takeScreenshot(new File(String.format("%s/%s", dir, name)));
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