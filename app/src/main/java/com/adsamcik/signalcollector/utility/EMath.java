package com.adsamcik.signalcollector.utility;

public class EMath {
	public static int step(int value, int direction, final int step) {
		int target = value + direction;

		if (target % step != 0 && sign(direction) == sign(target))
			target = target / step + sign(direction);
		else
			target /= step;
		return target * step;
	}

	public static int sign(int value) {
		if (value > 0)
			return 1;
		else if (value < 0)
			return -1;
		else
			return 0;

	}
}
