package com.adsamcik.signalcollector;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.support.annotation.NonNull;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.design.widget.TabLayout;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import com.adsamcik.signalcollector.fragments.FragmentMain;
import com.adsamcik.signalcollector.fragments.FragmentMap;
import com.adsamcik.signalcollector.fragments.FragmentSettings;
import com.adsamcik.signalcollector.fragments.FragmentStats;
import com.adsamcik.signalcollector.interfaces.ITabFragment;
import com.adsamcik.signalcollector.play.PlayController;
import com.google.firebase.crash.FirebaseCrash;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends FragmentActivity {
	public static final String TAG = "Signals";

	private FloatingActionButton fabOne;
	private FloatingActionButton fabTwo;

	private ViewPager viewPager;

	@Override
	protected void onStart() {
		super.onStart();
		Setting.initializeSharedPreferences(this);

		if (!Setting.getPreferences(this).getBoolean(Setting.HAS_BEEN_LAUNCHED, false)) {
			startActivity(new Intent(this, IntroActivity.class));
			return;
		}
		setContentView(R.layout.activity_main);
		DataStore.setContext(this);

		PlayController.initializeGamesClient(findViewById(R.id.container), this);
		PlayController.initializeActivityClient(this);

		RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		lp.setMargins(0, 0, 0, (Extensions.hasNavBar(getWindowManager()) ? Extensions.getNavBarHeight(this) : 0) + Extensions.dpToPx(this, 25 - 16));
		findViewById(R.id.relative_layout_fabs).setLayoutParams(lp);


		Extensions.initialize(this);

		if (Setting.getPreferences(this).getBoolean(Setting.SCHEDULED_UPLOAD, false))
			DataStore.requestUpload(this, true);

		DataStore.getDataFileNames(true);

		ColorStateList primary = ColorStateList.valueOf(Color.argb(255, 255, 255, 255));
		ColorStateList secondary = ColorStateList.valueOf(Color.argb(255, 54, 95, 179));

		fabOne = (FloatingActionButton) findViewById(R.id.toggleTracking_fab);
		fabOne.setBackgroundTintList(secondary);
		fabOne.setImageTintList(primary);

		fabTwo = (FloatingActionButton) findViewById(R.id.upload_fab);
		fabTwo.setBackgroundTintList(primary);
		fabTwo.setImageTintList(secondary);

		Resources r = getResources();
		// Set up the viewPager with the sections adapter.
		viewPager = (ViewPager) findViewById(R.id.container);
		viewPager.setOffscreenPageLimit(3);

		ViewPagerAdapter adapter = new ViewPagerAdapter(getSupportFragmentManager());
		adapter.addFrag(new FragmentMain().setFabs(fabOne, fabTwo), r.getString(R.string.menu_dashboard));
		adapter.addFrag(new FragmentMap(), r.getString(R.string.menu_map));
		adapter.addFrag(new FragmentStats(), r.getString(R.string.menu_stats));
		adapter.addFrag(new FragmentSettings(), r.getString(R.string.menu_settings));
		viewPager.setAdapter(adapter);

		final Activity a = this;
		viewPager.addOnPageChangeListener(
				new ViewPager.OnPageChangeListener() {
					ITabFragment prevFragment = (ITabFragment) adapter.mFragmentList.get(0);

					@Override
					public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
					}

					@Override
					public void onPageSelected(int position) {
						ViewPagerAdapter adapter = (ViewPagerAdapter) viewPager.getAdapter();
						prevFragment.onLeave();

						ITabFragment tf = (ITabFragment) adapter.getItem(position);
						Log.d(TAG, "Activity " + a);
						if (!tf.onEnter(a, fabOne, fabTwo)) {
							viewPager.setCurrentItem(adapter.getItemPosition(prevFragment));
							Snackbar.make(findViewById(R.id.container), "An error occurred", 5);
							FirebaseCrash.log("Something went wrong on fragment initialization.");
						} else
							prevFragment = tf;
					}

					@Override
					public void onPageScrollStateChanged(int state) {
					}
				}
		);

		TabLayout tabLayout = (TabLayout) findViewById(R.id.tabs);
		tabLayout.setupWithViewPager(viewPager);
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
		for (int grantResult : grantResults) {
			if (grantResult != PackageManager.PERMISSION_GRANTED)
				return;
		}
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode == 9001 && resultCode == -1)
			PlayController.reconnect();
	}

	private class ViewPagerAdapter extends FragmentPagerAdapter {
		private final List<Fragment> mFragmentList = new ArrayList<>();
		private final List<String> mFragmentTitleList = new ArrayList<>();

		private ViewPagerAdapter(FragmentManager manager) {
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

		private void addFrag(Fragment fragment, String title) {
			mFragmentList.add(fragment);
			mFragmentTitleList.add(title);
		}

		@Override
		public CharSequence getPageTitle(int position) {
			return mFragmentTitleList.get(position);
		}
	}
}
