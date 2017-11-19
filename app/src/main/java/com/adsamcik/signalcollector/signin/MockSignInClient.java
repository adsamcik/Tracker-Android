package com.adsamcik.signalcollector.signin;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.util.Log;

import com.adsamcik.signalcollector.interfaces.IContextValueCallback;


public class MockSignInClient implements ISignInClient {
	private User u = null;

	@Override
	public void signIn(@NonNull Activity activity, @NonNull IContextValueCallback<Context, User> userValueCallback) {
		signInSilent(activity, userValueCallback);
	}

	@Override
	public void signInSilent(@NonNull Context context, @NonNull IContextValueCallback<Context, User> userValueCallback) {
		if(u != null) {
			userValueCallback.callback(context, u);
			return;
		}

		int left = (int) (System.currentTimeMillis() % 4);
		Log.d("MockSigninSignals", "State " + left);
		if(left == 2) {
			userValueCallback.callback(context, null);
			return;
		}

		u = new User("MOCKED", "BLEH");
		switch (left) {
			case 0:
				u.mockServerData();
				break;
			case 1:
				new Handler().postDelayed(u::mockServerData, 100 + System.currentTimeMillis() % 1000);
				break;
			case 3:
				//no server data received
				break;
		}

		userValueCallback.callback(context, u);
	}

	@Override
	public void signOut(@NonNull Context context) {
		u = null;
	}

	@Override
	public void onSignInResult(@NonNull Activity activity, int resultCode, Intent data) {
		//do nothing
	}

}
