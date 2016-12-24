package com.adsamcik.signalcollector.utility;

import android.app.Activity;
import android.content.Intent;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.StringRes;
import android.support.v4.app.FragmentActivity;
import android.view.View;
import android.widget.Button;

import com.adsamcik.signalcollector.R;
import com.google.android.gms.auth.api.Auth;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.SignInButton;
import com.google.android.gms.common.api.GoogleApiClient;

import java.lang.ref.WeakReference;


public class Signin implements GoogleApiClient.OnConnectionFailedListener {
	private static final String TOKEN = "kNeoeSe";

	public static final int RC_SIGN_IN = 4654;
	private final GoogleApiClient client;
	private WeakReference<SignInButton> signInButton;
	private WeakReference<Button> signOutButton;
	private final WeakReference<FragmentActivity> activityWeakReference;

	private static Signin instance = null;

	private Activity getActivity() {
		return activityWeakReference != null ? activityWeakReference.get() : null;
	}

	public static Signin getInstance(@Nullable FragmentActivity fragmentActivity) {
		if (instance == null && fragmentActivity != null)
			instance = new Signin(fragmentActivity);
		return instance;
	}

	public static String getToken(@Nullable FragmentActivity fragmentActivity) {
		Signin signin = getInstance(fragmentActivity);
		assert signin != null;
		return Preferences.get().getString(TOKEN, null);
	}

	private Signin(@NonNull FragmentActivity activity) {
		activityWeakReference = new WeakReference<>(activity);
		GoogleSignInOptions gso = new GoogleSignInOptions.Builder()
				.requestIdToken(activity.getResources().getString(R.string.server_client_id))
				.build();
		client = new GoogleApiClient.Builder(activity)
				.enableAutoManage(activity, this)
				.addApi(Auth.GOOGLE_SIGN_IN_API, gso)
				.build();
	}

	public Signin manageButtons(@NonNull SignInButton signInButton, @NonNull Button signOutButton) {
		this.signInButton = new WeakReference<>(signInButton);
		this.signOutButton = new WeakReference<>(signOutButton);
		updateButtons(client.isConnecting() | client.isConnected());
		return this;
	}

	public Signin forgetButtons() {
		signOutButton = null;
		signInButton = null;
		return this;
	}

	private void updateButtons(boolean signed) {
		if (signInButton != null && signOutButton != null) {
			SignInButton signInButton = this.signInButton.get();
			Button signOutButton = this.signOutButton.get();
			if (signInButton != null && signOutButton != null) {
				if (signed) {
					signInButton.setVisibility(View.GONE);
					signOutButton.setVisibility(View.VISIBLE);
					signOutButton.setOnClickListener(v -> revokeAccess());
				} else {
					signInButton.setVisibility(View.VISIBLE);
					signOutButton.setVisibility(View.GONE);
					signInButton.setOnClickListener((v) -> {
						Activity a = getActivity();
						if (a != null) {
							Intent signInIntent = Auth.GoogleSignInApi.getSignInIntent(client);
							activityWeakReference.get().startActivityForResult(signInIntent, RC_SIGN_IN);
						}
					});
				}
			}
		}
	}

	public void onSignedIn(@NonNull String token) {
		updateButtons(true);
		showSnackbar(R.string.signed_in_message);
		Preferences.get().edit().putString(TOKEN, token).apply();
	}

	private void onSignedOut() {
		updateButtons(false);
		showSnackbar(R.string.signed_out_message);
	}

	private void showSnackbar(@StringRes int messageResId) {
		Activity a = getActivity();
		if (a != null)
			new SnackMaker(a).showSnackbar(a.getString(messageResId));
	}

	private void revokeAccess() {
		Auth.GoogleSignInApi.revokeAccess(client).setResultCallback(status -> onSignedOut());
		Preferences.get().edit().remove(TOKEN).apply();
	}

	@Override
	public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
		Activity activity = getActivity();
		if (activity != null) {
			Intent signInIntent = Auth.GoogleSignInApi.getSignInIntent(client);
			activityWeakReference.get().startActivityForResult(signInIntent, RC_SIGN_IN);
		}
	}
}
