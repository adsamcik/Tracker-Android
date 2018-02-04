package com.adsamcik.signals.utilities.enums;

import android.support.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static com.adsamcik.signals.utilities.enums.ResolvedActivity.IN_VEHICLE;
import static com.adsamcik.signals.utilities.enums.ResolvedActivity.ON_FOOT;
import static com.adsamcik.signals.utilities.enums.ResolvedActivity.STILL;
import static com.adsamcik.signals.utilities.enums.ResolvedActivity.UNKNOWN;

@IntDef({STILL, ON_FOOT, IN_VEHICLE, UNKNOWN})
@Retention(RetentionPolicy.SOURCE)
public @interface ResolvedActivity {
	int STILL = 0;
	int ON_FOOT = 1;
	int IN_VEHICLE = 2;
	int UNKNOWN = 3;
}
