package com.adsamcik.signalcollector;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

public class NoiseTracking {
	private static final String TAG = "SignalsNoise";
	private static double REFERENCE = 0.00002;
	private static final int SAMPLING = 44100;
	private static final int bufferSize = AudioRecord.getMinBufferSize(SAMPLING, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
	private static final AudioRecord audioRecorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLING, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, 2 * bufferSize);

	private static double getAmplitude() {
		short[] buffer = new short[bufferSize];
		audioRecorder.read(buffer, 0, bufferSize);
		int max = 0;
		for(short s : buffer) {
			if(Math.abs(s) > max) {
				max = Math.abs(s);
			}
		}
		Log.d(TAG, "max " + max);
		return max;
	}

	public static double GetNoiseLevel() {
		return 20 * Math.log10(Math.abs(getAmplitude()));
	}

	public static void Record() {
		audioRecorder.startRecording();
	}

	public static void Stop() {
		audioRecorder.stop();
	}
}
