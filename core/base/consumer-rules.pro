# Consumer ProGuard rules for sbase module
# These rules will be automatically applied to any module that depends on sbase

# Keep all database-related classes that are used by other modules
-keep class com.adsamcik.tracker.shared.base.database.** { *; }
-keep interface com.adsamcik.tracker.shared.base.database.dao.** { *; }

# Keep data classes that are used across modules
-keep class com.adsamcik.tracker.shared.base.data.** { *; }

# Keep Time class used by other modules
-keep class com.adsamcik.tracker.shared.base.Time { *; }

# Keep extension functions (Kotlin generates *Kt classes for top-level functions)
-keep class com.adsamcik.tracker.shared.base.extension.StringExtensionsKt { *; }
-keep class com.adsamcik.tracker.shared.base.extension.** { *; }

# Keep other utility classes that might be referenced
-keep class com.adsamcik.tracker.shared.base.** { *; }

# Prevent warnings for classes that are used via reflection or companion objects
-dontwarn com.adsamcik.tracker.shared.base.database.ObjectBaseDatabase
-dontwarn com.adsamcik.tracker.shared.base.database.dao.BaseDao
-dontwarn com.adsamcik.tracker.shared.base.Time
-dontwarn com.adsamcik.tracker.shared.base.data.**
