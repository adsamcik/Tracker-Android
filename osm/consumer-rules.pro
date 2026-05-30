# OSM module consumer rules
# osm4j uses reflection via protobuf-generated code – keep protobuf message classes.
-keep class crosby.binary.** { *; }
-keep class de.topobyte.osm4j.** { *; }
-dontwarn de.topobyte.osm4j.**
-dontwarn crosby.binary.**
