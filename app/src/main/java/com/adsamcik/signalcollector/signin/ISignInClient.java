package com.adsamcik.signalcollector.signin;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.support.annotation.NonNull;

import com.adsamcik.signalcollector.interfaces.IValueCallback;

interface ISignInClient {
	int RC_SIGN_IN = 4654;

	void signIn(@NonNull final Activity activity, @NonNull IValueCallback<User> userValueCallback);
	void signInSilent(@NonNull final Context context, @NonNull IValueCallback<User> userValueCallback);
	void signOut(@NonNull final Context context);
	void onSignInResult(@NonNull Activity activity,int resultCode, Intent data);
}
