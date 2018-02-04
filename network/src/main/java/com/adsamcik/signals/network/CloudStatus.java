package com.adsamcik.signals.network;

import android.support.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static com.adsamcik.signals.network.CloudStatus.ERROR;
import static com.adsamcik.signals.network.CloudStatus.NO_SYNC_REQUIRED;
import static com.adsamcik.signals.network.CloudStatus.SYNC_AVAILABLE;
import static com.adsamcik.signals.network.CloudStatus.SYNC_IN_PROGRESS;
import static com.adsamcik.signals.network.CloudStatus.SYNC_SCHEDULED;
import static com.adsamcik.signals.network.CloudStatus.UNKNOWN;

@IntDef({UNKNOWN, NO_SYNC_REQUIRED, SYNC_AVAILABLE, SYNC_SCHEDULED, SYNC_IN_PROGRESS, ERROR})
@Retention(RetentionPolicy.SOURCE)
public @interface CloudStatus {
	int UNKNOWN = -1;
	int NO_SYNC_REQUIRED = 0;
	int SYNC_AVAILABLE = 1;
	int SYNC_SCHEDULED = 2;
	int SYNC_IN_PROGRESS = 3;
	int ERROR = 4;
}
