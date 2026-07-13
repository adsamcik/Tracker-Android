package com.adsamcik.tracker.tracker.component.producer

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CellSnapshotGateTest {

    private fun reading(
        cellId: Long = 1L,
        areaCode: Int = 100,
        signalStrengthAsu: Int = 20,
    ) = CellFingerprintReading(
        cellId = cellId,
        areaCode = areaCode,
        mcc = "230",
        mnc = "01",
        networkType = 4,
        signalStrengthAsu = signalStrengthAsu,
    )

    @Test
    fun `fingerprint ignores ordering and small signal noise`() {
        val first = listOf(reading(cellId = 1L), reading(cellId = 2L, signalStrengthAsu = 31))
        val reordered = listOf(first[1].copy(signalStrengthAsu = 32), first[0].copy(signalStrengthAsu = 21))

        CellSnapshotGate.fingerprint(first) shouldBe CellSnapshotGate.fingerprint(reordered)
    }

    @Test
    fun `area code change creates a new fingerprint`() {
        val original = CellSnapshotGate.fingerprint(listOf(reading(areaCode = 100)))
        val changed = CellSnapshotGate.fingerprint(listOf(reading(areaCode = 101)))

        (original != changed) shouldBe true
    }

    @Test
    fun `unchanged snapshot waits for heartbeat`() {
        CellSnapshotGate.shouldRecordSnapshot("new", "old", 10, 9, 100) shouldBe true
        CellSnapshotGate.shouldRecordSnapshot("same", "same", 50, 10, 100) shouldBe false
        CellSnapshotGate.shouldRecordSnapshot("same", "same", 110, 10, 100) shouldBe true
    }
}
