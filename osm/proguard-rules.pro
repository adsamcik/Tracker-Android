# OSM module proguard rules
# Mirrors consumer-rules.pro for in-module test/release builds.
-keep class crosby.binary.** { *; }
-keep class de.topobyte.osm4j.** { *; }
-dontwarn de.topobyte.osm4j.**
-dontwarn crosby.binary.**
