package com.adsamcik.signalcollector;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
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

import com.adsamcik.signalcollector.play.PlayController;
import com.github.paolorotolo.appintro.AppIntro2;
import com.github.paolorotolo.appintro.AppIntro2Fragment;

import java.util.ArrayList;
import java.util.List;

public class IntroActivity extends AppIntro2 {
	private int slideNumber = 0;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		addSlide(AppIntro2Fragment.newInstance("Signal Collector", "Welcome [insert your name] to the exciting world of data collecting", R.drawable.ic_signals, Color.parseColor("#3F51B5")));
		if (Build.VERSION.SDK_INT > 22 && (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_DENIED || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_DENIED)) {
			addSlide(AppIntro2Fragment.newInstance("Permissions", "The app needs location to place data you collect into a map and phone permission to get identification number to distinguish your uploads from others.", R.drawable.ic_settings_applications_black_24dp, Color.parseColor("#3F64BB")));
			askForPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.READ_PHONE_STATE}, 2);
		}
		addSlide(AppIntro2Fragment.newInstance("Automatic tracking and uploading", "As long as all background services are running, the app can manage tracking and uploading by itself. You can change this in settings.", R.drawable.ic_satellite_24dp, Color.parseColor("#3E77C1")));
		addSlide(AppIntro2Fragment.newInstance("What is collected", "wifi, cell, GPS position, pressure, IMEI (device identification), device manufacturer and model, time, android version", R.drawable.ic_attachment_24dp, Color.parseColor("#3E89C6")));
		if (PlayController.isPlayServiceAvailable(getApplicationContext()))
			addSlide(AppIntro2Fragment.newInstance("Google Play Games", "If you login to google play games, you will have access to achievements and personal stats.", R.drawable.ic_games_controller, Color.parseColor("#3D9CCC")));

		// Hide Skip/Done button.
		Window window = getWindow();
		window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
		window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
		window.setStatusBarColor(Color.parseColor("#3D9CCC"));

		setProgressButtonEnabled(true);
		setNavBarColor("#3D9CCC");
		skipButtonEnabled = false;
		//setNextPageSwipeLock(true);
		//setSwipeLock(true);


		// Turn vibration on and set intensity.
		// NOTE: you will probably need to ask VIBRATE permission in Manifest.
		//setVibrate(true);
		//setVibrateIntensity(30);
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

			//if (ContextCompat.checkSelfPermission(instance, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
			//    permissions.add(Manifest.permission.RECORD_AUDIO);

			if (permissions.size() == 0)
				return true;

			requestPermissions(permissions.toArray(new String[permissions.size()]), 0);
		}
		return false;
	}

	@Override
	public void onDonePressed(Fragment currentFragment) {
		Setting.getPreferences(this).edit().putBoolean(Setting.HAS_BEEN_LAUNCHED, true).apply();
		startActivity(new Intent(this, MainActivity.class));
	}

	@Override
	public void onSlideChanged(@Nullable Fragment oldFragment, @Nullable Fragment newFragment) {
		if (slidesNumber == ++slideNumber && PlayController.isPlayServiceAvailable(getApplicationContext()))
			PlayController.initializeGamesClient(findViewById(android.R.id.content), this);
	}

	@Override
	public void onBackPressed() {

	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
		for (int grantResult : grantResults) {
			if (grantResult != PackageManager.PERMISSION_GRANTED) {
				Toast.makeText(this, "Both permissions are required.", Toast.LENGTH_SHORT).show();
				CheckAllTrackingPermissions();
			}
		}
	}
}
