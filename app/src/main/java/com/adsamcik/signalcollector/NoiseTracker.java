package com.adsamcik.signalcollector;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.AsyncTask;
import android.util.Log;

public class NoiseTracker {
	public static final int PERMISSION_ID = 14159195;
	private final String TAG = "SignalsNoise";
	private final double REFERENCE = 0.00002;
	private final int SAMPLING = 44100;
	private final int bufferSize = AudioRecord.getMinBufferSize(SAMPLING, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
	private final AudioRecord audioRecorder;

	private short currentIndex = -1;
	private final short MAX_HISTORY_SIZE = 20;
	private final short MAX_HISTORY_INDEX = MAX_HISTORY_SIZE - 1;
	private final short[] values = new short[MAX_HISTORY_SIZE];

	private AsyncTask task;

	public NoiseTracker() {
		audioRecorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLING, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
	}

	public NoiseTracker start() {
		if (audioRecorder.getState() == AudioRecord.RECORDSTATE_STOPPED)
			audioRecorder.startRecording();
		if (task == null || task.getStatus() == AsyncTask.Status.FINISHED)
			task = new NoiseCheckTask().execute(audioRecorder);
		return this;
	}

	public NoiseTracker stop() {
		audioRecorder.stop();
		if (task != null) {
			task.cancel(true);
			task = null;
		}
		currentIndex = 0;
		return this;
	}

	public double getSample(final int seconds) {
		Log.d(TAG, "count " + currentIndex);
		if (currentIndex == -1)
			return -1;
		final short s = seconds > currentIndex ? currentIndex : (short) seconds;
		int avg = 0;
		for (int i = currentIndex - s; i <= currentIndex; i++)
			avg += values[i];

		avg /= s + 1;
		currentIndex = 0;
		return avg;
	}

	private class NoiseCheckTask extends AsyncTask<AudioRecord, Void, Void> {
		private final int SKIP_BUFFERS = 20;

		@Override
		protected Void doInBackground(AudioRecord... records) {
			AudioRecord audio = records[0];
			while (true) {
				int state = audio.getState();
				if (state == AudioRecord.STATE_UNINITIALIZED)
					continue;
				else if (state < 0 || audio.getRecordingState() == AudioRecord.RECORDSTATE_STOPPED)
					break;

				try {
					Thread.sleep(500);
					values[++currentIndex] = getApproxAmplitude();
					if (currentIndex >= MAX_HISTORY_INDEX)
						break;
				} catch (InterruptedException e) {
					//Log.w(TAG, e.getMessage() + " interrupted");
					break;
				}
			}
			stop();
			return null;
		}


		private short getApproxAmplitude() {
			short[] buffer = new short[bufferSize];
			audioRecorder.read(buffer, 0, bufferSize);
			int val = 0;
			for (int i = 0; i < buffer.length; i += SKIP_BUFFERS)
				val += (short) Math.abs(buffer[i]);
			return (short) (val / (buffer.length / SKIP_BUFFERS));
		}
	}
}
