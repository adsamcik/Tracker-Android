package com.adsamcik.tracker.impexp.importer.file

import com.adsamcik.tracker.impexp.portable.LocationFilePointV1
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.MotionState
import com.adsamcik.tracker.shared.base.database.data.SampleQuality

/** Restores the saved measurements without claiming a live provider, session or recording grant. */
internal fun LocationFilePointV1.toImportedSample(createdAtMs: Long): LocationSample {
	val saved = measurement
	return LocationSample(
		timeMs = saved.timeMs,
		elapsedRealtimeNanos = 0L,
		latE7 = saved.latE7,
		lonE7 = saved.lonE7,
		altitudeM = saved.altitudeM,
		rawGpsAltitudeM = saved.rawGpsAltitudeM,
		hAccM = saved.horizontalAccuracyM,
		vAccM = saved.verticalAccuracyM,
		speedMps = saved.speedMps,
		speedAccuracyMps = saved.speedAccuracyMps,
		provider = saved.provider,
		quality = SampleQuality.valueOf(saved.quality.name),
		motionState = saved.motionState?.let { MotionState.valueOf(it.name) },
		policy = null,
		bucketId = null,
		createdAt = createdAtMs,
		permissionPrecision = saved.permissionPrecision,
		isMock = saved.isMock,
		altitudeDatum = saved.altitudeDatum,
		altitudeSource = saved.altitudeSource,
		altitudeConversionStatus = saved.altitudeConversionStatus,
		rawGpsAltitudeDatum = saved.rawGpsAltitudeDatum,
		rawPlatformSpeedMps = saved.rawSpeedMps,
		rawPlatformSpeedAccuracyMps = saved.rawSpeedAccuracyMps,
		bearingDeg = saved.bearingDeg,
		bearingAccuracyDeg = saved.bearingAccuracyDeg,
	)
}
