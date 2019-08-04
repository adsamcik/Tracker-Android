package com.adsamcik.signalcollector.tracker.data.collection

import com.adsamcik.signalcollector.common.data.Location
import com.adsamcik.signalcollector.common.data.TrackerSession

data class CollectionDataEcho(val collectionData: CollectionData, val session: TrackerSession)