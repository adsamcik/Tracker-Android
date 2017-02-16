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
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.auth.api.signin.GoogleSignInResult;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.SignInButton;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.OptionalPendingResult;
import com.google.firebase.crash.FirebaseCrash;

import java.lang.ref.WeakReference;


public class Signin implements GoogleApiClient.OnConnectionFailedListener {
	public static final int RC_SIGN_IN = 4654;
	private final GoogleApiClient client;
	private WeakReference<SignInButton> signInButton;
	private WeakReference<Button> signOutButton;
	private final WeakReference<FragmentActivity> activityWeakReference;

	private String token = null;

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
		return signin.getToken();
	}

	private String getToken() {
		if (token != null)
			return token;
		else if (client != null && client.isConnected()) {
			GoogleSignInAccount acc = Auth.GoogleSignInApi.getSignInResultFromIntent(Auth.GoogleSignInApi.getSignInIntent(client)).getSignInAccount();
			assert acc != null;
			return acc.getIdToken();
		} else
			return null;
	}

	public static String getTokenFromResult(@NonNull GoogleSignInAccount acc) {
		String token = acc.getIdToken();
		assert token != null;
		return token;
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

		OptionalPendingResult<GoogleSignInResult> pendingResult = Auth.GoogleSignInApi.silentSignIn(client);

		if (pendingResult.isDone()) {
			final GoogleSignInAccount acc = pendingResult.get().getSignInAccount();
			assert acc != null;
			onSignedIn(getTokenFromResult(acc), false);
		} else {
			pendingResult.setResultCallback((@NonNull GoogleSignInResult result) -> {
						if (result.isSuccess()) {
							final GoogleSignInAccount acc = result.getSignInAccount();
							assert acc != null;
							onSignedIn(getTokenFromResult(acc), false);
						}
					}
			);
		}
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
					signOutButton.setOnClickListener(v -> signout());
				} else {
					signInButton.setVisibility(View.VISIBLE);
					signOutButton.setVisibility(View.GONE);
					signInButton.setOnClickListener((v) -> {
						Activity a = getActivity();
						if (a != null) {
							activityWeakReference.get().startActivityForResult(Auth.GoogleSignInApi.getSignInIntent(client), RC_SIGN_IN);
						}
					});
				}
			}
		}
	}

	public void onSignedIn(@NonNull String token, boolean showSnackbar) {
		updateButtons(true);
		if (showSnackbar)
			showSnackbar(R.string.signed_in_message);
		this.token = token;
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

	private void signout() {
		if (client.isConnected()) {
			Auth.GoogleSignInApi.signOut(client).setResultCallback(status -> onSignedOut());
			token = null;
		} else if (client.isConnecting()) {
			showSnackbar(R.string.signin_not_ready);
		} else
			FirebaseCrash.report(new Throwable("Signout called while client is not even connecting. SOMETHING IS WRONG! PANIC!"));
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
