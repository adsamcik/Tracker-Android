package com.adsamcik.tracker.shared.base.logging

import com.adsamcik.tracker.logging.api.ReporterFacade

@Suppress("TooManyFunctions", "unused")
object Asserts {
    fun assertTrue(value: Boolean) {
        if (!value) ReporterFacade.report("Assertion failed. Expected true but got false.")
    }

    fun assertTrue(value: Boolean, message: () -> String) {
        if (!value) ReporterFacade.report("Assertion failed. Expected true but got false. ${message()}")
    }

    fun assertFalse(value: Boolean) {
        if (value) ReporterFacade.report("Assertion failed. Expected false but got true.")
    }

    fun assertFalse(value: Boolean, message: () -> String) {
        if (value) ReporterFacade.report("Assertion failed. Expected false but got true. ${message()}")
    }

    fun assertEqual(expect: Any, actual: Any) {
        if (expect != actual) ReporterFacade.report("Assertion failed. Expected not equal to actual. Expected: $expect. Actual: $actual.")
    }

    fun assertEqual(expect: Any, actual: Any, message: () -> String) {
        if (expect != actual) ReporterFacade.report("Assertion failed. Expected not equal to actual. Expected: $expect. Actual: $actual. ${message()}")
    }

    /**
     * Asserts that [value] is strictly greater than [threshold].
     */
    fun assertMore(value: Long, threshold: Long) {
        if (value <= threshold) ReporterFacade.report("Assertion failed. Expected $value to be > $threshold.")
    }

    /**
     * Asserts that [value] is strictly greater than [threshold].
     */
    fun assertMore(value: Int, threshold: Int) {
        if (value <= threshold) ReporterFacade.report("Assertion failed. Expected $value to be > $threshold.")
    }

    /**
     * Asserts that [value] is strictly greater than [threshold] with a lazy message.
     */
    fun assertMore(value: Long, threshold: Long, message: () -> String) {
        if (value <= threshold) ReporterFacade.report("Assertion failed. Expected $value to be > $threshold. ${message()}")
    }

    /**
     * Asserts that [value] is strictly greater than [threshold] with a lazy message.
     */
    fun assertMore(value: Int, threshold: Int, message: () -> String) {
        if (value <= threshold) ReporterFacade.report("Assertion failed. Expected $value to be > $threshold. ${message()}")
    }
}
