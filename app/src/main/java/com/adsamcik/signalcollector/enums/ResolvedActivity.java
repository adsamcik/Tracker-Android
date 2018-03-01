package com.adsamcik.signalcollector.enums;

import android.support.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static com.adsamcik.signalcollector.enums.ResolvedActivity.IN_VEHICLE;
import static com.adsamcik.signalcollector.enums.ResolvedActivity.ON_FOOT;
import static com.adsamcik.signalcollector.enums.ResolvedActivity.STILL;
import static com.adsamcik.signalcollector.enums.ResolvedActivity.UNKNOWN;

@IntDef({STILL, ON_FOOT, IN_VEHICLE, UNKNOWN})
@Retention(RetentionPolicy.SOURCE)
@interface ResolvedActivity {
	int STILL = 0;
	int ON_FOOT = 1;
	int IN_VEHICLE = 2;
	int UNKNOWN = 3;
}
