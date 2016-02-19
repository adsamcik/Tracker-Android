package com.adsamcik.signalcollector;

import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.util.Log;

import com.adsamcik.signalcollector.Services.TrackerService;

import java.nio.charset.Charset;

public class LoadAndUploadTask extends AsyncTask<String, Void, Void> {
    protected Void doInBackground(String... fileNames) {
        if (fileNames.length == 0)
            return null;

        long actualSize = 0;
        long approxSize = TrackerService.approxSize;

        for (String fileName : fileNames) {
            Log.d(DataStore.TAG, "Loading " + fileName);
            if (fileName == null || fileName.trim().length() == 0) {
                Log.w(DataStore.TAG, "Null or empty file name was in load and upload task.");
                continue;
            }

            StringBuilder builder = DataStore.loadStringAsBuilder(fileName);

            if (builder == null || builder.length() == 0) {
                return null;
            }
            else {
                DataStore.deleteFile(fileName);
                builder.setCharAt(0, '[');
                builder.append(']');
            }
            long size = builder.toString().getBytes(Charset.defaultCharset()).length;
            TrackerService.approxSize -= size;
            DataStore.upload(builder.toString(), fileName, size);
            actualSize += size;
        }

        TrackerService.approxSize += actualSize - approxSize;
        if(TrackerService.approxSize > 0)
            TrackerService.approxSize = 0;
        return null;
    }
}
