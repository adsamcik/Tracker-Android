package com.adsamcik.signalcollector.utility;

import android.app.Activity;
import android.content.Context;
import android.content.IntentSender;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.StringRes;
import android.support.v4.app.FragmentActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.interfaces.IValueCallback;
import com.google.android.gms.auth.api.Auth;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.auth.api.signin.GoogleSignInResult;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.SignInButton;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.OptionalPendingResult;
import com.google.firebase.crash.FirebaseCrash;
import com.google.firebase.iid.FirebaseInstanceId;

import java.lang.ref.WeakReference;
import java.util.concurrent.TimeUnit;


public class Signin implements GoogleApiClient.OnConnectionFailedListener, GoogleApiClient.ConnectionCallbacks {
	public static final int RC_SIGN_IN = 4654;
	private static final int ERROR_REQUEST_CODE = 3543;

	private SigninStatus status = SigninStatus.NOT_SIGNED;

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

	public static Signin signin(@NonNull FragmentActivity fragmentActivity) {
		if (instance == null)
			instance = new Signin(fragmentActivity);
		else if (instance.getActivity() == null) {
			instance.setActivity(fragmentActivity);
			if (instance.status == SigninStatus.SILENT_SIGNIN_FAILED && !instance.resolvingError)
				fragmentActivity.startActivityForResult(Auth.GoogleSignInApi.getSignInIntent(instance.client), RC_SIGN_IN);
		}
		return instance;
	}

	public static Signin signin(@NonNull Context context) {
		if (instance == null)
			instance = new Signin(context);
		return instance;
	}

	public static String getToken(@NonNull Context context) {
		return signin(context).token;
	}

	public static void getTokenAsync(@NonNull Context context, IValueCallback<String> callback) {
		Signin instance = signin(context);
		if (instance.token != null)
			callback.callback(instance.token);
		else
			instance.onSignedCallback = callback;
	}

	public static void removeTokenListener() {
		if(instance == null)
			return;

		instance.onSignedCallback = null;
	}

	public static String getUserID(@NonNull Context context) {
		return Preferences.get(context).getString(Preferences.PREF_USER_ID, null);
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
		if (googleApiClient.isConnected())
			return;

		googleApiClient.connect();
		OptionalPendingResult<GoogleSignInResult> pendingResult = Auth.GoogleSignInApi.silentSignIn(googleApiClient);

		if (pendingResult.isDone()) {
			final GoogleSignInAccount acc = pendingResult.get().getSignInAccount();
			assert acc != null;
			onSignedIn(acc, false);
		} else {
			updateStatus(SigninStatus.SIGNIN_IN_PROGRESS);
			pendingResult.setResultCallback((@NonNull GoogleSignInResult result) -> {
						if (result.isSuccess()) {
							final GoogleSignInAccount acc = result.getSignInAccount();
							assert acc != null;
							onSignedIn(acc, false);
						} else
							updateStatus(SigninStatus.SILENT_SIGNIN_FAILED);
					}
					, 10, TimeUnit.SECONDS);
		}
	}

	public Signin setButtons(@NonNull SignInButton signInButton, @NonNull Button signOutButton) {
		this.signInButton = new WeakReference<>(signInButton);
		this.signOutButton = new WeakReference<>(signOutButton);
		updateStatus(status);
		return this;
	}

	private void updateStatus(SigninStatus signinStatus) {
		status = signinStatus;
		if (signInButton != null && signOutButton != null) {
			SignInButton signInButton = this.signInButton.get();
			Button signOutButton = this.signOutButton.get();
			if (signInButton != null && signOutButton != null) {
				switch (status) {
					case SIGNED:
						signInButton.setVisibility(View.GONE);
						signOutButton.setVisibility(View.VISIBLE);
						signOutButton.setOnClickListener(v -> signout());
						break;
					case SIGNIN_FAILED:
					case SILENT_SIGNIN_FAILED:
					case NOT_SIGNED:
						signInButton.setVisibility(View.VISIBLE);
						signOutButton.setVisibility(View.GONE);
						signInButton.setOnClickListener((v) -> {
							Activity a = getActivity();
							if (a != null) {
								a.startActivityForResult(Auth.GoogleSignInApi.getSignInIntent(client), RC_SIGN_IN);
							}
						});
						break;
					case SIGNIN_IN_PROGRESS:
						signInButton.setVisibility(View.GONE);
						signOutButton.setVisibility(View.GONE);
						break;
				}
			}
		}
	}

	public static void onSignedIn(@NonNull GoogleSignInAccount account, boolean showSnackbar, @NonNull Context context) {
		signin(context).onSignedIn(account, showSnackbar);
	}

	private void onSignedIn(@NonNull GoogleSignInAccount account, boolean showSnackbar) {
		updateStatus(SigninStatus.SIGNED);
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

		//todo uncomment this when server is ready
		//SharedPreferences sp = Preferences.get(context);
		//if (!sp.getBoolean(Preferences.PREF_SENT_TOKEN_TO_SERVER, false)) {
		String token = FirebaseInstanceId.getInstance().getToken();
		Log.d("TOKEN", token);
		if (token != null)
			Network.register(this.token, token);
		else
			FirebaseCrash.report(new Throwable("Token is null"));
		//}
	}

	public static void onSignInFailed(@NonNull Context context) {
		signin(context).updateStatus(SigninStatus.SIGNIN_FAILED);
	}

	private void onSignedOut() {
		updateStatus(SigninStatus.NOT_SIGNED);
		showSnackbar(R.string.signed_out_message);
	}

	private void showSnackbar(@StringRes int messageResId) {
		Activity a = getActivity();
		if (a != null)
			new SnackMaker(a).showSnackbar(a.getString(messageResId));
	}

	private void signout() {
		if (status == SigninStatus.SIGNED) {
			if (client.isConnected())
				Auth.GoogleSignInApi.signOut(client).setResultCallback(status -> onSignedOut());
			client.disconnect();
			token = null;
			updateStatus(SigninStatus.NOT_SIGNED);
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

	public enum SigninStatus {
		NOT_SIGNED,
		SIGNIN_IN_PROGRESS,
		SIGNED,
		SILENT_SIGNIN_FAILED,
		SIGNIN_FAILED
	}
}
