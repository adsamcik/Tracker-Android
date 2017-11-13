package com.adsamcik.signalcollector.utility;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.google.firebase.crash.FirebaseCrash;
import com.vimeo.stag.UseStag;

@UseStag
public abstract class TranslateableString {
	private String defaultString;
	private String identifier;

	public TranslateableString() {
		defaultString = null;
		identifier = null;
	}

	public TranslateableString(@NonNull String identifier, @Nullable String defaultString) {
		this.identifier = identifier;
		this.defaultString = defaultString;
	}

	public String getString(@NonNull Context context) {
		if (identifier == null)
			throw new RuntimeException("Translateable strings must have identifier");

		int id = getId(identifier);
		if (id == 0) {
			id = getId(identifier, context);
			if (id == 0) {
				if (defaultString == null)
					throw new RuntimeException("Translation not found and default string is null for identifier " + identifier);
				else
					FirebaseCrash.report(new RuntimeException("Missing translation for " + identifier));

				return defaultString;
			}
		}

		return context.getString(id);
	}

	protected abstract int getId(@NonNull String identifier);

	private int getId(@NonNull String identifier, @NonNull Context context) {
		return context.getResources().getIdentifier(identifier, "string", context.getPackageName());
	}

	//Stag
	public String getDefaultString() {
		return defaultString;
	}

	public void setDefaultString(String defaultString) {
		this.defaultString = defaultString;
	}

	public String getIdentifier() {
		return identifier;
	}

	public void setIdentifier(@NonNull String identifier) {
		this.identifier = identifier;
	}
}
