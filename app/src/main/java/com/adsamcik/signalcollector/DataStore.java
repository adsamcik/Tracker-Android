package com.adsamcik.signalcollector;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Debug;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.adsamcik.signalcollector.Services.TrackerService;
import com.google.gson.Gson;
import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.AsyncHttpResponseHandler;
import com.loopj.android.http.RequestParams;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;

import cz.msebera.android.httpclient.Header;

public class DataStore {
    public static final String TAG = "DATA-STORE";
    public static final String DATA_FILE = "dataStore";
    public static final String KEY_FILE_ID = "saveFileID";
    public static final String KEY_SIZE = "totalSize";

    //5242880
    public static final int MAX_FILE_SIZE = 48;

    private static Context context;
    private static boolean uploadRequested;

    public static SharedPreferences getPreferences() {
        if (Setting.sharedPreferences == null) {
            if (context != null)
                Setting.Initialize(PreferenceManager.getDefaultSharedPreferences(context));
            else {
                Log.e(TAG, Log.getStackTraceString(new Throwable("No shared preferences and null context")));
                return null;
            }
        }
        return Setting.sharedPreferences;
    }

    public static void setContext(Context c) {
        context = c;
    }

    public static boolean requestUpload(Context c) {
        uploadRequested = true;
        return updateAutoUploadState(c);
    }

    public static boolean updateAutoUploadState(Context c) {
        if (Setting.sharedPreferences == null)
            Setting.Initialize(PreferenceManager.getDefaultSharedPreferences(c));

        int autoUpload = Setting.sharedPreferences.getInt(Setting.AUTO_UPLOAD, 1);
        if (uploadRequested && autoUpload >= 1) {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);

            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            if (activeNetwork != null &&
                    activeNetwork.isConnectedOrConnecting() &&
                    (activeNetwork.getType() == ConnectivityManager.TYPE_WIFI ||
                            (autoUpload >= 2 &&
                                    activeNetwork.getType() == ConnectivityManager.TYPE_MOBILE &&
                                    !activeNetwork.isRoaming()))) {
                new LoadAndUploadTask().execute(getDataFileNames());
                uploadRequested = false;
                size = TrackerService.approxSize;
                TrackerService.approxSize = 0;
                return true;
            }
        }
        return false;
    }

    static String[] getDataFileNames() {
        SharedPreferences sp = getPreferences();
        if (sp == null)
            return null;
        int maxID = sp.getInt(KEY_FILE_ID, 0);
        String[] fileNames = new String[maxID + 1];
        for (int i = 0; i <= maxID; i++)
            fileNames[i] = DATA_FILE + i;
        return fileNames;
    }

    public static void upload(String data, final String name, final int size) {
        if (data.isEmpty()) return;
        String serialized = "{\"imei\":" + Extensions.getImei() +
                ",\"device\":\"" + Build.MODEL +
                "\",\"manufacturer\":\"" + Build.MANUFACTURER +
                "\",\"api\":" + Build.VERSION.SDK_INT +
                ",\"version\":" + BuildConfig.VERSION_CODE + ",";
        serialized += "\"data\":" + data + "}";

        RequestParams rp = new RequestParams();
        rp.add("imei", Extensions.getImei());
        rp.add("data", serialized);
        new AsyncHttpClient().post(Network.URL_DATA_UPLOAD, rp, new AsyncHttpResponseHandler(Looper.getMainLooper()) {

            @Override
            public void onSuccess(int statusCode, Header[] headers, byte[] responseBody) {
                Intent intent = new Intent(Setting.UPLOAD_BROADCAST_TAG);
                intent.putExtra("size", size);
                LocalBroadcastManager.getInstance(context).sendBroadcast(intent);

                intent = new Intent(MainActivity.StatusReceiver.BROADCAST_TAG);
                if (!TrackerService.isActive || TrackerService.approxSize == 0)
                    intent.putExtra("cloudStatus", 0);
                LocalBroadcastManager.getInstance(context).sendBroadcast(intent);

                Log.d(TAG, "Uploaded " + name);
            }

            @Override
            public void onFailure(int statusCode, Header[] headers, byte[] responseBody, Throwable error) {
                Intent intent = new Intent(MainActivity.StatusReceiver.BROADCAST_TAG);
                intent.putExtra("cloudStatus", 1);
                LocalBroadcastManager.getInstance(context).sendBroadcast(intent);

                TrackerService.approxSize += size;

                Log.e(TAG, "Upload failed " + name + " code " + statusCode);
            }
        });
    }

    public static boolean saveString(String fileName, String data) {
        try {
            FileOutputStream outputStream = MainActivity.context.openFileOutput(fileName, Context.MODE_PRIVATE);
            OutputStreamWriter osw = new OutputStreamWriter(outputStream);
            osw.write(data);
            osw.close();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public static void deleteFile(String fileName) {
        context.deleteFile(fileName);
    }

    public static void clearAllData() {
        SharedPreferences sp = getPreferences();
        if (sp == null)
            throw new RuntimeException("Data was no cleared! This is a bug.");

        int max = sp.getInt(KEY_SIZE, -1);
        for (int i = 0; i <= max; i++)
            context.deleteFile(DATA_FILE + i);

        sp.edit().remove(KEY_SIZE).apply();
    }

    public static int saveData(String data) {
        SharedPreferences sp = getPreferences();
        if (sp == null)
            return 0;

        int id = sp.getInt(KEY_FILE_ID, 0);
        String fileName = DATA_FILE + id;

        if (!saveStringAppend(fileName, data))
            return 0;


        if (sizeOf(fileName) > MAX_FILE_SIZE)
            sp.edit().putInt(KEY_FILE_ID, ++id).commit();

        int size = data.getBytes(Charset.defaultCharset()).length;
        sp.edit().putInt(KEY_SIZE, sp.getInt(KEY_SIZE, 0) + size).apply();

        Log.d("save", "saved");

        return size;
    }

    public static int sizeOfData() {
        SharedPreferences sp = getPreferences();
        if (sp == null) return -1;
        return sp.getInt(KEY_SIZE, 0);
    }

    public static int sizeOf(String fileName) {
        return loadString(fileName).getBytes(Charset.defaultCharset()).length;
    }

    public static boolean saveStringAppend(String fileName, String data) {
        try {
            FileOutputStream outputStream = MainActivity.context.openFileOutput(fileName, Context.MODE_APPEND);
            OutputStreamWriter osw = new OutputStreamWriter(outputStream);
            osw.write("," + data);
            osw.close();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T loadStringObject(String fileName, Class c) {
        String data = loadString(fileName);
        //field[] f = c.getDeclaredFields();
        try {
            Gson gson = new Gson();
            return (T) gson.fromJson(data, c);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static StringBuilder loadStringAsBuilder(String fileName) {
        if (!exists(fileName)) {
            Log.w(TAG, "file " + fileName + " does not exist");
            return null;
        }

        try {
            FileInputStream fis = context.openFileInput(fileName);
            InputStreamReader isr = new InputStreamReader(fis);
            BufferedReader br = new BufferedReader(isr);
            String receiveString;
            StringBuilder stringBuilder = new StringBuilder();

            while ((receiveString = br.readLine()) != null)
                stringBuilder.append(receiveString);

            isr.close();
            Log.d(TAG, fileName + " loaded size " + stringBuilder.toString());
            return stringBuilder;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static String loadString(String fileName) {
        StringBuilder sb = loadStringAsBuilder(fileName);
        if (sb != null)
            return sb.toString();
        else
            return "";
    }

    public static boolean exists(String fileName) {
        return new File(context.getFilesDir().getAbsolutePath() + "/" + fileName).exists();
    }


    public static String arrayToJSON(Object[] array) {
        if (array == null || array.length == 0)
            return "";
        String out = "[";
        String data;
        for (Object anArray : array) {
            data = objectToJSON(anArray);
            if (!data.equals("")) {
                out += data + ",";
            }
        }

        if (out.equals("["))
            return "";

        out = out.substring(0, out.length() - 1);
        out += "]";
        return out;
    }

    public static String objectToJSON(Object o) {
        if (o == null) return "";
        Class c = o.getClass();
        Field[] fields = c.getFields();
        String out = "{";
        for (Field field : fields) {
            try {
                if (field == null || Modifier.isStatic(field.getModifiers()))
                    continue;
                String typeName = field.getType().getSimpleName();
                String data = "";
                if (field.getType().isArray())
                    data = arrayToJSON((Object[]) field.get(o));
                else if (typeName.equals("double"))
                    data = Double.toString(field.getDouble(o));
                else if (typeName.equals("long"))
                    data = Long.toString(field.getLong(o));
                else if (typeName.equals("float"))
                    data = Float.toString(field.getFloat(o));
                else if (typeName.equals("String")) {
                    String val = (String) field.get(o);
                    if (val != null)
                        data = "\"" + val.replace("\"", "\\\"") + "\"";
                } else if (typeName.equals("int")) {
                    int value = field.getInt(o);
                    if (value != 0)
                        data = Integer.toString(value);
                } else if (typeName.equals("boolean"))
                    data = Boolean.toString(field.getBoolean(o));
                else if (!field.getType().isPrimitive())
                    data = objectToJSON(field.get(o));
                else
                    Log.e("type", "Unknown type " + typeName + " - " + field.getName());

                if (!data.equals("")) {
                    out += "\"" + field.getName() + "\":";
                    out += data;
                    out += ",";
                }
            } catch (Exception e) {
                e.printStackTrace();
                Log.e("Exception", field.getName() + " - " + e.getMessage());
            }
        }
        if (out.length() <= 1) return "";
        out = out.substring(0, out.length() - 1);
        out += "}";
        return out;
    }
}
