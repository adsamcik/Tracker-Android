package com.adsamcik.signalcollector.services;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.support.annotation.NonNull;

import com.adsamcik.signalcollector.file.DataStore;
import com.adsamcik.signalcollector.utility.Assist;
import com.adsamcik.signalcollector.utility.Constants;
import com.adsamcik.signalcollector.utility.Preferences;

public class UploadScheduler extends JobService {
	private static final int MIN_NO_ACTIVITY_DELAY = Assist.MINUTE_IN_MILLISECONDS * 30;

	public static void requestUploadSchedule(@NonNull Context context) {
		long sizeOfData = DataStore.sizeOfData();
		if(sizeOfData >= Constants.MIN_BACKGROUND_UPLOAD_FILE_SIZE) {
			JobScheduler scheduler = ((JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE));
			assert scheduler != null;
			JobInfo.Builder jb = new JobInfo.Builder(Preferences.UPLOAD_SCHEDULE_JOB, new ComponentName(context, UploadScheduler.class));
			jb.setPersisted(true);
			jb.setRequiresDeviceIdle(true);
			jb.setMinimumLatency(MIN_NO_ACTIVITY_DELAY);
			scheduler.schedule(jb.build());
		}
	}

	@Override
	public boolean onStartJob(JobParameters jobParameters) {
		long sizeOfData = DataStore.sizeOfData();
		if(sizeOfData > Constants.MIN_BACKGROUND_UPLOAD_FILE_SIZE)
			UploadService.requestUpload(getApplicationContext(), UploadService.UploadScheduleSource.BACKGROUND);
		return false;
	}

	@Override
	public boolean onStopJob(JobParameters jobParameters) {
		return false;
	}
}
