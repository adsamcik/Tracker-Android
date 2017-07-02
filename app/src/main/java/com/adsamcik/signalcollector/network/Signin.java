package com.adsamcik.signalcollector.network;

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
import android.widget.LinearLayout;

import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.interfaces.IValueCallback;
import com.adsamcik.signalcollector.utility.Preferences;
import com.adsamcik.signalcollector.utility.SnackMaker;
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
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.InstanceCreator;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Type;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;
import okhttp3.ResponseBody;


public class Signin implements GoogleApiClient.OnConnectionFailedListener, GoogleApiClient.ConnectionCallbacks {
	public static final int RC_SIGN_IN = 4654;
	private static final int ERROR_REQUEST_CODE = 3543;

	private static Signin instance = null;

	private SigninStatus status = SigninStatus.NOT_SIGNED;

	private final GoogleApiClient client;
	private WeakReference<SignInButton> signInButton;
	private WeakReference<LinearLayout> signedInMenu;
	private WeakReference<FragmentActivity> activityWeakReference;

	private boolean resolvingError = false;

	private User user = null;

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
		return signin(context).user != null ? signin(context).user.token : null;
	}

	public static void getTokenAsync(@NonNull Context context, IValueCallback<String> callback) {
		Signin instance = signin(context);
		if (instance.user != null)
			callback.callback(instance.user.token);
		else
			instance.onSignedCallback = callback;
	}

	public static User getUser(@NonNull Context context) {
		return signin(context).user;
	}

	public static void removeTokenListener() {
		if (instance == null)
			return;

		instance.onSignedCallback = null;
	}

	public static boolean isSignedIn() {
		return instance != null && instance.status == SigninStatus.SIGNED;
	}

	public static String getUserID(@NonNull Context context) {
		return Preferences.get(context).getString(Preferences.PREF_USER_ID, null);
	}

	private Signin(@NonNull FragmentActivity activity) {
		setActivity(activity);
		client = initializeClient(activity);
		silentSignIn(client, activity);
	}

	private Signin(@NonNull Context context) {
		activityWeakReference = null;
		client = initializeClient(context);
		silentSignIn(client, context);
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

	private void silentSignIn(GoogleApiClient googleApiClient, @NonNull Context context) {
		if (googleApiClient.isConnected())
			return;

		googleApiClient.connect();
		OptionalPendingResult<GoogleSignInResult> pendingResult = Auth.GoogleSignInApi.silentSignIn(googleApiClient);

		if (pendingResult.isDone()) {
			final GoogleSignInAccount acc = pendingResult.get().getSignInAccount();
			assert acc != null;
			onSignIn(acc, false, context);
		} else {
			updateStatus(SigninStatus.SIGNIN_IN_PROGRESS, context);
			pendingResult.setResultCallback((@NonNull GoogleSignInResult result) -> {
						if (result.isSuccess()) {
							final GoogleSignInAccount acc = result.getSignInAccount();
							assert acc != null;
							onSignIn(acc, false, context);
						} else
							updateStatus(SigninStatus.SILENT_SIGNIN_FAILED, context);
					}
					, 10, TimeUnit.SECONDS);
		}
	}

	public void setButtons(@NonNull SignInButton signInButton, @NonNull LinearLayout signedMenu, @NonNull Context context) {
		this.signInButton = new WeakReference<>(signInButton);
		this.signedInMenu = new WeakReference<>(signedMenu);
		updateStatus(status, context);
	}

	private void updateStatus(SigninStatus signinStatus, @NonNull Context context) {
		status = signinStatus;
		if (signInButton != null && signedInMenu != null) {
			SignInButton signInButton = this.signInButton.get();
			LinearLayout signedMenu = this.signedInMenu.get();
			if (signInButton != null && signedMenu != null) {
				switch (status) {
					case SIGNED:
						signInButton.setVisibility(View.GONE);
						signedMenu.setVisibility(View.VISIBLE);
						signedMenu.findViewById(R.id.sign_out_button).setOnClickListener(v -> signout(context));
						Network.client(user.token, context).newCall(Network.requestGET(Network.URL_USER_SETTINGS)).enqueue(new Callback() {
							@Override
							public void onFailure(@NonNull Call call, @NonNull IOException e) {
								if (activityWeakReference.get() != null)
									new SnackMaker(activityWeakReference.get()).showSnackbar(R.string.error_connection_failed);
							}

							@Override
							public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
								ResponseBody body = response.body();
								if (body != null) {
									String json = body.string();
									InstanceCreator<User> creator = type -> user;

									Gson gson = new GsonBuilder().registerTypeAdapter(User.class, creator).create();
									user = gson.fromJson(json, User.class);
								} else {
									//todo add job schedule to download data at later date
									Activity activity = getActivity();
									if (activity != null)
										new SnackMaker(activity).showSnackbar(R.string.error_connection_failed);
								}
								response.close();
							}
						});
						break;
					case SIGNIN_FAILED:
					case SILENT_SIGNIN_FAILED:
					case NOT_SIGNED:
						signInButton.setVisibility(View.VISIBLE);
						signedMenu.setVisibility(View.GONE);
						signInButton.setOnClickListener((v) -> {
							Activity a = getActivity();
							if (a != null) {
								a.startActivityForResult(Auth.GoogleSignInApi.getSignInIntent(client), RC_SIGN_IN);
							}
						});
						break;
					case SIGNIN_IN_PROGRESS:
						signInButton.setVisibility(View.GONE);
						signedMenu.setVisibility(View.GONE);
						break;
				}
			}
		}
	}

	public static void onSignedIn(@NonNull GoogleSignInAccount account, boolean showSnackbar, @NonNull Context context) {
		signin(context).onSignIn(account, showSnackbar, context);
	}

	private void onSignIn(@NonNull GoogleSignInAccount account, boolean showSnackbar, @NonNull Context context) {
		if (showSnackbar)
			showSnackbar(R.string.signed_in_message);

		this.user = new User(account.getId(), account.getIdToken());

		assert user.token != null;
		assert user.id != null;

		Preferences.get(context).edit().putString(Preferences.PREF_USER_ID, user.id).apply();

		if (onSignedCallback != null) {
			onSignedCallback.callback(user.token);
			onSignedCallback = null;
		}

		//todo uncomment this when server is ready
		//SharedPreferences sp = Preferences.get(context);
		//if (!sp.getBoolean(Preferences.PREF_SENT_TOKEN_TO_SERVER, false)) {
		String token = FirebaseInstanceId.getInstance().getToken();
		if (token != null)
			Network.register(user.token, token, context);
		else
			FirebaseCrash.report(new Throwable("Token is null"));
		//}

		updateStatus(SigninStatus.SIGNED, context);
	}

	public static void onSignInFailed(@NonNull final Context context) {
		signin(context).updateStatus(SigninStatus.SIGNIN_FAILED, context);
	}

	private void onSignedOut(@NonNull final Context context) {
		updateStatus(SigninStatus.NOT_SIGNED, context);
		showSnackbar(R.string.signed_out_message);
	}

	private void showSnackbar(@StringRes int messageResId) {
		Activity a = getActivity();
		if (a != null)
			new SnackMaker(a).showSnackbar(a.getString(messageResId));
	}

	private void signout(@NonNull final Context context) {
		if (status == SigninStatus.SIGNED) {
			if (client.isConnected())
				Auth.GoogleSignInApi.signOut(client).setResultCallback(status -> onSignedOut(context));
			client.disconnect();
			user = null;
			updateStatus(SigninStatus.NOT_SIGNED, context);
			Network.clearCookieJar();
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
