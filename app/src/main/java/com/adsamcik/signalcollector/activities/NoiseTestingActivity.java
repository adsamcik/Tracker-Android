package com.adsamcik.signalcollector.activities;

import android.app.Activity;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.util.MutableInt;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.adsamcik.signalcollector.NoiseTracker;
import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.utility.Preferences;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

public class NoiseTestingActivity extends DetailActivity {

	private Button startStopButton;
	private NoiseGetter noiseGetter;
	private ArrayList<String> arrayList = new ArrayList<>();
	private ArrayAdapter<String> adapter;

	private MutableInt delayBetweenCollections = new MutableInt(3);


	@Override
	protected void onCreate(Bundle savedInstanceState) {
		setTheme(Preferences.getTheme(this));
		super.onCreate(savedInstanceState);

		View v = getLayoutInflater().inflate(R.layout.layout_noise_testing, createContentParent(false));
		startStopButton = findViewById(R.id.noiseTestStartStopButton);

		setTitle(R.string.noise);

		TextView sampleIntervalTV = v.findViewById(R.id.dev_text_noise_sample_size);

		SeekBar seekBar = v.findViewById(R.id.dev_noise_sample_rate_seek_bar);
		seekBar.setMax(9);
		seekBar.incrementProgressBy(1);
		seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				delayBetweenCollections.value = progress + 1;
				sampleIntervalTV.setText(getString(R.string.x_second_short, delayBetweenCollections.value));
			}

			@Override
			public void onStartTrackingTouch(SeekBar seekBar) {

			}

			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {

			}
		});

		seekBar.setProgress(delayBetweenCollections.value - 1);

		adapter = new ArrayAdapter<>(this, R.layout.spinner_item, arrayList);
		final ListView listView = v.findViewById(R.id.dev_noise_list_view);
		listView.setAdapter(adapter);

		startStopButton.setOnClickListener(view -> {
			if (noiseGetter == null) {
				noiseGetter = new NoiseGetter(this, adapter, listView, delayBetweenCollections);
				noiseGetter.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
				startStopButton.setText(getString(R.string.stop));
			} else {
				noiseGetter.cancel(false);
				noiseGetter = null;
				startStopButton.setText(getString(R.string.start));
			}
		});

		findViewById(R.id.dev_noise_clear_list).setOnClickListener((f) -> adapter.clear());

	}


	@Override
	protected void onDestroy() {
		super.onDestroy();
		if (noiseGetter != null)
			noiseGetter.cancel(true);
	}


	private static class NoiseGetter extends AsyncTask<Void, Void, Void> {
		private final NoiseTracker noiseTracker;
		private final MutableInt delayBetweenSamples;
		private final ArrayAdapter<String> adapter;

		private final WeakReference<Activity> activity;
		private final WeakReference<ListView> listView;

		private NoiseGetter(@NonNull Activity activity, ArrayAdapter<String> adapter, ListView listView, MutableInt delayBetweenSamples) {
			this.delayBetweenSamples = delayBetweenSamples;
			noiseTracker = new NoiseTracker(activity);
			this.adapter = adapter;
			this.activity = new WeakReference<>(activity);
			this.listView = new WeakReference<>(listView);
		}

		@Override
		protected Void doInBackground(Void... params) {
			noiseTracker.start();
			while (delayBetweenSamples != null) {
				try {
					Thread.sleep(delayBetweenSamples.value * 1000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}

				if (isCancelled())
					break;

				short sample = noiseTracker.getSample(delayBetweenSamples.value);
				if (sample != -1) {
					Activity activity = this.activity.get();
					ListView listView = this.listView.get();
					if (activity != null && listView != null)
						activity.runOnUiThread(() -> {
							adapter.add(Integer.toString(sample));
							listView.smoothScrollToPosition(adapter.getCount() - 1);
						});
				}
				//todo add snackbar if noise tracker failed to initialize
			}
			noiseTracker.stop();
			return null;
		}

		@Override
		protected void onCancelled(Void aVoid) {
			super.onCancelled(aVoid);
			if (noiseTracker.isRunning())
				noiseTracker.stop();
		}
	}
}
