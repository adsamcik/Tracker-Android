package com.adsamcik.signalcollector;

import android.content.Context;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;

import com.adsamcik.signalcollector.utility.EArray;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DiscoveryTest {
	@Test
	public void average() throws Exception {
		short[] arr = {3, 5, 9, 13};
		Assert.assertEquals(7, EArray.avg(arr));

		short[] result = EArray.avgEvery(arr, 2);
		short[] target = new short[]{4, 11};
		Assert.assertEquals(target.length, result.length);
		for (int i = 0; i < target.length; i++) {
			Assert.assertEquals(target[i], result[i]);
		}


		short[] arr2 = {0, 1, 3, 4};
		Assert.assertEquals(2, EArray.avg(arr2));
	}
}