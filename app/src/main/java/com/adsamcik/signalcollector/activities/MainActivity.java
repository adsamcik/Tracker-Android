package com.adsamcik.signalcollector.activities;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.StringRes;
import android.support.design.widget.BottomNavigationView;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.content.ContextCompat;
import android.widget.LinearLayout;

import com.adsamcik.signalcollector.services.UploadService;
import com.adsamcik.signalcollector.utility.Assist;
import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.utility.DataStore;
import com.adsamcik.signalcollector.utility.Network;
import com.adsamcik.signalcollector.utility.SnackMaker;
import com.adsamcik.signalcollector.utility.Failure;
import com.adsamcik.signalcollector.fragments.FragmentTracker;
import com.adsamcik.signalcollector.fragments.FragmentMap;
import com.adsamcik.signalcollector.fragments.FragmentSettings;
import com.adsamcik.signalcollector.fragments.FragmentStats;
import com.adsamcik.signalcollector.interfaces.ITabFragment;
import com.adsamcik.signalcollector.utility.Signin;
import com.adsamcik.signalcollector.services.ActivityService;
import com.google.android.gms.auth.api.Auth;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInResult;
import com.google.firebase.crash.FirebaseCrash;
import com.google.firebase.iid.FirebaseInstanceId;

public class MainActivity extends FragmentActivity {
	public static final String TAG = "SignalsMainActivity";

	private FloatingActionButton fabOne;
	private FloatingActionButton fabTwo;

	private ITabFragment currentFragment = null;

	private Signin signin;

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.activity_main);
		DataStore.setContext(this);
		SnackMaker snackMaker = new SnackMaker(this);

		signin = Signin.getInstance(this);

		Failure<String> s = ActivityService.initializeActivityClient(this);
		if (s.hasFailed())
			snackMaker.showSnackbar(s.value);

		Assist.initialize(this);

		ColorStateList primary = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.textPrimary));
		ColorStateList secondary = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.colorAccent));

		fabOne = (FloatingActionButton) findViewById(R.id.fabOne);
		fabOne.setBackgroundTintList(secondary);
		fabOne.setImageTintList(primary);

		fabTwo = (FloatingActionButton) findViewById(R.id.fabTwo);
		fabTwo.setBackgroundTintList(primary);
		fabTwo.setImageTintList(secondary);

		if (!Assist.hasNavBar(getWindowManager())) {
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
			lp.setMargins(0, 0, 0, 0);
			fabOne.setLayoutParams(lp);
		}

		BottomNavigationView bottomNavigationView = (BottomNavigationView) findViewById(R.id.bottom_navigation);
		final FragmentManager fragmentManager = getSupportFragmentManager();

		final FragmentActivity activity = this;

		bottomNavigationView.setOnNavigationItemSelectedListener(
				item -> {
					switch (item.getItemId()) {
						case R.id.action_tracker:
							handleBottomNav(FragmentTracker.class, R.string.menu_tracker);
							break;
						case R.id.action_map:
							handleBottomNav(FragmentMap.class, R.string.menu_map);
							break;
						case R.id.action_stats:
							handleBottomNav(FragmentStats.class, R.string.menu_stats);
							break;
						case R.id.action_settings:
							handleBottomNav(FragmentSettings.class, R.string.menu_settings);
							break;
						default:
							FirebaseCrash.report(new Throwable("Unknown fragment item id " + item.getItemId()));
							return false;
					}
					return true;
				});

		FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();
		currentFragment = new FragmentTracker();
		fragmentTransaction.replace(R.id.container, (FragmentTracker) currentFragment, getString(R.string.menu_tracker));
		fragmentTransaction.commit();
		currentFragment.onEnter(activity, fabOne, fabTwo);

		Context context = getApplicationContext();
		//todo uncomment this when server is ready
		//SharedPreferences sp = Preferences.get(context);
		//if (!sp.getBoolean(Preferences.SENT_TOKEN_TO_SERVER, false)) {
		String token = FirebaseInstanceId.getInstance().getToken();
		if (token != null)
			Network.registerToken(token, context);
		//}
	}

	void handleBottomNav(Class tClass, @StringRes int resId) {
		if (currentFragment.getClass() == tClass)
			currentFragment.onHomeAction();
		else {
			fabOne.hide();
			fabTwo.hide();

			FragmentManager fragmentManager = getSupportFragmentManager();
			FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();
			fragmentTransaction.setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out);

			if (currentFragment != null) {
				currentFragment.onLeave(this);
				fragmentTransaction.remove((Fragment) currentFragment);
			}

			try {
				currentFragment = (ITabFragment) tClass.newInstance();
			} catch (InstantiationException | IllegalAccessException e) {
				FirebaseCrash.report(e);
				return;
			}

			String str = getString(resId);
			fragmentTransaction.replace(R.id.container, (Fragment) currentFragment, str).commit();

			currentFragment.onEnter(this, fabOne, fabTwo);
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
		if (requestCode == 0)
			return;
		boolean success = true;
		for (int grantResult : grantResults) {
			if (grantResult != PackageManager.PERMISSION_GRANTED) {
				success = false;
				break;
			}
		}
		if (currentFragment != null)
			currentFragment.onPermissionResponse(requestCode, success);
	}


	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);

		if (requestCode == Signin.RC_SIGN_IN) {
			GoogleSignInResult result = Auth.GoogleSignInApi.getSignInResultFromIntent(data);
			if (result.isSuccess()) {
				GoogleSignInAccount acct = result.getSignInAccount();
				assert acct != null;
				String token = Signin.getTokenFromResult(acct);
				signin.onSignedIn(token, true);
				Network.registerUser(token, this);
			} else
				new SnackMaker(this).showSnackbar(getString(R.string.error_failed_signin));
		}
	}
}
