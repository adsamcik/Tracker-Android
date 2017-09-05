package com.adsamcik.signalcollector.enums;

import android.support.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static com.adsamcik.signalcollector.enums.ChallengeDifficulty.EASY;
import static com.adsamcik.signalcollector.enums.ChallengeDifficulty.HARD;
import static com.adsamcik.signalcollector.enums.ChallengeDifficulty.MEDIUM;
import static com.adsamcik.signalcollector.enums.ChallengeDifficulty.VERY_EASY;
import static com.adsamcik.signalcollector.enums.ChallengeDifficulty.VERY_HARD;

@IntDef({VERY_EASY, EASY, MEDIUM, HARD, VERY_HARD})
@Retention(RetentionPolicy.SOURCE)
public @interface ChallengeDifficulty {
	int VERY_EASY = 0;
	int EASY = 1;
	int MEDIUM = 2;
	int HARD = 3;
	int VERY_HARD = 4;
}
