package com.adsamcik.signalcollector.utility;

import android.content.Context;
import android.support.annotation.NonNull;

import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.enums.ResolvedActivity;
import com.google.android.gms.location.DetectedActivity;

public class ActivityInfo {
	public final int activity;
	public final int confidence;
	public final @ResolvedActivity int resolvedActivity;

	public ActivityInfo(final int activity, final int confidence) {
		this.activity = activity;
		this.confidence = confidence;
		this.resolvedActivity = resolveActivity(activity);
	}

	/**
	 * 0 still/default
	 * 1 foot
	 * 2 vehicle
	 * 3 tilting
	 */
	private static @ResolvedActivity int resolveActivity(int activity) {
		switch (activity) {
			case DetectedActivity.STILL:
				return ResolvedActivity.STILL;
			case DetectedActivity.RUNNING:
				return ResolvedActivity.ON_FOOT;
			case DetectedActivity.ON_FOOT:
				return ResolvedActivity.ON_FOOT;
			case DetectedActivity.WALKING:
				return ResolvedActivity.ON_FOOT;
			case DetectedActivity.ON_BICYCLE:
				return ResolvedActivity.IN_VEHICLE;
			case DetectedActivity.IN_VEHICLE:
				return ResolvedActivity.IN_VEHICLE;
			case DetectedActivity.TILTING:
				return ResolvedActivity.UNKNOWN;
			default:
				return ResolvedActivity.UNKNOWN;
		}
	}

	public String getActivityName() {
		return getActivityName(activity);
	}

	public static String getActivityName(int activity) {
		switch (activity) {
			case DetectedActivity.IN_VEHICLE:
				return "In Vehicle";
			case DetectedActivity.ON_BICYCLE:
				return "On Bicycle";
			case DetectedActivity.ON_FOOT:
				return "On Foot";
			case DetectedActivity.WALKING:
				return "Walking";
			case DetectedActivity.STILL:
				return "Still";
			case DetectedActivity.TILTING:
				return "Tilting";
			case DetectedActivity.RUNNING:
				return "Running";
			case DetectedActivity.UNKNOWN:
				return "Unknown";
		}
		return "N/A";
	}

	public static String getResolvedActivityName(@NonNull Context context, @ResolvedActivity int resolvedActivity) {
		switch (resolveActivity(resolvedActivity)) {
			case 0:
				return context.getString(R.string.activity_idle);
			case 1:
				return context.getString(R.string.activity_on_foot);
			case 2:
				return context.getString(R.string.activity_in_vehicle);
			case 3:
			default:
				return context.getString(R.string.activity_unknown);
		}
	}
}
