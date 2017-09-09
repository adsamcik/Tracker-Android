package com.adsamcik.signalcollector.activities;

import android.app.AlertDialog;
import android.content.DialogInterface;
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
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import com.adsamcik.signalcollector.fragments.FragmentIntro;
import com.adsamcik.signalcollector.fragments.FragmentSettings;
import com.adsamcik.signalcollector.interfaces.ICallback;
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
	private AlertDialog.Builder autoUploadDialog;
	private boolean openedTrackingAlert = false;
	private boolean openedSigninAlert = false;
	private boolean openedThemeAlert = false;

	private Fragment currentFragment;

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
			Log.d(TAG, "callback");
			if (!openedThemeAlert && pager.getCurrentItem() == 0) {
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


		addSlide(FragmentIntro.newInstance(r.getString(R.string.intro_welcome_title), r.getString(R.string.intro_welcome_description), R.drawable.ic_intro_theme, Color.parseColor("#8B8B8B"), window, themeCallback));

		ICallback automationSlideCallback = () -> {
			if (!openedTrackingAlert && pager.getCurrentItem() == 1) {
				openedTrackingAlert = true;
				new AlertDialog.Builder(this)
						.setTitle(R.string.intro_enable_auto_tracking_title)
						.setMessage(Build.VERSION.SDK_INT >= 23 ? R.string.intro_enable_auto_tracking_description_23 : R.string.intro_enable_auto_tracking_description)
						.setPositiveButton(R.string.yes, (dialog, whichButton) -> {
							if (Build.VERSION.SDK_INT >= 23 && ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
								requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_PERMISSION_REQUEST_CODE);
							} else {
								Preferences.get(this).edit().putInt(Preferences.PREF_AUTO_TRACKING, 1).apply();
								autoUploadDialog.show();
							}
						})
						.setNegativeButton(R.string.no, ((dialogInterface, i) -> {
							Preferences.get(this).edit().putInt(Preferences.PREF_AUTO_TRACKING, 0).apply();
							autoUploadDialog.show();
						}))
						.setCancelable(false)
						.show();
			}
		};

		autoUploadDialog = new AlertDialog.Builder(this)
				.setTitle(R.string.intro_enable_auto_upload_title)
				.setMessage(R.string.intro_enable_auto_upload_description)
				.setCancelable(false)
				.setPositiveButton(R.string.yes, (dialog, whichButton) -> {
					Preferences.get(this).edit().putInt(Preferences.PREF_AUTO_UPLOAD, 1).apply();
					nextSlide(1);
				})
				.setNegativeButton(R.string.no, ((dialogInterface, i) -> {
					Preferences.get(this).edit().putInt(Preferences.PREF_AUTO_UPLOAD, 0).apply();
					nextSlide(1);
				}));

		addSlide(FragmentIntro.newInstance(r.getString(R.string.intro_auto_track_up_title), r.getString(R.string.intro_auto_track_up), R.drawable.ic_intro_auto_tracking_upload, Color.parseColor("#4c6699"), window, automationSlideCallback));
		//askForPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, 2);


		ICallback googleSigninSlideCallback = () -> {
			if (!openedSigninAlert && pager.getCurrentItem() == 2) {
				openedSigninAlert = true;

				View v = getLayoutInflater().inflate(R.layout.intro_dialog_signin, null);
				AlertDialog dialog = new AlertDialog.Builder(this)
						.setTitle(R.string.intro_enable_auto_tracking_title)
						.setNegativeButton(R.string.cancel, ((dialogInterface, i) -> {
							Preferences.get(this).edit().putInt(Preferences.PREF_AUTO_TRACKING, 0).apply();
						}))
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

		addSlide(FragmentIntro.newInstance(r.getString(R.string.intro_signin_title), r.getString(R.string.intro_signing_description), R.drawable.ic_intro_permissions, Color.parseColor("#cc3333"), window, Signin.isSignedIn() ? null : googleSigninSlideCallback));
		window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
		window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
		window.setStatusBarColor(Color.parseColor("#11A63D"));

		setProgressButtonEnabled(true);
		setNavBarColor("#4c6699");
		skipButtonEnabled = false;
	}

	private void nextSlide(int currentSlide) {
		if (pager.getCurrentItem() == currentSlide)
			pager.goToNextSlide();
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
		Log.d(TAG, "fragment");
		if(currentFragment != null) {
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
			startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK));
		finish();
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
		if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
			boolean isSuccess = grantResults[0] == PackageManager.PERMISSION_GRANTED;
			Toast.makeText(this, isSuccess ? R.string.intro_notification_enabled_auto_tracking : R.string.intro_notification_no_permission_auto_tracking, Toast.LENGTH_SHORT).show();
			Preferences.get(this).edit().putInt(Preferences.PREF_AUTO_TRACKING, isSuccess ? 1 : 0).apply();
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
