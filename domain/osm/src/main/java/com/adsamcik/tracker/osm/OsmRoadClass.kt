package com.adsamcik.tracker.osm

/**
 * Driveable OSM `highway=*` values that the importer accepts.
 *
 * Anything not in this enum is silently dropped during import — pedestrian
 * paths, cycleways, tracks, footways, etc. are intentionally excluded because
 * they would otherwise inflate the on-device road graph by 3-5x while never
 * being matched against a vehicle speed sample.
 *
 * The `defaultMaxspeedKmh` is the fallback used when a way has no
 * `maxspeed=*` tag, no `zone:maxspeed`, and no `source:maxspeed` we can
 * resolve. Numbers chosen to match the most common European urban/rural mix;
 * country-aware defaults are a Phase 2b concern.
 */
enum class OsmRoadClass(
	val osmValue: String,
	val defaultMaxspeedKmh: Int,
) {
	MOTORWAY("motorway", 130),
	MOTORWAY_LINK("motorway_link", 80),
	TRUNK("trunk", 110),
	TRUNK_LINK("trunk_link", 70),
	PRIMARY("primary", 90),
	PRIMARY_LINK("primary_link", 60),
	SECONDARY("secondary", 80),
	SECONDARY_LINK("secondary_link", 50),
	TERTIARY("tertiary", 70),
	TERTIARY_LINK("tertiary_link", 40),
	UNCLASSIFIED("unclassified", 50),
	RESIDENTIAL("residential", 50),
	LIVING_STREET("living_street", 20),
	SERVICE("service", 30),
	ROAD("road", 50),
	;

	companion object {
		private val byValue: Map<String, OsmRoadClass> =
			entries.associateBy { it.osmValue }

		/**
		 * Returns the matching [OsmRoadClass] for an OSM `highway=*` tag value,
		 * or `null` when the tag is missing / not driveable.
		 */
		fun fromOsmValue(value: String?): OsmRoadClass? =
			value?.let { byValue[it] }
	}
}
