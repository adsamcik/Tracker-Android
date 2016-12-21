package com.adsamcik.signalcollector.utility;


import android.content.Context;
import android.support.annotation.NonNull;

import com.google.firebase.analytics.FirebaseAnalytics;

public class FirebaseAssist {
	public static final String STOP_TILL_RECHARGE_EVENT = "stop_till_recharge";
	public static final String STOP_EVENT = "notification_stop";
	public static final String MANUAL_UPLOAD_EVENT = "manual_upload";

	public static final String PARAM_SOURCE = "source";

	public static final String autoTrackingString = "auto_tracking";
	public static final String autoUploadString = "auto_upload";
	public static final String uploadNotificationString = "upload_notifications";

	public static void updateValue(@NonNull Context context, @NonNull String name, @NonNull String value) {
		FirebaseAnalytics.getInstance(context).setUserProperty(name, value);
	}
}
