package com.adsamcik.signalcollector.tracker.data.collection

import com.adsamcik.signalcollector.common.data.TrackerSession
import java.io.Serializable

data class CollectionDataEcho(val collectionData: CollectionData, val session: TrackerSession)