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
import com.adsamcik.signalcollector.interfaces.IValueCallback;
import com.google.android.gms.auth.api.Auth;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.auth.api.signin.GoogleSignInResult;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.ErrorDialogFragment;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.SignInButton;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.OptionalPendingResult;
import com.google.firebase.crash.FirebaseCrash;

import java.lang.ref.WeakReference;


public class Signin implements GoogleApiClient.OnConnectionFailedListener, GoogleApiClient.ConnectionCallbacks {
	public static final int RC_SIGN_IN = 4654;
	private static final int ERROR_REQUEST_CODE = 3543;

	private final GoogleApiClient client;
	private WeakReference<SignInButton> signInButton;
	private WeakReference<Button> signOutButton;
	private WeakReference<FragmentActivity> activityWeakReference;

	private String token = null;
	private boolean resolvingError = false;

	private static Signin instance = null;

	private IValueCallback<String> onSignedCallback;

	private Activity getActivity() {
		return activityWeakReference != null ? activityWeakReference.get() : null;
	}

	private void setActivity(@NonNull FragmentActivity activity) {
		activityWeakReference = new WeakReference<>(activity);
	}

	public static Signin getInstance(@NonNull FragmentActivity fragmentActivity) {
		if (instance == null)
			instance = new Signin(fragmentActivity);
		else if (instance.getActivity() == null)
			instance.setActivity(fragmentActivity);
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

	public static void getTokenAsync(@NonNull Context context, IValueCallback<String> callback) {
		Signin instance = getInstance(context);
		if (instance.token != null)
			callback.callback(instance.token);
		else
			instance.onSignedCallback = callback;
	}

	private Signin(@NonNull FragmentActivity activity) {
		setActivity(activity);
		client = initializeClient(activity);
		Preferences.get(activity);
		silentSignIn(client);
	}

	private Signin(@NonNull Context context) {
		activityWeakReference = null;
		client = initializeClient(context);
		Preferences.get(context);
		silentSignIn(client);
	}

	private GoogleApiClient initializeClient(@NonNull Context context) {
		GoogleSignInOptions gso = new GoogleSignInOptions.Builder()
				.requestIdToken(context.getString(R.string.server_client_id))
				.requestId()
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
			onSignedIn(acc, false);
		} else {
			pendingResult.setResultCallback((@NonNull GoogleSignInResult result) -> {
						if (result.isSuccess()) {
							final GoogleSignInAccount acc = result.getSignInAccount();
							assert acc != null;
							onSignedIn(acc, false);
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

	public static void onSignedIn(@NonNull GoogleSignInAccount account, boolean showSnackbar, @NonNull Context context) {
		getInstance(context).onSignedIn(account, showSnackbar);
	}

	private void onSignedIn(@NonNull GoogleSignInAccount account, boolean showSnackbar) {
		updateButtons(true);
		if (showSnackbar)
			showSnackbar(R.string.signed_in_message);
		this.token = account.getIdToken();
		assert token != null;

		assert account.getId() != null;
		Preferences.get().edit().putString(Preferences.PREF_USER_ID, account.getId()).apply();

		if (onSignedCallback != null) {
			onSignedCallback.callback(token);
			onSignedCallback = null;
		}
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
			if (client.isConnected())
				Auth.GoogleSignInApi.signOut(client).setResultCallback(status -> onSignedOut());
			client.disconnect();
			token = null;
			updateButtons(false);
		}
	}

	@Override
	public void onConnectionFailed(@NonNull ConnectionResult result) {
		if (resolvingError)
			return;
		Activity activity = getActivity();

		if (activity != null) {
			if (result.hasResolution()) {
				try {
					resolvingError = true;
					result.startResolutionForResult(getActivity(), RC_SIGN_IN);
				} catch (IntentSender.SendIntentException e) {
					client.connect();
				}
			} else {
				GoogleApiAvailability.getInstance().getErrorDialog(activity, result.getErrorCode(), ERROR_REQUEST_CODE).show();
				resolvingError = true;
			}
		}
	}

	@Override
	public void onConnected(@Nullable Bundle bundle) {
		resolvingError = false;
	}

	@Override
	public void onConnectionSuspended(int i) {

	}
}
