package com.adsamcik.signalcollector.utility;

import android.app.Activity;
import android.content.Context;
import android.content.IntentSender;
import android.os.Bundle;
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


public class Signin implements GoogleApiClient.OnConnectionFailedListener, GoogleApiClient.ConnectionCallbacks {
	public static final int RC_SIGN_IN = 4654;
	private static final int REQUEST_RESOLVE_ERROR = 1001;

	private final GoogleApiClient client;
	private WeakReference<SignInButton> signInButton;
	private WeakReference<Button> signOutButton;
	private final WeakReference<FragmentActivity> activityWeakReference;

	private String token = null;
	private boolean resolvingError = false;

	private static Signin instance = null;

	private Activity getActivity() {
		return activityWeakReference != null ? activityWeakReference.get() : null;
	}

	public static Signin getInstance(@NonNull FragmentActivity fragmentActivity) {
		if (instance == null)
			instance = new Signin(fragmentActivity);
		return instance;
	}

	public static Signin getInstance(@NonNull Context context) {
		if (instance == null)
			instance = new Signin(context);
		return instance;
	}

	public static String getToken(@NonNull Context context) {
		return getInstance(context).token;
	}

	public static String getTokenFromResult(@NonNull GoogleSignInAccount acc) {
		String token = acc.getIdToken();
		assert token != null;
		return token;
	}

	private Signin(@NonNull FragmentActivity activity) {
		activityWeakReference = new WeakReference<>(activity);
		client = initializeClient(activity);
		silentSignIn(client);
	}

	private Signin(@NonNull Context context) {
		activityWeakReference = null;
		client = initializeClient(context);
		silentSignIn(client);
	}

	private GoogleApiClient initializeClient(@NonNull Context context) {
		GoogleSignInOptions gso = new GoogleSignInOptions.Builder()
				.requestIdToken(context.getString(R.string.server_client_id))
				.build();
		return new GoogleApiClient.Builder(context)
				.addConnectionCallbacks(this)
				.addOnConnectionFailedListener(this)
				.addApi(Auth.GOOGLE_SIGN_IN_API, gso)
				.build();
	}

	private void silentSignIn(GoogleApiClient googleApiClient) {
		OptionalPendingResult<GoogleSignInResult> pendingResult = Auth.GoogleSignInApi.silentSignIn(googleApiClient);

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

	public Signin setButtons(@NonNull SignInButton signInButton, @NonNull Button signOutButton) {
		this.signInButton = new WeakReference<>(signInButton);
		this.signOutButton = new WeakReference<>(signOutButton);
		updateButtons(token != null);
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
							a.startActivityForResult(Auth.GoogleSignInApi.getSignInIntent(client), RC_SIGN_IN);
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
		if (token != null) {
			Auth.GoogleSignInApi.signOut(client).setResultCallback(status -> onSignedOut());
			token = null;
		} else
			FirebaseCrash.report(new Throwable("Signout called while client has null token. SOMETHING IS WRONG! PANIC!"));
	}

	@Override
	public void onConnectionFailed(@NonNull ConnectionResult result) {
		if (resolvingError) {
			// Already attempting to resolve an error.
			return;
		} else if (result.hasResolution() && getActivity() != null) {
			try {
				resolvingError = true;
				result.startResolutionForResult(getActivity(), REQUEST_RESOLVE_ERROR);
			} catch (IntentSender.SendIntentException e) {
				client.connect();
			}
		} else {
			// Show dialog using GoogleApiAvailability.getErrorDialog()
			//showErrorDialog(result.getErrorCode());
			resolvingError = true;
		}
		/*Activity activity = getActivity();
		if (activity != null) {
			Intent signInIntent = Auth.GoogleSignInApi.getSignInIntent(client);
			activity.startActivityForResult(signInIntent, RC_SIGN_IN);
		}*/
	}

	@Override
	public void onConnected(@Nullable Bundle bundle) {
		resolvingError = false;
	}

	@Override
	public void onConnectionSuspended(int i) {

	}
}
