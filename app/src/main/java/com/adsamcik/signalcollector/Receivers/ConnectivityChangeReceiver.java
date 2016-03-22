package com.adsamcik.signalcollector.Receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.TelephonyManager;

import com.adsamcik.signalcollector.DataStore;
import com.adsamcik.signalcollector.Extensions;

public class ConnectivityChangeReceiver extends BroadcastReceiver {

	@Override
	public void onReceive(Context context, Intent intent) {
		Extensions.initialize((TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE));
		DataStore.updateAutoUploadState(context);
	}
}