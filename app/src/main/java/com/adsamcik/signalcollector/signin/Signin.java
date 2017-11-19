package com.adsamcik.signalcollector.signin;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.StringRes;
import android.support.v4.app.FragmentActivity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

import com.adsamcik.signalcollector.BuildConfig;
import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.activities.MainActivity;
import com.adsamcik.signalcollector.file.DataStore;
import com.adsamcik.signalcollector.fragments.FragmentSettings;
import com.adsamcik.signalcollector.interfaces.IContextValueCallback;
import com.adsamcik.signalcollector.interfaces.IValueCallback;
import com.adsamcik.signalcollector.network.Network;
import com.adsamcik.signalcollector.network.NetworkLoader;
import com.adsamcik.signalcollector.utility.Assist;
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

import junit.framework.Assert;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import static com.adsamcik.signalcollector.signin.Signin.SigninStatus.NOT_SIGNED;
import static com.adsamcik.signalcollector.signin.Signin.SigninStatus.SIGNED;
import static com.adsamcik.signalcollector.signin.Signin.SigninStatus.SIGNED_NO_DATA;
import static com.adsamcik.signalcollector.signin.Signin.SigninStatus.SIGNIN_FAILED;
import static com.adsamcik.signalcollector.signin.Signin.SigninStatus.SIGNIN_IN_PROGRESS;
import static com.adsamcik.signalcollector.signin.Signin.SigninStatus.SILENT_SIGNIN_FAILED;


public class Signin {
	public static final int RC_SIGN_IN = 4654;
	private static final int ERROR_REQUEST_CODE = 3543;

	private static Signin instance = null;

	private @SigninStatus
	int status = NOT_SIGNED;

	private WeakReference<SignInButton> signInButton;
	private WeakReference<LinearLayout> signedInMenu;
	private WeakReference<Activity> activityWeakReference;

	private User user = null;

	private final ArrayList<IValueCallback<User>> onSignedCallbackList = new ArrayList<>(2);

	private IContextValueCallback<Context, User> onSignInInternal = (context, value) -> {
		callOnSigninCallbacks();
		if (value == null)
			updateStatus(SIGNIN_FAILED, context);
		else if(value.isServerDataAvailable())
			updateStatus(SIGNED, context);
		else
			updateStatus(SIGNED_NO_DATA, context);
	};

	private ISignInClient client;

	private Activity getActivity() {
		return activityWeakReference != null ? activityWeakReference.get() : null;
	}

	private void setActivity(@NonNull Activity activity) {
		activityWeakReference = new WeakReference<>(activity);
	}

	private void initializeClient() {
		if (client == null) {
			if (Assist.isEmulator())
				client = new MockSignInClient();
			else
				client = new GoogleSignInSignalsClient();
		}
	}

	public static Signin signin(@NonNull Activity activity, @Nullable IValueCallback<User> callback, boolean silentOnly) {
		if (instance == null)
			instance = new Signin(activity, callback, silentOnly);
		else if (instance.getActivity() == null || (instance.status == SIGNIN_FAILED && !silentOnly)) {
			if (callback != null)
				instance.onSignedCallbackList.add(callback);
			instance.setActivity(activity);
			if (!silentOnly && ((instance.status == SILENT_SIGNIN_FAILED) || instance.status == SIGNIN_FAILED))
				instance.client.signIn(activity, instance.onSignInInternal);
		} else if (callback != null && instance.user != null)
			callback.callback(instance.user);

		return instance;
	}

	private static Signin signin(@NonNull Context context, @Nullable IValueCallback<User> callback) {
		if (instance == null)
			//instance is assigned in constructor to make it sooner available
			new Signin(context, callback);
		else if (callback != null) {
			if (instance.user != null)
				callback.callback(instance.user);
			else if (instance.status == SIGNIN_FAILED || instance.status == SILENT_SIGNIN_FAILED)
				callback.callback(null);
			else
				instance.onSignedCallbackList.add(callback);
		}

		return instance;
	}


	private Signin(@NonNull Activity activity, @Nullable IValueCallback<User> callback, boolean silent) {
		if (callback != null)
			onSignedCallbackList.add(callback);

		instance = this;

		setActivity(activity);
		initializeClient();
		if (silent)
			client.signInSilent(activity, onSignInInternal);
		else
			client.signIn(activity, onSignInInternal);
	}

	private Signin(@NonNull Context context, @Nullable IValueCallback<User> callback) {
		if (callback != null)
			onSignedCallbackList.add(callback);

		instance = this;

		activityWeakReference = null;
		initializeClient();
		client.signInSilent(context, onSignInInternal);
	}

	public static void getUserAsync(@NonNull Context context, IValueCallback<User> callback) {
		signin(context, callback);
	}

	public @Nullable
	User getUser() {
		return user;
	}

	public static @Nullable
	String getUserID(@NonNull Context context) {
		return Preferences.get(context).getString(Preferences.PREF_USER_ID, null);
	}

	public static void removeOnSignedListeners() {
		if (instance == null)
			return;
		instance.onSignedCallbackList.clear();
	}

	public static boolean isSignedIn() {
		return instance != null && instance.status == SIGNED;
	}

	public void setButtons(@NonNull SignInButton signInButton, @NonNull LinearLayout signedMenu, @NonNull Context context) {
		this.signInButton = new WeakReference<>(signInButton);
		this.signedInMenu = new WeakReference<>(signedMenu);
		updateStatus(status, context);
	}

	private void updateStatus(@SigninStatus int signinStatus, @NonNull Context context) {
		status = signinStatus;
		if (signInButton != null && signedInMenu != null) {
			SignInButton signInButton = this.signInButton.get();
			LinearLayout signedMenu = this.signedInMenu.get();
			if (signInButton != null && signedMenu != null) {
				switch (status) {
					case SIGNED:
						signedMenu.setVisibility(View.VISIBLE);
					case SIGNED_NO_DATA:
						signedMenu.findViewById(R.id.sign_out_button).setOnClickListener(v -> signout(context));
						signInButton.setVisibility(View.GONE);
						break;
					case SIGNIN_FAILED:
					case SILENT_SIGNIN_FAILED:
					case NOT_SIGNED:
						signInButton.setVisibility(View.VISIBLE);
						signedMenu.setVisibility(View.GONE);
						signInButton.setOnClickListener((v) -> {
							Activity a = getActivity();
							if (a != null) {
								initializeClient();
								client.signIn(a, (ctx, user) -> {
									if (user != null) {
										if (user.isServerDataAvailable())
											updateStatus(SIGNED, ctx);
										else {
											updateStatus(SIGNED_NO_DATA, ctx);
											user.addServerDataCallback(value -> {

											});
										}
									}
								});
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

	public static void onSignResult(@NonNull Activity activity, int resultCode, @NonNull Intent intent) {
		GoogleSignInResult result = Auth.GoogleSignInApi.getSignInResultFromIntent(intent);
		if (result.isSuccess()) {
			GoogleSignInAccount acct = result.getSignInAccount();
			assert acct != null;
			instance.client.onSignInResult(activity, resultCode, intent);
		} else {
			new SnackMaker(activity).showSnackbar(activity.getString(R.string.error_failed_signin));
			onSignedInFailed(activity, result.getStatus().getStatusCode());
		}

	}

	private static void onSignedInFailed(@NonNull Context context, int statusCode) {
		Signin signin = signin(context, null);
		signin.onSignInFailed(context);
		signin.showSnackbar(context.getString(R.string.error_failed_signin, statusCode));
	}

	public static boolean isMock() {
		if (instance == null)
			throw new RuntimeException("Cannot ask if is mock before signing in");

		return instance.client instanceof MockSignInClient;
	}

	static void onSignOut(@NonNull Context context) {
		instance.onSignedOut(context);
	}

	private void onSignInFailed(@NonNull final Context context) {
		updateStatus(SIGNIN_FAILED, context);
		callOnSigninCallbacks();
	}

	private synchronized void callOnSigninCallbacks() {
		for (IValueCallback<User> c : onSignedCallbackList)
			c.callback(user);
		onSignedCallbackList.clear();
	}

	private void onSignedOut(@NonNull final Context context) {
		updateStatus(NOT_SIGNED, context);
		showSnackbar(R.string.signed_out_message);
	}

	private void showSnackbar(@StringRes int messageResId) {
		Activity a = getActivity();
		if (a != null && a instanceof MainActivity)
			new SnackMaker(a).showSnackbar(a.getString(messageResId));
	}

	private void showSnackbar(@NonNull String message) {
		Activity a = getActivity();
		if (a != null && a instanceof MainActivity)
			new SnackMaker(a).showSnackbar(message);
	}

	private void signout(@NonNull final Context context) {
		if (status == SIGNED || status == SIGNED_NO_DATA) {
			client.signOut(context);
			client = null;
			user = null;
			updateStatus(NOT_SIGNED, context);
			Network.clearCookieJar(context);
			Preferences.get(context).edit().remove(Preferences.PREF_USER_ID).remove(Preferences.PREF_USER_DATA).remove(Preferences.PREF_USER_STATS).remove(Preferences.PREF_REGISTERED_USER).apply();
			DataStore.delete(context, Preferences.PREF_USER_DATA);
			DataStore.delete(context, Preferences.PREF_USER_STATS);
			callOnSigninCallbacks();
		}
	}

	@IntDef({NOT_SIGNED, SIGNIN_IN_PROGRESS, SIGNED, SIGNED_NO_DATA, SILENT_SIGNIN_FAILED, SIGNIN_FAILED})
	@Retention(RetentionPolicy.SOURCE)
	public @interface SigninStatus {
		int NOT_SIGNED = 0;
		int SIGNIN_IN_PROGRESS = 1;

		int SIGNED = 2;
		int SIGNED_NO_DATA = 3;

		int SILENT_SIGNIN_FAILED = -1;
		int SIGNIN_FAILED = -2;
	}
}
