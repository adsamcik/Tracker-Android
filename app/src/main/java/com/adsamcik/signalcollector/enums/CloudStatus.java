package com.adsamcik.signalcollector.enums;

import android.support.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static com.adsamcik.signalcollector.enums.CloudStatus.ERROR;
import static com.adsamcik.signalcollector.enums.CloudStatus.NO_SYNC_REQUIRED;
import static com.adsamcik.signalcollector.enums.CloudStatus.SYNC_AVAILABLE;
import static com.adsamcik.signalcollector.enums.CloudStatus.SYNC_IN_PROGRESS;
import static com.adsamcik.signalcollector.enums.CloudStatus.SYNC_SCHEDULED;
import static com.adsamcik.signalcollector.enums.CloudStatus.UNKNOWN;

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
