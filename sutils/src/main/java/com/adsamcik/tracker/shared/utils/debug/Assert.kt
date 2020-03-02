package com.adsamcik.tracker.shared.utils.debug

/**
 * Assert value is true
 */
fun assertTrue(value: Boolean) {
	if (!value) {
		Reporter.report("Assertion failed. Expected true but got false.")
	}
}

/**
 * Assert value is true
 */
fun assertFalse(value: Boolean) {
	if (value) {
		Reporter.report("Assertion failed. Expected false but got true.")
	}
}

/**
 * Assert value is true.
 *
 * @param expect Expected value.
 * @param actual Actual value.
 */
fun assertEqual(expect: Any, actual: Any) {
	if (expect != actual) {
		Reporter.report("Assertion failed. Expected not equal to actual. Expected: $expect. Actual: $actual.")
	}
}


/**
 * Assert value is more than threshold.
 *
 * @param value Value.
 * @param threshold Threshold.
 *
 */
fun assertIsMore(value: Float, threshold: Float) {
	if (value <= threshold) {
		Reporter.report("Assertion failed. $value ≤ $threshold.")
	}
}

/**
 * Assert value is more or equal to threshold.
 *
 * @param value Value.
 * @param threshold Threshold.
 *
 */
fun assertIsMoreOrEqual(value: Int, threshold: Int) {
	if (value < threshold) {
		assertIsMoreOrEqualError(value.toString(), threshold.toString())
	}
}

/**
 * Assert value is more or equal to threshold.
 *
 * @param value Value.
 * @param threshold Threshold.
 *
 */
fun assertIsMoreOrEqual(value: Long, threshold: Long) {
	if (value < threshold) {
		assertIsMoreOrEqualError(value.toString(), threshold.toString())
	}
}

/**
 * Assert value is more or equal to threshold.
 *
 * @param value Value.
 * @param threshold Threshold.
 *
 */
fun assertIsMoreOrEqual(value: Double, threshold: Double) {
	if (value < threshold) {
		assertIsMoreOrEqualError(value.toString(), threshold.toString())
	}
}

/**
 * Assert value is more or equal to threshold.
 *
 * @param value Value.
 * @param threshold Threshold.
 *
 */
fun assertIsMoreOrEqual(value: Float, threshold: Float) {
	if (value < threshold) {
		assertIsMoreOrEqualError(value.toString(), threshold.toString())
	}
}

private fun assertIsMoreOrEqualError(value: String, threshold: String) {
	Reporter.report("Assertion failed. $value < $threshold.")
}

/**
 * Assert value is less than threshold.
 *
 * @param value Value.
 * @param threshold Threshold.
 *
 */
fun assertIsLess(value: Long, threshold: Long) {
	if (value >= threshold) {
		assertIsLessError(value.toString(), threshold.toString())
	}
}

/**
 * Assert value is less than threshold.
 *
 * @param value Value.
 * @param threshold Threshold.
 *
 */
fun assertIsLess(value: Int, threshold: Int) {
	if (value >= threshold) {
		assertIsLessError(value.toString(), threshold.toString())
	}
}

/**
 * Assert value is less than threshold.
 *
 * @param value Value.
 * @param threshold Threshold.
 *
 */
fun assertIsLess(value: Double, threshold: Double) {
	if (value >= threshold) {
		assertIsLessError(value.toString(), threshold.toString())
	}
}

/**
 * Assert value is less than threshold.
 *
 * @param value Value.
 * @param threshold Threshold.
 *
 */
fun assertIsLess(value: Float, threshold: Float) {
	if (value >= threshold) {
		assertIsLessError(value.toString(), threshold.toString())
	}
}

private fun assertIsLessError(value: String, threshold: String) {
	Reporter.report("Assertion failed. $value ≥ $threshold.")
}


/**
 * Assert value is less or equal to threshold.
 *
 * @param value Value.
 * @param threshold Threshold.
 *
 */
fun assertIsLessOrEqual(value: Float, threshold: Float) {
	if (value > threshold) {
		Reporter.report("Assertion failed. $value > $threshold.")
	}
}
