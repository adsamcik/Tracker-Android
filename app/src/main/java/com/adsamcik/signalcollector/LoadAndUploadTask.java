package com.adsamcik.signalcollector;

import android.content.SharedPreferences;
import android.os.AsyncTask;

import com.adsamcik.signalcollector.Data.Data;

public class LoadAndUploadTask extends AsyncTask<String, Void, String> {
    protected String doInBackground(String... fileNames) {
        if (fileNames.length == 0 || fileNames[0] == null || fileNames[0].trim().length() == 0)
            return null;

        StringBuilder builder = DataStore.loadStringAsBuilder(fileNames[0]);
        if (builder == null || builder.length() == 0)
            return null;
        else {
            DataStore.clearData(fileNames[0]);
            builder.setCharAt(0, '[');

            builder.append(']');
        }
        return builder.toString();
    }

    protected void onPostExecute(String result) {
        if (result != null && !result.equals(""))
            DataStore.upload(result);
    }
}
