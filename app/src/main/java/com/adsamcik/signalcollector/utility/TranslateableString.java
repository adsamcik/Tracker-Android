package com.adsamcik.signalcollector.utility;

import android.content.Context;
import android.support.annotation.NonNull;

import com.google.firebase.crash.FirebaseCrash;

public abstract class TranslateableString {
	private String defaultString;
	private String identifier = null;

	public String getString(@NonNull Context context) {
		if(identifier == null)
			throw new RuntimeException("Translateable strings must have identifier");

		int id = getId(identifier);
		if(id == 0) {
			id = getId(identifier, context);
			if(id == 0) {
				FirebaseCrash.report(new RuntimeException("Missing translation"));
				return defaultString;
			}
		}

		return context.getString(id);
	}

	protected abstract int getId(@NonNull String identifier);

	private int getId(@NonNull String identifier, @NonNull Context context) {
		return context.getResources().getIdentifier(identifier, "string", context.getPackageName());
	}
}
