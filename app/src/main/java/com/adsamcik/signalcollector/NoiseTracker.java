package com.adsamcik.signalcollector;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.NoiseSuppressor;
import android.os.AsyncTask;
import android.support.annotation.NonNull;

import com.adsamcik.signalcollector.utility.EArray;
import com.google.firebase.crash.FirebaseCrash;

public class NoiseTracker implements SensorEventListener {
	private final String TAG = "SignalsNoise";
	private static final int SAMPLING = 22050;
	// AudioRecord.getMinBufferSize(SAMPLING, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2
	private static final int bufferSize = SAMPLING * 2;

	private final short MAX_HISTORY_SIZE = 20;
	private final short[] values = new short[MAX_HISTORY_SIZE];
	private final boolean[] valuesPocket = new boolean[MAX_HISTORY_SIZE];

	private final SensorManager mSensorManager;
	private final Sensor mProximity;
	private boolean proximityNear;

	private NoiseCheckTask task;

	/**
	 * Creates new instance of noise tracker. Does not start tracking.
	 */
	public NoiseTracker(@NonNull Context context) {
		mSensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
		assert mSensorManager != null;
		mProximity = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
	}

	/**
	 * Starts noise tracking.
	 *
	 * @return this
	 */

	public NoiseTracker start() {
		if (task == null || task.getStatus() == AsyncTask.Status.FINISHED) {
			task = new NoiseCheckTask(this);
			task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
		}
		mSensorManager.registerListener(this, mProximity, SensorManager.SENSOR_DELAY_NORMAL);
		return this;
	}

	/**
	 * Checks whether audio recorder is recording
	 *
	 * @return true if audioRecorder is running
	 */
	public boolean isRunning() {
		return task != null;
	}

	/**
	 * Stops noise tracking
	 *
	 * @return this
	 */
	public NoiseTracker stop() {
		if (task != null) {
			task.cancel(true);
			task = null;
		}
		mSensorManager.unregisterListener(this);

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
		short currentIndex = task.currentIndex;
		if (currentIndex == -1)
			return -1;
		final short s = seconds > currentIndex ? currentIndex : (short) seconds;
		int avg = 0;
		int cnt = 0;
		for (int i = currentIndex - s; i <= currentIndex; i++) {
			if (valuesPocket[i]) {
				avg += values[i];
				cnt++;
			} else {
				avg += 2 * values[i];
				cnt += 2;
			}
		}

		avg /= cnt;
		task.currentIndex = 0;
		return (short) avg;
	}

	@Override
	public void onSensorChanged(SensorEvent sensorEvent) {
		if (sensorEvent.sensor.getType() == Sensor.TYPE_PROXIMITY)
			proximityNear = sensorEvent.values[0] < sensorEvent.sensor.getMaximumRange();
	}

	@Override
	public void onAccuracyChanged(Sensor sensor, int i) {

	}

	private static class NoiseCheckTask extends AsyncTask<AudioRecord, Void, Void> {
		private final int PROCESS_SAMPLES_EVERY_SECOND = 25;
		private final int SKIP_SAMPLES = SAMPLING / PROCESS_SAMPLES_EVERY_SECOND;

		private final AudioRecord audioRecorder;
		private short lastAvg = -1;
		private short currentIndex = -1;
		private final NoiseTracker noiseTracker;

		private NoiseCheckTask(NoiseTracker noiseTracker) {
			audioRecorder = new AudioRecord(MediaRecorder.AudioSource.CAMCORDER, SAMPLING, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
			if (NoiseSuppressor.isAvailable()) {
				NoiseSuppressor noiseSuppressor = NoiseSuppressor.create(audioRecorder.getAudioSessionId());
				if (noiseSuppressor == null)
					FirebaseCrash.report(new Throwable("noise suppressor is null when it should be available."));
				else if (!noiseSuppressor.getEnabled())
					noiseSuppressor.setEnabled(true);
			}
			this.noiseTracker = noiseTracker;
		}

		@Override
		protected Void doInBackground(AudioRecord... records) {
			audioRecorder.startRecording();
			while (!isCancelled() && audioRecorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
				boolean inPocket = noiseTracker.proximityNear;
				short approxVal = getApproxAmplitude(inPocket);
				if (approxVal == -1)
					break;
				else if (approxVal == -2)
					continue;
				noiseTracker.values[++currentIndex] = approxVal;
				noiseTracker.valuesPocket[currentIndex] = inPocket;

				if (currentIndex >= noiseTracker.MAX_HISTORY_SIZE - 1)
					break;

				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					break;
				}
			}
			noiseTracker.stop();
			return null;
		}

		private short[][] deinterleaveData(short[] samples, int numChannels) {
			int numFrames = samples.length / numChannels;

			short[][] result = new short[numChannels][];
			for (int ch = 0; ch < numChannels; ch++) {
				result[ch] = new short[numFrames];
				for (int i = 0; i < numFrames; i++) {
					result[ch][i] = samples[numChannels * i + ch];
				}
			}
			return result;
		}

		@Override
		protected void onPostExecute(Void aVoid) {
			if (audioRecorder.getState() > 0)
				audioRecorder.stop();
			super.onPostExecute(aVoid);
		}

		@Override
		protected void onCancelled() {
			if (audioRecorder.getState() > 0)
				audioRecorder.stop();
			super.onCancelled();
		}

		private short getApproxAmplitude(boolean inPocket) {
			short[] temp = new short[bufferSize];
			int audioState = audioRecorder.read(temp, 0, bufferSize);


			if (audioState == AudioRecord.ERROR_INVALID_OPERATION || audioState == AudioRecord.ERROR_BAD_VALUE) {
				FirebaseCrash.report(new Throwable("Noise tracking failed with state " + audioState));
				return -1;
			}

			return EArray.avgAbs(temp);



			/*Complex[][] results = new Complex[2][];
			final int count = audioState / 2;

			for (int times = 0; times < 2; times++) {
				Complex[] complex = new Complex[count];
				for (int i = 0; i < count; i++) {
					//Put the time domain data into a complex number with imaginary part as 0:
					complex[i] = new Complex(temp[(times * count) + i], 0);
				}
				//Perform FFT analysis on the chunk:
				results[times] = transformer.transform(complex, TransformType.FORWARD);
			}

			double[] noiseWave = new double[bufferSize / SKIP_SAMPLES];
			for (int i = 1; i < results.length - 1; i += SKIP_SAMPLES) {
				short amp = (short) Math.abs(results[i].);
				if (amp < min && Math.abs(amp - Math.abs(buffer[i - 1])) < 100 && Math.abs(amp - Math.abs(buffer[i + 1])) < 100) {
					min = amp;
				}
			}

			short min = Short.MAX_VALUE;
			for (int i = 1; i < results.length - 1; i += SKIP_SAMPLES) {
				short amp = (short) Math.abs(results[i].);
				if (amp < min && Math.abs(amp - Math.abs(buffer[i - 1])) < 100 && Math.abs(amp - Math.abs(buffer[i + 1])) < 100) {
					min = amp;
				}
			}
			return min;*/

			/*if (inPocket) {
				short min = Short.MAX_VALUE;
				for (int i = 1; i < results.length - 1; i += SKIP_SAMPLES) {
					short amp = (short) Math.abs(results[i].);
					if (amp < min && Math.abs(amp - Math.abs(buffer[i - 1])) < 100 && Math.abs(amp - Math.abs(buffer[i + 1])) < 100) {
						min = amp;
					}
				}
				return min;
			} else {
				int avg = 0;

				for (int i = 0; i < buffer.length; i += SKIP_SAMPLES) {
					short amp = (short) Math.abs(buffer[i]);
					avg += amp;
				}

				avg /= PROCESS_SAMPLES_EVERY_SECOND;

				if (lastAvg != -1)
					avg = (avg + lastAvg) / 2;

				int finalAvg = 0;
				short count = 0;
				for (int i = 0; i < buffer.length; i += SKIP_SAMPLES) {
					short amp = (short) Math.abs(buffer[i]);
					if (amp < avg) {
						finalAvg += avg;
						count++;
					}
				}

				if (count == 0)
					return -2;

				lastAvg = (short) avg;
				return (short) (finalAvg / count);
			}*/
		}
	}
}
