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
import android.support.design.widget.Snackbar;
import android.support.design.widget.TabLayout;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.view.ViewPager;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import com.adsamcik.signalcollector.fragments.FragmentMain;
import com.adsamcik.signalcollector.fragments.FragmentMap;
import com.adsamcik.signalcollector.fragments.FragmentSettings;
import com.adsamcik.signalcollector.fragments.FragmentStats;
import com.adsamcik.signalcollector.fragments.ITabFragment;
import com.adsamcik.signalcollector.play.PlayController;
import com.adsamcik.signalcollector.services.TrackerService;
import com.google.firebase.crash.FirebaseCrash;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends FragmentActivity {
	public static final String TAG = "Signals";

	public static MainActivity instance;
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
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.activity_main);
		context = getApplicationContext();
		DataStore.setContext(context);
		Setting.initializeSharedPreferences(context);
		instance = this;

		if (!Setting.getPreferences(this).getBoolean(Setting.HAS_BEEN_LAUNCHED, false))
			startActivity(new Intent(this, IntroActivity.class));

		PlayController.setContext(context);
		PlayController.setActivity(this);

		if (Setting.getPreferences(this).getBoolean(Setting.HAS_BEEN_LAUNCHED, false)) {
			PlayController.setContext(context);
			PlayController.setActivity(this);
			PlayController.initializeGamesClient(findViewById(R.id.container));
			PlayController.initializeActivityClient();
		}

		Resources r = getResources();

		// Set up the viewPager with the sections adapter.
		viewPager = (ViewPager) findViewById(R.id.container);
		ViewPagerAdapter adapter = new ViewPagerAdapter(getSupportFragmentManager());
		adapter.addFrag(new FragmentMain(), r.getString(R.string.menu_dashboard));
		if (PlayController.isLogged())
			adapter.addFrag(new FragmentMap(), r.getString(R.string.menu_map));
		adapter.addFrag(new FragmentStats(), r.getString(R.string.menu_stats));
		adapter.addFrag(new FragmentSettings(), r.getString(R.string.menu_settings));
		viewPager.setAdapter(adapter);
		viewPager.setOffscreenPageLimit(3);

		viewPager.addOnPageChangeListener(
				new ViewPager.OnPageChangeListener() {
					ITabFragment prevFragment = null;

					@Override
					public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
					}

					@Override
					public void onPageSelected(int position) {
						ViewPagerAdapter adapter = (ViewPagerAdapter) viewPager.getAdapter();
						prevFragment.onLeave();

						ITabFragment tf = (ITabFragment) adapter.getItem(position);

						if (!tf.onEnter(MainActivity.instance, fabOne, fabTwo)) {
							viewPager.setCurrentItem(adapter.getItemPosition(prevFragment));
							Snackbar.make(findViewById(R.id.container), "An error occurred", 5);
							FirebaseCrash.log("Something went wrong on fragment initialization.");
						} else
							prevFragment = (ITabFragment) adapter.getItem(position);
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

		RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		lp.setMargins(0, 0, 0, (Extensions.hasNavBar(getWindowManager()) ? Extensions.getNavBarHeight(context) : 0) + Extensions.dpToPx(context, 25));
		findViewById(R.id.relative_layout_fabs).setLayoutParams(lp);

		fabOne = (FloatingActionButton) findViewById(R.id.toggleTracking_fab);
		fabOne.setBackgroundTintList(secondary);
		fabOne.setImageTintList(primary);

		fabTwo = (FloatingActionButton) findViewById(R.id.upload_fab);
		fabTwo.setBackgroundTintList(primary);
		fabTwo.setImageTintList(secondary);

		if (DataStore.recountDataSize() > 0)
			setCloudStatus(1);
		else
			setCloudStatus(0);

		IntentFilter filter = new IntentFilter(StatusReceiver.BROADCAST_TAG);
		statusReceiver = new StatusReceiver();

		LocalBroadcastManager.getInstance(this).registerReceiver(statusReceiver, filter);

		if (TrackerService.isActive)
			changeTrackerButton(1);

		Extensions.initialize(context);

		if (Setting.getPreferences(context).getBoolean(Setting.SCHEDULED_UPLOAD, false))
			DataStore.requestUpload(context, true);

		DataStore.getDataFileNames(true);

		//Log.d(TAG,  FirebaseInstanceId.getInstance().getToken());
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
		if (fabTwo == null)
			throw new RuntimeException("upload fab is null. This should not happen.");

		int item = viewPager.getCurrentItem();
		switch (status) {
			case 0:
				if (item == 0) {
					//fabTwo.setImageResource(R.drawable.ic_cloud_done_24dp);
					fabTwo.hide();
				}
				cloudStatus = 0;
				break;
			case 1:
				if (item == 0) {
					fabTwo.setImageResource(R.drawable.ic_file_upload_24dp);
					fabTwo.show();
				}
				cloudStatus = 1;
				break;
			case 2:
				if (item == 0)
					fabTwo.setImageResource(R.drawable.ic_cloud_upload_24dp);
				cloudStatus = 2;
				break;
			case 3:
				if (item == 0)
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
		if (viewPager.getCurrentItem() == 0) {
			switch (status) {
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

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
		for (int grantResult : grantResults) {
			if (grantResult != PackageManager.PERMISSION_GRANTED)
				return;
		}
	}


	@Override
	protected void onDestroy() {
		super.onDestroy();
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
