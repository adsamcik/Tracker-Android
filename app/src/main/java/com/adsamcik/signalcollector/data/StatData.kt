package com.adsamcik.signalcollector.data

import com.vimeo.stag.UseStag

@UseStag
class StatData {
    var id: String
    var value: String

    internal constructor(id: String, value: String) {
        this.id = id
        this.value = value
    }
}
