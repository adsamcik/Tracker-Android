package com.adsamcik.signalcollector.signin;


import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.support.annotation.NonNull;

import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.interfaces.IContextValueCallback;
import com.adsamcik.signalcollector.network.Network;
import com.adsamcik.signalcollector.network.NetworkLoader;
import com.adsamcik.signalcollector.utility.Preferences;
import com.google.android.gms.auth.api.Auth;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.auth.api.signin.GoogleSignInResult;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.crash.FirebaseCrash;
import com.google.firebase.iid.FirebaseInstanceId;

public class GoogleSignInSignalsClient implements ISignInClient {
	private GoogleSignInClient client;
	private User user;

	private IContextValueCallback<Context, User> userValueCallback;

	private GoogleSignInOptions getOptions(@NonNull Context context) {
		return new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
				.requestIdToken(context.getString(R.string.server_client_id))
				.requestId()
				.build();
	}

	private void silentSignInInternal(@NonNull OnCompleteListener<GoogleSignInAccount> onCompleteListener) {
		if (client == null)
			throw new RuntimeException("Client is null");

		Task<GoogleSignInAccount> task = client.silentSignIn();
		if (task.isSuccessful()) {
			onCompleteListener.onComplete(task);
		} else {
			task.addOnCompleteListener(onCompleteListener);
		}
	}

	@Override
	public void signIn(@NonNull Activity activity, @NonNull IContextValueCallback<Context, User> userValueCallback) {
		client = GoogleSignIn.getClient(activity, getOptions(activity));

		OnCompleteListener<GoogleSignInAccount> onCompleteListener = task -> {
			try {
				user = resolveUser(activity, task.getResult(ApiException.class));
				userValueCallback.callback(activity, user);
			} catch (ApiException e) {
				this.userValueCallback = userValueCallback;
				Intent signInIntent = client.getSignInIntent();
				activity.startActivityForResult(signInIntent, RC_SIGN_IN);
			}
		};
		silentSignInInternal(onCompleteListener);
	}

	@Override
	public void signInSilent(@NonNull Context context, @NonNull IContextValueCallback<Context, User> userValueCallback) {
		client = GoogleSignIn.getClient(context, getOptions(context));
		OnCompleteListener<GoogleSignInAccount> onCompleteListener = task -> {
			try {
				user = resolveUser(context, task.getResult(ApiException.class));
			} catch (ApiException e) {
				//do nothing
			}
		};
		silentSignInInternal(onCompleteListener);
	}

	@Override
	public void signOut(@NonNull Context context) {
		client.signOut().addOnCompleteListener(task -> Signin.Companion.onSignOut(context));
	}

	private User resolveUser(@NonNull Context context, @NonNull GoogleSignInAccount account) {
		User user = new User(account.getId(), account.getIdToken());

		assert user.token != null;
		assert user.id != null;

		Preferences.get(context).edit().putString(Preferences.PREF_USER_ID, user.id).apply();

		if (userValueCallback != null)
			userValueCallback.callback(context, user);

		//todo uncomment this when server is ready
		//SharedPreferences sp = Preferences.get(context);
		//if (!sp.getBoolean(Preferences.PREF_SENT_TOKEN_TO_SERVER, false)) {
		String token = FirebaseInstanceId.getInstance().getToken();
		if (token != null)
			Network.INSTANCE.register(context, user.token, token);
		else
			FirebaseCrash.report(new Throwable("Token is null"));
		//}

		NetworkLoader.INSTANCE.requestStringSigned(Network.INSTANCE.getURL_USER_INFO(), 10, context, Preferences.PREF_USER_DATA, (state, value) -> {
			if (state.isDataAvailable()) {
				user.deserializeServerData(value);
			}
		});

		return user;
	}

	@Override
	public void onSignInResult(@NonNull Activity activity, int resultCode, Intent intent) {
		GoogleSignInResult result = Auth.GoogleSignInApi.getSignInResultFromIntent(intent);
		if (result.isSuccess()) {
			GoogleSignInAccount acct = result.getSignInAccount();
			assert acct != null;
			user = resolveUser(activity, acct);
		}
	}
}
