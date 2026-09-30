package com.adsamcik.tracker.shared.base.database.data

import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test

class RadioPurposeItemQualifierTest {
	@Test
	fun `v6 qualifies available radio identity while v5 keeps the historical empty-only rule`() {
		for (source in SOURCES) {
			val ambient = demand(source, "ambient", SourceBrokerPurpose.AMBIENT_PRODUCT, 600_000L)
			val authority = authority(source, listOf(ambient), listOf(
				snapshot(source, 1L, 0L, listOf(ambient)),
			))
			val v6 = if (source == SourceDestinationOwnerEntity.SOURCE_WIFI) {
				val payload = WifiWalPayload(listOf(WifiWalPayloadObservation(
					"aa:bb:cc:dd:ee:ff", 2_412, -60, nanos(570_000L), "Home Wi-Fi",
				)), null, null, "UTC")
				assertEquals(emptyList(), assertIs<RadioPurposeItemQualification.Qualified>(
					RadioPurposeItemQualifier.wifi(payload, authority, LEGACY_WIFI_WAL_PAYLOAD_VERSION),
				).unionIndexes)
				RadioPurposeItemQualifier.wifi(payload, authority, WIFI_WAL_PAYLOAD_VERSION)
			} else {
				val payload = CellWalPayload(null, listOf(CellWalPayloadObservation(
					"310:260:123:456", "LTE", true, -90, nanos(570_000L),
				)), 0, "UTC")
				assertEquals(emptyList(), assertIs<RadioPurposeItemQualification.Qualified>(
					RadioPurposeItemQualifier.cell(payload, authority, LEGACY_CELL_WAL_PAYLOAD_VERSION),
				).unionIndexes)
				RadioPurposeItemQualifier.cell(payload, authority, CELL_WAL_PAYLOAD_VERSION)
			}
			assertEquals(listOf(0), assertIs<RadioPurposeItemQualification.Qualified>(v6).unionIndexes)
		}
	}

	@Test
	fun `two minute item remains background eligible while one minute session gets only fresh item`() {
		for (source in SOURCES) {
			val ambient = demand(source, "ambient", SourceBrokerPurpose.AMBIENT_PRODUCT, 600_000L)
			val capture = demand(source, "capture", SourceBrokerPurpose.SESSION_CAPTURE, 60_000L, 540_000L)
			val result = qualify(source, listOf(480_000L, 570_000L), authority(source, listOf(ambient, capture),
				listOf(snapshot(source, 1L, 0L, listOf(ambient)),
					snapshot(source, 2L, 540_000L, listOf(ambient, capture)))))

			assertEquals(listOf(0, 1), result.acceptedIndexesByDemandId[ambient.demandId])
			assertEquals(listOf(1), result.acceptedIndexesByDemandId[capture.demandId])
			assertEquals(listOf(0, 1), result.unionIndexes)
		}
	}

	@Test
	fun `joining or leaving session cannot change continuing background subset`() {
		for (source in SOURCES) {
			val ambient = demand(source, "ambient", SourceBrokerPurpose.AMBIENT_PRODUCT, 600_000L)
			val capture = demand(source, "capture", SourceBrokerPurpose.SESSION_CAPTURE, 60_000L, 540_000L)
			val first = snapshot(source, 1L, 0L, listOf(ambient))
			val joined = snapshot(source, 2L, 540_000L, listOf(ambient, capture))
			val left = snapshot(source, 3L, 580_000L, listOf(ambient))
			val times = listOf(480_000L, 570_000L)
			val before = qualify(source, times, authority(source, listOf(ambient), listOf(first)))
			val during = qualify(source, times, authority(source, listOf(ambient, capture), listOf(first, joined)))
			val after = qualify(source, times, authority(source, listOf(ambient, capture), listOf(first, joined, left)))

			assertEquals(before.acceptedIndexesByDemandId[ambient.demandId], during.acceptedIndexesByDemandId[ambient.demandId])
			assertEquals(before.acceptedIndexesByDemandId[ambient.demandId], after.acceptedIndexesByDemandId[ambient.demandId])
			assertEquals(listOf(1), after.acceptedIndexesByDemandId[capture.demandId])
		}
	}

	@Test
	fun `replacement demand with same purpose and consent cannot borrow earlier demand observations`() {
		for (source in SOURCES) {
			val old = demand(source, "old", SourceBrokerPurpose.AMBIENT_PRODUCT, 600_000L)
			val replacement = old.copy(demandId = "new", requestedElapsedRealtimeNanos = nanos(550_000L))
			val result = qualify(source, listOf(480_000L, 570_000L), authority(source, listOf(old, replacement),
				listOf(snapshot(source, 1L, 0L, listOf(old)), snapshot(source, 2L, 550_000L, listOf(replacement)))))

			assertEquals(mapOf(old.demandId to listOf(0), replacement.demandId to listOf(1)),
				result.acceptedIndexesByDemandId)
		}
	}

	@Test
	fun `same demand disappearing at an intermediate revision keeps only items from active spans`() {
		for (source in SOURCES) {
			val ambient = demand(source, "ambient", SourceBrokerPurpose.AMBIENT_PRODUCT, 600_000L)
			val result = qualify(source, listOf(480_000L, 530_000L, 570_000L), authority(source, listOf(ambient), listOf(
				snapshot(source, 1L, 0L, listOf(ambient)), snapshot(source, 2L, 500_000L, emptyList()),
				snapshot(source, 3L, 550_000L, listOf(ambient)))))

			assertEquals(mapOf(ambient.demandId to listOf(0, 2)), result.acceptedIndexesByDemandId)
		}
	}

	@Test
	fun `latest authorization wins at equal elapsed boundaries while keeping earlier authorized items`() {
		for (source in SOURCES) {
			val ambient = demand(source, "ambient", SourceBrokerPurpose.AMBIENT_PRODUCT, 600_000L)
			val result = qualify(source, listOf(549_999L, 550_000L), authority(source, listOf(ambient), listOf(
				snapshot(source, 1L, 0L, listOf(ambient)), snapshot(source, 2L, 550_000L, emptyList()),
				snapshot(source, 3L, 550_000L, listOf(ambient)))))

			assertEquals(listOf(0, 1), result.unionIndexes)
		}
	}

	@Test
	fun `callback deny preserves earlier authorized item but missing historical authority yields no item`() {
		for (source in SOURCES) {
			val ambient = demand(source, "ambient", SourceBrokerPurpose.AMBIENT_PRODUCT, 600_000L)
			val denied = qualify(source, listOf(480_000L), authority(source, listOf(ambient), listOf(
				snapshot(source, 1L, 0L, listOf(ambient)), snapshot(source, 2L, 550_000L, emptyList()))))
			val beforeConsent = qualify(source, listOf(480_000L), authority(source, listOf(ambient), listOf(
				snapshot(source, 2L, 550_000L, listOf(ambient)))))

			assertEquals(listOf(0), denied.unionIndexes)
			assertEquals(mapOf(ambient.demandId to listOf(0)), denied.acceptedIndexesByDemandId)
			assertEquals(emptyList(), beforeConsent.unionIndexes)
		}
	}

	@Test
	fun `demand activation cannot be lent backward by an earlier stamped authorization`() {
		for (source in SOURCES) {
			val ambient = demand(source, "ambient", SourceBrokerPurpose.AMBIENT_PRODUCT, 600_000L, 550_000L)
			val result = qualify(source, listOf(480_000L, 570_000L), authority(source, listOf(ambient), listOf(
				snapshot(source, 1L, 0L, listOf(ambient)))))

			assertEquals(listOf(1), result.unionIndexes)
		}
	}

	@Test
	fun `changing immutable demand constraints invalidates exact reconstructed authorization`() {
		for (source in SOURCES) {
			val original = demand(source, "ambient", SourceBrokerPurpose.AMBIENT_PRODUCT, 600_000L)
			for (changed in listOf(original.copy(maximumAgeMs = 60_000L),
				original.copy(minimumAcquisitionSpec = "different-floor"),
				original.copy(consentEpoch = 4L), original.copy(liveAmbientRetentionPolicyId = "retention",
					liveAmbientRetentionApprovalRevision = 2L))) {
				val result = qualifyRaw(source, listOf(570_000L), authority(source, listOf(changed), listOf(
					snapshot(source, 1L, 0L, listOf(original)))))
				assertEquals(RadioPurposeItemFailure.AUTHORIZATION_UNVERIFIABLE,
					assertIs<RadioPurposeItemQualification.Rejected>(result).reason)
			}
		}
	}

	@Test
	fun `boot and physical generation cannot be substituted`() {
		for (source in SOURCES) {
			val ambient = demand(source, "ambient", SourceBrokerPurpose.AMBIENT_PRODUCT, 600_000L)
			val original = authority(source, listOf(ambient), listOf(snapshot(source, 1L, 0L, listOf(ambient))))
			for (changed in listOf(original.copy(clockDomainId = "other-boot"), original.copy(registrationGeneration = 2L))) {
				assertEquals(RadioPurposeItemFailure.AUTHORIZATION_UNVERIFIABLE,
					assertIs<RadioPurposeItemQualification.Rejected>(qualifyRaw(source, listOf(570_000L), changed)).reason)
			}
		}
	}

	@Test
	fun `provider lifetime age and retirement boundaries are inclusive at start and exclusive at end`() {
		for (source in SOURCES) {
			val ambient = demand(source, "ambient", SourceBrokerPurpose.AMBIENT_PRODUCT, 60_000L)
			val original = authority(source, listOf(ambient), listOf(snapshot(source, 1L, 0L, listOf(ambient))))
			assertEquals(listOf(1, 2), qualify(source, listOf(539_999L, 540_000L, 570_000L), original).unionIndexes)
			assertEquals(listOf(0), qualify(source, listOf(570_000L, 580_000L), original.copy(
				providerStartInclusiveNanos = nanos(570_000L), providerEndExclusiveNanos = nanos(580_000L))).unionIndexes)
			val retired = ambient.copy(retireBootId = BOOT, retireElapsedRealtimeNanos = nanos(580_000L))
			assertEquals(listOf(0), qualify(source, listOf(570_000L, 580_000L), original.copy(immutableDemands = listOf(retired))).unionIndexes)
		}
	}

	@Test
	fun `future missing and malformed items cannot reach any destination`() {
		for (source in SOURCES) {
			val ambient = demand(source, "ambient", SourceBrokerPurpose.AMBIENT_PRODUCT, Long.MAX_VALUE)
			val result = qualify(source, listOf(600_001L, null, 0L, 570_000L),
				authority(source, listOf(ambient), listOf(snapshot(source, 1L, 0L, listOf(ambient)))), invalidIndexes = setOf(3))
			assertEquals(emptyList(), result.unionIndexes)
		}
	}

	@Test
	fun `authorization order overflow duplicates and incomplete demand reconstruction fail closed`() {
		for (source in SOURCES) {
			val ambient = demand(source, "ambient", SourceBrokerPurpose.AMBIENT_PRODUCT, 600_000L)
			val first = snapshot(source, 1L, 0L, listOf(ambient))
			val second = snapshot(source, 2L, 540_000L, listOf(ambient))
			val original = authority(source, listOf(ambient), listOf(first, second))
			for (changed in listOf(original.copy(authorizationTimeline = listOf(second, first)),
				original.copy(immutableDemands = emptyList()), original.copy(immutableDemands = listOf(ambient, ambient)),
				original.copy(authorizationTimeline = emptyList()))) {
				assertEquals(RadioPurposeItemFailure.AUTHORIZATION_UNVERIFIABLE,
					assertIs<RadioPurposeItemQualification.Rejected>(qualifyRaw(source, listOf(570_000L), changed)).reason)
			}
			assertEquals(RadioPurposeItemFailure.DEPENDENCY_OVERFLOW,
				assertIs<RadioPurposeItemQualification.Rejected>(qualifyRaw(source, listOf(570_000L),
					original.copy(authorizationTimeline = List(257) { first }))).reason)
		}
	}

	@Test
	fun `qualification reuses original received time even when later receipt would make item stale`() {
		for (source in SOURCES) {
			val ambient = demand(source, "ambient", SourceBrokerPurpose.AMBIENT_PRODUCT, 60_000L)
			val original = authority(source, listOf(ambient), listOf(snapshot(source, 1L, 0L, listOf(ambient))))
			val originalResult = qualify(source, listOf(570_000L), original)
			assertEquals(originalResult, qualify(source, listOf(570_000L), original.copy()))
			assertEquals(emptyList(), qualify(source, listOf(570_000L), original.copy(receivedElapsedNanos = nanos(660_000L))).unionIndexes)
		}
	}

	private fun qualify(
		source: Int, timesMs: List<Long?>, authority: RadioPurposeItemAuthority, invalidIndexes: Set<Int> = emptySet(),
	) = assertIs<RadioPurposeItemQualification.Qualified>(qualifyRaw(source, timesMs, authority, invalidIndexes))

	private fun qualifyRaw(
		source: Int, timesMs: List<Long?>, authority: RadioPurposeItemAuthority, invalidIndexes: Set<Int> = emptySet(),
	): RadioPurposeItemQualification = if (source == SourceDestinationOwnerEntity.SOURCE_WIFI) {
		RadioPurposeItemQualifier.wifi(WifiWalPayload(timesMs.mapIndexed { index, time ->
			WifiWalPayloadObservation("", if (index in invalidIndexes) 1 else 2_412, -60, time?.let(::nanos))
		}, null, null, "UTC"), authority)
	} else {
		RadioPurposeItemQualifier.cell(CellWalPayload(null, timesMs.mapIndexed { index, time ->
			CellWalPayloadObservation("", if (index in invalidIndexes) "UNKNOWN" else "LTE", true, -90, time?.let(::nanos))
		}, 0, "UTC"), authority)
	}

	private fun authority(source: Int, demands: List<SourceDemandEntity>, timeline: List<SourceAuthorizationSnapshot>) =
		RadioPurposeItemAuthority(source, 1L, BOOT, 0L, Long.MAX_VALUE, nanos(600_000L), timeline, demands)

	private fun snapshot(source: Int, revision: Long, effectiveMs: Long, demands: List<SourceDemandEntity>) =
		requireNotNull(SourceBrokerAuthorization.rows(source, 1L, revision, demands, BOOT, nanos(effectiveMs), effectiveMs)
			.toAuthorizationSnapshotOrNull())

	private fun demand(source: Int, id: String, purpose: String, maximumAgeMs: Long, requestedMs: Long = 0L) =
		SourceDemandEntity(
			demandId = id, consumerId = "consumer-$id", sourceKind = source, purpose = purpose,
			logicalTrackingId = "logical".takeIf { purpose == SourceBrokerPurpose.SESSION_CAPTURE },
			serviceRunId = "run".takeIf { purpose == SourceBrokerPurpose.SESSION_CAPTURE },
			manifestRevision = 1L.takeIf { purpose == SourceBrokerPurpose.SESSION_CAPTURE },
			lifecycleLeaseGeneration = 1L.takeIf { purpose == SourceBrokerPurpose.SESSION_CAPTURE },
			sourcePolicyRevision = 1L, consentEpoch = 3L, persistenceEligible = true, qosCode = 0,
			minimumAcquisitionSpec = "test-floor", adaptiveReductionAllowed = true, maximumAgeMs = maximumAgeMs,
			desiredLatencyMs = Long.MAX_VALUE, requestedDeliveryLatencyMs = null, requestedBootId = BOOT,
			requestedElapsedRealtimeNanos = nanos(requestedMs), requestedAtMs = requestedMs,
			status = SourceDemandEntity.STATUS_ACTIVE, retireBootId = null, retireElapsedRealtimeNanos = null, retiredAtMs = null,
		)

	private fun nanos(milliseconds: Long): Long = milliseconds * 1_000_000L

	private companion object {
		const val BOOT = "boot"
		val SOURCES = listOf(SourceDestinationOwnerEntity.SOURCE_WIFI, SourceDestinationOwnerEntity.SOURCE_CELL)
	}
}
