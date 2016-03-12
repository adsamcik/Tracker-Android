package com.adsamcik.signalcollector.Fragments;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.content.res.ResourcesCompat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;

import com.adsamcik.signalcollector.DataStore;
import com.adsamcik.signalcollector.MainActivity;
import com.adsamcik.signalcollector.Play.PlayController;
import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.Setting;

public class FragmentSettings extends Fragment {
	String[] mTrackingString, mAutoupString;
	ImageView mTrackingNone, mTrackingOnFoot, mTrackingAlways;
	ImageView mAutoupDisabled, mAutoupWifi, mAutoupAlways;
	TextView textView_PlayLog;

	SharedPreferences mSharedPreferences;

	ImageView mTrackingSelected, mAutoupSelected;

	ColorStateList mSelectedState;
	ColorStateList mDefaultState;

	void updateTracking(int select) {
		mSharedPreferences.edit().putInt(Setting.BACKGROUND_TRACKING, select).apply();
		ImageView selected;
		switch(select) {
			case 0:
				selected = mTrackingNone;
				break;
			case 1:
				selected = mTrackingOnFoot;
				break;
			case 2:
				selected = mTrackingAlways;
				break;
			default:
				return;
		}
		mTrackingSelected.setImageTintList(mDefaultState);
		selected.setImageTintList(mSelectedState);
		mTrackingSelected = selected;
	}

	void updateAutoup(int select) {
		mSharedPreferences.edit().putInt(Setting.AUTO_UPLOAD, select).apply();
		ImageView selected;
		switch(select) {
			case 0:
				selected = mAutoupDisabled;
				break;
			case 1:
				selected = mAutoupWifi;
				break;
			case 2:
				selected = mAutoupAlways;
				break;
			default:
				return;
		}
		mAutoupSelected.setImageTintList(mDefaultState);
		selected.setImageTintList(mSelectedState);
		mAutoupSelected = selected;
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		final View rootView = inflater.inflate(R.layout.fragment_settings, container, false);
		mSharedPreferences = Setting.getPreferences();
		final Resources resources = getResources();

		mSelectedState = ResourcesCompat.getColorStateList(resources, R.color.selected_value, getContext().getTheme());
		mDefaultState = ResourcesCompat.getColorStateList(resources, R.color.default_value, getContext().getTheme());

		updateTracking(mSharedPreferences.getInt(Setting.BACKGROUND_TRACKING, 1));
		updateAutoup(mSharedPreferences.getInt(Setting.AUTO_UPLOAD, 1));

		mTrackingString = resources.getStringArray(R.array.background_tracking_options);
		mAutoupString = resources.getStringArray(R.array.automatic_upload_options);

		mTrackingNone = (ImageView) rootView.findViewById(R.id.tracking_none);
		mTrackingNone.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				updateTracking(0);
			}
		});
		mTrackingOnFoot = (ImageView) rootView.findViewById(R.id.tracking_onfoot);
		mTrackingOnFoot.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				updateTracking(1);
			}
		});
		mTrackingAlways = (ImageView) rootView.findViewById(R.id.tracking_always);
		mTrackingAlways.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				updateTracking(2);
			}
		});

		mAutoupDisabled = (ImageView) rootView.findViewById(R.id.autoupload_disabled);
		mAutoupWifi = (ImageView) rootView.findViewById(R.id.autoupload_wifi);
		mAutoupAlways = (ImageView) rootView.findViewById(R.id.autoupload_always);

		automaticTracking = (Spinner) rootView.findViewById(R.id.spinner_trackingOptions);
		final ArrayAdapter<CharSequence> adapterTracking = ArrayAdapter.createFromResource(getContext(),
				R.array.background_tracking_options, android.R.layout.simple_spinner_item);
// Specify the layout to use when the list of choices appears
		adapterTracking.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
// Apply the adapter to the spinner
		automaticTracking.setAdapter(adapterTracking);
		automaticTracking.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			@Override
			public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
				Setting.getPreferences().edit().putInt(Setting.BACKGROUND_TRACKING, position).apply();
			}

			@Override
			public void onNothingSelected(AdapterView<?> parent) {
				parent.setSelection(Setting.getPreferences().getInt(Setting.BACKGROUND_TRACKING, 1));
			}
		});
		automaticTracking.setSelection(Setting.getPreferences().getInt(Setting.BACKGROUND_TRACKING, 1));

		automaticUpload = (Spinner) rootView.findViewById(R.id.spinner_automaticUpload);
		final ArrayAdapter<CharSequence> adapterAutoUpload = ArrayAdapter.createFromResource(getContext(),
				R.array.automatic_upload, android.R.layout.simple_spinner_item);
// Specify the layout to use when the list of choices appears
		adapterAutoUpload.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
// Apply the adapter to the spinner
		automaticUpload.setAdapter(adapterAutoUpload);
		automaticUpload.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			@Override
			public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
				Setting.getPreferences().edit().putInt(Setting.AUTO_UPLOAD, position).apply();
			}

			@Override
			public void onNothingSelected(AdapterView<?> parent) {
				parent.setSelection(Setting.getPreferences().getInt(Setting.AUTO_UPLOAD, 1));
			}
		});
		automaticUpload.setSelection(Setting.getPreferences().getInt(Setting.AUTO_UPLOAD, 1));

		textView_PlayLog = (TextView) rootView.findViewById(R.id.play_loginButton);

		if(PlayController.gamesController != null)
			PlayController.gamesController.setUI(rootView);

		textView_PlayLog.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				if(!PlayController.apiGames || !PlayController.isLogged())
					PlayController.initializeGamesClient(rootView);
				else {
					textView_PlayLog.setText(R.string.settings_playGamesLogin);
					PlayController.destroyGamesClient();
				}

			}
		});

		rootView.findViewById(R.id.play_achievements).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				PlayController.gamesController.showAchievements();

			}
		});

		/*rootView.findViewById(R.id.ib_leaderboards).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				PlayController.gamesController.showLeaderboard("CgkIw77dzcwdEAIQCw");

			}
		});*/

		rootView.findViewById(R.id.other_clear).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				DataStore.clearAllData();
				((MainActivity) getActivity()).setCloudStatus(0);
			}
		});

		return rootView;
	}
}
