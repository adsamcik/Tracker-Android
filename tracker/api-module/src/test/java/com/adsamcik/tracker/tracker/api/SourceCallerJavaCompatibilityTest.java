package com.adsamcik.tracker.tracker.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose;
import com.adsamcik.tracker.shared.model.tracking.TrackingSource;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SourceCallerJavaCompatibilityTest {
	@SuppressWarnings("deprecation")
	@Test
	void constructsAndReadsPublicCallerAndReadinessContracts() {
		TrackingPurposeLeaseIdentity captureLease = TrackingPurposeLeaseIdentity.create(
				TrackingSource.LOCATION,
				TrackingPurpose.SESSION_CAPTURE,
				11L,
				7L,
				5L,
				13L,
				17L,
				"location-capture-owner"
		);
		TrackingPurposeLeaseIdentity controlLease = TrackingPurposeLeaseIdentity.create(
				TrackingSource.ACTIVITY,
				TrackingPurpose.CONTROL,
				11L,
				7L,
				5L,
				13L,
				17L,
				"activity-control-owner"
		);
		TrackingPurposeLeaseIdentity ambientLease = TrackingPurposeLeaseIdentity.create(
				TrackingSource.WIFI,
				TrackingPurpose.AMBIENT_PRODUCT,
				11L,
				7L,
				5L,
				13L,
				17L,
				"wifi-ambient-owner"
		);
		TrackingPurposeLeaseIdentity legacyLease = TrackingPurposeLeaseIdentity.createLegacy(
				TrackingCaptureSource.LOCATION,
				TrackingPurpose.SESSION_CAPTURE,
				11L,
				7L,
				5L,
				13L,
				17L,
				"legacy-location-owner"
		);
		SourceCallerManifestIdentity manifest =
				new SourceCallerManifestIdentity("logical-java", 3L);
		SourceCallerDemandIdentity capture =
				new SourceCallerDemandIdentity(captureLease, manifest);
		SourceCallerDemandIdentity control =
				new SourceCallerDemandIdentity(controlLease, null);
		SourceCallerDemandIdentity ambient =
				new SourceCallerDemandIdentity(ambientLease, null);

		AutomaticTrackingOperationalAvailability.Ready automaticReady =
				AutomaticTrackingOperationalAvailability.ready(controlLease);
		AutomaticTrackingOperationalAvailability legacyAutomatic =
				AutomaticTrackingOperationalAvailability.legacyUnavailable();
		AmbientSourceOperationalAvailability ambientReady =
				AmbientSourceOperationalAvailability.ready(
						AmbientTrackingSource.WIFI,
						AmbientAcquisitionMechanism.WIFI_SCAN_RESULTS,
						ambientLease
				);

		SourceCallerRequest.ManualSessionStart manual =
				SourceCallerRequest.ManualSessionStart.create(
						Set.of(TrackingSource.LOCATION),
						manifest,
						Set.of(capture)
				);
		SourceCallerRequest.AutomaticSessionStart automatic =
				SourceCallerRequest.AutomaticSessionStart.create(
						Set.of(TrackingSource.LOCATION),
						Set.of(TrackingSource.ACTIVITY),
						manifest,
						Set.of(capture, control)
				);
		SourceCallerRequest.RecoverySessionStart recovery =
				SourceCallerRequest.RecoverySessionStart.create(
						Set.of(TrackingSource.LOCATION),
						manifest,
						Set.of(capture)
				);
		SourceCallerRequest.Ambient ambientRequest =
				SourceCallerRequest.Ambient.create(
						TrackingSource.WIFI,
						Set.of(ambient),
						true
				);
		SourceCallerRequest.Ambient defaultOffAmbient =
				SourceCallerRequest.Ambient.create(
						TrackingSource.WIFI,
						Set.of(ambient)
				);
		SourceCallerRequest.PurposeOwnerMutation ownerMutation =
				SourceCallerRequest.PurposeOwnerMutation.create(
						TrackingSource.ACTIVITY,
						TrackingPurpose.CONTROL,
						true,
						Set.of(control)
				);

		SourceCallerReplayReference reference =
				new SourceCallerReplayReference("java-replay-reference");
		SourceCallerRequest.PurposeOwnerRetirement ownerRetirement =
				SourceCallerRequest.PurposeOwnerRetirement.create(
						TrackingSource.ACTIVITY,
						TrackingPurpose.CONTROL,
						reference
				);
		SourceCallerAcceptanceReceipt receipt =
				new SourceCallerAcceptanceReceipt(reference, Set.of(capture));
		SourceCallerRequest.Replay replay = SourceCallerRequest.Replay.create(
				SourceCallerReplayKind.PROCESS_RECOVERY,
				receipt.getReference(),
				TrackingPurpose.SESSION_CAPTURE,
				receipt.getPermittedDemandIdentities()
		);
		SourceCallerRequest.Replay legacyForegroundServiceReplay =
				SourceCallerRequest.Replay.create(
						SourceCallerReplayKind.FOREGROUND_SERVICE,
						receipt.getReference(),
						TrackingPurpose.SESSION_CAPTURE,
						receipt.getPermittedDemandIdentities()
				);
		SourceCallerRequest.Replay legacyRestartReplay = SourceCallerRequest.Replay.create(
				SourceCallerReplayKind.RESTART,
				receipt.getReference(),
				TrackingPurpose.SESSION_CAPTURE,
				receipt.getPermittedDemandIdentities()
		);
		SourceCallerRequest.Replay legacyRecoveryReplay = SourceCallerRequest.Replay.create(
				SourceCallerReplayKind.RECOVERY,
				receipt.getReference(),
				TrackingPurpose.SESSION_CAPTURE,
				receipt.getPermittedDemandIdentities()
		);

		assertSame(TrackingSource.LOCATION, captureLease.getSource());
		assertSame(TrackingSource.LOCATION, legacyLease.getSource());
		assertSame(TrackingPurpose.CONTROL, controlLease.getPurpose());
		assertSame(
				controlLease,
				automaticReady.getIdentity()
		);
		assertFalse(legacyAutomatic.isOperational());
		assertSame(ambientLease, ambientReady.getOperationalIdentity());
		assertEquals(Set.of(TrackingSource.LOCATION), manual.getRequestedCapturedSources());
		assertEquals(Set.of(TrackingSource.ACTIVITY), automatic.getDeclaredControlDependencies());
		assertEquals(manifest, recovery.getManifestIdentity());
		assertEquals(Set.of(ambient), ambientRequest.getRequestedDemandIdentities());
		assertFalse(defaultOffAmbient.getEnabled());
		assertTrue(ownerMutation.getEnabled());
		assertEquals("java-replay-reference", reference.getValue());
		assertSame(reference, ownerRetirement.getReference());
		assertSame(reference, replay.getReference());
		assertSame(
				SourceCallerReplayKind.FOREGROUND_SERVICE_DELIVERY,
				SourceCallerReplayKind.valueOf("FOREGROUND_SERVICE").getCanonicalKind()
		);
		assertSame(
				SourceCallerReplayKind.ACTIVE_REDELIVERY,
				SourceCallerReplayKind.valueOf("RESTART").getCanonicalKind()
		);
		assertSame(
				SourceCallerReplayKind.PROCESS_RECOVERY,
				SourceCallerReplayKind.valueOf("RECOVERY").getCanonicalKind()
		);
		assertSame(
				SourceCallerReplayKind.FOREGROUND_SERVICE_DELIVERY,
				legacyForegroundServiceReplay.getReplayKind()
		);
		assertSame(
				SourceCallerReplayKind.ACTIVE_REDELIVERY,
				legacyRestartReplay.getReplayKind()
		);
		assertSame(
				SourceCallerReplayKind.PROCESS_RECOVERY,
				legacyRecoveryReplay.getReplayKind()
		);
		assertEquals(Set.of(capture), receipt.getPermittedDemandIdentities());
	}
}
