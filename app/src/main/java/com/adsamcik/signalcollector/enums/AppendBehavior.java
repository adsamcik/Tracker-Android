package com.adsamcik.signalcollector.enums;

import android.support.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static com.adsamcik.signalcollector.enums.AppendBehavior.Any;
import static com.adsamcik.signalcollector.enums.AppendBehavior.First;
import static com.adsamcik.signalcollector.enums.AppendBehavior.FirstFirst;
import static com.adsamcik.signalcollector.enums.AppendBehavior.FirstLast;
import static com.adsamcik.signalcollector.enums.AppendBehavior.Last;

@IntDef({FirstFirst, First, FirstLast, Any, Last})
@Retention(RetentionPolicy.SOURCE)
public @interface AppendBehavior {
	int FirstFirst = 0;
	int First = 1;
	int FirstLast = 2;
	int Any = 3;
	int Last = 4;
}
