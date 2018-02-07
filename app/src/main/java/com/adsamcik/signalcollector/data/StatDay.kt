package com.adsamcik.signalcollector.data

import com.vimeo.stag.UseStag

@UseStag
class StatDay {
    var age: Int = 0
    var wifi: Int = 0
    var cell: Int = 0
    var locations: Int = 0
    var minutes: Int = 0
    var uploaded: Long = 0

    constructor(minutes: Int, locations: Int, wifi: Int, cell: Int, age: Int, upload: Long) {
        this.minutes = minutes
        this.wifi = wifi
        this.cell = cell
        this.locations = locations
        this.age = age
        this.uploaded = upload
    }

    fun add(cellCount: Int, wifiCount: Int) {
        bumpLocation().addCell(cellCount).addWifi(wifiCount)
    }

    fun add(day: StatDay) {
        locations += day.locations
        wifi += day.wifi
        cell += day.cell
        minutes += day.minutes
        uploaded += day.uploaded
    }

    fun addCell(value: Int): StatDay {
        cell += value
        return this
    }

    fun addWifi(value: Int): StatDay {
        wifi += value
        return this
    }

    fun addMinutes(value: Int): StatDay {
        minutes += value
        return this
    }

    fun bumpLocation(): StatDay {
        locations++
        return this
    }

    fun age(value: Int): Int {
        age += value
        return age
    }

    operator fun plusAssign(days: MutableList<StatDay>) {
        for (d in days) add(d)
    }

    //STAG CONSTRUCTOR AND GETTERS AND SETTERS//

    internal constructor()

    internal fun getUpload(): Long = uploaded

    internal fun setUpload(upload: Long) {
        this.uploaded = upload
    }
}
