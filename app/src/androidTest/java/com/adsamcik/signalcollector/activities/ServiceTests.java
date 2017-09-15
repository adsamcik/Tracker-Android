package com.adsamcik.signalcollector.activities;

import android.content.Context;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.support.test.uiautomator.UiDevice;

import com.adsamcik.signalcollector.signin.Signin;

import org.junit.Assert;
import org.junit.Before;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;



@RunWith(AndroidJUnit4.class)
public class ServiceTests {
	private Context context;

	@Before
	public void before() {
		UiDevice mDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
		context = InstrumentationRegistry.getTargetContext();
	}

	private AtomicBoolean asyncBooleanVariable;

	@org.junit.Test
	public void SigninTest() throws InterruptedException {
		final Lock lock = new ReentrantLock();
		final Condition callbackReceived = lock.newCondition();

		asyncBooleanVariable = new AtomicBoolean(false);
		Signin.getUserAsync(context, value -> {
			lock.lock();
			Assert.assertNotNull(value);
			asyncBooleanVariable.set(true);
			callbackReceived.signal();
			lock.unlock();
		});

		if (!asyncBooleanVariable.get()) {
			lock.lock();
			try {
				Assert.assertTrue(callbackReceived.await(10, TimeUnit.SECONDS));
			} finally {
				lock.unlock();
			}
		}
	}

}
