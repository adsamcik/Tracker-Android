package com.adsamcik.signalcollector.utility;


import android.content.Context;
import android.support.annotation.NonNull;
import android.support.test.InstrumentationRegistry;

import com.adsamcik.signalcollector.R;
import com.google.gson.Gson;

import junit.framework.Assert;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TranslateableStringTest {
	private Context appContext = InstrumentationRegistry.getTargetContext();
	private Gson gson = new Gson();
	private final String identifier = "activity_idle";
	private final int identifierInt = R.string.activity_idle;
	private final String defaultString = "Default string";

	@Test
	public void loadFromJson() {
		final String json = "{\"defaultString\":\"" + defaultString + "\",\"identifier\":" + identifier + "}";
		Assert.assertEquals(appContext.getString(identifierInt), gson.fromJson(json, TranslateableString.class).getString(appContext));
	}

	@Test
	public void basicValueTest() {
		final TranslateableString target = new TranslateableString(identifier, defaultString, (identifier) -> identifierInt);

		Assert.assertEquals(defaultString, target.getDefaultString());
		Assert.assertEquals(identifier, target.getIdentifier());
		Assert.assertEquals(appContext.getString(identifierInt), target.getString(appContext));
	}

	@Test
	public void exceptionTest() {
		final TranslateableString target = new TranslateableString();

		try {
			target.getString(appContext);
			throw new IllegalStateException("Get string did not throw exception");
		} catch (RuntimeException e) {
			//everything works as expected
		}

		target.setIdentifier("");

		try {
			target.getString(appContext);
			throw new IllegalStateException("Get string did not throw exception");
		} catch (RuntimeException e) {
			//everything works as expected
		}
	}

	@Test
	public void dynamicResourceTest() {
		final TranslateableString target = new TranslateableString();
		target.setIdentifier(identifier);
		Assert.assertEquals(appContext.getString(identifierInt), target.getString(appContext));
	}
}
