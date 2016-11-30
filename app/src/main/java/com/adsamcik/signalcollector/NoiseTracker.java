package com.adsamcik.signalcollector;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.NoiseSuppressor;
import android.os.AsyncTask;

public class NoiseTracker {
	private final String TAG = "SignalsNoise";
	private final int SAMPLING = 44100;
	private final int bufferSize = AudioRecord.getMinBufferSize(SAMPLING, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
	private final AudioRecord audioRecorder;
	private final NoiseSuppressor noiseSuppressor;

	private short currentIndex = -1;
	private final short MAX_HISTORY_SIZE = 20;
	private final short[] values = new short[MAX_HISTORY_SIZE];

	private AsyncTask task;

	/**
	 * Creates new instance of noise tracker. Does not start tracking.
	 */
	public NoiseTracker() {
		audioRecorder = new AudioRecord(MediaRecorder.AudioSource.CAMCORDER, SAMPLING, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
	}

	/**
	 * Starts noise tracking.
	 *
	 * @return this
	 */
	public NoiseTracker start() {
		noiseSuppressor = NoiseSuppressor.create(audioRecorder.getAudioSessionId());
		if (audioRecorder.getState() == AudioRecord.RECORDSTATE_STOPPED)
			audioRecorder.startRecording();
		if (task == null || task.getStatus() == AsyncTask.Status.FINISHED)
			task = new NoiseCheckTask().execute(audioRecorder);
		return this;
	}

	/**
	 * Stops noise tracking
	 *
	 * @return this
	 */
	public NoiseTracker stop() {
		if (audioRecorder.getState() > 0)
			audioRecorder.stop();
		if (task != null) {
			task.cancel(true);
			task = null;
		}
		if(noiseSuppressor != null)
			noiseSuppressor.release();
		
		currentIndex = 0;
		return this;
	}

	/**
	 * Calculates average noise value from the last x seconds. Tracker does not save more than {@link #MAX_HISTORY_SIZE} seconds.
	 * Noise history is cleaned after getting this sample.
	 *
	 * @param seconds seconds from history which should be taken into account.
	 * @return average noise (amplitude) value
	 */
	public short getSample(final int seconds) {
		if (currentIndex == -1)
			return -1;
		final short s = seconds > currentIndex ? currentIndex : (short) seconds;
		int avg = 0;
		for (int i = currentIndex - s; i <= currentIndex; i++)
			avg += values[i];

		avg /= s + 1;
		currentIndex = 0;
		return (short) avg;
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
					short MAX_HISTORY_INDEX = MAX_HISTORY_SIZE - 1;
					if (currentIndex >= MAX_HISTORY_INDEX)
						break;
				} catch (InterruptedException e) {
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
