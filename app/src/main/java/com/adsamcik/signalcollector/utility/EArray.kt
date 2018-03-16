package com.adsamcik.signalcollector.utility

object EArray {

    fun avgEvery(array: ShortArray, batchSize: Int): ShortArray {
        val left = array.size % batchSize
        val avg = ShortArray(array.size / batchSize + if (left > 0) 1 else 0)
        var i = 0
        while (i < avg.size) {
            avg[i] = avg(array, i * batchSize, batchSize)
            i++
        }

        return avg
    }

    fun avg(array: ShortArray, startIndex: Int, batchSize: Int): Short {
        var bSize = batchSize
        if (startIndex >= array.size)
            throw IllegalArgumentException("Start index must be smaller than array length")

        //first index that shouldn't be used
        val endIndex: Int
        if (startIndex + bSize >= array.size) {
            endIndex = array.size
            bSize = array.size - startIndex
        } else
            endIndex = startIndex + bSize

        val average = array[startIndex].toInt() + (startIndex + 1 until endIndex).sumBy { array[it].toInt() }

        return (average / bSize).toShort()
    }

    fun sum(array: ShortArray): Int = array.sumBy { it.toInt() }

    fun sumAbs(array: ShortArray): Int = array.sumBy { Math.abs(it.toInt()) }

    fun avg(array: ShortArray): Short = (sum(array) / array.size).toShort()

    fun avgAbs(array: ShortArray): Short = (sumAbs(array) / array.size).toShort()
}
