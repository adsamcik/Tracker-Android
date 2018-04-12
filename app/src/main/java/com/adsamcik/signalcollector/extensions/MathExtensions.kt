package com.adsamcik.signalcollector.extensions

/**
 * Returns a pseudo-random short n, where n >= 0 && n < 32767.
 * This method reuses a single instance of Random.
 * This method is thread-safe because access to the Random is synchronized, but this harms scalability.
 * Applications may find a performance benefit from allocating a Random for each of their threads.
 */
fun Math.shortRandom(): Short {
    return (Math.random() * Short.MAX_VALUE).toShort()
}