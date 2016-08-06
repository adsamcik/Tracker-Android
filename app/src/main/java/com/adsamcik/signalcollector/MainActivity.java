package com.adsamcik.signalcollector;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.CoordinatorLayout;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.design.widget.TabLayout;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;
import android.view.View;

import com.adsamcik.signalcollector.classes.DataStore;
import com.adsamcik.signalcollector.classes.SnackMaker;
import com.adsamcik.signalcollector.classes.Success;
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

	private SnackMaker snackMaker;

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (!Setting.getPreferences(this).getBoolean(Setting.HAS_BEEN_LAUNCHED, false)) {
			startActivity(new Intent(this, IntroActivity.class));
			return;
		}
		setContentView(R.layout.activity_main);
		DataStore.setContext(this);

		View containerView = findViewById(R.id.container);
		if(containerView != null) {
			PlayController.initializeGamesClient(containerView, this);
			snackMaker = new SnackMaker(containerView);
		}
		else
			FirebaseCrash.report(new Throwable("container view is null. something is wrong."));

		Success s = PlayController.initializeActivityClient(this);
		if(!s.getSuccess())
			snackMaker.showSnackbar(s.message);

		Extensions.initialize(this);

		if (Setting.getPreferences(this).getBoolean(Setting.SCHEDULED_UPLOAD, false))
			DataStore.requestUpload(this, true);

		ColorStateList primary = ColorStateList.valueOf(Color.argb(255, 255, 255, 255));
		ColorStateList secondary = ColorStateList.valueOf(Color.argb(255, 54, 95, 179));

		fabOne = (FloatingActionButton) findViewById(R.id.toggleTracking_fab);
		fabOne.setBackgroundTintList(secondary);
		fabOne.setImageTintList(primary);

		fabTwo = (FloatingActionButton) findViewById(R.id.upload_fab);
		fabTwo.setBackgroundTintList(primary);
		fabTwo.setImageTintList(secondary);

		Resources r = getResources();


		if(!Extensions.hasNavBar(getWindowManager())) {
			CoordinatorLayout.LayoutParams lp = new CoordinatorLayout.LayoutParams(CoordinatorLayout.LayoutParams.WRAP_CONTENT, CoordinatorLayout.LayoutParams.WRAP_CONTENT);
			lp.setMargins(0, 0, 0, 0);
			fabOne.setLayoutParams(lp);
		}

		if (viewPager == null && containerView != null) {
			viewPager = (ViewPager) containerView;
			viewPager.setOffscreenPageLimit(1);

			ViewPagerAdapter adapter = new ViewPagerAdapter(getSupportFragmentManager());
			adapter.addFrag(new FragmentMain(), r.getString(R.string.menu_dashboard));
			adapter.addFrag(new FragmentMap(), r.getString(R.string.menu_map));
			adapter.addFrag(new FragmentStats(), r.getString(R.string.menu_stats));
			adapter.addFrag(new FragmentSettings(), r.getString(R.string.menu_settings));
			viewPager.setAdapter(adapter);

			final Activity a = this;
			viewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
				ITabFragment prevFragment = (ITabFragment) adapter.mFragmentList.get(0);
				int prevFragmentIndex = 0;

				@Override
				public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
				}

				@Override
				public void onPageSelected(int position) {
					ViewPagerAdapter adapter = (ViewPagerAdapter) viewPager.getAdapter();
					prevFragment.onLeave();

					ITabFragment tf = (ITabFragment) adapter.getItem(position);
					Success response = tf.onEnter(a, fabOne, fabTwo);
					if (!response.getSuccess()) {
						if (prevFragmentIndex == position) {
							FirebaseCrash.report(new Exception("Failed to create current fragment which is also previous fragment. Preventing freeze."));
							return;
						}
						final View v = findViewById(R.id.container);
						if (v == null) {
							FirebaseCrash.report(new Exception("Container was not found. Is Activity created?"));
							return;
						}
						//it cannot be null because this is handled in getSuccess
						@SuppressWarnings("ConstantConditions") Snackbar snack = Snackbar.make(v, response.message, 4000);
						View view = snack.getView();
						view.setPadding(0, 0, 0, Extensions.getNavBarHeight(a));
						snack.show();
						fabOne.hide();
						fabTwo.hide();
					} else {
						prevFragmentIndex = position;
						prevFragment = tf;
					}
				}

				@Override
				public void onPageScrollStateChanged(int state) {
				}
			});
		}

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
