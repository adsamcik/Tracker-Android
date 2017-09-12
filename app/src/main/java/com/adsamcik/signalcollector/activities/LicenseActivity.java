package com.adsamcik.signalcollector.activities;

import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.utility.Preferences;
import com.google.firebase.crash.FirebaseCrash;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import de.psdev.licensesdialog.LicensesDialog;
import de.psdev.licensesdialog.licenses.ApacheSoftwareLicense20;
import de.psdev.licensesdialog.licenses.License;
import de.psdev.licensesdialog.licenses.MITLicense;
import de.psdev.licensesdialog.model.Notice;

public class LicenseActivity extends DetailActivity {

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		setTheme(Preferences.getTheme(this));
		super.onCreate(savedInstanceState);
		LinearLayout parent = createScrollableContentParent(true);

		InputStream isMeta = getResources().openRawResource(R.raw.third_party_license_metadata);
		InputStream isLicense = getResources().openRawResource(R.raw.third_party_licenses);
		BufferedReader rMeta = new BufferedReader(new InputStreamReader(isMeta));
		BufferedReader rLicense = new BufferedReader(new InputStreamReader(isLicense));

		try {
			String name;
			while ((name = rMeta.readLine()) != null) {
				int firstSpace = name.indexOf(' ');
				int length = name.length();
				name = name.substring(firstSpace + 1, length);
				Button button = addButton(parent, name);
				addLicenseDialogListener(button, name, rLicense.readLine());
			}
		} catch (IOException e) {
			FirebaseCrash.report(e);
		}


		final String GSON = "Gson";
		addButton(parent, GSON).setOnClickListener(view -> {
			Notice notice = new Notice(GSON, "https://github.com/google/gson", "Copyright 2008 Google Inc", new ApacheSoftwareLicense20());
			new LicensesDialog.Builder(this)
					.setNotices(notice)
					.build()
					.show();
		});

		final String OKHTTP = "OkHttp";
		addButton(parent, OKHTTP).setOnClickListener(view -> {
			Notice notice = new Notice(OKHTTP, "https://github.com/square/okhttp", "Copyright 2016 Square, Inc.", new ApacheSoftwareLicense20());
			new LicensesDialog.Builder(this)
					.setNotices(notice)
					.build()
					.show();
		});

		final String LICENSE_DIALOG = "LicensesDialog";
		addButton(parent, LICENSE_DIALOG).setOnClickListener(view -> {
			Notice notice = new Notice(LICENSE_DIALOG, "https://github.com/PSDev/LicensesDialog", "Copyright 2013-2017 Philip Schiffer", new ApacheSoftwareLicense20());
			new LicensesDialog.Builder(this)
					.setNotices(notice)
					.build()
					.show();
		});


		setTitle(R.string.open_source_licenses);
	}

	private Button addButton(final ViewGroup parent, final String name) {
		Button button = new Button(this);
		button.setText(name);
		parent.addView(button);
		return button;
	}

	private void addLicenseDialogListener(Button button, String name, String licenseURL) {
		button.setOnClickListener(view -> {
			final Notice notice = resolveNotice(name, licenseURL);
			new LicensesDialog.Builder(this)
					.setNotices(notice)
					.build()
					.show();
		});
	}

	private Notice resolveNotice(String name, String licenseURL) {
		String lowerName = name.toLowerCase();
		if (lowerName.startsWith("stag")) {
			return new Notice(name, "https://github.com/vimeo/stag-java", "Copyright (c) 2016 Vimeo", new MITLicense());
		} else if (lowerName.equals("appintro")) {
			return new Notice("AppIntro", "https://github.com/apl-devs/AppIntro", "Copyright 2015 Paolo Rotolo\n" + "Copyright 2016 Maximilian Narr", new ApacheSoftwareLicense20());
		} else if (lowerName.equals("persistentcookiejar"))
			return new Notice("PersistentCookieJar", "https://github.com/franmontiel/PersistentCookieJar", "Copyright 2016 Francisco José Montiel Navarro", new ApacheSoftwareLicense20());
		else {
			final License license = resolveLicense(licenseURL);
			if (license == null)
				return new Notice(name, null, licenseURL, null);
			else
				return new Notice(name, null, null, license);
		}
	}

	private License resolveLicense(String url) {
		if (url.startsWith("http://www.apache.org/licenses/LICENSE-2.0") || url.startsWith("https://api.github.com/licenses/apache-2.0"))
			return new ApacheSoftwareLicense20();
		else if (url.startsWith("http://www.opensource.org/licenses/mit-license"))
			return new MITLicense();
		else
			return null;
	}
}
