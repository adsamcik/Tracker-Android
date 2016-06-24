package com.adsamcik.signalcollector;

import android.content.Intent;
import android.os.AsyncTask;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.adsamcik.signalcollector.Services.TrackerService;

import java.nio.charset.Charset;

public class LoadAndUploadTask extends AsyncTask<String, Void, Void> {
	static boolean isUploading = false;
	static String[] files;
	static int index;

	protected Void doInBackground(String... fileNames) {
		if (fileNames.length == 0) {
			Log.e(DataStore.TAG, "No file names were entered");
			return null;
		} else if (DataStore.getContext() == null) {
			Log.e(DataStore.TAG, "DataStore context is null");
			return null;
		} else if (isUploading) {
			Log.w(DataStore.TAG, "Upload already in progress");
			return null;
		}
		files = fileNames;

		isUploading = true;
		TrackerService.approxSize = DataStore.sizeOfData();

		for (index = 0; index < files.length; index++) {
			String fileName = files[index];
			if (fileName == null || fileName.trim().length() == 0) {
				Log.e(DataStore.TAG, "Null or empty file name was in load and upload task. This should not happen.");
				continue;
			}

			StringBuilder builder = DataStore.loadStringAsBuilder(fileName);

			if (builder == null || builder.length() == 0) {
				Log.e(DataStore.TAG, "File" + fileName + " did not exist or was empty. This should not happen.");
				continue;
			} else {
				builder.setCharAt(0, '[');
				builder.append(']');
			}

			long size = builder.toString().getBytes(Charset.defaultCharset()).length;
			DataStore.upload(builder.toString(), fileName, size);
		}

		if (TrackerService.approxSize < 0)
			TrackerService.approxSize = 0;

		Intent intent = new Intent(MainActivity.StatusReceiver.BROADCAST_TAG);
		if (TrackerService.approxSize == 0)
			intent.putExtra("cloudStatus", 0);
		LocalBroadcastManager.getInstance(DataStore.getContext()).sendBroadcast(intent);

		TrackerService.onUploadComplete(fileNames.length - 1);

		DataStore.recountDataSize();

		isUploading = false;
		return null;
	}

	@Override
	protected void onCancelled() {
		if (isUploading) {
			if (TrackerService.approxSize < 0)
				TrackerService.approxSize = 0;

			Intent intent = new Intent(MainActivity.StatusReceiver.BROADCAST_TAG);
			if (TrackerService.approxSize == 0)
				intent.putExtra("cloudStatus", 0);
			LocalBroadcastManager.getInstance(DataStore.getContext()).sendBroadcast(intent);
			if (index > 0)
				TrackerService.onUploadComplete(index - 1);

			DataStore.recountDataSize();
		}
		super.onCancelled();
	}
}
