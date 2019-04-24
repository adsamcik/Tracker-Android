package com.adsamcik.signalcollector.app

/**
 * Singleton containing many useful constant values used across the application
 * Byte size units prefix U_
 */
object Constants {
	//Time constants
	const val MILLISECONDS_IN_NANOSECONDS: Long = 1000000L
	const val SECOND_IN_MILLISECONDS: Long = 1000L
	const val SECOND_IN_NANOSECONDS: Long = SECOND_IN_MILLISECONDS * MILLISECONDS_IN_NANOSECONDS
	const val MINUTE_IN_MILLISECONDS: Long = 60 * SECOND_IN_MILLISECONDS
	const val HOUR_IN_MILLISECONDS: Long = 60 * MINUTE_IN_MILLISECONDS
	const val DAY_IN_MILLISECONDS: Long = 24 * HOUR_IN_MILLISECONDS
	const val WEEK_IN_MILLISECONDS: Long = 7 * DAY_IN_MILLISECONDS
	const val DAY_IN_MINUTES: Long = DAY_IN_MILLISECONDS / MINUTE_IN_MILLISECONDS
	//Other constants
	const val MIN_COLLECTIONS_SINCE_LAST_UPLOAD: Int = 300
	/**
	 * 10^3
	 */
	private const val U_DECIMAL = 1000
	//Units
	//use prefix U_
	const val U_KILOBYTE: Int = U_DECIMAL
	const val U_MEGABYTE: Int = U_KILOBYTE * U_DECIMAL
	/**
	 * (10^3) / 4 => 250
	 */
	private const val U_QUARTER_DECIMAL = U_DECIMAL / 4
	/**
	 * 2^10
	 */
	const val U_KIBIBYTE: Int = 1024
	const val U_MEBIBYTE: Int = U_KIBIBYTE * U_KIBIBYTE
	/**
	 * 2^10 / 4 => 2^8
	 */
	private const val U_QUARTER_KIBIBYTE = U_KIBIBYTE / 4
	private const val U_EIGHTH_KIBIBYTE = U_KIBIBYTE / 8
	//File sizes
	const val MIN_BACKGROUND_UPLOAD_FILE_LIMIT_SIZE: Int = U_KIBIBYTE * U_EIGHTH_KIBIBYTE
	const val MAX_BACKGROUND_UPLOAD_FILE_LIMIT_SIZE: Int = U_MEBIBYTE
	const val MIN_MAX_DIFF_BGUP_FILE_LIMIT_SIZE: Int = MAX_BACKGROUND_UPLOAD_FILE_LIMIT_SIZE - MIN_BACKGROUND_UPLOAD_FILE_LIMIT_SIZE

	const val MIN_USER_UPLOAD_FILE_SIZE: Int = U_KIBIBYTE * U_QUARTER_KIBIBYTE

	//Base units to make sure there are no typos
	const val MAX_DATA_FILE_SIZE: Int = U_MEBIBYTE
}
