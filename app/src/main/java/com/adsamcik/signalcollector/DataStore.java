package com.adsamcik.signalcollector;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
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

import cz.msebera.android.httpclient.Header;

public class DataStore {
    public static final String DATA_FILE = "dataStore";
    private static Context context;
    private static boolean uploadRequested;
    private static boolean uploadInProgress = false;
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

    public static void setContext(Context c) {
        context = c;
    }

    public static boolean requestUpload(Context c) {
        uploadRequested = true;
        return updateAutoUploadState(c);
    }

    public static boolean updateAutoUploadState(Context c) {
        if (uploadInProgress) return false;
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
        Log.d("data", data);
        new AsyncHttpClient().post(Setting.URL_DATA_UPLOAD, rp, uploadResponse);
    }

    public static <T> boolean save(String fileName, T data) {
        try {
            FileOutputStream fos = context.openFileOutput(fileName, Context.MODE_APPEND);
            ObjectOutputStream os = new ObjectOutputStream(fos);
            os.writeObject(data);
            os.close();
            fos.close();
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static boolean saveStringObject(String fileName, Object data) {
        return saveString(fileName, new Gson().toJson(data));
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
        String data = LoadString(fileName);
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

    public static StringBuilder LoadStringAsBuilder(String fileName) {
        if (!Exists(fileName))
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

    public static String LoadString(String fileName) {
        StringBuilder sb = LoadStringAsBuilder(fileName);
        if (sb != null)
            return sb.toString();
        else
            return "";
    }

    public static boolean Exists(String fileName) {
        return new File(context.getFilesDir().getAbsolutePath() + "/" + fileName).exists();
    }


    public static String ArrayToJSON(Object[] array) {
        if (array == null || array.length == 0)
            return "";
        String out = "[";
        String data;
        for (Object anArray : array) {
            data = ObjectToJSON(anArray);
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

    public static String ObjectToJSON(Object o) {
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
                    data = ArrayToJSON((Object[]) field.get(o));
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
                    data = ObjectToJSON(field.get(o));
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
