package com.adsamcik.signalcollector.activities;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import com.adsamcik.signalcollector.fragments.FragmentIntro;
import com.adsamcik.signalcollector.utility.Assist;
import com.adsamcik.signalcollector.utility.Preferences;
import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.utility.Signin;
import com.github.paolorotolo.appintro.AppIntro2;
import com.github.paolorotolo.appintro.AppIntro2Fragment;
import com.github.paolorotolo.appintro.AppIntroFragment;

import java.util.ArrayList;
import java.util.List;

public class IntroActivity extends AppIntro2 {

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Window window = getWindow();
		Resources r = getResources();
		if (Build.VERSION.SDK_INT > 22 && (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_DENIED || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_DENIED)) {
			addSlide(FragmentIntro.newInstance(r.getString(R.string.intro_permissions_title), r.getString(R.string.intro_permissions), R.drawable.ic_permissions, Color.parseColor("#b35959"), window));
			askForPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.READ_PHONE_STATE}, 1);
		}
		addSlide(FragmentIntro.newInstance(r.getString(R.string.intro_auto_track_up_title), r.getString(R.string.intro_auto_track_up), R.drawable.ic_auto_tracking_upload, Color.parseColor("#4c6699"), window));
		addSlide(FragmentIntro.newInstance(r.getString(R.string.intro_permissions_noise_title), r.getString(R.string.intro_permissions_noise), R.drawable.ic_permission_noise, Color.parseColor("#b35959"), window));
		askForPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 4);

		window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
		window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
		window.setStatusBarColor(Color.parseColor("#11A63D"));

		setProgressButtonEnabled(true);
		setNavBarColor("#4c6699");
		skipButtonEnabled = false;
	}

	/**
	 * Checks all required permissions
	 *
	 * @return true if all permissions are granted
	 */
	private boolean CheckAllTrackingPermissions() {
		if (Build.VERSION.SDK_INT > 22) {
			List<String> permissions = new ArrayList<>();
			if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
				permissions.add(android.Manifest.permission.ACCESS_FINE_LOCATION);

			if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
				permissions.add(android.Manifest.permission.READ_PHONE_STATE);

			if (permissions.size() == 0)
				return true;

			requestPermissions(permissions.toArray(new String[permissions.size()]), 0);
		}
		return false;
	}

	@Override
	public void onDonePressed(Fragment currentFragment) {
		Preferences.get(this).edit().putBoolean(Preferences.HAS_BEEN_LAUNCHED, true).apply();
		startActivity(new Intent(this, MainActivity.class));
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
		if (permissions.length == 2) {
			for (int grantResult : grantResults) {
				if (grantResult != PackageManager.PERMISSION_GRANTED) {
					Toast.makeText(this, "Both permissions are required.", Toast.LENGTH_SHORT).show();
					CheckAllTrackingPermissions();
				}
			}
		} else if (permissions.length == 1) {
			Preferences.get(getApplicationContext()).edit().putBoolean(Preferences.TRACKING_NOISE_ENABLED, grantResults[0] == PackageManager.PERMISSION_GRANTED).apply();
			if (grantResults[0] != PackageManager.PERMISSION_GRANTED)
				Toast.makeText(this, "Noise can be enabled in settings.", Toast.LENGTH_SHORT).show();
		}
	}
}
