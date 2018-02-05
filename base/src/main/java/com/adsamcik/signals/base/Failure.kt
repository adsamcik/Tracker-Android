package com.adsamcik.signals.base

class Failure<T> {
    val value: T?

    /**
     * Creates successful instance
     */
    constructor() {
        value = null
    }

    /**
     * Create unsuccessful instance
     *
     * @param message value
     */
    constructor(message: T) {
        this.value = message
    }

    /**
     * Returns failure
     *
     * @return true if failed
     */
    fun hasFailed(): Boolean = value != null
}
