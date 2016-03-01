package com.adsamcik.signalcollector.Fragments;

import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import com.adsamcik.signalcollector.DataStore;
import com.adsamcik.signalcollector.MainActivity;
import com.adsamcik.signalcollector.Play.PlayController;
import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.Setting;

public class FragmentSettings extends Fragment {
	Spinner automaticTracking, automaticUpload;
	TextView textView_PlayLog;

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		final View rootView = inflater.inflate(R.layout.fragment_settings, container, false);

		automaticTracking = (Spinner) rootView.findViewById(R.id.spinner_trackingOptions);
		ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(getContext(),
				R.array.background_tracking_options, android.R.layout.simple_spinner_item);
// Specify the layout to use when the list of choices appears
		adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
// Apply the adapter to the spinner
		automaticTracking.setAdapter(adapter);
		automaticTracking.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			@Override
			public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
				Setting.sharedPreferences.edit().putInt(Setting.BACKGROUND_TRACKING, position).apply();
			}

			@Override
			public void onNothingSelected(AdapterView<?> parent) {
				parent.setSelection(Setting.sharedPreferences.getInt(Setting.BACKGROUND_TRACKING, 1));
			}
		});
		automaticTracking.setSelection(Setting.sharedPreferences.getInt(Setting.BACKGROUND_TRACKING, 1));

		automaticUpload = (Spinner) rootView.findViewById(R.id.spinner_automaticUpload);
		adapter = ArrayAdapter.createFromResource(getContext(),
				R.array.automatic_upload, android.R.layout.simple_spinner_item);
// Specify the layout to use when the list of choices appears
		adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
// Apply the adapter to the spinner
		automaticUpload.setAdapter(adapter);
		automaticUpload.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			@Override
			public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
				Setting.sharedPreferences.edit().putInt(Setting.AUTO_UPLOAD, position).apply();
			}

			@Override
			public void onNothingSelected(AdapterView<?> parent) {
				parent.setSelection(Setting.sharedPreferences.getInt(Setting.AUTO_UPLOAD, 1));
			}
		});
		automaticUpload.setSelection(Setting.sharedPreferences.getInt(Setting.AUTO_UPLOAD, 1));

		textView_PlayLog = (TextView) rootView.findViewById(R.id.textView_playLoginText);

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

		rootView.findViewById(R.id.ib_achievements).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				PlayController.gamesController.showAchievements();

			}
		});

		rootView.findViewById(R.id.ib_leaderboards).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				PlayController.gamesController.showLeaderboard("CgkIw77dzcwdEAIQCw");

			}
		});

		rootView.findViewById(R.id.textView_Clear).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				DataStore.clearAllData();
				((MainActivity) getActivity()).changeCloudStatus(0);
			}
		});

		return rootView;

	}
}
