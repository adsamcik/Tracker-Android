# sbase module ProGuard rules

-keep public class com.adsamcik.tracker.*

# Keep all extension functions in this module (used by dependent modules)
-keep class com.adsamcik.tracker.shared.base.extension.** { *; }

# --- Moshi @JsonClass data classes ---
# Classes annotated with @JsonClass(generateAdapter = true) use generated adapters
# that reference fields by name. Keep annotated class names and their fields.
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}
-keepnames @com.squareup.moshi.JsonClass class *
-if @com.squareup.moshi.JsonClass class *
-keep class <1>JsonAdapter {
    <init>(...);
    <fields>;
}

# --- Room entities and DAOs ---
# Room compiler generates implementations; keep entity class names for schema stability
-keep @androidx.room.Entity class * { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Dao interface * { *; }

# --- Room TypeConverters ---
-keep class com.adsamcik.tracker.shared.base.database.converter.** { *; }

-dontwarn java.lang.invoke.StringConcatFactory