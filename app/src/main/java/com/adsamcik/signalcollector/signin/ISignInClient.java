package com.adsamcik.signalcollector.signin;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.support.annotation.NonNull;

import com.adsamcik.signalcollector.interfaces.IContextValueCallback;

interface ISignInClient {
	int RC_SIGN_IN = 4654;

	void signIn(@NonNull final Activity activity, @NonNull IContextValueCallback<Context, User> userValueCallback);

	void signInSilent(@NonNull final Context context, @NonNull IContextValueCallback<Context, User> userValueCallback);

	void signOut(@NonNull final Context context);

	void onSignInResult(@NonNull Activity activity, int resultCode, Intent data);
}
