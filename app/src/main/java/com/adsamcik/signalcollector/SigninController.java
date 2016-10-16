package com.adsamcik.signalcollector;

import android.content.Intent;
import android.support.annotation.NonNull;
import android.support.v4.app.FragmentActivity;
import android.view.View;
import android.widget.Button;

import com.adsamcik.signalcollector.utility.Preferences;
import com.adsamcik.signalcollector.utility.SnackMaker;
import com.google.android.gms.auth.api.Auth;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.SignInButton;
import com.google.android.gms.common.api.GoogleApiClient;

import java.lang.ref.WeakReference;


public class SigninController implements GoogleApiClient.OnConnectionFailedListener {
	private static final String TOKEN = "userTOKEN";

	public static final int RC_SIGN_IN = 4654;
	private final GoogleApiClient client;
	private SignInButton signInButton;
	private Button signOutButton;
	private final WeakReference<FragmentActivity> activityWeakReference;

	private static WeakReference<SigninController> instance;

	public static SigninController getInstance(FragmentActivity fragmentActivity) {
		if (instance == null || instance.get() == null)
			return (instance = new WeakReference<>(new SigninController(fragmentActivity))).get();
		return instance.get();
	}

	private SigninController(@NonNull FragmentActivity activity) {
		activityWeakReference = new WeakReference<>(activity);
		GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
				.requestIdToken(activity.getResources().getString(R.string.server_client_id))
				.build();
		client = new GoogleApiClient.Builder(activity)
				.enableAutoManage(activity, this)
				.addApi(Auth.GOOGLE_SIGN_IN_API, gso)
				.build();
	}

	public SigninController manageButtons(@NonNull SignInButton signInButton, @NonNull Button signOutButton) {
		this.signInButton = signInButton;
		this.signOutButton = signOutButton;
		updateButtons(client.isConnected());
		return this;
	}

	public SigninController forgetButtons() {
		signOutButton = null;
		signInButton = null;
		return this;
	}

	private void updateButtons(boolean signed) {
		if (signInButton != null && signOutButton != null) {
			if(signed) {
				signInButton.setVisibility(View.GONE);
				signOutButton.setVisibility(View.VISIBLE);
				signOutButton.setOnClickListener(v -> revokeAccess());
			}
			else {
				signInButton.setVisibility(View.VISIBLE);
				signOutButton.setVisibility(View.GONE);
				signInButton.setOnClickListener((v) -> {
					Intent signInIntent = Auth.GoogleSignInApi.getSignInIntent(client);
					activityWeakReference.get().startActivityForResult(signInIntent, RC_SIGN_IN);
				});
			}
		}
	}

	public void onSignedIn() {
		updateButtons(true);
		new SnackMaker(activityWeakReference.get().findViewById(R.id.container)).showSnackbar("Signed in successfully");
	}

	private void onSignedOut() {
		updateButtons(false);
		new SnackMaker(activityWeakReference.get().findViewById(R.id.container)).showSnackbar("Signed out");
	}

	private void revokeAccess() {
		Auth.GoogleSignInApi.revokeAccess(client).setResultCallback(status -> onSignedOut());
	}

	@Override
	public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
		Intent signInIntent = Auth.GoogleSignInApi.getSignInIntent(client);
		activityWeakReference.get().startActivityForResult(signInIntent, RC_SIGN_IN);
	}

	public String getToken() {
		return Preferences.get().getString(TOKEN, null);
	}
}
