package com.adsamcik.signalcollector.tracker.data.collection

import com.adsamcik.signalcollector.tracker.data.session.TrackerSession

data class CollectionDataEcho(val location: Location, val collectionData: CollectionData, val session: TrackerSession)