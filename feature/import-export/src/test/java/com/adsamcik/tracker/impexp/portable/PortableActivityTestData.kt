package com.adsamcik.tracker.impexp.portable

import com.adsamcik.tracker.shared.base.database.ActivityCapturedPortableIntegrity
import com.adsamcik.tracker.shared.base.database.PortableActivityCaptureCoverage
import com.adsamcik.tracker.shared.base.database.PortableActivityDeletionScopeDigest
import com.adsamcik.tracker.shared.base.database.PortableActivityEntryV1
import com.adsamcik.tracker.shared.base.database.PortableActivityEnvelopeV1
import com.adsamcik.tracker.shared.base.database.PortableActivityFragmentV1
import com.adsamcik.tracker.shared.base.database.PortableActivityIdentityKind
import com.adsamcik.tracker.shared.base.database.PortableActivityOpaqueIdentity
import com.adsamcik.tracker.shared.base.database.PortableActivityRunV1
import com.adsamcik.tracker.shared.base.database.PortableActivitySessionMode
import com.adsamcik.tracker.shared.base.database.PortableActivityWindowCoverage
import com.adsamcik.tracker.shared.base.database.PortableActivityWindowV1
import com.adsamcik.tracker.shared.base.database.PortableActivityZoneEpochV1

internal fun activityEnvelope(vararg entries: PortableActivityEntryV1): PortableActivityEnvelopeV1 {
	val values = entries.toList()
	return PortableActivityEnvelopeV1(
		contentChecksum = ActivityCapturedPortableIntegrity.envelopeChecksum(values),
		entries = values,
	)
}

internal fun activityEntry(
	seed: String = "entry",
	startTimeMs: Long = 1_000L,
): PortableActivityEntryV1 {
	val windowIdentity = PortableActivityOpaqueIdentity.derive(
		PortableActivityIdentityKind.CAPTURE_WINDOW,
		"$seed-window",
	)
	val fragments = listOf(
		PortableActivityFragmentV1.Gap(0L, 100L, "NO_QUALIFIED_EVIDENCE"),
	)
	val window = PortableActivityWindowV1(
		identity = windowIdentity,
		contentChecksum = ActivityCapturedPortableIntegrity.windowChecksum(
			identity = windowIdentity,
			startOffsetNanos = 0L,
			endOffsetNanos = 100L,
			storedZoneId = "UTC",
			coverage = PortableActivityWindowCoverage.NONE,
			knownActiveDurationNanos = 0L,
			knownInactiveDurationNanos = 0L,
			unknownActivityDurationNanos = 0L,
			unobservedDurationNanos = 100L,
			fragments = fragments,
		),
		startOffsetNanos = 0L,
		endOffsetNanos = 100L,
		storedZoneId = "UTC",
		coverage = PortableActivityWindowCoverage.NONE,
		knownActiveDurationNanos = 0L,
		knownInactiveDurationNanos = 0L,
		unknownActivityDurationNanos = 0L,
		unobservedDurationNanos = 100L,
		fragments = fragments,
	)
	val runIdentity = PortableActivityOpaqueIdentity.derive(
		PortableActivityIdentityKind.PHYSICAL_RUN,
		"$seed-run",
	)
	val run = PortableActivityRunV1(
		identity = runIdentity,
		deletionScopeDigest = PortableActivityDeletionScopeDigest.derive(
			"$seed-logical",
			"$seed-run",
		),
		contentChecksum = ActivityCapturedPortableIntegrity.runChecksum(
			identity = runIdentity,
			deletionScopeDigest = PortableActivityDeletionScopeDigest.derive(
				"$seed-logical",
				"$seed-run",
			),
			startTimeMs = startTimeMs,
			endTimeMs = startTimeMs + 1_000L,
			captureCoverage = PortableActivityCaptureCoverage.PARTIAL_RUN,
			zoneEpochs = listOf(PortableActivityZoneEpochV1(startTimeMs, "UTC")),
			windows = listOf(window),
		),
		startTimeMs = startTimeMs,
		endTimeMs = startTimeMs + 1_000L,
		captureCoverage = PortableActivityCaptureCoverage.PARTIAL_RUN,
		zoneEpochs = listOf(PortableActivityZoneEpochV1(startTimeMs, "UTC")),
		windows = listOf(window),
	)
	val identity = PortableActivityOpaqueIdentity.derive(
		PortableActivityIdentityKind.LOGICAL_ENTRY,
		"$seed-logical",
	)
	return PortableActivityEntryV1(
		identity = identity,
		contentChecksum = ActivityCapturedPortableIntegrity.entryChecksum(
			identity = identity,
			sessionMode = PortableActivitySessionMode.MANUAL,
			startTimeMs = startTimeMs,
			endTimeMs = startTimeMs + 1_000L,
			runs = listOf(run),
		),
		sessionMode = PortableActivitySessionMode.MANUAL,
		startTimeMs = startTimeMs,
		endTimeMs = startTimeMs + 1_000L,
		runs = listOf(run),
	)
}
