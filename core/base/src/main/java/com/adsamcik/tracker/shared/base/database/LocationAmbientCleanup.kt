package com.adsamcik.tracker.shared.base.database

/** Caller must commit the ambient consent/retention fence in this same Room transaction. */
suspend fun AppDatabase.removeLocationAmbientPurposeInCurrentTransaction() {
	val dao = locationPurposeDao()
	dao.removeAmbientAssociations()
	dao.deleteUnassociatedAmbientObservations()
	dao.deleteUnassociatedAmbientDecisions()
	dao.deleteUnassociatedSamples()
	dao.deleteAmbientCurationState()
}

/** Retention removes only the invalid purpose; a retained capture association keeps its sample. */
suspend fun AppDatabase.pruneLocationAmbientPurposeInCurrentTransaction(retainedFromMs: Long) {
	val dao = locationPurposeDao()
	dao.removeAmbientAssociationsBefore(retainedFromMs)
	dao.deleteUnassociatedAmbientObservations(retainedFromMs)
	dao.deleteUnassociatedAmbientDecisions(retainedFromMs)
	dao.deleteUnassociatedSamples()
	// Checkpoints contain coordinate-bearing curation state, so do not retain an old anchor.
	dao.clearAmbientCurationCheckpoints()
}
