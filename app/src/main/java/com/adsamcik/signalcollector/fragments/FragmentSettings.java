package com.adsamcik.signalcollector.fragments;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.StringRes;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.adsamcik.signalcollector.BuildConfig;
import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.activities.ActivityRecognitionActivity;
import com.adsamcik.signalcollector.activities.DebugFileActivity;
import com.adsamcik.signalcollector.activities.FeedbackActivity;
import com.adsamcik.signalcollector.activities.FileSharingActivity;
import com.adsamcik.signalcollector.activities.IntroActivity;
import com.adsamcik.signalcollector.activities.LicenseActivity;
import com.adsamcik.signalcollector.activities.MainActivity;
import com.adsamcik.signalcollector.activities.NoiseTestingActivity;
import com.adsamcik.signalcollector.data.MapLayer;
import com.adsamcik.signalcollector.file.CacheStore;
import com.adsamcik.signalcollector.file.DataStore;
import com.adsamcik.signalcollector.interfaces.INonNullValueCallback;
import com.adsamcik.signalcollector.interfaces.ITabFragment;
import com.adsamcik.signalcollector.interfaces.IValueCallback;
import com.adsamcik.signalcollector.interfaces.IVerify;
import com.adsamcik.signalcollector.network.Network;
import com.adsamcik.signalcollector.network.NetworkLoader;
import com.adsamcik.signalcollector.network.Prices;
import com.adsamcik.signalcollector.services.ActivityService;
import com.adsamcik.signalcollector.services.ActivityWakerService;
import com.adsamcik.signalcollector.services.TrackerService;
import com.adsamcik.signalcollector.signin.Signin;
import com.adsamcik.signalcollector.signin.User;
import com.adsamcik.signalcollector.utility.Assist;
import com.adsamcik.signalcollector.utility.Failure;
import com.adsamcik.signalcollector.utility.FirebaseAssist;
import com.adsamcik.signalcollector.utility.Preferences;
import com.adsamcik.signalcollector.utility.SnackMaker;
import com.adsamcik.slider.IntSlider;
import com.adsamcik.slider.Slider;
import com.google.android.gms.common.SignInButton;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.crash.FirebaseCrash;
import com.google.gson.Gson;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MultipartBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static com.adsamcik.signalcollector.utility.Constants.DAY_IN_MINUTES;

public class FragmentSettings extends Fragment implements ITabFragment {
	private final String TAG = "SignalsSettings";
	private final int REQUEST_CODE_PERMISSIONS_MICROPHONE = 401;

	private String[] trackingString, autoupString;
	private ImageView trackingNone, trackingOnFoot, trackingAlways;
	private ImageView autoupDisabled, autoupWifi, autoupAlways;
	private TextView autoupDesc, trackDesc, signInNoConnection;

	private View devView;

	private ImageView mTrackingSelected = null, mAutoupSelected = null;

	private SignInButton signInButton;
	private LinearLayout signedInMenu;
	private Signin signin;

	private Switch switchNoise;

	private ColorStateList mSelectedState;
	private ColorStateList mDefaultState;

	private View rootView;

	private int dummyNotificationIndex = 1972;

	public final IValueCallback<User> userSignedCallback = u -> {
		if (u != null) {
			final Activity activity = getActivity();
			if (activity != null) {
				NetworkLoader.request(Network.URL_USER_PRICES, DAY_IN_MINUTES, activity, Preferences.PREF_USER_PRICES, Prices.class, (s, p) -> {
					//todo check when server data are available
					if (s.isSuccess()) {
						if (p == null)
							new SnackMaker(activity).showSnackbar(R.string.error_invalid_data);
						else
							resolveUserMenuOnLogin(u, p);
					} else
						new SnackMaker(activity).showSnackbar(R.string.error_connection_failed);
				});
			}
		}
	};

	private void updateTracking(int select) {
		ImageView selected;
		switch (select) {
			case 0:
				selected = trackingNone;
				break;
			case 1:
				selected = trackingOnFoot;
				break;
			case 2:
				selected = trackingAlways;
				break;
			default:
				return;
		}

		FirebaseAssist.updateValue(getContext(), FirebaseAssist.autoUploadString, trackingString[select]);
		trackDesc.setText(trackingString[select]);
		updateState(mTrackingSelected, selected, Preferences.PREF_AUTO_TRACKING, select);

		if (mTrackingSelected != null)
			if (select == 0)
				ActivityService.removeAutoTracking(getContext(), MainActivity.class);
			else
				ActivityService.requestAutoTracking(getContext(), MainActivity.class);

		mTrackingSelected = selected;
	}

	private void updateAutoup(int select) {
		ImageView selected;
		switch (select) {
			case 0:
				selected = autoupDisabled;
				break;
			case 1:
				selected = autoupWifi;
				break;
			case 2:
				selected = autoupAlways;
				break;
			default:
				return;
		}
		FirebaseAssist.updateValue(getContext(), FirebaseAssist.autoUploadString, autoupString[select]);

		autoupDesc.setText(autoupString[select]);
		updateState(mAutoupSelected, selected, Preferences.PREF_AUTO_UPLOAD, select);
		mAutoupSelected = selected;
	}

	private void updateState(@Nullable ImageView selected, @NonNull ImageView select, @NonNull String preference, int index) {
		Context context = getContext();
		assert context != null;
		Preferences.get(context).edit().putInt(preference, index).apply();

		if (selected != null)
			setInactive(selected);
		select.setImageTintList(mSelectedState);
		select.setImageAlpha(Color.alpha(mSelectedState.getDefaultColor()));
	}

	private void setInactive(@NonNull ImageView item) {
		item.setImageTintList(mDefaultState);
		item.setImageAlpha(Color.alpha(mDefaultState.getDefaultColor()));
	}


	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		rootView = inflater.inflate(R.layout.fragment_settings, container, false);
		final Activity activity = getActivity();
		assert activity != null;
		final SharedPreferences sharedPreferences = Preferences.get(activity);

		findViews(rootView);
		initializeVersionLicense(rootView, devView);

		initializeClassVariables(activity);

		updateTracking(sharedPreferences.getInt(Preferences.PREF_AUTO_TRACKING, Preferences.DEFAULT_AUTO_TRACKING));

		updateAutoup(sharedPreferences.getInt(Preferences.PREF_AUTO_UPLOAD, Preferences.DEFAULT_AUTO_UPLOAD));

		initializeSignIn(activity);

		initializeAutoTrackingSection(activity, rootView);

		initializeAutoUploadSection(activity, rootView);

		initializeTrackingOptionsSection(activity, rootView);

		initializeExportSection(rootView);

		initializeOtherSection(activity, rootView);

		initializeDevSection(activity, rootView);

		return rootView;
	}


	private void initializeVersionLicense(@NonNull View rootView, @NonNull View devView) {
		rootView.findViewById(R.id.open_source_licenses).setOnClickListener(v -> startActivity(new Intent(getActivity(), LicenseActivity.class)));

		final TextView versionView = rootView.findViewById(R.id.versionNum);
		try {
			versionView.setText(String.format("%1$s - %2$s", BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME));
		} catch (Exception e) {
			Log.d(TAG, "Failed to set version");
		}

		versionView.setOnLongClickListener(view -> {
			boolean setVisible = devView.getVisibility() == View.GONE;
			devView.setVisibility(setVisible ? View.VISIBLE : View.GONE);
			Preferences.get(rootView.getContext()).edit().putBoolean(Preferences.PREF_SHOW_DEV_SETTINGS, setVisible).apply();
			new SnackMaker(getActivity()).showSnackbar(getString(setVisible ? R.string.dev_join : R.string.dev_leave));
			return true;
		});
	}

	private void findViews(@NonNull View rootView) {
		autoupDesc = rootView.findViewById(R.id.autoupload_description);
		trackDesc = rootView.findViewById(R.id.tracking_description);

		trackingNone = rootView.findViewById(R.id.tracking_none);
		trackingNone.setOnClickListener(v -> updateTracking(0));
		setInactive(trackingNone);
		trackingOnFoot = rootView.findViewById(R.id.tracking_onfoot);
		trackingOnFoot.setOnClickListener(v -> updateTracking(1));
		setInactive(trackingOnFoot);
		trackingAlways = rootView.findViewById(R.id.tracking_always);
		trackingAlways.setOnClickListener(v -> updateTracking(2));
		setInactive(trackingAlways);

		autoupDisabled = rootView.findViewById(R.id.autoupload_disabled);
		autoupDisabled.setOnClickListener(v -> updateAutoup(0));
		setInactive(autoupDisabled);
		autoupWifi = rootView.findViewById(R.id.autoupload_wifi);
		autoupWifi.setOnClickListener(v -> updateAutoup(1));
		setInactive(autoupWifi);
		autoupAlways = rootView.findViewById(R.id.autoupload_always);
		autoupAlways.setOnClickListener(v -> updateAutoup(2));
		setInactive(autoupAlways);

		devView = rootView.findViewById(R.id.dev_corner_layout);

		signInButton = rootView.findViewById(R.id.sign_in_button);
		signedInMenu = rootView.findViewById(R.id.signed_in_menu);
		signInNoConnection = rootView.findViewById(R.id.sign_in_message);
	}

	private void initializeClassVariables(@NonNull Activity activity) {
		final Resources resources = getResources();
		ColorStateList[] csl = Assist.getSelectionStateLists(resources, activity.getTheme());
		mSelectedState = csl[1];
		mDefaultState = csl[0];

		trackingString = resources.getStringArray(R.array.background_tracking_options);
		autoupString = resources.getStringArray(R.array.automatic_upload_options);
	}

	private void initializeSignIn(@NonNull Activity activity) {
		if (Assist.hasNetwork(activity)) {
			signin = Signin.signin(activity, null, true);
			signin.setButtons(signInButton, signedInMenu, activity);
			Signin.getUserAsync(activity, userSignedCallback);
		} else
			signInNoConnection.setVisibility(View.VISIBLE);
	}

	private void initializeAutoTrackingSection(@NonNull Activity activity, @NonNull View rootView) {
		setSwitchChangeListener(activity,
				Preferences.PREF_ACTIVITY_WATCHER_ENABLED,
				rootView.findViewById(R.id.switch_activity_watcher),
				Preferences.DEFAULT_ACTIVITY_WATCHER_ENABLED,
				value -> ActivityWakerService.poke(activity));

		IntSlider activityFrequencySlider = rootView.findViewById(R.id.settings_seekbar_watcher_frequency);
		//todo update to not set useless values because of setItems below
		setSeekbar(activity,
				activityFrequencySlider,
				rootView.findViewById(R.id.settings_text_activity_frequency),
				0,
				300,
				30,
				Preferences.PREF_ACTIVITY_UPDATE_RATE,
				Preferences.DEFAULT_ACTIVITY_UPDATE_RATE,
				(progress) -> {
					if (progress == 0)
						return getString(R.string.frequency_asap);
					else if (progress < 60)
						return getString(R.string.frequency_seconds, progress);
					else if (progress % 60 == 0)
						return getString(R.string.frequency_minute, progress / 60);
					else {
						int minutes = progress / 60;
						return getString(R.string.frequency_minute_second, minutes, progress - minutes * 60);
					}
				},
				value -> {
					ActivityService.requestActivity(activity, MainActivity.class, value);
					ActivityWakerService.poke(activity);
				});

		activityFrequencySlider.setItems(new Integer[]{0, 5, 10, 30, 60, 120, 240, 300, 600});

		setSwitchChangeListener(activity, Preferences.PREF_STOP_TILL_RECHARGE, rootView.findViewById(R.id.switchDisableTrackingTillRecharge), false, (b) -> {
			if (b) {
				Bundle bundle = new Bundle();
				bundle.putString(FirebaseAssist.PARAM_SOURCE, "settings");
				FirebaseAnalytics.getInstance(activity).logEvent(FirebaseAssist.STOP_TILL_RECHARGE_EVENT, bundle);
				if (TrackerService.isRunning())
					activity.stopService(new Intent(activity, TrackerService.class));
			}
		});
	}

	private void initializeAutoUploadSection(@NonNull Activity activity, @NonNull View rootView) {
		TextView valueAutoUploadAt = rootView.findViewById(R.id.settings_autoupload_at_value);
		IntSlider seekAutoUploadAt = rootView.findViewById(R.id.settings_autoupload_at_seekbar);

		setSeekbar(activity,
				seekAutoUploadAt,
				valueAutoUploadAt,
				1,
				10,
				1,
				Preferences.PREF_AUTO_UPLOAD_AT_MB,
				Preferences.DEFAULT_AUTO_UPLOAD_AT_MB,
				(progress) -> getString(R.string.settings_autoupload_at_value, progress),
				null);

		setSwitchChangeListener(activity, Preferences.PREF_AUTO_UPLOAD_SMART, rootView.findViewById(R.id.switchAutoUploadSmart), Preferences.DEFAULT_AUTO_UPLOAD_SMART, value -> ((ViewGroup) seekAutoUploadAt.getParent()).setVisibility(value ? View.GONE : View.VISIBLE));

		if (Preferences.get(activity).getBoolean(Preferences.PREF_AUTO_UPLOAD_SMART, Preferences.DEFAULT_AUTO_UPLOAD_SMART)) {
			((ViewGroup) seekAutoUploadAt.getParent()).setVisibility(View.GONE);
		}
	}

	private void initializeTrackingOptionsSection(@NonNull Activity activity, @NonNull View rootView) {
		setSwitchChangeListener(activity, Preferences.PREF_TRACKING_WIFI_ENABLED, rootView.findViewById(R.id.switchTrackWifi), Preferences.DEFAULT_TRACKING_WIFI_ENABLED, null);
		setSwitchChangeListener(activity, Preferences.PREF_TRACKING_CELL_ENABLED, rootView.findViewById(R.id.switchTrackCell), Preferences.DEFAULT_TRACKING_CELL_ENABLED, null);
		final Switch switchTrackLocation = rootView.findViewById(R.id.switchTrackLocation);
		setSwitchChangeListener(activity, Preferences.PREF_TRACKING_LOCATION_ENABLED, switchTrackLocation, Preferences.DEFAULT_TRACKING_LOCATION_ENABLED, (s) -> {
			if (!s) {
				AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(activity, R.style.AlertDialog);
				alertDialogBuilder
						.setPositiveButton(getText(R.string.yes), null)
						.setNegativeButton(getText(R.string.cancel), (dialog, which) -> switchTrackLocation.setChecked(true))
						.setMessage(getText(R.string.alert_disable_location_tracking_description))
						.setTitle(R.string.alert_disable_location_tracking_title);

				alertDialogBuilder.create().show();
			}

		});

		switchNoise = rootView.findViewById(R.id.switchTrackNoise);
		switchNoise.setChecked(Preferences.get(activity).getBoolean(Preferences.PREF_TRACKING_NOISE_ENABLED, false));
		switchNoise.setOnCheckedChangeListener((CompoundButton compoundButton, boolean b) -> {
			if (b && Build.VERSION.SDK_INT > 22 && ContextCompat.checkSelfPermission(activity, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
				activity.requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQUEST_CODE_PERMISSIONS_MICROPHONE);
			else
				Preferences.get(activity).edit().putBoolean(Preferences.PREF_TRACKING_NOISE_ENABLED, b).apply();
		});
	}

	private void initializeExportSection(@NonNull View rootView) {
		rootView.findViewById(R.id.export_share_button).setOnClickListener(v -> startActivity(new Intent(getActivity(), FileSharingActivity.class)));
	}

	private void initializeOtherSection(@NonNull Activity activity, @NonNull View rootView) {
		Switch darkThemeSwitch = rootView.findViewById(R.id.switchDarkTheme);
		darkThemeSwitch.setChecked(Preferences.getTheme(activity) == R.style.AppThemeDark);
		darkThemeSwitch.setOnCheckedChangeListener((CompoundButton compoundButton, boolean b) -> {
			int theme = b ? R.style.AppThemeDark : R.style.AppThemeLight;
			Preferences.setTheme(activity, theme);

			//If activity is first started than finished it will finish the new activity
			activity.finish();
			startActivity(activity.getIntent());
		});

		setSwitchChangeListener(activity,
				Preferences.PREF_UPLOAD_NOTIFICATIONS_ENABLED,
				rootView.findViewById(R.id.switchNotificationsUpload),
				true,
				(b) -> FirebaseAssist.updateValue(activity, FirebaseAssist.uploadNotificationString, Boolean.toString(b)));

		rootView.findViewById(R.id.other_clear_data).setOnClickListener(v -> {
			AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(activity);
			alertDialogBuilder
					.setPositiveButton(getResources().getText(R.string.yes), (dialog, which) -> DataStore.clearAllData(activity))
					.setNegativeButton(getResources().getText(R.string.no), (dialog, which) -> {
					})
					.setMessage(getResources().getText(R.string.alert_clear_text));

			alertDialogBuilder.show();
		});

		rootView.findViewById(R.id.other_reopen_tutorial).setOnClickListener(v -> {
			startActivity(new Intent(activity, IntroActivity.class));
			activity.finish();
		});

		rootView.findViewById(R.id.other_feedback).setOnClickListener(v -> {
			if (Signin.isSignedIn())
				startActivity(new Intent(getActivity(), FeedbackActivity.class));
			else
				new SnackMaker(getActivity()).showSnackbar(R.string.feedback_error_not_signed_in);
		});
	}

	private void initializeDevSection(@NonNull Activity activity, @NonNull View rootView) {
		boolean isDevEnabled = Preferences.get(activity).getBoolean(Preferences.PREF_SHOW_DEV_SETTINGS, false);
		devView.setVisibility(isDevEnabled ? View.VISIBLE : View.GONE);

		rootView.findViewById(R.id.dev_button_cache_clear).setOnClickListener((v) -> createClearDialog(activity, CacheStore::clearAll, R.string.settings_cleared_all_cache_files));
		rootView.findViewById(R.id.dev_button_data_clear).setOnClickListener((v) -> createClearDialog(activity, DataStore::clearAll, R.string.settings_cleared_all_data_files));
		rootView.findViewById(R.id.dev_button_upload_reports_clear).setOnClickListener((v) -> createClearDialog(activity, c -> {
			DataStore.delete(activity, DataStore.RECENT_UPLOADS_FILE);
			Preferences.get(activity).edit().remove(Preferences.PREF_OLDEST_RECENT_UPLOAD).apply();
		}, R.string.settings_cleared_all_upload_reports));

		rootView.findViewById(R.id.dev_button_browse_files).setOnClickListener(v -> createFileAlertDialog(activity, activity.getFilesDir(), (file) -> {
			String name = file.getName();
			return !name.startsWith("DATA") && !name.startsWith("firebase") && !name.startsWith("com.") && !name.startsWith("event_store") && !name.startsWith("_m_t") && !name.equals("ZoomTables.data");
		}));

		rootView.findViewById(R.id.dev_button_browse_cache_files).setOnClickListener(v -> createFileAlertDialog(activity, activity.getCacheDir(), (file) -> !file.getName().startsWith("com.") && !file.isDirectory()));


		rootView.findViewById(R.id.dev_button_noise_tracking).setOnClickListener(v -> startActivity(new Intent(getActivity(), NoiseTestingActivity.class)));

		rootView.findViewById(R.id.dev_button_notification_dummy).setOnClickListener(v -> {
			String helloWorld = getString(R.string.dev_notification_dummy);
			int color = ContextCompat.getColor(activity, R.color.color_primary);
			Random rng = new Random(System.currentTimeMillis());
			String[] facts = getResources().getStringArray(R.array.lorem_ipsum_facts);
			NotificationCompat.Builder notiBuilder = new NotificationCompat.Builder(activity, getString(R.string.channel_other_id))
					.setSmallIcon(R.drawable.ic_signals)
					.setTicker(helloWorld)
					.setColor(color)
					.setLights(color, 2000, 5000)
					.setContentTitle(getString(R.string.did_you_know))
					.setContentText(facts[rng.nextInt(facts.length)])
					.setWhen(System.currentTimeMillis());
			NotificationManager notificationManager = (NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE);
			assert notificationManager != null;
			notificationManager.notify(dummyNotificationIndex++, notiBuilder.build());
		});

		rootView.findViewById(R.id.dev_button_activity_recognition).setOnClickListener(v -> startActivity(new Intent(getActivity(), ActivityRecognitionActivity.class)));
	}

	private void createClearDialog(@NonNull Context context, IValueCallback<Context> clearFunction, @StringRes int snackBarString) {
		AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(context);
		alertDialogBuilder
				.setPositiveButton(getResources().getText(R.string.yes), (dialog, which) -> {
					new SnackMaker(getActivity()).showSnackbar(snackBarString);
					clearFunction.callback(context);
				})
				.setNegativeButton(getResources().getText(R.string.no), (dialog, which) -> {
				})
				.setMessage(getResources().getText(R.string.alert_confirm_generic));

		alertDialogBuilder.show();
	}

	private void createFileAlertDialog(@NonNull Context context, @NonNull File directory, @Nullable IVerify<File> verifyFunction) {
		File[] files = directory.listFiles();
		ArrayList<String> temp = new ArrayList<>();
		for (File file : files) {
			if (verifyFunction == null || verifyFunction.verify(file)) {
				temp.add(file.getName() + "|  " + Assist.humanReadableByteCount(file.length(), true));
			}
		}

		Collections.sort(temp, String::compareTo);

		AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(context);
		String[] fileNames = new String[temp.size()];
		alertDialogBuilder
				.setTitle(getString(R.string.dev_browse_files))
				.setItems(temp.toArray(fileNames), (dialog, which) -> {
					Intent intent = new Intent(getActivity(), DebugFileActivity.class);
					intent.putExtra("directory", directory.getPath());
					intent.putExtra("fileName", fileNames[which].substring(0, fileNames[which].lastIndexOf('|')));
					startActivity(intent);
				})
				.setNegativeButton(R.string.cancel, (dialog, which) -> {
				});

		alertDialogBuilder.show();
	}

	private void setSeekbar(@NonNull Context context,
	                        @NonNull IntSlider slider,
	                        @NonNull TextView title,
	                        int minValue,
	                        int maxValue,
	                        int step,
	                        @Nullable String preference,
	                        int defaultValue,
	                        @NonNull Slider.IStringify<Integer> textGenerationFuncton,
	                        @Nullable INonNullValueCallback<Integer> valueCallback) {
		slider.setMaxValue(maxValue);
		int previousProgress = Preferences.get(context).getInt(preference, defaultValue) - minValue;
		slider.setProgressValue(previousProgress);
		slider.setStep(step);
		slider.setMinValue(minValue);
		slider.setTextView(title, textGenerationFuncton);
		if (valueCallback != null)
			slider.setOnValueChangeListener((value, fromUser) -> valueCallback.callback(slider.getValue()));
	}

	private void setSwitchChangeListener(@NonNull final Context context, @NonNull final String name, Switch s, final boolean defaultState, @Nullable final INonNullValueCallback<Boolean> callback) {
		s.setChecked(Preferences.get(context).getBoolean(name, defaultState));
		s.setOnCheckedChangeListener((CompoundButton compoundButton, boolean b) -> {
			Preferences.get(context).edit().putBoolean(name, b).apply();
			if (callback != null)
				callback.callback(b);
		});
	}

	private void resolveUserMenuOnLogin(@NonNull final User u, @NonNull final Prices prices) {
		Activity activity = getActivity();
		if (activity != null) {
			activity.runOnUiThread(() -> {
				DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, Locale.getDefault());
				LinearLayout userInfoLayout = (LinearLayout) signedInMenu.getChildAt(0);
				userInfoLayout.setVisibility(View.VISIBLE);

				TextView wPointsTextView = (TextView) userInfoLayout.getChildAt(0);
				wPointsTextView.setText(String.format(activity.getString(R.string.user_have_wireless_points), Assist.formatNumber(u.getWirelessPoints())));

				LinearLayout mapAccessLayout = (LinearLayout) userInfoLayout.getChildAt(1);
				Switch mapAccessSwitch = (Switch) mapAccessLayout.getChildAt(0);
				TextView mapAccessTimeTextView = ((TextView) mapAccessLayout.getChildAt(1));

				mapAccessSwitch.setText(activity.getString(R.string.user_renew_map));
				mapAccessSwitch.setChecked(u.getNetworkPreferences().renewMap);
				mapAccessSwitch.setOnCheckedChangeListener((CompoundButton compoundButton, boolean b) -> {
					compoundButton.setEnabled(false);
					MultipartBody body = new MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("value", Boolean.toString(b)).build();
					Network.client(activity, u.token).newCall(Network.requestPOST(Network.URL_USER_UPDATE_MAP_PREFERENCE, body)).enqueue(new Callback() {
						@Override
						public void onFailure(@NonNull Call call, @NonNull IOException e) {
							activity.runOnUiThread(() -> {
								compoundButton.setEnabled(true);
								compoundButton.setChecked(!b);
							});
						}

						@Override
						public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
							if (response.isSuccessful()) {
								User.NetworkInfo networkInfo = u.getNetworkInfo();
								u.getNetworkPreferences().renewMap = b;
								if (b) {
									ResponseBody body = response.body();
									if (body != null) {
										long temp = networkInfo.mapAccessUntil;
										networkInfo.mapAccessUntil = Long.parseLong(body.string());
										if (temp != networkInfo.mapAccessUntil) {
											u.addWirelessPoints(-prices.PRICE_30DAY_MAP);
											activity.runOnUiThread(() -> {
												wPointsTextView.setText(activity.getString(R.string.user_have_wireless_points, Assist.formatNumber(u.getWirelessPoints())));
												mapAccessTimeTextView.setText(String.format(activity.getString(R.string.user_access_date), dateFormat.format(new Date(networkInfo.mapAccessUntil))));
												mapAccessTimeTextView.setVisibility(View.VISIBLE);
											});
										}

									} else
										FirebaseCrash.report(new Throwable("Body is null"));
								}
								DataStore.saveString(activity, Preferences.PREF_USER_DATA, new Gson().toJson(u), false);
							} else {
								activity.runOnUiThread(() -> compoundButton.setChecked(!b));
								new SnackMaker(activity).showSnackbar(R.string.user_not_enough_wp);
							}
							activity.runOnUiThread(() -> compoundButton.setEnabled(true));
							response.close();
						}
					});
				});

				if (u.getNetworkInfo().mapAccessUntil > System.currentTimeMillis())
					mapAccessTimeTextView.setText(String.format(activity.getString(R.string.user_access_date), dateFormat.format(new Date(u.getNetworkInfo().mapAccessUntil))));
				else
					mapAccessTimeTextView.setVisibility(View.GONE);
				((TextView) mapAccessLayout.getChildAt(2)).setText(String.format(activity.getString(R.string.user_cost_per_month), Assist.formatNumber(prices.PRICE_30DAY_MAP)));

				LinearLayout userMapAccessLayout = (LinearLayout) userInfoLayout.getChildAt(2);
				Switch userMapAccessSwitch = (Switch) userMapAccessLayout.getChildAt(0);
				TextView personalMapAccessTimeTextView = ((TextView) userMapAccessLayout.getChildAt(1));

				userMapAccessSwitch.setText(activity.getString(R.string.user_renew_personal_map));
				userMapAccessSwitch.setChecked(u.getNetworkPreferences().renewPersonalMap);
				userMapAccessSwitch.setOnCheckedChangeListener((CompoundButton compoundButton, boolean b) -> {
					compoundButton.setEnabled(false);
					MultipartBody body = new MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("value", Boolean.toString(b)).build();
					Network.client(activity, u.token).newCall(Network.requestPOST(Network.URL_USER_UPDATE_PERSONAL_MAP_PREFERENCE, body)).enqueue(new Callback() {
						@Override
						public void onFailure(@NonNull Call call, @NonNull IOException e) {
							activity.runOnUiThread(() -> {
								compoundButton.setEnabled(true);
								compoundButton.setChecked(!b);
							});
						}

						@Override
						public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
							if (response.isSuccessful()) {
								User.NetworkInfo networkInfo = u.getNetworkInfo();
								u.getNetworkPreferences().renewPersonalMap = b;
								if (b) {
									ResponseBody body = response.body();
									if (body != null) {
										long temp = networkInfo.personalMapAccessUntil;
										networkInfo.personalMapAccessUntil = Long.parseLong(body.string());
										if (temp != networkInfo.personalMapAccessUntil) {
											u.addWirelessPoints(-prices.PRICE_30DAY_PERSONAL_MAP);
											activity.runOnUiThread(() -> {
												wPointsTextView.setText(activity.getString(R.string.user_have_wireless_points, Assist.formatNumber(u.getWirelessPoints())));
												personalMapAccessTimeTextView.setText(String.format(activity.getString(R.string.user_access_date), dateFormat.format(new Date(networkInfo.personalMapAccessUntil))));
												personalMapAccessTimeTextView.setVisibility(View.VISIBLE);
											});
										}

									} else
										FirebaseCrash.report(new Throwable("Body is null"));
								}
								DataStore.saveString(activity, Preferences.PREF_USER_DATA, new Gson().toJson(u), false);
							} else {
								activity.runOnUiThread(() -> compoundButton.setChecked(!b));
								new SnackMaker(activity).showSnackbar(R.string.user_not_enough_wp);
							}
							activity.runOnUiThread(() -> compoundButton.setEnabled(true));
							response.close();
						}
					});
				});

				if (u.getNetworkInfo().personalMapAccessUntil > System.currentTimeMillis())
					personalMapAccessTimeTextView.setText(String.format(activity.getString(R.string.user_access_date), dateFormat.format(new Date())));
				else
					personalMapAccessTimeTextView.setVisibility(View.GONE);
				((TextView) userMapAccessLayout.getChildAt(2)).setText(String.format(activity.getString(R.string.user_cost_per_month), Assist.formatNumber(prices.PRICE_30DAY_PERSONAL_MAP)));
			});

			if (u.getNetworkInfo().hasMapAccess())
				NetworkLoader.request(Network.URL_MAPS_AVAILABLE, DAY_IN_MINUTES, activity, Preferences.PREF_AVAILABLE_MAPS, MapLayer[].class, (state, layerArray) -> {
					if (layerArray != null && layerArray.length > 0) {
						SharedPreferences sp = Preferences.get(activity);
						String defaultOverlay = sp.getString(Preferences.PREF_DEFAULT_MAP_OVERLAY, layerArray[0].getName());
						int index = MapLayer.indexOf(layerArray, defaultOverlay);
						final int selectIndex = index == -1 ? 0 : index;
						if (index == -1)
							sp.edit().putString(Preferences.PREF_DEFAULT_MAP_OVERLAY, layerArray[0].getName()).apply();

						CharSequence[] items = new CharSequence[layerArray.length];
						for (int i = 0; i < layerArray.length; i++)
							items[i] = layerArray[i].getName();

						activity.runOnUiThread(() -> {
							final Button mapOverlayButton = rootView.findViewById(R.id.setting_map_overlay_button);

							final ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, R.layout.spinner_item, MapLayer.toStringArray(layerArray));
							adapter.setDropDownViewResource(R.layout.spinner_item);
							mapOverlayButton.setText(items[selectIndex]);
							mapOverlayButton.setOnClickListener(v -> {
								String ov = sp.getString(Preferences.PREF_DEFAULT_MAP_OVERLAY, layerArray[0].getName());
								int in = MapLayer.indexOf(layerArray, ov);
								int selectIn = in == -1 ? 0 : in;

								AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(activity, R.style.AlertDialog);
								alertDialogBuilder
										.setTitle(getString(R.string.settings_default_map_overlay))
										.setSingleChoiceItems(items, selectIn, (dialog, which) -> {
											Preferences.get(activity).edit().putString(Preferences.PREF_DEFAULT_MAP_OVERLAY, adapter.getItem(which)).apply();
											mapOverlayButton.setText(items[which]);
											dialog.dismiss();
										})
										.setNegativeButton(R.string.cancel, (dialog, which) -> {
										});

								alertDialogBuilder.create().show();
							});

							final LinearLayout mDOLayout = rootView.findViewById(R.id.settings_map_overlay_layout);
							mDOLayout.setVisibility(View.VISIBLE);
						});
					}
				});
		}
	}

	@NonNull
	@Override
	public Failure<String> onEnter(@NonNull FragmentActivity activity, @NonNull FloatingActionButton fabOne, @NonNull FloatingActionButton fabTwo) {
		return new Failure<>();
	}

	@Override
	public void onLeave(@NonNull FragmentActivity activity) {
		if (signin != null) {
			signin = null;
		}
	}

	@Override
	public void onPermissionResponse(int requestCode, boolean success) {
		switch (requestCode) {
			case REQUEST_CODE_PERMISSIONS_MICROPHONE:
				if (success)
					Preferences.get(getContext()).edit().putBoolean(Preferences.PREF_TRACKING_NOISE_ENABLED, true).apply();
				else
					switchNoise.setChecked(false);
				break;
			default:
				throw new UnsupportedOperationException("Permissions with requestPOST code " + requestCode + " has no defined behavior");
		}
	}

	@Override
	public void onHomeAction() {
		View v = getView();
		if (v != null) {
			Assist.verticalSmoothScrollTo(v.findViewById(R.id.settings_scrollbar), 0, 500);
		}
	}

}
