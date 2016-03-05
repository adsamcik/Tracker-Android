package com.adsamcik.signalcollector.Services;

import android.content.Intent;

public class InstanceIDListenerService extends com.google.android.gms.iid.InstanceIDListenerService {

	/**
	 * Called if InstanceID token is updated. This may occur if the security of
	 * the previous token had been compromised. This call is initiated by the
	 * InstanceID provider.
	 */
	// [START refresh_token]
	@Override
	public void onTokenRefresh() {
		// Fetch updated Instance ID token and notify our app's server of any changes (if applicable).
		Intent intent = new Intent(this, RegistrationIntentService.class);
		startService(intent);
	}
}
