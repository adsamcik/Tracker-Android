package com.adsamcik.signalcollector.play;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.FragmentActivity;
import android.view.View;
import android.widget.Button;

import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.Preferences;
import com.adsamcik.signalcollector.classes.Network;
import com.google.android.gms.auth.api.Auth;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.auth.api.signin.GoogleSignInResult;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.SignInButton;
import com.google.android.gms.common.api.GoogleApiClient;
import java.lang.ref.WeakReference;


public class SigninController implements GoogleApiClient.OnConnectionFailedListener {
	public static final int RC_SIGN_IN = 4654;
	private GoogleApiClient client;
	private WeakReference<SignInButton> buttonWeakReference;
	private WeakReference<FragmentActivity> activityWeakReference;

	private static WeakReference<SigninController> instance;

	public static SigninController getInstance(FragmentActivity fragmentActivity) {
		if(instance == null || instance.get() == null)
			return (instance = new WeakReference<>(new SigninController(fragmentActivity))).get();
		return instance.get();
	}

	private SigninController(@NonNull FragmentActivity activity) {
		activityWeakReference = new WeakReference<>(activity);
		GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
				.requestEmail()
				.build();
		client = new GoogleApiClient.Builder(activity)
				.enableAutoManage(activity, this)
				.addApi(Auth.GOOGLE_SIGN_IN_API, gso)
				.build();
	}

	public SigninController manageButton(SignInButton b) {
		buttonWeakReference = new WeakReference<>(b);
		b.setOnClickListener((v) -> {
			Intent signInIntent = Auth.GoogleSignInApi.getSignInIntent(client);
			activityWeakReference.get().startActivityForResult(signInIntent, RC_SIGN_IN);
		});
		return this;
	}

	public SigninController forgetButton() {
		if (buttonWeakReference != null && buttonWeakReference.get() != null)
			buttonWeakReference.get().setOnClickListener(null);
		buttonWeakReference = null;
		return this;
	}

	private void updateUI(boolean connected) {
		SignInButton button = this.buttonWeakReference.get();
		if (button != null) {
			/*if (connected) {
				button.setText(R.string.settings_playGamesLogout);
				button.setTextColor(Color.rgb(255, 110, 110));
			} else {
				button.setText(R.string.settings_playGamesLogin);
				button.setTextColor(Color.rgb(110, 255, 110));
			}*/
		}
	}

	@Override
	public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
		Intent signInIntent = Auth.GoogleSignInApi.getSignInIntent(client);
		activityWeakReference.get().startActivityForResult(signInIntent, RC_SIGN_IN);
	}
}
