package com.adsamcik.signalcollector.utility;

public class Constants {
	//Units
	//use prefix U_
	public static final int U_KILOBYTE = 1000;
	public static final int U_KIBIBYTE = 1024;

	public static final int U_MEGABYTE = 1000 * U_KILOBYTE;
	public static final int U_MEBIBYTE = 1024 * U_KILOBYTE;

	//File sizes
	public static final int MIN_UPLOAD_FILE_SIZE = U_KIBIBYTE * 768;
	public static final int MAX_DATA_FILE_SIZE = U_MEBIBYTE;
}
