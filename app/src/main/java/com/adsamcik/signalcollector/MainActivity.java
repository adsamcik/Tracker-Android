package com.adsamcik.signalcollector;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.TabLayout;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.view.ViewPager;
import android.telephony.TelephonyManager;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.TextView;

import com.adsamcik.signalcollector.Fragments.FragmentMain;
import com.adsamcik.signalcollector.Fragments.FragmentMap;
import com.adsamcik.signalcollector.Fragments.FragmentSettings;
import com.adsamcik.signalcollector.Fragments.FragmentStats;
import com.adsamcik.signalcollector.Play.PlayController;
import com.adsamcik.signalcollector.Services.RegistrationIntentService;
import com.adsamcik.signalcollector.Services.TrackerService;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends FragmentActivity {
    public static final String TAG = "SignalCollector";

    public static MainActivity instance;
    public static Context context;
    //static boolean tracking = false;
    //0 - Data are synced
    //1 - Sync required
    //2 - Sync in progress
    //-1 - Error
    static int cloudStatus;
    //0 - No save needed
    //1 - Save required
    //2 - Save in progress
    static int saveStatus;
    PowerManager powerManager;
    FloatingActionButton trackingFab, uploadFab;
    TextView textApproxSize;
    Intent trackerService;

    UpdateInfoReceiver updateReceiver;
    StatusReceiver statusReceiver;

    boolean uploadFabHidden = false, uploadAvailable = false;

    @Override
    protected void onStart() {
        super.onStart();

        if (!Setting.sharedPreferences.getBoolean(Setting.HAS_BEEN_LAUNCHED, false)) {
            startActivity(new Intent(this, IntroActivity.class));
        } else {
            PlayController.setContext(context);
            PlayController.setActivity(this);
            PlayController.initializeGamesClient(findViewById(R.id.container));
            PlayController.initializeActivityClient();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        context = getApplicationContext();
        DataStore.setContext(context);
        Setting.Initialize(PreferenceManager.getDefaultSharedPreferences(context));
        instance = this;

        PlayController.setContext(context);
        PlayController.setActivity(this);

        // Set up the viewPager with the sections adapter.
        final ViewPager viewPager = (ViewPager) findViewById(R.id.container);

        setupViewPager(viewPager);

        viewPager.addOnPageChangeListener(
                new ViewPager.OnPageChangeListener() {
                    int prevPos = 0;

                    @Override
                    public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
                    }

                    @Override
                    public void onPageSelected(int position) {
                        if (position == 1 || prevPos == 1)
                            ((FragmentMap) ((ViewPagerAdapter) viewPager.getAdapter()).getItem(1)).SetActive(position == 1);

                        if (position == 0 || prevPos == 0)
                            enableFabs(position == 0);

                        prevPos = position;
                    }

                    @Override
                    public void onPageScrollStateChanged(int state) {
                    }
                }
        );

        TabLayout tabLayout = (TabLayout) findViewById(R.id.tabs);
        tabLayout.setupWithViewPager(viewPager);

        textApproxSize = (TextView) findViewById(R.id.textApproxSize);

        //Onclick
        trackingFab = (FloatingActionButton) findViewById(R.id.toggleTracking_fab);
        trackingFab.setOnClickListener(
                new View.OnClickListener() {
                    public void onClick(View v) {
                        toggleCollecting(!TrackerService.isActive);
                    }
                }
        );

        uploadFab = (FloatingActionButton) findViewById(R.id.upload_fab);
        uploadFab.setOnClickListener(
                new View.OnClickListener() {
                    public void onClick(View v) {
                        if (cloudStatus == 1) {
                            if (TrackerService.service != null)
                                stopService(TrackerService.service);
                            changeCloudStatus(2);
                            new LoadAndUploadTask().execute(DataStore.getDataFileNames());
                        }
                    }
                }
        );

        trackerService = new Intent(instance, TrackerService.class);

        powerManager = (PowerManager) this.getSystemService(Context.POWER_SERVICE);

        int sizeTemp = DataStore.sizeOfData();
        if (sizeTemp > 0) {
            changeCloudStatus(1);
            textApproxSize.setText(Extensions.humanReadableByteCount(sizeTemp, false));
        } else
            changeCloudStatus(0);


        IntentFilter filter = new IntentFilter(StatusReceiver.BROADCAST_TAG);
        statusReceiver = new StatusReceiver();

        LocalBroadcastManager.getInstance(this).registerReceiver(statusReceiver, filter);

        if (TrackerService.isActive)
            changeTrackerButton(1);

        Extensions.Initialize((TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE));

        if (PlayController.isPlayServiceAvailable()) {
            // Start IntentService to register this application with GCM.
            Intent intent = new Intent(this, RegistrationIntentService.class);
            startService(intent);
        }
    }

    public void setupUpdateReceiver(TextView textTime, TextView textWifiCount, TextView textCurrentCell, TextView textCellCount, TextView textAccuracy, TextView textLatitude, TextView textLongitude, TextView textAltitude, TextView textPressure, TextView textActivity) {
        IntentFilter filter = new IntentFilter(UpdateInfoReceiver.BROADCAST_TAG);
        updateReceiver = new UpdateInfoReceiver(textTime, textWifiCount, textCurrentCell, textCellCount, textAccuracy, textLatitude, textLongitude, textAltitude, textPressure, textActivity);
        LocalBroadcastManager.getInstance(this).registerReceiver(updateReceiver, filter);
    }

    private void setupViewPager(ViewPager viewPager) {
        ViewPagerAdapter adapter = new ViewPagerAdapter(getSupportFragmentManager());
        adapter.addFrag(new FragmentMain(), "MAIN");
        adapter.addFrag(new FragmentMap(), "MAP");
        adapter.addFrag(new FragmentStats(), "STATS");
        adapter.addFrag(new FragmentSettings(), "SETTINGS");
        viewPager.setAdapter(adapter);
    }

    public void toggleCollecting(boolean enable) {
        if ((!TrackerService.isActive && saveStatus == 2) || TrackerService.isActive == enable)
            return;
        if (checkAllTrackingPermissions()) {
            if (!TrackerService.isActive) {
                trackerService.putExtra("approxSize", DataStore.sizeOfData());

                startService(trackerService);
                TrackerService.service = trackerService;
            } else {
                stopService(TrackerService.service);
            }
        }
    }

    /**
     * 0 - No cloud sync required
     * 1 - Data available for sync
     * 2 - Syncing data
     * 3 - Cloud error
     */
    public void changeCloudStatus(int status) {
        switch (status) {
            case 0:
                uploadFab.setImageResource(R.drawable.ic_cloud_done_black_48dp);
                uploadFab.hide();
                textApproxSize.setVisibility(View.GONE);
                uploadAvailable = false;
                cloudStatus = 0;
                break;
            case 1:
                uploadFab.setImageResource(R.drawable.ic_cloud_upload_black_48dp);
                if (!uploadFabHidden)
                    uploadFab.show();
                textApproxSize.setVisibility(View.VISIBLE);
                uploadAvailable = true;
                cloudStatus = 1;
                break;
            case 2:
                uploadFab.setImageResource(R.drawable.ic_cloud_queue_black_48dp);
                cloudStatus = 2;
                break;
            case 3:
                uploadFab.setImageResource(R.drawable.ic_cloud_off_black_48dp);
                cloudStatus = 3;
                break;

        }
    }

    public void enableFabs(boolean show) {
        if (!show) {
            trackingFab.hide();
            uploadFab.hide();
            uploadFabHidden = true;
        } else {
            trackingFab.show();
            if (uploadAvailable)
                uploadFab.show();
            uploadFabHidden = false;
        }
    }

    /**
     * 0 - start tracking icon
     * 1 - stop tracking icon
     * 2 - saving icon
     */
    public void changeTrackerButton(int status) {
        switch (status) {
            case 0:
                trackingFab.setImageResource(R.drawable.ic_play_arrow_black_48dp);
                break;
            case 1:
                trackingFab.setImageResource(R.drawable.ic_pause_black_48dp);
                break;
            case 2:
                trackingFab.setImageResource(R.drawable.ic_loop_black_48dp);
                break;

        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
        for (int grantResult : grantResults) {
            if (grantResult != PackageManager.PERMISSION_GRANTED)
                return;
        }
        toggleCollecting(true);
    }

    boolean checkAllTrackingPermissions() {
        if (Build.VERSION.SDK_INT > 22) {
            List<String> permissions = new ArrayList<>();
            if (ContextCompat.checkSelfPermission(instance, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);

            if (ContextCompat.checkSelfPermission(instance, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.READ_PHONE_STATE);

            //if (ContextCompat.checkSelfPermission(instance, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            //    permissions.add(Manifest.permission.RECORD_AUDIO);

            if (permissions.size() == 0)
                return true;

            requestPermissions(permissions.toArray(new String[permissions.size()]), 0);
        }
        return false;
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(updateReceiver);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 9001 && resultCode == -1)
            PlayController.gapiGamesClient.connect();
    }

    public class StatusReceiver extends BroadcastReceiver {
        public static final String BROADCAST_TAG = "signalCollectorStatus";

        @Override
        public void onReceive(Context context, Intent intent) {
            changeCloudStatus(intent.getIntExtra("cloudStatus", -1));
            changeTrackerButton(intent.getIntExtra("trackerStatus", -1));
        }
    }

    public class UpdateInfoReceiver extends BroadcastReceiver {
        public static final String BROADCAST_TAG = "signalCollectorUpdate";
        final TextView textTime;
        final TextView textWifiCount;
        final TextView textCurrentCell;
        final TextView textCellCount;
        final TextView textAccuracy;
        final TextView textLatitude;
        final TextView textLongitude;
        final TextView textAltitude;
        final TextView textPressure;
        final TextView textActivity;

        public UpdateInfoReceiver(TextView textTime, TextView textWifiCount, TextView textCurrentCell, TextView textCellCount, TextView textAccuracy, TextView textLatitude, TextView textLongitude, TextView textAltitude, TextView textPressure, TextView textActivity) {
            this.textTime = textTime;
            this.textWifiCount = textWifiCount;
            this.textCurrentCell = textCurrentCell;
            this.textCellCount = textCellCount;
            this.textAccuracy = textAccuracy;
            this.textLatitude = textLatitude;
            this.textLongitude = textLongitude;
            this.textAltitude = textAltitude;
            this.textPressure = textPressure;
            this.textActivity = textActivity;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            Resources res = getResources();
            if (cloudStatus == 0) changeCloudStatus(1);

            textApproxSize.setText(Extensions.humanReadableByteCount(intent.getLongExtra("approxSize", 0), false));

            textTime.setText(String.format(res.getString(R.string.main_last_update), DateFormat.format("HH:mm:ss", intent.getLongExtra("time", 0))));

            int wifiCount = intent.getIntExtra("wifiCount", -1);
            if (wifiCount >= 0) {
                textWifiCount.setText(String.format(res.getString(R.string.main_wifi_count), wifiCount));
            }

            int cellCount = intent.getIntExtra("cellCount", -1);
            if (cellCount >= 0) {
                textCurrentCell.setText(String.format(res.getString(R.string.main_signal_strength), intent.getStringExtra("cellType"), intent.getIntExtra("cellDbm", -1), intent.getIntExtra("cellAsu", -1)));
                textCellCount.setText(String.format(res.getString(R.string.main_cell_count), cellCount));
            }


            textAccuracy.setText(String.format(res.getString(R.string.main_accuracy), intent.getIntExtra("accuracy", -1)));

            textLatitude.setText(String.format(res.getString(R.string.main_latitude), intent.getDoubleExtra("latitude", -1)));
            textLongitude.setText(String.format(res.getString(R.string.main_longitude), intent.getDoubleExtra("longitude", -1)));
            textAltitude.setText(String.format(res.getString(R.string.main_altitude), (int) intent.getDoubleExtra("altitude", -1)));

            float pressure = intent.getFloatExtra("pressure", -1);
            if (pressure > 0) {
                textPressure.setText(String.format(res.getString(R.string.main_pressure), pressure));
            }

            textActivity.setText("Activity " + intent.getStringExtra("activity"));
        }
    }

    class ViewPagerAdapter extends FragmentPagerAdapter {
        private final List<Fragment> mFragmentList = new ArrayList<>();
        private final List<String> mFragmentTitleList = new ArrayList<>();

        public ViewPagerAdapter(FragmentManager manager) {
            super(manager);
        }

        @Override
        public Fragment getItem(int position) {
            return mFragmentList.get(position);
        }

        @Override
        public int getCount() {
            return mFragmentList.size();
        }

        public void addFrag(Fragment fragment, String title) {
            mFragmentList.add(fragment);
            mFragmentTitleList.add(title);
        }

        @Override
        public CharSequence getPageTitle(int position) {
            return mFragmentTitleList.get(position);
        }
    }
}
