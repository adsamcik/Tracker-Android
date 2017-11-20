package com.adsamcik.signalcollector.file;

import android.content.Context;
import android.os.Build;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.util.Log;

import com.adsamcik.signalcollector.BuildConfig;
import com.adsamcik.signalcollector.data.RawData;
import com.adsamcik.signalcollector.signin.Signin;
import com.adsamcik.signalcollector.utility.Constants;
import com.google.gson.Gson;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DataStoreTest {
	private Gson gson = new Gson();
	private Context appContext = InstrumentationRegistry.getTargetContext();

	@Before
	public void clearAll() {
		DataStore.INSTANCE.clearAll(appContext);
		Signin.Companion.getUserAsync(appContext, null);
	}

	@Test
	public void saveArraySigned() throws Exception {
		final String fileHeader = "\"model\":\"" + Build.MODEL +
				"\",\"manufacturer\":\"" + Build.MANUFACTURER +
				"\",\"api\":" + Build.VERSION.SDK_INT +
				",\"version\":" + BuildConfig.VERSION_CODE + "," +
				"\"data\":";

		if(!Signin.Companion.isSignedIn()) {
			Log.w("SignalsTest","Please sign in before doing this test");
			return;
		}

		RawData[] rawData = new RawData[2];
		rawData[0] = new RawData(System.currentTimeMillis());
		rawData[1] = new RawData(System.currentTimeMillis() + Constants.MINUTE_IN_MILLISECONDS);

		assertEquals(DataStore.INSTANCE.SaveStatus.SAVE_SUCCESS, DataStore.INSTANCE.saveData(appContext, rawData));
		String loadedData = DataStore.INSTANCE.loadAppendableJsonArray(appContext, DataStore.INSTANCE.getDATA_FILE() + 0 + DataFile.Companion.getSEPARATOR() + 2);
		int firstComma = loadedData.indexOf(',');
		assertEquals(fileHeader + gson.toJson(rawData), loadedData.substring(firstComma + 1));
	}
}