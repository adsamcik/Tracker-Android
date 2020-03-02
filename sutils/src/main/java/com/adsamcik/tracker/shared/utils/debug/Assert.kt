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
fun assertMore(value: Long, threshold: Long) {
	if (value <= threshold) {
		assertMoreError(value.toString(), threshold.toString())
	}
}

/**
 * Assert value is more than threshold.
 *
 * @param value Value.
 * @param threshold Threshold.
 *
 */
fun assertMore(value: Int, threshold: Int) {
	if (value <= threshold) {
		assertMoreError(value.toString(), threshold.toString())
	}
}

/**
 * Assert value is more than threshold.
 *
 * @param value Value.
 * @param threshold Threshold.
 *
 */
fun assertMore(value: Double, threshold: Double) {
	if (value <= threshold) {
		assertMoreError(value.toString(), threshold.toString())
	}
}

/**
 * Assert value is more than threshold.
 *
 * @param value Value.
 * @param threshold Threshold.
 *
 */
fun assertMore(value: Float, threshold: Float) {
	if (value <= threshold) {
		assertMoreError(value.toString(), threshold.toString())
	}
}

private fun assertMoreError(value: String, threshold: String) {
	Reporter.report("Assertion failed. $value ≤ $threshold.")
}

/**
 * Assert value is more or equal to threshold.
 *
 * @param value Value.
 * @param threshold Threshold.
 *
 */
fun assertMoreOrEqual(value: Int, threshold: Int) {
	if (value < threshold) {
		assertMoreOrEqualError(value.toString(), threshold.toString())
	}
}

/**
 * Assert value is more or equal to threshold.
 *
 * @param value Value.
 * @param threshold Threshold.
 *
 */
fun assertMoreOrEqual(value: Long, threshold: Long) {
	if (value < threshold) {
		assertMoreOrEqualError(value.toString(), threshold.toString())
	}
}

/**
 * Assert value is more or equal to threshold.
 *
 * @param value Value.
 * @param threshold Threshold.
 *
 */
fun assertMoreOrEqual(value: Double, threshold: Double) {
	if (value < threshold) {
		assertMoreOrEqualError(value.toString(), threshold.toString())
	}
}

/**
 * Assert value is more or equal to threshold.
 *
 * @param value Value.
 * @param threshold Threshold.
 *
 */
fun assertMoreOrEqual(value: Float, threshold: Float) {
	if (value < threshold) {
		assertMoreOrEqualError(value.toString(), threshold.toString())
	}
}

private fun assertMoreOrEqualError(value: String, threshold: String) {
	Reporter.report("Assertion failed. $value < $threshold.")
}

/**
 * Assert value is less than threshold.
 *
 * @param value Value.
 * @param threshold Threshold.
 *
 */
fun assertLess(value: Long, threshold: Long) {
	if (value >= threshold) {
		assertLessError(value.toString(), threshold.toString())
	}
}

/**
 * Assert value is less than threshold.
 *
 * @param value Value.
 * @param threshold Threshold.
 *
 */
fun assertLess(value: Int, threshold: Int) {
	if (value >= threshold) {
		assertLessError(value.toString(), threshold.toString())
	}
}

/**
 * Assert value is less than threshold.
 *
 * @param value Value.
 * @param threshold Threshold.
 *
 */
fun assertLess(value: Double, threshold: Double) {
	if (value >= threshold) {
		assertLessError(value.toString(), threshold.toString())
	}
}

/**
 * Assert value is less than threshold.
 *
 * @param value Value.
 * @param threshold Threshold.
 *
 */
fun assertLess(value: Float, threshold: Float) {
	if (value >= threshold) {
		assertLessError(value.toString(), threshold.toString())
	}
}

private fun assertLessError(value: String, threshold: String) {
	Reporter.report("Assertion failed. $value ≥ $threshold.")
}


/**
 * Assert value is less or equal to threshold.
 *
 * @param value Value.
 * @param threshold Threshold.
 *
 */
fun assertLessOrEqual(value: Float, threshold: Float) {
	if (value > threshold) {
		Reporter.report("Assertion failed. $value > $threshold.")
	}
}
