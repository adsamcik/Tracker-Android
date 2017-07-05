package com.adsamcik.signalcollector.services;

import android.content.pm.PackageManager;
import android.support.v4.content.ContextCompat;

import com.adsamcik.signalcollector.network.Network;
import com.adsamcik.signalcollector.network.Signin;
import com.google.firebase.iid.FirebaseInstanceId;
import com.google.firebase.iid.FirebaseInstanceIdService;

public class InstanceIDListenerService extends FirebaseInstanceIdService {

	/**
	 * Called if InstanceID token is updated. This may occur if the security of
	 * the previous token had been compromised. Note that this is also called
	 * when the InstanceID token is initially generated, so this is where
	 * you retrieve the token.
	 */
	@Override
	public void onTokenRefresh() {
		String refreshedToken = FirebaseInstanceId.getInstance().getToken();
		if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
			Signin.getUserAsync(this, value -> {
				if (value != null)
					Network.register(value.token, refreshedToken, this);
			});
		}
	}

}
