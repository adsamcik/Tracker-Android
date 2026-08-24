# Tracker module ProGuard rules
# Applied during this module's own R8 pass (isMinifyEnabled = true)

# Preserve line numbers for crash stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Notification components ---
# NotificationComponents use this::class.java.simpleName as preference keys stored in Room.
# R8 renaming would change the key, breaking stored notification preference lookups.
-keepnames class * extends com.adsamcik.tracker.tracker.notification.TrackerNotificationComponent

# --- Collection trigger components ---
# TrackerTimerManager uses ::class.java.simpleName as preference keys for timer selection.
-keepnames class * extends com.adsamcik.tracker.tracker.component.CollectionTriggerComponent

# --- Cross-module class references ---
# Keep classes from shared modules referenced by tracker (prevent R8 missing class errors)
-keep class com.adsamcik.tracker.shared.base.** { *; }
-keep class com.adsamcik.tracker.shared.preferences.** { *; }
-keep class com.adsamcik.tracker.shared.utils.** { *; }
-keep class com.adsamcik.tracker.stats.api.** { *; }
-keep class com.adsamcik.tracker.stats.engine.** { *; }

# --- Hilt entry points ---
# Hilt generates these and accesses them reflectively via EntryPoints.get()
-keep class com.adsamcik.tracker.tracker.api.BackgroundTrackingApi$BackgroundTrackingApiEntryPoint { *; }
-keep class com.adsamcik.tracker.tracker.module.TrackerModuleInitializer$TrackerModuleInitializerEntryPoint { *; }
-keep class com.adsamcik.tracker.tracker.api.TrackerServiceApi$TrackerServiceApiEntryPoint { *; }
-keep class com.adsamcik.tracker.tracker.component.consumer.post.NotificationComponent$NotificationComponentEntryPoint { *; }
-keep class com.adsamcik.tracker.tracker.notification.component.SkiNotificationComponent$NotificationComponentEntryPoint { *; }

# --- Suppress warnings for classes provided by dependencies ---
-dontwarn java.lang.invoke.StringConcatFactory
