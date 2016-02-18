package com.adsamcik.signalcollector;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.preference.Preference;
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
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;

import cz.msebera.android.httpclient.Header;

public class DataStore {
    public static final String TAG = "DATA-STORE";
    public static final String DATA_FILE = "dataStore";
    public static final String KEY_FILE_ID = "saveFileID";

    public static final int MAX_FILESIZE = 5242880;

    private static Context context;
    private static boolean uploadRequested;
    private static long size;
    private static String uploadCache;

    private static AsyncHttpResponseHandler uploadResponse = new AsyncHttpResponseHandler() {
        @Override
        public void onSuccess(int statusCode, Header[] headers, byte[] responseBody) {
            Intent intent = new Intent(Setting.UPLOAD_BROADCAST_TAG);
            intent.putExtra("size", uploadCache.getBytes().length);
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
            uploadCache = "";

            intent = new Intent(MainActivity.StatusReceiver.BROADCAST_TAG);
            if (!TrackerService.isActive || TrackerService.approxSize == 0)
                intent.putExtra("cloudStatus", 0);
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent);

            size = 0;
        }

        @Override
        public void onFailure(int statusCode, Header[] headers, byte[] responseBody, Throwable error) {
            saveStringAppend(DATA_FILE, uploadCache.substring(1, uploadCache.length() - 1));
            uploadCache = "";
            Intent intent = new Intent(MainActivity.StatusReceiver.BROADCAST_TAG);
            intent.putExtra("cloudStatus", 1);
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent);

            TrackerService.approxSize += size;
            size = 0;

            /*for (int i=0; i< headers.length; i++)
                if(headers[i] != null) Log.d("response", "test " + headers[i].getName() + " " + headers[i].getValue());
            Log.d("response", "Status " + statusCode + " message " + new String(responseBody));*/
        }
    };

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
                new LoadAndUploadTask().execute();
                uploadRequested = false;
                size = TrackerService.approxSize;
                TrackerService.approxSize = 0;
                return true;
            }
        }
        return false;
    }

    public static void upload(String data) {
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
        uploadCache = data;
        new AsyncHttpClient().post(Network.URL_DATA_UPLOAD, rp, uploadResponse);
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

    public static boolean clearData(String fileName) {
        try {
            MainActivity.context.openFileOutput(fileName, Context.MODE_PRIVATE).close();
            return true;
        } catch (Exception e) {
            Log.d("noFile", "no saved data");
            return false;
        }
    }

    public static boolean saveData(String data) {
        SharedPreferences sp = getPreferences();
        if (sp == null)
            return false;

        int id = sp.getInt(KEY_FILE_ID, 0);
        String fileName = DATA_FILE + id;

        boolean result = saveStringAppend(fileName, data);
        if (sizeOf(fileName) > MAX_FILESIZE)
            sp.edit().putInt(KEY_FILE_ID, ++id).apply();

        return result;
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

    public static Object load(String fileName) {
        try {
            FileInputStream fis = context.openFileInput(fileName);
            ObjectInputStream is = new ObjectInputStream(fis);
            Object object = is.readObject();
            is.close();
            fis.close();
            return object;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T LoadStringObject(String fileName, Class c) {
        String data = loadString(fileName);
        //field[] f = c.getDeclaredFields();
        try {
            Gson gson = new Gson();
            return (T) gson.fromJson(data, c);
            //T object = (T) c.newInstance();

            /*for (Field field : f) {
                field.set(object, );
            }*/
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static StringBuilder loadStringAsBuilder(String fileName) {
        if (!exists(fileName))
            return null;
        try {
            FileInputStream fis = context.openFileInput(fileName);
            InputStreamReader isr = new InputStreamReader(fis);
            BufferedReader br = new BufferedReader(isr);
            String receiveString;
            StringBuilder stringBuilder = new StringBuilder();

            while ((receiveString = br.readLine()) != null) {
                stringBuilder.append(receiveString);
            }

            isr.close();
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
