package com.adsamcik.signalcollector.services;

import android.util.Log;

import com.adsamcik.signalcollector.classes.Network;
import com.google.firebase.iid.FirebaseInstanceId;
import com.google.firebase.iid.FirebaseInstanceIdService;

public class InstanceIDListenerService extends FirebaseInstanceIdService {

	/**
	 * Called if InstanceID token is updated. This may occur if the security of
	 * the previous token had been compromised. Note that this is also called
	 * when the InstanceID token is initially generated, so this is where
	 * you retrieve the token.
	 */
	//[START refresh_token]
	@Override
	public void onTokenRefresh() {
		// Get updated InstanceID token.
		String refreshedToken = FirebaseInstanceId.getInstance().getToken();
		//Log.d("IIDL_SERVICE", "Refreshed token: " + refreshedToken);
		Network.registerToken(refreshedToken, getApplicationContext());
	}

}
