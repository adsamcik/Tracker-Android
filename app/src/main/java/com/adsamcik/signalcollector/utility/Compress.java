package com.adsamcik.signalcollector.utility;


import com.google.firebase.crash.FirebaseCrash;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class Compress {
	private static final int BUFFER = 2048;

	private Compress() {}

	public static File zip(final String[] files, final String zipFile) {
		try  {
			BufferedInputStream origin;
			FileOutputStream dest = new FileOutputStream(zipFile);

			ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(dest));

			byte data[] = new byte[BUFFER];

			for (String _file : files) {
				FileInputStream fi = new FileInputStream(_file);
				origin = new BufferedInputStream(fi, BUFFER);
				ZipEntry entry = new ZipEntry(_file.substring(_file.lastIndexOf("/") + 1));
				out.putNextEntry(entry);
				int count;
				while ((count = origin.read(data, 0, BUFFER)) != -1) {
					out.write(data, 0, count);
				}
				origin.close();
			}

			out.close();
			return new File(zipFile);
		} catch(Exception e) {
			e.printStackTrace();
			FirebaseCrash.report(e);
		}
		return null;
	}
}
