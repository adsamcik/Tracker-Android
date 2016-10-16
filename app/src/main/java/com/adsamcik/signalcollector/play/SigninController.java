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

import com.google.android.gms.auth.api.Auth;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.SignInButton;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;

import java.lang.ref.WeakReference;


public class SigninController implements GoogleApiClient.OnConnectionFailedListener {
	public static final int RC_SIGN_IN = 4654;
	private GoogleApiClient client;
	private SignInButton signInButton;
	private Button signOutButton;
	private WeakReference<FragmentActivity> activityWeakReference;

	private static WeakReference<SigninController> instance;

	public static SigninController getInstance(FragmentActivity fragmentActivity) {
		if (instance == null || instance.get() == null)
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

	public SigninController manageButtons(@NonNull SignInButton signInButton, @NonNull Button signOutButton) {
		this.signInButton = signInButton;
		this.signOutButton = signOutButton;
		if (client.isConnected())
			onSignedIn();
		else
			onSignedOut();
		return this;
	}

	public SigninController forgetButtons() {
		signOutButton = null;
		signInButton = null;
		return this;
	}

	public void onSignedIn() {
		if (signInButton != null && signOutButton != null) {
			signInButton.setVisibility(View.GONE);
			signOutButton.setVisibility(View.VISIBLE);
			signOutButton.setOnClickListener(v -> revokeAccess());
		}
	}

	private void onSignedOut() {
		if (signInButton != null && signOutButton != null) {
			signInButton.setVisibility(View.VISIBLE);
			signOutButton.setVisibility(View.GONE);
			signInButton.setOnClickListener((v) -> {
				Intent signInIntent = Auth.GoogleSignInApi.getSignInIntent(client);
				activityWeakReference.get().startActivityForResult(signInIntent, RC_SIGN_IN);
			});
		}
	}

	private void revokeAccess() {
		Auth.GoogleSignInApi.revokeAccess(client).setResultCallback(
				new ResultCallback<Status>() {
					@Override
					public void onResult(Status status) {
						onSignedOut();
					}
				});
	}

	@Override
	public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
		Intent signInIntent = Auth.GoogleSignInApi.getSignInIntent(client);
		activityWeakReference.get().startActivityForResult(signInIntent, RC_SIGN_IN);
	}
}
