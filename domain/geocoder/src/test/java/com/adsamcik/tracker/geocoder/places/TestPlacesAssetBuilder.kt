package com.adsamcik.tracker.geocoder.places

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds a synthetic `places.geo` buffer in Kotlin, mirroring the format produced
 * by `tools/geocoder/build_places.py`, so [PlacesDataset] can be unit-tested
 * without the multi-megabyte bundled asset.
 */
object TestPlacesAssetBuilder {

    private const val CELL_SIZE_E7 = 2_500_000

    data class TestPlace(
        val latE7: Int,
        val lonE7: Int,
        val name: String,
        val country: String,
        val population: Int,
    )

    private fun cellKey(latE7: Int, lonE7: Int): Long {
        val latCell = Math.floorDiv(latE7, CELL_SIZE_E7)
        val lonCell = Math.floorDiv(lonE7, CELL_SIZE_E7)
        return (latCell.toLong() shl 32) or (lonCell.toLong() and 0xFFFFFFFFL)
    }

    fun build(places: List<TestPlace>): ByteArray {
        val sorted = places.sortedBy { cellKey(it.latE7, it.lonE7) }

        // Cell index (cellKey, start, count) in ascending cellKey order.
        data class Cell(val key: Long, val start: Int, val count: Int)
        val cells = ArrayList<Cell>()
        var curKey: Long? = null
        var curStart = 0
        sorted.forEachIndexed { idx, p ->
            val k = cellKey(p.latE7, p.lonE7)
            if (k != curKey) {
                if (curKey != null) cells.add(Cell(curKey!!, curStart, idx - curStart))
                curKey = k
                curStart = idx
            }
        }
        if (curKey != null) cells.add(Cell(curKey!!, curStart, sorted.size - curStart))

        // String blob with de-dup.
        val blob = ByteArrayOutputStream()
        val offsets = HashMap<String, Int>()
        val records = ByteBuffer.allocate(sorted.size * 20).order(ByteOrder.LITTLE_ENDIAN)
        for (p in sorted) {
            val nameBytes = p.name.toByteArray(Charsets.UTF_8)
            val off = offsets.getOrPut(p.name) {
                val o = blob.size()
                blob.write(nameBytes)
                o
            }
            val country = (p.country + "  ").substring(0, 2)
            records.putInt(p.latE7)
            records.putInt(p.lonE7)
            records.putInt(p.population)
            records.put(country.toByteArray(Charsets.US_ASCII))
            records.putInt(off)
            records.putShort(nameBytes.size.toShort())
        }

        val out = ByteArrayOutputStream()
        val header = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        header.put("TGEO".toByteArray(Charsets.US_ASCII))
        header.put(1) // version
        header.put(0); header.put(0); header.put(0) // reserved
        header.putInt(sorted.size)
        header.putInt(CELL_SIZE_E7)
        header.putInt(cells.size)
        out.write(header.array())

        val cellBuf = ByteBuffer.allocate(cells.size * 16).order(ByteOrder.LITTLE_ENDIAN)
        for (c in cells) {
            cellBuf.putLong(c.key)
            cellBuf.putInt(c.start)
            cellBuf.putInt(c.count)
        }
        out.write(cellBuf.array())
        out.write(records.array())
        out.write(blob.toByteArray())
        return out.toByteArray()
    }
}
