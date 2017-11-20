package com.adsamcik.signalcollector.services;

import com.adsamcik.signalcollector.network.Network;
import com.adsamcik.signalcollector.signin.Signin;
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
		assert refreshedToken != null;
		Signin.Companion.getUserAsync(this, user -> {
			if (user != null)
				Network.INSTANCE.register(this, user.getToken(), refreshedToken);
		});
	}

}
