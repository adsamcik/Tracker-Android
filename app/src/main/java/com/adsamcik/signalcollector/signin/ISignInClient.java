package com.adsamcik.signalcollector.signin;

import android.app.Activity;
import android.content.Context;
import android.support.annotation.NonNull;

interface ISignInClient {
	int RC_SIGN_IN = 4654;

	void signIn(@NonNull final Activity activity);
	void signInSilent(@NonNull final Context context);

	void signOut(@NonNull final Activity activity);


}
