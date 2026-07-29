package com.adsamcik.tracker.shared.base.mapper

import com.adsamcik.tracker.shared.base.data.Location as EntityLocation
import com.adsamcik.tracker.shared.base.database.data.LocationSample as EntityLocationSample
import com.adsamcik.tracker.shared.base.database.data.MotionState as EntityMotionState
import com.adsamcik.tracker.shared.base.database.data.SampleQuality as EntitySampleQuality
import com.adsamcik.tracker.shared.base.database.data.SkiRunSegment as EntitySkiRunSegment
import com.adsamcik.tracker.shared.base.database.data.SkiSegmentType as EntitySkiSegmentType
import com.adsamcik.tracker.shared.base.database.data.Trip as EntityTrip
import com.adsamcik.tracker.shared.base.database.data.TripDaySummary as EntityTripDaySummary
import com.adsamcik.tracker.shared.model.Location as ModelLocation
import com.adsamcik.tracker.shared.model.LocationSample as ModelLocationSample
import com.adsamcik.tracker.shared.model.MotionState as ModelMotionState
import com.adsamcik.tracker.shared.model.SampleQuality as ModelSampleQuality
import com.adsamcik.tracker.shared.model.SkiRunSegment as ModelSkiRunSegment
import com.adsamcik.tracker.shared.model.SkiSegmentType as ModelSkiSegmentType
import com.adsamcik.tracker.shared.model.Trip as ModelTrip
import com.adsamcik.tracker.shared.model.TripDaySummary as ModelTripDaySummary

fun EntityLocation.toModel(): ModelLocation = ModelLocation(
time = time,
latitude = latitude,
longitude = longitude,
altitude = altitude,
horizontalAccuracy = horizontalAccuracy,
verticalAccuracy = verticalAccuracy,
speed = speed,
speedAccuracy = speedAccuracy,
)

fun ModelLocation.toEntity(): EntityLocation = EntityLocation(
time = time,
latitude = latitude,
longitude = longitude,
altitude = altitude,
horizontalAccuracy = horizontalAccuracy,
verticalAccuracy = verticalAccuracy,
speed = speed,
speedAccuracy = speedAccuracy,
)

fun EntityTrip.toModel(): ModelTrip = ModelTrip(
id = id,
startTimeMs = startTimeMs,
endTimeMs = endTimeMs,
distanceM = distanceM,
steps = steps,
primaryActivity = primaryActivity,
activityConfidence = activityConfidence,
sampleCount = sampleCount,
source = source,
createdAt = createdAt,
hasDistanceAnomaly = hasDistanceAnomaly,
)

fun ModelTrip.toEntity(): EntityTrip = EntityTrip(
id = id,
startTimeMs = startTimeMs,
endTimeMs = endTimeMs,
distanceM = distanceM,
steps = steps,
primaryActivity = primaryActivity,
activityConfidence = activityConfidence,
sampleCount = sampleCount,
source = source,
createdAt = createdAt,
hasDistanceAnomaly = hasDistanceAnomaly,
)

fun EntityTripDaySummary.toModel(): ModelTripDaySummary = ModelTripDaySummary(
tripCount = tripCount,
totalDistanceM = totalDistanceM,
totalSteps = totalSteps,
totalDurationMs = totalDurationMs,
)

fun ModelTripDaySummary.toEntity(): EntityTripDaySummary = EntityTripDaySummary(
tripCount = tripCount,
totalDistanceM = totalDistanceM,
totalSteps = totalSteps,
totalDurationMs = totalDurationMs,
)

fun EntityLocationSample.toModel(): ModelLocationSample = ModelLocationSample(
id = id,
timeMs = timeMs,
elapsedRealtimeNanos = elapsedRealtimeNanos,
latE7 = latE7,
lonE7 = lonE7,
altitudeM = altitudeM,
rawGpsAltitudeM = rawGpsAltitudeM,
hAccM = hAccM,
vAccM = vAccM,
speedMps = speedMps,
speedAccuracyMps = speedAccuracyMps,
provider = provider,
quality = quality.toModel(),
motionState = motionState?.toModel(),
policy = policy,
bucketId = bucketId,
createdAt = createdAt,
receivedElapsedRealtimeNanos = receivedElapsedRealtimeNanos,
deliveryAgeMs = deliveryAgeMs,
acquisitionMode = acquisitionMode,
requestPriority = requestPriority,
permissionPrecision = permissionPrecision,
batchIndex = batchIndex,
batchSize = batchSize,
isMock = isMock,
estimatorVersion = estimatorVersion,
calibrationVersion = calibrationVersion,
sourceSignalId = sourceSignalId,
sourceEventId = sourceEventId,
clockDomainId = clockDomainId,
sourceRevision = sourceRevision,
altitudeDatum = altitudeDatum,
altitudeSource = altitudeSource,
altitudeConversionStatus = altitudeConversionStatus,
rawGpsAltitudeDatum = rawGpsAltitudeDatum,
	altitudeModelVersion = altitudeModelVersion,
	rawPlatformSpeedMps = rawPlatformSpeedMps,
	rawPlatformSpeedAccuracyMps = rawPlatformSpeedAccuracyMps,
	bearingDeg = bearingDeg,
	bearingAccuracyDeg = bearingAccuracyDeg,
	bootClockDomainId = bootClockDomainId,
)

fun ModelLocationSample.toEntity(): EntityLocationSample = EntityLocationSample(
id = id,
timeMs = timeMs,
elapsedRealtimeNanos = elapsedRealtimeNanos,
latE7 = latE7,
lonE7 = lonE7,
altitudeM = altitudeM,
rawGpsAltitudeM = rawGpsAltitudeM,
hAccM = hAccM,
vAccM = vAccM,
speedMps = speedMps,
speedAccuracyMps = speedAccuracyMps,
provider = provider,
quality = quality.toEntity(),
motionState = motionState?.toEntity(),
policy = policy,
bucketId = bucketId,
createdAt = createdAt,
receivedElapsedRealtimeNanos = receivedElapsedRealtimeNanos,
deliveryAgeMs = deliveryAgeMs,
acquisitionMode = acquisitionMode,
requestPriority = requestPriority,
permissionPrecision = permissionPrecision,
batchIndex = batchIndex,
batchSize = batchSize,
isMock = isMock,
estimatorVersion = estimatorVersion,
calibrationVersion = calibrationVersion,
sourceSignalId = sourceSignalId,
sourceEventId = sourceEventId,
clockDomainId = clockDomainId,
sourceRevision = sourceRevision,
altitudeDatum = altitudeDatum,
altitudeSource = altitudeSource,
altitudeConversionStatus = altitudeConversionStatus,
rawGpsAltitudeDatum = rawGpsAltitudeDatum,
	altitudeModelVersion = altitudeModelVersion,
	rawPlatformSpeedMps = rawPlatformSpeedMps,
	rawPlatformSpeedAccuracyMps = rawPlatformSpeedAccuracyMps,
	bearingDeg = bearingDeg,
	bearingAccuracyDeg = bearingAccuracyDeg,
	bootClockDomainId = bootClockDomainId,
)

fun EntitySkiRunSegment.toModel(): ModelSkiRunSegment = ModelSkiRunSegment(
id = id,
sessionId = sessionId,
runIndex = runIndex,
segmentType = segmentType.toModel(),
startTimeMs = startTimeMs,
endTimeMs = endTimeMs,
verticalM = verticalM,
distanceM = distanceM,
maxSpeedMps = maxSpeedMps,
avgSpeedMps = avgSpeedMps,
liftType = liftType,
createdAt = createdAt,
)

fun ModelSkiRunSegment.toEntity(): EntitySkiRunSegment = EntitySkiRunSegment(
id = id,
sessionId = sessionId,
runIndex = runIndex,
segmentType = segmentType.toEntity(),
startTimeMs = startTimeMs,
endTimeMs = endTimeMs,
verticalM = verticalM,
distanceM = distanceM,
maxSpeedMps = maxSpeedMps,
avgSpeedMps = avgSpeedMps,
liftType = liftType,
createdAt = createdAt,
)

private fun EntitySampleQuality.toModel(): ModelSampleQuality = ModelSampleQuality.valueOf(name)
private fun ModelSampleQuality.toEntity(): EntitySampleQuality = EntitySampleQuality.valueOf(name)
private fun EntityMotionState.toModel(): ModelMotionState = ModelMotionState.valueOf(name)
private fun ModelMotionState.toEntity(): EntityMotionState = EntityMotionState.valueOf(name)
private fun EntitySkiSegmentType.toModel(): ModelSkiSegmentType = ModelSkiSegmentType.valueOf(name)
private fun ModelSkiSegmentType.toEntity(): EntitySkiSegmentType = EntitySkiSegmentType.valueOf(name)
