package com.adsamcik.tracker.impexp.exporter

import com.adsamcik.tracker.impexp.portable.CellSessionFileV2
import com.adsamcik.tracker.impexp.portable.WifiSessionFileV2
import com.adsamcik.tracker.impexp.portable.radioChildDigest
import com.adsamcik.tracker.shared.base.database.PortableCapturedCellEntryV1
import com.adsamcik.tracker.shared.base.database.PortableCapturedCellObservationV1
import com.adsamcik.tracker.shared.base.database.PortableCapturedCellRunV1
import com.adsamcik.tracker.shared.base.database.PortableCellOpaqueIdentity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellChildEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiChildEntity
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiObservationV1
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiRunV1
import com.adsamcik.tracker.stats.api.repository.PortableWifiOpaqueIdentity
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Conversion/portable child contracts only; imported authority and Room selection are separate. */
class ImportedRadioExportMappingTest {
	@Test fun `wifi indexes each run once and keeps exact child values revisions and input order`() = runTest {
		val runs = wifiRuns()
		val entry = wifiEntry(runs)
		val rows = (0 until 50).flatMap { listOf(wifiRow(0, it), wifiRow(1, it)) }.reversed()
		val result = mapImportedWifiSessionChildren(entry, rows)
		runs.forEach { run -> verify(exactly = 1) { run.observations } }
		assertEquals(rows.map { it.runIdentity to it.itemIndex }, result.map { it.runIdentity to it.itemIndex })
		assertEquals(rows.map { it.ssid }, result.map { it.ssid })
		assertEquals(rows.map { it.contentChecksum }, result.map { it.contentChecksum })
		assertEquals(rows.map { if (it.runIdentity == RUNS[0]) 1L else 2L },
			result.map { it.semanticRevision })
		assertEquals(100, WifiSessionFileV2(entry, result).children.size)
	}

	@Test fun `cell indexes each run once and keeps exact child values revisions and input order`() = runTest {
		val runs = cellRuns()
		val entry = cellEntry(runs)
		val rows = (0 until 50).flatMap { listOf(cellRow(0, it), cellRow(1, it)) }.reversed()
		val result = mapImportedCellSessionChildren(entry, rows)
		runs.forEach { run -> verify(exactly = 1) { run.observations } }
		assertEquals(rows.map { it.runIdentity to it.itemIndex }, result.map { it.runIdentity to it.itemIndex })
		assertEquals(rows.map { it.towerIdentity }, result.map { it.towerIdentity })
		assertEquals(rows.map { it.contentChecksum }, result.map { it.contentChecksum })
		assertEquals(rows.map { if (it.runIdentity == RUNS[0]) 1L else 2L },
			result.map { it.semanticRevision })
		assertEquals(100, CellSessionFileV2(entry, result).children.size)
	}

	@Test fun `children cannot borrow a matching observation from another run or file`() = runTest {
		val wifi = wifiEntry(wifiRuns())
		val cell = cellEntry(cellRuns())
		assertFailsWith<IllegalArgumentException> {
			mapImportedWifiSessionChildren(wifi, listOf(wifiRow(0, 0).copy(runIdentity = RUNS[1])))
		}
		assertFailsWith<IllegalArgumentException> {
			mapImportedCellSessionChildren(cell, listOf(cellRow(0, 0).copy(runIdentity = RUNS[1])))
		}
		assertFailsWith<IllegalStateException> {
			mapImportedWifiSessionChildren(wifi, listOf(wifiRow(0, 0).copy(entryIdentity = "f".repeat(64))))
		}
		assertFailsWith<IllegalStateException> {
			mapImportedCellSessionChildren(cell, listOf(cellRow(0, 0).copy(entryIdentity = "f".repeat(64))))
		}
	}

	@Test fun `a checksum from another semantic revision cannot become a current child`() = runTest {
		assertFailsWith<IllegalArgumentException> {
			mapImportedWifiSessionChildren(wifiEntry(wifiRuns()), listOf(wifiRow(1, 0, checksumRevision = 1)))
		}
		assertFailsWith<IllegalArgumentException> {
			mapImportedCellSessionChildren(cellEntry(cellRuns()), listOf(cellRow(1, 0, checksumRevision = 1)))
		}
	}

	@Test fun `cancelled empty conversion never reads either source entry`() = runTest {
		val wifi = mockk<PortableCapturedWifiEntryV1>()
		val cell = mockk<PortableCapturedCellEntryV1>()
		val wifiAttempt = async {
			currentCoroutineContext().cancel()
			mapImportedWifiSessionChildren(wifi, emptyList())
		}
		val cellAttempt = async {
			currentCoroutineContext().cancel()
			mapImportedCellSessionChildren(cell, emptyList())
		}
		assertFailsWith<CancellationException> { wifiAttempt.await() }
		assertFailsWith<CancellationException> { cellAttempt.await() }
		verify(exactly = 0) { wifi.runs }
		verify(exactly = 0) { cell.runs }
	}

	@Test fun `cancellation while obtaining a row stops before later cell conversions`() = runTest {
		val readIndices = mutableListOf<Int>()
		val entry = cellEntry(cellRuns())
		val attempt = async {
			val job = requireNotNull(currentCoroutineContext()[Job])
			val rows = object : AbstractList<ImportedCellChildEntity>() {
				override val size = 2
				override fun get(index: Int): ImportedCellChildEntity {
					readIndices += index
					job.cancel()
					return cellRow(index, 0)
				}
			}
			mapImportedCellSessionChildren(entry, rows)
		}
		assertFailsWith<CancellationException> { attempt.await() }
		assertEquals(listOf(0), readIndices)
	}

	private fun wifiRuns(): List<PortableCapturedWifiRunV1> = (0..1).map { index ->
		val observation = mockk<PortableCapturedWifiObservationV1> {
			every { identity } returns PortableWifiOpaqueIdentity(OBSERVATIONS[index])
			every { semanticRevision } returns index + 1L
			every { coverageStartTimeMs } returns WALL_TIME
			every { latestPossibleTimeMs } returns WALL_TIME
			every { observationCount } returns 50
		}
		mockk {
			every { identity } returns PortableWifiOpaqueIdentity(RUNS[index])
			every { observations } returns listOf(observation)
		}
	}

	private fun cellRuns(): List<PortableCapturedCellRunV1> = (0..1).map { index ->
		val observation = mockk<PortableCapturedCellObservationV1> {
			every { identity } returns PortableCellOpaqueIdentity(OBSERVATIONS[index])
			every { semanticRevision } returns index + 1L
			every { coverageStartTimeMs } returns WALL_TIME
			every { latestPossibleTimeMs } returns WALL_TIME
			every { acceptedChildCount } returns 50
		}
		mockk {
			every { identity } returns PortableCellOpaqueIdentity(RUNS[index])
			every { observations } returns listOf(observation)
		}
	}

	private fun wifiEntry(selectedRuns: List<PortableCapturedWifiRunV1>): PortableCapturedWifiEntryV1 = mockk {
		every { identity } returns PortableWifiOpaqueIdentity(ENTRY)
		every { runs } returns selectedRuns
	}

	private fun cellEntry(selectedRuns: List<PortableCapturedCellRunV1>): PortableCapturedCellEntryV1 = mockk {
		every { identity } returns PortableCellOpaqueIdentity(ENTRY)
		every { runs } returns selectedRuns
	}

	private fun wifiRow(run: Int, index: Int, checksumRevision: Long = run + 1L): ImportedWifiChildEntity {
		val ssid = "network-$run-$index"
		val digest = radioChildDigest("wifi", listOf(ENTRY, RUNS[run], OBSERVATIONS[run]),
			listOf(checksumRevision, index, PROVIDER_TIME, WALL_TIME, 0L, "00:11:22:33:44:55",
				ssid, 2_412, -40))
		return ImportedWifiChildEntity(ENTRY, 1, RUNS[run], OBSERVATIONS[run], index,
			PROVIDER_TIME, WALL_TIME, 0, "00:11:22:33:44:55", ssid, 2_412, -40, digest)
	}

	private fun cellRow(run: Int, index: Int, checksumRevision: Long = run + 1L): ImportedCellChildEntity {
		val tower = "tower-$run-$index"
		val digest = radioChildDigest("cell", listOf(ENTRY, RUNS[run], OBSERVATIONS[run]),
			listOf(checksumRevision, index, PROVIDER_TIME, WALL_TIME, 0L, tower, "LTE", true, -40))
		return ImportedCellChildEntity(ENTRY, 1, RUNS[run], OBSERVATIONS[run], index,
			PROVIDER_TIME, WALL_TIME, 0, tower, "LTE", true, -40, digest)
	}

	private companion object {
		val ENTRY = "a".repeat(64)
		val RUNS = listOf("b".repeat(64), "c".repeat(64))
		val OBSERVATIONS = listOf("d".repeat(64), "e".repeat(64))
		const val WALL_TIME = 1_000L
		const val PROVIDER_TIME = 100L
	}
}
