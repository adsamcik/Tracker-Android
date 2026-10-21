package com.adsamcik.tracker.tracker.source.ingress

import com.adsamcik.tracker.shared.base.database.dao.SourceBrokerDao
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.mockk.coEvery
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ContinuousAmbientSensorAuthorityTest {
	@Test
	fun `Pressure window and Activity point keep their all-day member across session retirement`() = runTest {
		for (source in listOf(SourceKind.PRESSURE, SourceKind.ACTIVITY)) {
			val background = demand(source, "background", SourceBrokerPurpose.AMBIENT_PRODUCT)
			val capture = demand(source, "capture", SourceBrokerPurpose.SESSION_CAPTURE)
			val original = rows(source, 1L, 10L, listOf(background, capture))
			val next = rows(source, 2L, 100L, listOf(background))
			val dao = timeline(original, next)
			val members = dao.continuousAmbientSensorMembers(requireNotNull(original.toAuthorizationSnapshotOrNull()),
				if (source == SourceKind.PRESSURE) { 90L } else { 110L }, 110L)
			assertEquals(listOf("background"), members.map { it.demandId })
			assertEquals(1L, members.single().authorizationRevision)
		}
	}

	@Test
	fun `quality replacement retains the original Pressure window under unchanged all-day consent`() = runTest {
		val source = SourceKind.PRESSURE
		val originalDemand = demand(source, "background", SourceBrokerPurpose.AMBIENT_PRODUCT)
		val replacement = originalDemand.copy(demandId = "new-quality", sourcePolicyRevision = 2L,
			maximumAgeMs = 30_000L, desiredLatencyMs = 10_000L)
		val original = rows(source, 1L, 10L, listOf(originalDemand))
		val next = rows(source, 2L, 100L, listOf(replacement))
		val members = timeline(original, next).continuousAmbientSensorMembers(
			requireNotNull(original.toAuthorizationSnapshotOrNull()), 90L, 110L)
		assertEquals(original.single().memberId, members.single().memberId)
		assertEquals("background", members.single().demandId)
		assertEquals(1L, members.single().authorizationRevision)
	}

	@Test
	fun `a new consent epoch cannot qualify the earlier part of a Pressure window`() = runTest {
		val source = SourceKind.PRESSURE
		val originalDemand = demand(source, "background", SourceBrokerPurpose.AMBIENT_PRODUCT)
		val replacement = originalDemand.copy(demandId = "new-consent", sourcePolicyRevision = 2L, consentEpoch = 2L)
		val original = rows(source, 1L, 10L, listOf(originalDemand))
		val next = rows(source, 2L, 100L, listOf(replacement))
		assertTrue(timeline(original, next).continuousAmbientSensorMembers(
			requireNotNull(original.toAuthorizationSnapshotOrNull()), 90L, 110L).isEmpty())
	}

	@Test
	fun `an interrupted all-day grant cannot be borrowed from equal-looking endpoints`() = runTest {
		val source = SourceKind.PRESSURE
		val background = demand(source, "background", SourceBrokerPurpose.AMBIENT_PRODUCT)
		val control = demand(source, "control", SourceBrokerPurpose.CONTROL_AUTOSTART)
		val original = rows(source, 1L, 10L, listOf(background, control))
		val revoked = rows(source, 2L, 100L, listOf(control))
		val renewed = rows(source, 3L, 105L, listOf(background, control))
		assertTrue(timeline(original, revoked, renewed).continuousAmbientSensorMembers(
			requireNotNull(original.toAuthorizationSnapshotOrNull()), 90L, 110L).isEmpty())
	}

	@Test
	fun `control-only or newly added all-day authority cannot authorize an earlier window`() = runTest {
		val source = SourceKind.PRESSURE
		val control = demand(source, "control", SourceBrokerPurpose.CONTROL_AUTOSTART)
		val original = rows(source, 1L, 10L, listOf(control))
		val withBackground = rows(source, 2L, 100L,
			listOf(control, demand(source, "background", SourceBrokerPurpose.AMBIENT_PRODUCT)))
		val dao = timeline(original, withBackground)
		assertTrue(dao.continuousAmbientSensorMembers(requireNotNull(original.toAuthorizationSnapshotOrNull()),
			90L, 110L).isEmpty())
		assertTrue(dao.continuousAmbientSensorMembers(requireNotNull(withBackground.toAuthorizationSnapshotOrNull()),
			90L, 110L).isEmpty())
	}

	private fun timeline(vararg rows: List<SourceAuthorizationEntity>): SourceBrokerDao {
		val dao = mockk<SourceBrokerDao>()
		coEvery { dao.authorizationAtBounded(any(), any(), any(), any(), any()) } coAnswers {
			val observed = arg<Long>(3)
			rows.lastOrNull { it.first().effectiveElapsedRealtimeNanos <= observed }.orEmpty()
		}
		coEvery { dao.nextAuthorizationRevisionBounded(any(), any(), any(), any()) } coAnswers {
			val revision = arg<Long>(2)
			rows.firstOrNull { it.first().authorizationRevision > revision }.orEmpty()
		}
		return dao
	}

	private fun rows(source: SourceKind, revision: Long, effective: Long, demands: List<SourceDemandEntity>) =
		SourceBrokerAuthorization.rows(source.stableCode, 1L, revision, demands, "boot", effective, effective)

	private fun demand(source: SourceKind, id: String, purpose: String) = SourceDemandEntity(
		demandId = id, consumerId = id, sourceKind = source.stableCode, purpose = purpose,
		logicalTrackingId = "session".takeIf { purpose == SourceBrokerPurpose.SESSION_CAPTURE },
		serviceRunId = "run".takeIf { purpose == SourceBrokerPurpose.SESSION_CAPTURE },
		manifestRevision = 1L.takeIf { purpose == SourceBrokerPurpose.SESSION_CAPTURE },
		lifecycleLeaseGeneration = 1L.takeIf { purpose == SourceBrokerPurpose.SESSION_CAPTURE },
		sourcePolicyRevision = 1L, consentEpoch = 1L, persistenceEligible = purpose != SourceBrokerPurpose.CONTROL_AUTOSTART,
		qosCode = 2, maximumAgeMs = 60_000L, desiredLatencyMs = 30_000L,
		requestedBootId = "boot", requestedElapsedRealtimeNanos = 1L, requestedAtMs = 1L,
		status = SourceDemandEntity.STATUS_ACTIVE, retireBootId = null, retireElapsedRealtimeNanos = null, retiredAtMs = null,
	)
}
