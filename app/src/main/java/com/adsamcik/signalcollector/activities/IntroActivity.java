package com.adsamcik.signalcollector.activities;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import com.adsamcik.signalcollector.fragments.FragmentIntro;
import com.adsamcik.signalcollector.fragments.FragmentSettings;
import com.adsamcik.signalcollector.interfaces.ICallback;
import com.adsamcik.signalcollector.interfaces.INonNullValueCallback;
import com.adsamcik.signalcollector.utility.Assist;
import com.adsamcik.signalcollector.utility.Signin;
import com.adsamcik.signalcollector.utility.Preferences;
import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.utility.SnackMaker;
import com.github.paolorotolo.appintro.AppIntro2;
import com.google.android.gms.auth.api.Auth;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInResult;

import java.util.ArrayList;
import java.util.List;

public class IntroActivity extends AppIntro2 {
	private final String TAG = "SignalsIntro";
	private final int LOCATION_PERMISSION_REQUEST_CODE = 201;
	private AlertDialog.Builder autoUploadDialog, batteryOptimalizationDialog;
	private boolean openedTrackingAlert = false;
	private boolean openedSigninAlert = false;
	private boolean openedThemeAlert = false;

	private Fragment currentFragment;

	private int requestedTracking = 0;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		setTheme(Preferences.getTheme(this));
		super.onCreate(savedInstanceState);
		Window window = getWindow();
		Resources r = getResources();
		/*if (Build.VERSION.SDK_INT > 22 && (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_DENIED || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_DENIED)) {
			addSlide(FragmentIntro.newInstance(r.getString(R.string.intro_permissions_title), r.getString(R.string.intro_permissions), R.drawable.ic_permissions, Color.parseColor("#b35959"), window));
			askForPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.READ_PHONE_STATE}, 1);
		}*/
		setFadeAnimation();
		setColorTransitionsEnabled(true);

		ICallback themeCallback = () -> {
			if (!openedThemeAlert && getProgress() == 0) {
				openedThemeAlert = true;
				new AlertDialog.Builder(this)
						.setTitle(R.string.intro_theme_select_title)
						.setPositiveButton(R.string.intro_theme_light, (dialog, whichButton) -> {
							Preferences.setTheme(this, R.style.AppThemeLight);
							setTheme(R.style.AppThemeLight);
							nextSlide(0);
						})
						.setNegativeButton(R.string.intro_theme_dark, ((dialogInterface, i) -> {
							Preferences.setTheme(this, R.style.AppThemeDark);
							setTheme(R.style.AppThemeDark);
							nextSlide(0);
						}))
						.setCancelable(false)
						.show();
			}
		};

		if (Build.VERSION.SDK_INT >= 23)
			batteryOptimalizationDialog = new AlertDialog.Builder(this)
					.setTitle(R.string.intro_disable_battery_optimalizations_title)
					.setMessage(R.string.intro_disable_battery_optimalizations_description)
					.setPositiveButton(android.R.string.ok, (dialogInterface, i) -> {
						Assist.requestBatteryOptimalizationDisable(this);
						autoUploadDialog.show();
					})
					.setNegativeButton(android.R.string.no, (dialogInterface, i) -> autoUploadDialog.show())
					.setCancelable(false);

		ICallback automationSlideCallback = () -> {
			if (!openedTrackingAlert && getProgress() == 1) {
				openedTrackingAlert = true;

				String[] options = getResources().getStringArray(R.array.background_tracking_options);
				new AlertDialog.Builder(this)
						.setTitle(R.string.intro_enable_auto_tracking_title)
						.setMessage(Build.VERSION.SDK_INT >= 23 ? R.string.intro_enable_auto_tracking_description_23 : R.string.intro_enable_auto_tracking_description)
						.setPositiveButton(options[2], (dialog, whichButton) -> trackingDialogResponse(2))
						.setNegativeButton(options[1], ((dialogInterface, i) -> trackingDialogResponse(1)))
						.setNeutralButton(options[0], ((dialogInterface, i) -> trackingDialogResponse(0)))
						.setCancelable(false)
						.show();
			}
		};


		INonNullValueCallback<Integer> uploadSetCallback = (value) -> {
			Preferences.get(this).edit().putInt(Preferences.PREF_AUTO_UPLOAD, value).apply();
			nextSlide(1);
		};

		String[] uploadOptions = getResources().getStringArray(R.array.automatic_upload_options);
		autoUploadDialog = new AlertDialog.Builder(this)
				.setTitle(R.string.intro_enable_auto_upload_title)
				.setMessage(R.string.intro_enable_auto_upload_description)
				.setCancelable(false)
				.setPositiveButton(uploadOptions[2], (dialog, whichButton) -> uploadSetCallback.callback(2))
				.setNeutralButton(uploadOptions[1], (dialog, whichButton) -> uploadSetCallback.callback(1))
				.setNegativeButton(uploadOptions[0], ((dialogInterface, i) -> uploadSetCallback.callback(0)));
		//askForPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, 2);


		ICallback googleSigninSlideCallback = () -> {
			if (!openedSigninAlert && getProgress() == 2) {
				openedSigninAlert = true;

				View v = getLayoutInflater().inflate(R.layout.intro_dialog_signin, null);
				AlertDialog dialog = new AlertDialog.Builder(this)
						.setTitle(R.string.intro_enable_auto_tracking_title)
						.setNegativeButton(R.string.cancel, ((dialogInterface, i) -> Preferences.get(this).edit().putInt(Preferences.PREF_AUTO_TRACKING, 0).apply()))
						.setCancelable(false)
						.create();

				v.findViewById(R.id.sign_in_button).setOnClickListener((x) -> {
					dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setEnabled(false);
					dialog.setMessage(getString(R.string.signin_connecting));
					Signin.signin(currentFragment.getActivity(), false, (user) -> {
						if (user == null)
							new SnackMaker(currentFragment.getView()).showSnackbar(R.string.error_failed_signin);
						dialog.dismiss();
					});

				});

				dialog.setView(v);
				dialog.show();
			}
		};

		window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
		window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
		window.setStatusBarColor(Color.parseColor("#11A63D"));

		setProgressButtonEnabled(true);
		setNavBarColor("#4c6699");
		skipButtonEnabled = false;

		addSlide(FragmentIntro.newInstance(r.getString(R.string.intro_welcome_title), r.getString(R.string.intro_welcome_description), R.drawable.ic_intro_theme, Color.parseColor("#8B8B8B"), window, themeCallback));
		addSlide(FragmentIntro.newInstance(r.getString(R.string.intro_auto_track_up_title), r.getString(R.string.intro_auto_track_up), R.drawable.ic_intro_auto_tracking_upload, Color.parseColor("#4c6699"), window, automationSlideCallback));
		addSlide(FragmentIntro.newInstance(r.getString(R.string.intro_signin_title), r.getString(R.string.intro_signing_description), R.drawable.ic_intro_permissions, Color.parseColor("#cc3333"), window, Signin.isSignedIn() ? null : googleSigninSlideCallback));
		addSlide(FragmentIntro.newInstance(r.getString(R.string.intro_activites_title), r.getString(R.string.intro_activities_description), R.drawable.ic_intro_activites, Color.parseColor("#007b0c"), window, null));
	}

	private void trackingDialogResponse(int option) {
		if (option > 0 && Build.VERSION.SDK_INT >= 23 && ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
			requestedTracking = option;
			requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_PERMISSION_REQUEST_CODE);
		} else {
			Preferences.get(this).edit().putInt(Preferences.PREF_AUTO_TRACKING, option).apply();
			if (option > 0 && shouldShowBatteryOptimalizationDialog(this))
				batteryOptimalizationDialog.show();
			else
				autoUploadDialog.show();
		}
	}

	private void nextSlide(int currentSlide) {
		if (getProgress() == currentSlide)
			pager.goToNextSlide();
	}

	private int getProgress() {
		return isRtl() ? slidesNumber - pager.getCurrentItem() - 1 : pager.getCurrentItem();
	}

	private boolean shouldShowBatteryOptimalizationDialog(@NonNull Context context) {
		PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
		assert powerManager != null;
		return Build.VERSION.SDK_INT >= 23 && powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
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
	public void onSlideChanged(@Nullable Fragment oldFragment, @Nullable Fragment newFragment) {
		super.onSlideChanged(oldFragment, newFragment);
		currentFragment = newFragment;
		if (currentFragment != null) {
			//no check to ensure further changes handle this case
			FragmentIntro fragmentIntro = (FragmentIntro) currentFragment;
			if (fragmentIntro.hasCallback())
				setSwipeLock(true);
			else
				setSwipeLock(false);
		}
	}

	@Override
	public void onDonePressed(Fragment currentFragment) {
		Preferences.get(this).edit().putBoolean(Preferences.PREF_HAS_BEEN_LAUNCHED, true).apply();
		if (isTaskRoot())
			startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK));
		finish();
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
		if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
			boolean success = grantResults[0] == PackageManager.PERMISSION_GRANTED;
			Preferences.get(this).edit().putInt(Preferences.PREF_AUTO_TRACKING, success ? requestedTracking : 0).apply();

			if (success && shouldShowBatteryOptimalizationDialog(this))
				batteryOptimalizationDialog.show();
			else
				autoUploadDialog.show();
		}
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);

		if (requestCode == Signin.RC_SIGN_IN) {
			GoogleSignInResult result = Auth.GoogleSignInApi.getSignInResultFromIntent(data);
			if (result.isSuccess()) {
				if (currentFragment instanceof FragmentSettings) {
					FragmentSettings fragmentSettings = (FragmentSettings) currentFragment;
					Signin.getUserDataAsync(this, fragmentSettings.userSignedCallback);
				}

				GoogleSignInAccount acct = result.getSignInAccount();
				assert acct != null;
				Signin.onSignedIn(acct, this);
			} else {
				new SnackMaker(this).showSnackbar(getString(R.string.error_failed_signin));
				Signin.onSignedInFailed(this);
			}
		}
	}
}
