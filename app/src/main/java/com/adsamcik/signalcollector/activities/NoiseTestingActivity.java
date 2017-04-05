package com.adsamcik.signalcollector.activities;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;

import com.adsamcik.signalcollector.NoiseTracker;
import com.adsamcik.signalcollector.R;

public class NoiseTestingActivity extends Activity {

	private Button startStopButton;
	private NoiseTracker noiseTracker;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_noise_testing);
		noiseTracker = new NoiseTracker(this);
		startStopButton = (Button) findViewById(R.id.noiseTestStartStopButton);
		startStopButton.setOnClickListener(view -> {
			if (noiseTracker.isRunning()) {
				noiseTracker.start();
				startStopButton.setText("STOP");
			} else {
				noiseTracker.stop();
				startStopButton.setText("START");
			}
		});
	}
}
