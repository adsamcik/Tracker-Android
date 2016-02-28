package com.adsamcik.signalcollector;

import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.adsamcik.signalcollector.Services.TrackerService;

import java.nio.charset.Charset;

public class LoadAndUploadTask extends AsyncTask<String, Void, Void> {
    protected Void doInBackground(String... fileNames) {
        if (fileNames.length == 0) {
            Log.e(DataStore.TAG, "No file names were entered");
            return null;
        } else if (DataStore.getContext() == null) {
            Log.e(DataStore.TAG, "DataStore context is null");
            return null;
        }

        long actualSize = 0;
        long approxSize = TrackerService.approxSize;

        for (String fileName : fileNames) {
            if (fileName == null || fileName.trim().length() == 0) {
                Log.w(DataStore.TAG, "Null or empty file name was in load and upload task");
                continue;
            }

            StringBuilder builder = DataStore.loadStringAsBuilder(fileName);

            if (builder == null || builder.length() == 0) {
                Log.w(DataStore.TAG, "File" + fileName + " did not exist or was empty");
                continue;
            } else {
                builder.setCharAt(0, '[');
                builder.append(']');
            }
            long size = builder.toString().getBytes(Charset.defaultCharset()).length;
            TrackerService.approxSize -= size;
            DataStore.upload(builder.toString(), fileName, size);
            actualSize += size;
        }

        TrackerService.approxSize += actualSize - approxSize;
        if (TrackerService.approxSize < 0)
            TrackerService.approxSize = 0;

        Intent intent = new Intent(MainActivity.StatusReceiver.BROADCAST_TAG);
        if (!TrackerService.isActive || TrackerService.approxSize == 0)
            intent.putExtra("cloudStatus", 0);

        LocalBroadcastManager.getInstance(DataStore.getContext()).sendBroadcast(intent);

        return null;
    }
}
