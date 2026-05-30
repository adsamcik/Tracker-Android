package com.adsamcik.tracker.stats.data.speed

import com.adsamcik.tracker.osm.speed.OsmSpeedLimitSource
import com.adsamcik.tracker.shared.base.database.dao.OsmImportDao
import com.adsamcik.tracker.stats.api.speed.SpeedLimitSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [SpeedLimitSource] dispatcher used in production.
 *
 * Decision tree on every call:
 *  - If the call is missing coordinates → fall back to [FixedSpeedLimitSource].
 *  - If the user has imported at least one OSM region → ask
 *    [OsmSpeedLimitSource]; on a hit (way within 50 m) return the road's
 *    limit, otherwise fall back to [FixedSpeedLimitSource].
 *  - Otherwise → [FixedSpeedLimitSource].
 *
 * The OSM count is read fresh on each call (a sub-millisecond `COUNT(*)`
 * query) so that disabling all OSM regions in Settings takes effect for the
 * very next sample without restart.
 *
 * Stays strictly offline — both backing sources are local.
 */
@Singleton
class DefaultSpeedLimitSource @Inject constructor(
	private val fixed: FixedSpeedLimitSource,
	private val osm: OsmSpeedLimitSource,
	private val osmImportDao: OsmImportDao,
) : SpeedLimitSource {

	override suspend fun limitMpsAt(epochMs: Long, latE7: Int?, lonE7: Int?): Double {
		if (latE7 == null || lonE7 == null) {
			return fixed.limitMpsAt(epochMs, latE7, lonE7)
		}
		if (osmImportDao.count() == 0) {
			return fixed.limitMpsAt(epochMs, latE7, lonE7)
		}
		val osmLimit = osm.findRoadLimitMps(latE7, lonE7)
		return osmLimit ?: fixed.limitMpsAt(epochMs, latE7, lonE7)
	}
}
