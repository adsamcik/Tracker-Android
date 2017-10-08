package com.adsamcik.signalcollector.utility;

public class EMath {
	public static int step(int value, int direction, final int step) {
		int increment = Math.abs(direction) / step * sign(direction);
		return value + increment * step;
	}

	public static int step(int value, final int step) {
		return  value /= step;
	}

	public static int sign(int value) {
		if(value > 0)
			return 1;
		else if(value < 0)
			return -1;
		else
			return 0;

	}
}
