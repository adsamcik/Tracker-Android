package com.adsamcik.signalcollector.fragments;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.res.ResourcesCompat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.adsamcik.signalcollector.Assist;
import com.adsamcik.signalcollector.Preferences;
import com.adsamcik.signalcollector.classes.DataStore;
import com.adsamcik.signalcollector.classes.Success;
import com.adsamcik.signalcollector.interfaces.ITabFragment;
import com.adsamcik.signalcollector.play.PlayController;
import com.adsamcik.signalcollector.R;
import com.google.firebase.crash.FirebaseCrash;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

public class FragmentSettings extends Fragment implements ITabFragment {
	private final String TAG = "SignalsSettings";
	private final int REQUEST_CODE_PERMISSIONS_MICROPHONE = 401;

	private String[] mTrackingString, mAutoupString;
	private ImageView mTrackingNone, mTrackingOnFoot, mTrackingAlways;
	private ImageView mAutoupDisabled, mAutoupWifi, mAutoupAlways;
	private TextView textView_PlayLog, mAutoupDesc, mTrackDesc;
	private Switch switchNoise;

	private SharedPreferences mSharedPreferences;

	private ImageView mTrackingSelected, mAutoupSelected;

	private ColorStateList mSelectedState;
	private ColorStateList mDefaultState;

	private void updateTracking(int select) {
		mSharedPreferences.edit().putInt(Preferences.BACKGROUND_TRACKING, select).apply();
		ImageView selected;
		switch (select) {
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
		mTrackDesc.setText(mTrackingString[select]);
		if (mTrackingSelected != null)
			mTrackingSelected.setImageTintList(mDefaultState);
		selected.setImageTintList(mSelectedState);
		mTrackingSelected = selected;
	}

	private void updateAutoup(int select) {
		mSharedPreferences.edit().putInt(Preferences.AUTO_UPLOAD, select).apply();
		ImageView selected;
		switch (select) {
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
		mAutoupDesc.setText(mAutoupString[select]);
		if (mAutoupSelected != null)
			mAutoupSelected.setImageTintList(mDefaultState);
		selected.setImageTintList(mSelectedState);
		mAutoupSelected = selected;
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		final View rootView = inflater.inflate(R.layout.fragment_settings, container, false);
		final Context context = getContext();
		mSharedPreferences = Preferences.get(context);
		final Resources resources = getResources();

		try {
			((TextView) rootView.findViewById(R.id.versionNum)).setText(context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName);
		} catch (Exception e) {
			Log.d(TAG, "Failed to set version");
		}

		mSelectedState = ResourcesCompat.getColorStateList(resources, R.color.selected_value, context.getTheme());
		mDefaultState = ResourcesCompat.getColorStateList(resources, R.color.default_value, context.getTheme());

		mTrackingString = resources.getStringArray(R.array.background_tracking_options);
		mAutoupString = resources.getStringArray(R.array.automatic_upload_options);

		mAutoupDesc = (TextView) rootView.findViewById(R.id.autoupload_description);
		mTrackDesc = (TextView) rootView.findViewById(R.id.tracking_description);

		mTrackingNone = (ImageView) rootView.findViewById(R.id.tracking_none);
		mTrackingNone.setOnClickListener(v -> updateTracking(0));
		mTrackingOnFoot = (ImageView) rootView.findViewById(R.id.tracking_onfoot);
		mTrackingOnFoot.setOnClickListener(v -> updateTracking(1));
		mTrackingAlways = (ImageView) rootView.findViewById(R.id.tracking_always);
		mTrackingAlways.setOnClickListener(v -> updateTracking(2));

		mAutoupDisabled = (ImageView) rootView.findViewById(R.id.autoupload_disabled);
		mAutoupDisabled.setOnClickListener(v -> updateAutoup(0));
		mAutoupWifi = (ImageView) rootView.findViewById(R.id.autoupload_wifi);
		mAutoupWifi.setOnClickListener(v -> updateAutoup(1));
		mAutoupAlways = (ImageView) rootView.findViewById(R.id.autoupload_always);
		mAutoupAlways.setOnClickListener(v -> updateAutoup(2));

		updateTracking(mSharedPreferences.getInt(Preferences.BACKGROUND_TRACKING, 1));
		updateAutoup(mSharedPreferences.getInt(Preferences.AUTO_UPLOAD, 1));

		textView_PlayLog = (TextView) rootView.findViewById(R.id.play_loginButton);

		if (PlayController.gamesController != null)
			PlayController.gamesController.setUI(rootView);

		textView_PlayLog.setOnClickListener(v -> {
			if (!PlayController.isLogged())
				PlayController.initializeGamesClient(rootView, getActivity());
			else {
				textView_PlayLog.setText(R.string.settings_playGamesLogin);
				PlayController.destroyGamesClient();
			}

		});

		rootView.findViewById(R.id.play_achievements).setOnClickListener(v -> {
			if (PlayController.isLogged())
				PlayController.gamesController.showAchievements(getActivity());
			else
				PlayController.initializeGamesClient(rootView, getActivity());
		});

		rootView.findViewById(R.id.other_clear).setOnClickListener(v -> {
			AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(context, R.style.AlertDialog);
			alertDialogBuilder
					.setPositiveButton(getResources().getText(R.string.alert_clear_confirm), (dialog, which) -> DataStore.clearAllData())
					.setNegativeButton(getResources().getText(R.string.alert_clear_cancel), (dialog, which) -> {
					})
					.setMessage(getResources().getText(R.string.alert_clear_text));

			alertDialogBuilder.create().show();
		});

		Spinner mapOverlaySpinner = (Spinner) rootView.findViewById(R.id.setting_map_overlay_spinner);

		Assist.getMapOverlays(Preferences.get(context), jsonStringArray -> {
			final List<String> list = new ArrayList<>();
			int selectIndex = -1;
			SharedPreferences sp = Preferences.get(context);

			try {
				JSONArray array = new JSONArray(jsonStringArray);
				if (array.length() == 0) {
					getActivity().runOnUiThread(() -> ((RelativeLayout) mapOverlaySpinner.getParent()).setVisibility(View.GONE));
					return;
				}
				String defaultItem;
				if (!sp.contains(Preferences.DEFAULT_MAP_OVERLAY)) {
					defaultItem = array.getString(0);
					sp.edit().putString(Preferences.DEFAULT_MAP_OVERLAY, defaultItem).apply();
				} else
					defaultItem = sp.getString(Preferences.DEFAULT_MAP_OVERLAY, null);

				for (int i = 0; i < array.length(); i++) {
					String item = array.getString(i);
					list.add(item);
					if(selectIndex == -1 && item.equals(defaultItem))
						selectIndex = i;
				}
			} catch (Exception e) {
				FirebaseCrash.report(e);
				getActivity().runOnUiThread(() -> ((RelativeLayout) mapOverlaySpinner.getParent()).setVisibility(View.GONE));
				return;
			}
			final ArrayAdapter<String> adapter = new ArrayAdapter<>(context, R.layout.spinner_item, list);
			adapter.setDropDownViewResource(R.layout.spinner_item);
			mapOverlaySpinner.setAdapter(adapter);
			if(selectIndex == -1) {
				sp.edit().putString(Preferences.DEFAULT_MAP_OVERLAY, adapter.getItem(0)).apply();
				selectIndex = 0;
			}
			mapOverlaySpinner.setSelection(selectIndex);

			mapOverlaySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
				@Override
				public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
					Preferences.get(context).edit().putString(Preferences.DEFAULT_MAP_OVERLAY, adapter.getItem(i)).apply();
				}

				@Override
				public void onNothingSelected(AdapterView<?> adapterView) {

				}
			});
		});

		setSwitchChangeListener(context, Preferences.TRACKING_WIFI_ENABLED, (Switch) rootView.findViewById(R.id.switchTrackWifi), true);
		setSwitchChangeListener(context, Preferences.TRACKING_CELL_ENABLED, (Switch) rootView.findViewById(R.id.switchTrackCell), true);

		switchNoise = (Switch) rootView.findViewById(R.id.switchTrackNoise);
		switchNoise.setChecked(Preferences.get(context).getBoolean(Preferences.TRACKING_NOISE_ENABLED, false));
		switchNoise.setOnCheckedChangeListener((CompoundButton compoundButton, boolean b) -> {
			if (b && Build.VERSION.SDK_INT > 22 && ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
				getActivity().requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQUEST_CODE_PERMISSIONS_MICROPHONE);
			else
				Preferences.get(context).edit().putBoolean(Preferences.TRACKING_NOISE_ENABLED, b).apply();
		});

		setSwitchChangeListener(context, Preferences.UPLOAD_NOTIFICATIONS_ENABLED, (Switch) rootView.findViewById(R.id.switchNotifications), true);

		return rootView;
	}

	private void setSwitchChangeListener(final Context context, final String name, Switch s, final boolean defaultState) {
		s.setChecked(Preferences.get(context).getBoolean(name, defaultState));
		s.setOnCheckedChangeListener((CompoundButton compoundButton, boolean b) -> Preferences.get(context).edit().putBoolean(name, b).apply());
	}

	@Override
	public Success<String> onEnter(FragmentActivity activity, FloatingActionButton fabOne, FloatingActionButton fabTwo) {
		return new Success<>();
	}

	@Override
	public void onLeave() {

	}

	@Override
	public void onPermissionResponse(int requestCode, boolean success) {
		switch (requestCode) {
			case REQUEST_CODE_PERMISSIONS_MICROPHONE:
				if (success)
					Preferences.get(getContext()).edit().putBoolean(Preferences.TRACKING_NOISE_ENABLED, true).apply();
				else
					switchNoise.setChecked(false);
				break;
			default:
				throw new UnsupportedOperationException("Permissions with request code " + requestCode + " has no defined behavior");
		}
	}

	@Override
	public ITabFragment newInstance() {
		return new FragmentSettings();
	}
}
