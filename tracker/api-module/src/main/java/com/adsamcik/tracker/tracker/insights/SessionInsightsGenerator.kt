package com.adsamcik.tracker.tracker.insights

import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot

interface SessionInsightsGenerator {
	suspend fun generate(session: TrackerSessionSnapshot): List<SessionInsight>
}
