package com.adsamcik.signalcollector.utility

object Constants {
    const val NOISE_ENABLED = true

    //Time constats
    const val SECOND_IN_MILLISECONDS = 1000L
    const val MINUTE_IN_MILLISECONDS = 60 * SECOND_IN_MILLISECONDS
    const val HOUR_IN_MILLISECONDS = 60 * MINUTE_IN_MILLISECONDS
    const val DAY_IN_MILLISECONDS = 24 * HOUR_IN_MILLISECONDS
    const val DAY_IN_MINUTES = DAY_IN_MILLISECONDS / MINUTE_IN_MILLISECONDS
    //Other constants
    const val MIN_COLLECTIONS_SINCE_LAST_UPLOAD = 300
    /**
     * 10^3
     */
    private const val U_DECIMAL = 1000
    //Units
    //use prefix U_
    const val U_KILOBYTE = U_DECIMAL
    const val U_MEGABYTE = U_KILOBYTE * U_DECIMAL
    /**
     * (10^3) / 4 => 250
     */
    private const val U_QUARTER_DECIMAL = U_DECIMAL / 4
    /**
     * 2^10
     */
    private const val U_BINARY = 1024
    const val U_KIBIBYTE = U_BINARY
    const val U_MEBIBYTE = U_KIBIBYTE * U_BINARY
    /**
     * 2^10 / 4 => 2^8
     */
    private const val U_QUARTER_BINARY = U_BINARY / 4
    //File sizes
    const val MIN_BACKGROUND_UPLOAD_FILE_SIZE = U_KIBIBYTE * U_QUARTER_BINARY * 2
    const val MIN_USER_UPLOAD_FILE_SIZE = U_KIBIBYTE * U_QUARTER_BINARY

    //Base units to make sure there are no typos
    const val MAX_DATA_FILE_SIZE = U_MEBIBYTE
}
