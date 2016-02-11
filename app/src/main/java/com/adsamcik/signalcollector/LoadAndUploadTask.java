package com.adsamcik.signalcollector;

import android.os.AsyncTask;

import com.adsamcik.signalcollector.Data.Data;

public class LoadAndUploadTask extends AsyncTask<Data[], Void, String> {
    protected String doInBackground(Data[]... data) {
        StringBuilder builder = DataStore.LoadStringAsBuilder(DataStore.DATA_FILE);
        if (builder == null || builder.length() == 0)
            return (data.length == 0 || data[0] == null || data[0].length > 0) ? "" : DataStore.ArrayToJSON(data[0]);
        else {
            DataStore.clearData(DataStore.DATA_FILE);
            builder.setCharAt(0, '[');

            if (data.length > 0) {
                String array = DataStore.ArrayToJSON(data[0]);
                builder.append(",").append(array.substring(1, array.length()));
            } else
                builder.append(']');
        }
        return builder.toString();
    }

    protected void onPostExecute(String result) {
        if (!result.equals(""))
            DataStore.upload(result);
    }
}
