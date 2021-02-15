@file:Suppress("TooManyFunctions", "unused")

package com.adsamcik.tracker.logger

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
fun assertTrue(value: Boolean, message: () -> String) {
	if (!value) {
		Reporter.report("Assertion failed. Expected true but got false. ${message()}")
	}
}

/**
 * Assert value is true
 *
 * @param value Boolean value
 */
fun assertFalse(value: Boolean) {
	if (value) {
		Reporter.report("Assertion failed. Expected false but got true.")
	}
}

/**
 * Assert value is true
 *
 * @param value Boolean value
 * @param message Message
 */
fun assertFalse(value: Boolean, message: () -> String) {
	if (value) {
		Reporter.report("Assertion failed. Expected false but got true. ${message()}")
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
 * Assert value is true.
 *
 * @param expect Expected value.
 * @param actual Actual value.
 * @param message message
 */
fun assertEqual(expect: Any, actual: Any, message: () -> String) {
	if (expect != actual) {
		Reporter.report("Assertion failed. Expected not equal to actual. Expected: $expect. Actual: $actual. ${message()}")
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
fun assertMore(value: Long, threshold: Long, message: () -> String) {
	if (value <= threshold) {
		assertMoreError(value.toString(), threshold.toString(), message)
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

private fun assertMoreError(value: String, threshold: String, message: () -> String) {
	Reporter.report("Assertion failed. $value ≤ $threshold. ${message()}")
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
		assertLessOrEqualError(value.toString(), threshold.toString())
	}
}

/**
 * Assert value is less or equal to threshold.
 *
 * @param value Value.
 * @param threshold Threshold.
 *
 */
fun assertLessOrEqual(value: Double, threshold: Double) {
	if (value > threshold) {
		assertLessOrEqualError(value.toString(), threshold.toString())
	}
}

/**
 * Assert value is less or equal to threshold.
 *
 * @param value Value.
 * @param threshold Threshold.
 *
 */
fun assertLessOrEqual(value: Int, threshold: Int) {
	if (value > threshold) {
		assertLessOrEqualError(value.toString(), threshold.toString())
	}
}

/**
 * Assert value is less or equal to threshold.
 *
 * @param value Value.
 * @param threshold Threshold.
 *
 */
fun assertLessOrEqual(value: Long, threshold: Long) {
	if (value > threshold) {
		assertLessOrEqualError(value.toString(), threshold.toString())
	}
}

private fun assertLessOrEqualError(value: String, threshold: String) {
	Reporter.report("Assertion failed. $value > $threshold.")
}


/**
 * Assert value is within bounds.
 *
 * @param value Value.
 * @param lowerBoundInclusive Lower inclusive bound.
 * @param upperBoundInclusive Upper inclusive bound.
 *
 */
fun assertWithin(value: Int, lowerBoundInclusive: Int, upperBoundInclusive: Int) {
	if (value !in lowerBoundInclusive..upperBoundInclusive) {
		assertWithinError(
				value.toString(),
				lowerBoundInclusive.toString(),
				upperBoundInclusive.toString()
		)
	}
}

/**
 * Assert value is within bounds.
 *
 * @param value Value.
 * @param lowerBoundInclusive Lower inclusive bound.
 * @param upperBoundInclusive Upper inclusive bound.
 *
 */
fun assertWithin(value: Long, lowerBoundInclusive: Long, upperBoundInclusive: Long) {
	if (value !in lowerBoundInclusive..upperBoundInclusive) {
		assertWithinError(
				value.toString(),
				lowerBoundInclusive.toString(),
				upperBoundInclusive.toString()
		)
	}
}

/**
 * Assert value is within bounds.
 *
 * @param value Value.
 * @param lowerBoundInclusive Lower inclusive bound.
 * @param upperBoundInclusive Upper inclusive bound.
 *
 */
fun assertWithin(value: Double, lowerBoundInclusive: Double, upperBoundInclusive: Double) {
	if (value !in lowerBoundInclusive..upperBoundInclusive) {
		assertWithinError(
				value.toString(),
				lowerBoundInclusive.toString(),
				upperBoundInclusive.toString()
		)
	}
}

/**
 * Assert value is within bounds.
 *
 * @param value Value.
 * @param lowerBoundInclusive Lower inclusive bound.
 * @param upperBoundInclusive Upper inclusive bound.
 *
 */
fun assertWithin(value: Float, lowerBoundInclusive: Float, upperBoundInclusive: Float) {
	if (value !in lowerBoundInclusive..upperBoundInclusive) {
		assertWithinError(
				value.toString(),
				lowerBoundInclusive.toString(),
				upperBoundInclusive.toString()
		)
	}
}

private fun assertWithinError(
		value: String,
		lowerBoundInclusive: String,
		upperBoundInclusive: String
) {
	Reporter.report("Assertion failed. $value is not within bounds ($lowerBoundInclusive..$upperBoundInclusive).")
}

/**
 * Assert value is not null
 */
fun assertNotNull(value: Any?) {
	if (value == null) {
		Reporter.report("Assertion failed. Value is null.")
	}
}
