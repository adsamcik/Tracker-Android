package com.adsamcik.signalcollector;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
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
import android.view.View;

import com.adsamcik.signalcollector.Fragments.FragmentMain;
import com.adsamcik.signalcollector.Fragments.FragmentMap;
import com.adsamcik.signalcollector.Fragments.FragmentSettings;
import com.adsamcik.signalcollector.Fragments.FragmentStats;
import com.adsamcik.signalcollector.Play.PlayController;
import com.adsamcik.signalcollector.Services.RegistrationIntentService;
import com.adsamcik.signalcollector.Services.TrackerService;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MainActivity extends FragmentActivity {
	public static final String TAG = "Signals";

	private static MainActivity instance;
	public static Context context;
	//static boolean tracking = false;
	//0 - Data are synced
	//1 - Sync required
	//2 - Sync in progress
	//-1 - Error
	private static int cloudStatus;
	//0 - No save needed
	//1 - Save required
	//2 - Save in progress
	private FloatingActionButton fabOne;
	private FloatingActionButton fabTwo;

	private StatusReceiver statusReceiver;
	private ViewPager viewPager;

	@Override
	protected void onStart() {
		super.onStart();

		if(!Setting.sharedPreferences.getBoolean(Setting.HAS_BEEN_LAUNCHED, false)) {
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
		Setting.initializeSharedPreferences(context);
		instance = this;

		PlayController.setContext(context);
		PlayController.setActivity(this);

		// Set up the viewPager with the sections adapter.
		viewPager = (ViewPager) findViewById(R.id.container);

		Resources r = getResources();
		ViewPagerAdapter adapter = new ViewPagerAdapter(getSupportFragmentManager());
		adapter.addFrag(new FragmentMain(), r.getString(R.string.menu_dashboard));
		adapter.addFrag(new FragmentMap(), r.getString(R.string.menu_map));
		adapter.addFrag(new FragmentStats(), r.getString(R.string.menu_stats));
		adapter.addFrag(new FragmentSettings(), r.getString(R.string.menu_settings));
		viewPager.setAdapter(adapter);

		viewPager.setOffscreenPageLimit(3);

		viewPager.addOnPageChangeListener(
				new ViewPager.OnPageChangeListener() {
					int prevPos = 0;

					@Override
					public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
					}

					@Override
					public void onPageSelected(int position) {
						if(prevPos == 1)
							((FragmentMap) ((ViewPagerAdapter) viewPager.getAdapter()).getItem(1)).onLeave();

						updateFabs(position);
						prevPos = position;
					}

					@Override
					public void onPageScrollStateChanged(int state) {
					}
				}
		);

		TabLayout tabLayout = (TabLayout) findViewById(R.id.tabs);
		tabLayout.setupWithViewPager(viewPager);

		ColorStateList primary = ColorStateList.valueOf(Color.argb(255, 255, 255, 255));
		ColorStateList secondary = ColorStateList.valueOf(Color.argb(255, 54, 95, 179));

		fabOne = (FloatingActionButton) findViewById(R.id.toggleTracking_fab);
		fabOne.setBackgroundTintList(secondary);
		fabOne.setImageTintList(primary);

		fabTwo = (FloatingActionButton) findViewById(R.id.upload_fab);
		fabTwo.setBackgroundTintList(primary);
		fabTwo.setImageTintList(secondary);

		if(DataStore.recountDataSize() > 0) {
			setCloudStatus(1);
		} else
			setCloudStatus(0);


		IntentFilter filter = new IntentFilter(StatusReceiver.BROADCAST_TAG);
		statusReceiver = new StatusReceiver();

		LocalBroadcastManager.getInstance(this).registerReceiver(statusReceiver, filter);

		if(TrackerService.isActive)
			changeTrackerButton(1);

		Extensions.Initialize((TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE));

		if(PlayController.isPlayServiceAvailable()) {
			// Start IntentService to register this application with GCM.
			Intent intent = new Intent(this, RegistrationIntentService.class);
			startService(intent);
		}

		DataStore.updateAutoUploadState(context);
	}

	private void toggleCollecting(boolean enable) {
		if((!TrackerService.isActive) || TrackerService.isActive == enable)
			return;
		if(checkAllTrackingPermissions()) {
			if(!TrackerService.isActive) {
				Intent trackerService = new Intent(instance, TrackerService.class);
				trackerService.putExtra("approxSize", DataStore.sizeOfData());
				startService(trackerService);
				TrackerService.service = trackerService;
			} else {
				stopService(TrackerService.service);
			}
		}
	}

	public int getCloudStatus() {
		return cloudStatus;
	}

	/**
	 * 0 - No cloud sync required
	 * 1 - Data available for sync
	 * 2 - Syncing data
	 * 3 - Cloud error
	 */
	public void setCloudStatus(int status) {
		if(fabTwo == null)
			throw new RuntimeException("upload fab is null. This should not happen.");

		int item = viewPager.getCurrentItem();
		switch(status) {
			case 0:
				if(item == 0) {
					//fabTwo.setImageResource(R.drawable.ic_cloud_done_24dp);
					fabTwo.hide();
				}
				cloudStatus = 0;
				break;
			case 1:
				if(item == 0) {
					fabTwo.setImageResource(R.drawable.ic_file_upload_24dp);
					fabTwo.show();
				}
				cloudStatus = 1;
				break;
			case 2:
				if(item == 0)
					fabTwo.setImageResource(R.drawable.ic_cloud_upload_24dp);
				cloudStatus = 2;
				break;
			case 3:
				if(item == 0)
					fabTwo.setImageResource(R.drawable.ic_cloud_off_24dp);
				cloudStatus = 3;
				break;

		}
	}

	/**
	 * 0 - start tracking icon
	 * 1 - stop tracking icon
	 * 2 - saving icon
	 */
	private void changeTrackerButton(int status) {
		if(viewPager.getCurrentItem() == 0) {
			switch(status) {
				case 0:
					fabOne.setImageResource(R.drawable.ic_play_arrow_24dp);
					break;
				case 1:
					fabOne.setImageResource(R.drawable.ic_pause_24dp);
					break;
				case 2:
					fabOne.setImageResource(R.drawable.ic_loop_24dp);
					break;

			}
		}
	}

	/**
	 * Handles fab updating (showing/hiding, icons, listeners)
	 *
	 * @param index current tab index
	 */
	private void updateFabs(final int index) {
		switch(index) {
			case 0:
				fabOne.show();
				changeTrackerButton(TrackerService.isActive ? 1 : 0);
				fabOne.setOnClickListener(
						new View.OnClickListener() {
							public void onClick(View v) {
								toggleCollecting(!TrackerService.isActive);
							}
						}
				);

				setCloudStatus(cloudStatus);
				fabTwo.setOnClickListener(
						new View.OnClickListener() {
							public void onClick(View v) {
								if(cloudStatus == 1) {
									setCloudStatus(2);
									new LoadAndUploadTask().execute(DataStore.getDataFileNames(true));
									Setting.getPreferences(context).edit().putInt(DataStore.KEY_FILE_ID, Setting.getPreferences(context).getInt(DataStore.KEY_FILE_ID, -1) + 1).commit();
								}
							}
						}
				);
				break;
			case 1:
				((FragmentMap) ((ViewPagerAdapter) viewPager.getAdapter()).getItem(index)).initializeFABs(fabOne, fabTwo);
				break;
			default:
				fabOne.hide();
				fabTwo.hide();
				break;
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
		for(int grantResult : grantResults) {
			if(grantResult != PackageManager.PERMISSION_GRANTED)
				return;
		}
		toggleCollecting(true);
	}

	private boolean checkAllTrackingPermissions() {
		if(Build.VERSION.SDK_INT > 22) {
			List<String> permissions = new ArrayList<>();
			if(ContextCompat.checkSelfPermission(instance, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
				permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);

			if(ContextCompat.checkSelfPermission(instance, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
				permissions.add(Manifest.permission.READ_PHONE_STATE);

			//if (ContextCompat.checkSelfPermission(instance, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
			//    permissions.add(Manifest.permission.RECORD_AUDIO);

			if(permissions.size() == 0)
				return true;

			requestPermissions(permissions.toArray(new String[permissions.size()]), 0);
		}
		return false;
	}


	@Override
	protected void onDestroy() {
		super.onDestroy();
		LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver);
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if(requestCode == 9001 && resultCode == -1)
			PlayController.gapiGamesClient.connect();
	}

	public class StatusReceiver extends BroadcastReceiver {
		public static final String BROADCAST_TAG = "signalCollectorStatus";

		@Override
		public void onReceive(Context context, Intent intent) {
			setCloudStatus(intent.getIntExtra("cloudStatus", -1));
			changeTrackerButton(intent.getIntExtra("trackerStatus", -1));
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
