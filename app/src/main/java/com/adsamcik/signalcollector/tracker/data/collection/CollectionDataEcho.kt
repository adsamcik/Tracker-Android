package com.adsamcik.signalcollector.tracker.data.collection

import com.adsamcik.signalcollector.common.data.Location
import com.adsamcik.signalcollector.tracker.data.session.TrackerSession

data class CollectionDataEcho(val location: Location, val collectionData: CollectionData, val session: TrackerSession) {
	constructor(location: android.location.Location, collectionData: CollectionData, session: TrackerSession) : this(Location(location), collectionData, session)
}