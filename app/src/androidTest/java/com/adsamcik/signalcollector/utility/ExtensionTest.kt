package com.adsamcik.signalcollector.utility


import android.support.test.runner.AndroidJUnit4
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExtensionTest {
    @Test
    @Throws(Exception::class)
    fun average() {
        val arr = shortArrayOf(3, 5, 9, 13)
        Assert.assertEquals(7, EArray.avg(arr).toLong())

        val result = EArray.avgEvery(arr, 2)
        val target = shortArrayOf(4, 11)
        Assert.assertEquals(target.size.toLong(), result.size.toLong())
        for (i in target.indices) {
            Assert.assertEquals(target[i].toLong(), result[i].toLong())
        }


        val arr2 = shortArrayOf(0, 1, 3, 4)
        Assert.assertEquals(2, EArray.avg(arr2).toLong())
    }
}