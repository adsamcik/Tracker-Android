package com.adsamcik.signalcollector.signin;


import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.support.annotation.NonNull;

import com.adsamcik.signalcollector.R;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;

public class GoogleSignInSignalsClient implements ISignInClient {
	private GoogleSignInClient client;

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
	public void signIn(@NonNull Activity activity) {
		client = GoogleSignIn.getClient(activity, getOptions(activity));

		OnCompleteListener<GoogleSignInAccount> onCompleteListener = task ->  {
			try {
				Signin.onSignedIn(task.getResult(ApiException.class), activity);
			} catch (ApiException e) {
				Intent signInIntent = client.getSignInIntent();
				activity.startActivityForResult(signInIntent, RC_SIGN_IN);
			}
		};
		silentSignInInternal(onCompleteListener);
	}

	@Override
	public void signInSilent(@NonNull Context context) {
		client = GoogleSignIn.getClient(context, getOptions(context));
		OnCompleteListener<GoogleSignInAccount> onCompleteListener = task ->  {
			try {
				Signin.onSignedIn(task.getResult(ApiException.class), context);
			} catch (ApiException e) {
				//do nothing
			}
		};
		silentSignInInternal(onCompleteListener);
	}

	@Override
	public void signOut(@NonNull Activity activity) {
		client.signOut().addOnCompleteListener(activity, task -> Signin.onSignOut());
	}
}
