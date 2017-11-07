package com.adsamcik.signalcollector.file;


import com.crashlytics.android.Crashlytics;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class Compress {
	private static final int BUFFER = 2048;

	private final ZipOutputStream zipStream;
	private final File file;

	/**
	 * Constructor for Compression class
	 * It will keep stream active until you call finish
	 *
	 * @param file Zip file (will be overwritten if already exists)
	 * @throws FileNotFoundException throws exception if there is issue with writting to zip file
	 */
	public Compress(File file) throws FileNotFoundException {
		zipStream = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(file)));
		this.file = file;
	}

	/**
	 * Add file to zip from string data
	 *
	 * @param data data
	 * @param name name of the file
	 * @return true if successfully added
	 */
	public boolean add(final String data, final String name) {
		ZipEntry entry = new ZipEntry(name);
		byte[] buffer = data.getBytes();
		try {
			zipStream.putNextEntry(entry);
			zipStream.write(buffer, 0, buffer.length);
			return true;
		} catch (IOException e) {
			Crashlytics.logException(e);
			return false;
		}
	}

	/**
	 * Add file to zip
	 *
	 * @param file file
	 * @return true if successfully added
	 */
	public boolean add(File file) {
		FileInputStream fi;

		try {
			fi = new FileInputStream(file);
		} catch (FileNotFoundException e) {
			Crashlytics.logException(e);
			return false;
		}
		BufferedInputStream origin = new BufferedInputStream(fi, BUFFER);
		ZipEntry entry = new ZipEntry(file.getName());
		byte data[] = new byte[BUFFER];
		try {
			zipStream.putNextEntry(entry);
			int count;
			while ((count = origin.read(data, 0, BUFFER)) != -1)
				zipStream.write(data, 0, count);

			origin.close();
			return true;
		} catch (IOException e) {
			e.printStackTrace();
			Crashlytics.logException(e);
			return false;
		}
	}

	/**
	 * Call after you're done writting into the zip file
	 *
	 * @return zip file
	 * @throws IOException Exception is thrown if error occurred during stream closing
	 */
	public File finish() throws IOException {
		zipStream.close();
		return file;
	}
}
