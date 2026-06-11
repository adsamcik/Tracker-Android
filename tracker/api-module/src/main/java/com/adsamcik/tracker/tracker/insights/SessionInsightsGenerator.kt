package com.adsamcik.tracker.tracker.insights

import com.adsamcik.tracker.shared.base.data.TrackerSession

interface SessionInsightsGenerator {
	suspend fun generate(session: TrackerSession): List<SessionInsight>
}
