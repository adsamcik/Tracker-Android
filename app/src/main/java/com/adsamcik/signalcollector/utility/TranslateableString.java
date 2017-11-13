package com.adsamcik.signalcollector.utility;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.webkit.ValueCallback;

import com.google.firebase.crash.FirebaseCrash;
import com.vimeo.stag.UseStag;

@UseStag
public class TranslateableString {
	private String defaultString;
	private String identifier;
	private IIdentifierResolver identifierResolver;

	public TranslateableString() {
		defaultString = null;
		identifier = null;
		identifierResolver = null;
	}

	public TranslateableString(@NonNull String identifier, @Nullable String defaultString, @Nullable IIdentifierResolver identifierResolver) {
		this.identifier = identifier;
		this.defaultString = defaultString;
		this.identifierResolver = identifierResolver;
	}

	public String getString(@NonNull Context context) {
		if (identifier == null)
			throw new RuntimeException("Translateable strings must have identifier");

		int id;
		if (identifierResolver == null || (id = identifierResolver.resolve(identifier)) == 0) {
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

	private int getId(@NonNull String identifier, @NonNull Context context) {
		return context.getResources().getIdentifier(identifier, "string", context.getPackageName());
	}

	//Stag
	String getDefaultString() {
		return defaultString;
	}

	void setDefaultString(String defaultString) {
		this.defaultString = defaultString;
	}

	String getIdentifier() {
		return identifier;
	}

	void setIdentifier(@NonNull String identifier) {
		this.identifier = identifier;
	}

	IIdentifierResolver getIdentifierResolver() {
		return identifierResolver;
	}

	void setIdentifierResolver(IIdentifierResolver identifierResolver) {
		this.identifierResolver = identifierResolver;
	}

	public interface IIdentifierResolver {
		int resolve(@NonNull String identifier);
	}
}
