package com.adsamcik.signalcollector;

import android.os.AsyncTask;
import android.util.Log;

public class LoadAndUploadTask extends AsyncTask<String, Void, Void> {
    protected Void doInBackground(String... fileNames) {
        if (fileNames.length == 0)
            return null;

        for (String fileName : fileNames) {
            Log.d(DataStore.TAG, fileName);
            if (fileName == null || fileName.trim().length() == 0) {
                Log.w(DataStore.TAG, "Null or empty filename was in load and upload task.");
                continue;
            }

            StringBuilder builder = DataStore.loadStringAsBuilder(fileName);

            if (builder == null || builder.length() == 0) {
                Log.w(DataStore.TAG, "File " + fileName + " does not exist or is empty.");
                return null;
            }
            else {
                DataStore.clearData(fileName);
                builder.setCharAt(0, '[');
                builder.append(']');
            }

            DataStore.upload(builder.toString());
        }
        return null;
    }
}
