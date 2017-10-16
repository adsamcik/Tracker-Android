package com.adsamcik.signalcollector.file;

import android.content.Context;
import android.os.Build;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;

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
		DataStore.clearAll(appContext);
		Signin.getUserAsync(appContext, null);
	}

	@Test
	public void saveArraySigned() throws Exception {
		final String fileHeader = "\"model\":\"" + Build.MODEL +
				"\",\"manufacturer\":\"" + Build.MANUFACTURER +
				"\",\"api\":" + Build.VERSION.SDK_INT +
				",\"version\":" + BuildConfig.VERSION_CODE + "," +
				"\"data\":";

		if(!Signin.isSignedIn())
			throw new Exception("Please sign in before doing this test");

		RawData[] rawData = new RawData[2];
		rawData[0] = new RawData(System.currentTimeMillis());
		rawData[1] = new RawData(System.currentTimeMillis() + Constants.MINUTE_IN_MILLISECONDS);

		assertEquals(DataStore.SaveStatus.SAVE_SUCCESS, DataStore.saveData(appContext, rawData));
		String loadedData = DataStore.loadAppendableJsonArray(appContext, DataStore.DATA_FILE + 0 + DataFile.SEPARATOR + 2);
		int firstComma = loadedData.indexOf(',');
		assertEquals(fileHeader + gson.toJson(rawData), loadedData.substring(firstComma + 1));
	}
}
