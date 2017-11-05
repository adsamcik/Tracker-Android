package com.adsamcik.signalcollector.utility;

public class EArray {
	private EArray() {
	}

	public static short[] avgEvery(short[] array, int batchSize) {
		int left = array.length % batchSize;
		short[] avg = new short[array.length / batchSize + (left > 0 ? 1 : 0)];
		int i = 0;
		for (; i < avg.length; i++)
			avg[i] = avg(array, i * batchSize, batchSize);

		return avg;
	}

	public static short avg(short[] array, int startIndex, int batchSize) {
		if (startIndex >= array.length)
			throw new IllegalArgumentException("Start index must be smaller than array length");

		//first index that shouldn't be used
		int endIndex;
		if (startIndex + batchSize >= array.length) {
			endIndex = array.length;
			batchSize = array.length - startIndex;
		} else
			endIndex = startIndex + batchSize;

		int average = array[startIndex];
		for (int i = startIndex + 1; i < endIndex; i++)
			average += array[i];

		return (short) (average / batchSize);
	}

	public static int sum(short[] array) {
		int sum = 0;
		for (short s : array)
			sum += s;
		return sum;
	}

	public static int sumAbs(short[] array) {
		int sum = 0;
		for (short s : array)
			sum += Math.abs(s);
		return sum;
	}

	public static short avg(short[] array) {
		return (short) (sum(array) / array.length);
	}

	public static short avgAbs(short[] array) {
		return (short) (sumAbs(array) / array.length);
	}
}
