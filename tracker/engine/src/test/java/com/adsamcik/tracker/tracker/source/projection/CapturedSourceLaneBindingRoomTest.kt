package com.adsamcik.tracker.tracker.source.projection

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.tracker.source.activity.ActivityCapturedFactDrainResult
import com.adsamcik.tracker.tracker.source.activity.ActivityCapturedFactProjectionLane
import com.adsamcik.tracker.tracker.source.activity.ActivityCapturedFactWriter
import com.adsamcik.tracker.tracker.source.activity.ActivityCapturedWalAdmissionAdapter
import com.adsamcik.tracker.tracker.source.cell.CellCapturedFactWriter
import com.adsamcik.tracker.tracker.source.cell.CellSessionFactDrainResult
import com.adsamcik.tracker.tracker.source.cell.CellSessionFactProjectionLane
import com.adsamcik.tracker.tracker.source.cell.CellWalQualificationAdapter
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneBinding
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneCatalog
import com.adsamcik.tracker.tracker.source.coordinator.SourceWriterTransitionSpec
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceIngress
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactWriter
import com.adsamcik.tracker.tracker.source.wifi.WifiSessionFactDrainResult
import com.adsamcik.tracker.tracker.source.wifi.WifiSessionFactProjectionLane
import com.adsamcik.tracker.tracker.source.wifi.WifiWalQualificationAdapter
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual lane/owner Room resolution; empty finite WAL deliberately does not prove writes/providers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CapturedSourceLaneBindingRoomTest {
    @Test fun `four source lanes recognize original and final masks under their exact canonical owner`() = runTest {
        for (spec in specs()) for (generation in listOf(1L, 2L)) {
            withDatabase { database ->
                val binding = requireNotNull(ExecutableSourceLaneCatalog().bindingFor(spec.source, generation,
                    spec.binding.projectionId, spec.binding.projectionVersion))
                seed(database, spec, binding, generation * 2L)
                resolvesEmpty(database, spec.source) shouldBe true
                database.sourceProjectionStateDao().activeProductLane(spec.source.stableCode)?.contiguousAdmissionOrdinal shouldBe 0L
            }
        }
    }

    @Test fun `final lanes cannot execute with the original owner or a contained final owner`() = runTest {
        for (spec in specs()) for (wrongOwner in listOf(2L, 5L)) {
            withDatabase { database ->
                val binding = requireNotNull(ExecutableSourceLaneCatalog().bindingFor(spec.source, 2L,
                    spec.binding.projectionId, spec.binding.projectionVersion))
                seed(database, spec, binding, wrongOwner)
                resolvesEmpty(database, spec.source) shouldBe false
                database.sourceProjectionStateDao().activeProductLane(spec.source.stableCode)?.contiguousAdmissionOrdinal shouldBe 0L
            }
        }
    }

    @Test fun `unknown binding and wrong final mask stay nonexecutable without advancing the frontier`() = runTest {
        for (spec in specs()) for ((generation, mask) in listOf(3L to 3L, 2L to 1L)) {
            withDatabase { database ->
                seed(database, spec, spec.binding.copy(bindingGeneration = generation), generation * 2L, mask)
                resolvesEmpty(database, spec.source) shouldBe false
                database.sourceProjectionStateDao().activeProductLane(spec.source.stableCode)?.contiguousAdmissionOrdinal shouldBe 0L
            }
        }
    }

    private fun specs() = listOf(SourceWriterTransitionSpec.ACTIVITY, SourceWriterTransitionSpec.PRESSURE,
        SourceWriterTransitionSpec.WIFI, SourceWriterTransitionSpec.CELL)

    private suspend fun withDatabase(block: suspend (AppDatabase) -> Unit) {
        val database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
        try { database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 0L)); block(database) }
        finally { database.close() }
    }

    private suspend fun seed(database: AppDatabase, spec: SourceWriterTransitionSpec, binding: ExecutableSourceLaneBinding,
        owner: Long, mask: Long = binding.captureModeMask) {
        database.sourceDestinationOwnerDao().insertIfAbsent(SourceDestinationOwnerEntity(spec.source.stableCode,
            spec.destination, spec.candidateOwner, owner, 1L))
        database.sourceProjectionStateDao().installProductLane(SourceProductProjectionLaneEntity(spec.source.stableCode,
            binding.bindingGeneration, binding.projectionId, binding.projectionVersion, mask,
            SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL, 1L, 1L, 0L,
            retentionRequired = true, status = SourceProductProjectionLaneEntity.STATUS_ACTIVE, installedAtMs = 1L, updatedAtMs = 1L))
    }

    private suspend fun resolvesEmpty(database: AppDatabase, source: SourceKind): Boolean = when (source) {
        SourceKind.ACTIVITY -> {
            val adapter = mockk<ActivityCapturedWalAdmissionAdapter>()
            val writer = mockk<ActivityCapturedFactWriter>()
            val result = ActivityCapturedFactProjectionLane(database, adapter, writer).drainAvailable()
            coVerify(exactly = 0) { adapter.admit(any()) }
            coVerify(exactly = 0) { writer.write(any()) }
            result == ActivityCapturedFactDrainResult.Complete(0L, 0, 0)
        }
        SourceKind.PRESSURE -> {
            val ingress = mockk<DurableSourceIngress>()
            val result = PressureSessionFactProjectionLane(database, ingress).drainAvailable()
            coVerify(exactly = 0) { ingress.committedSourceBatch(any(), any(), any(), any()) }
            result == PressureSessionFactDrainResult.Complete(0L, 0, 0)
        }
        SourceKind.WIFI -> {
            val adapter = mockk<WifiWalQualificationAdapter>()
            val writer = mockk<WifiCapturedFactWriter>()
            val result = WifiSessionFactProjectionLane(database, adapter, writer).drainAvailable()
            coVerify(exactly = 0) { adapter.qualify(any()) }
            coVerify(exactly = 0) { writer.write(any()) }
            result == WifiSessionFactDrainResult.Complete(0L, 0, 0)
        }
        SourceKind.CELL -> {
            val adapter = mockk<CellWalQualificationAdapter>()
            val writer = mockk<CellCapturedFactWriter>()
            val result = CellSessionFactProjectionLane(database, adapter, writer).drainAvailable()
            coVerify(exactly = 0) { adapter.qualify(any()) }
            coVerify(exactly = 0) { writer.write(any()) }
            result == CellSessionFactDrainResult.Complete(0L, 0, 0)
        }
        else -> error("This slice does not change Steps or Location lanes")
    }
}
