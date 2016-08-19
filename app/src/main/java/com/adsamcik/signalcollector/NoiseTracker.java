package com.adsamcik.signalcollector;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

public class NoiseTracker {
	private final String TAG = "SignalsNoise";
	private final double REFERENCE = 0.00002;
	private final int SAMPLING = 44100;
	private final int bufferSize = AudioRecord.getMinBufferSize(SAMPLING, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
	private final AudioRecord audioRecorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLING, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, 2 * bufferSize);

	private double getAmplitude() {
		short[] buffer = new short[bufferSize];
		audioRecorder.read(buffer, 0, bufferSize);
		int max = 0;
		for(short s : buffer) {
			if(Math.abs(s) > max) {
				max = Math.abs(s);
			}
		}
		Log.d(TAG, "max " + max + " count " + buffer.length);
		return max;
	}

	public double GetNoiseLevel() {
		return 20 * Math.log10(Math.abs(getAmplitude()));
	}

	public void Record() {
		audioRecorder.startRecording();
	}

	public void Stop() {
		audioRecorder.stop();
	}
}
