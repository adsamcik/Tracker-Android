# Tracker module consumer ProGuard rules
# These are automatically applied to any module that depends on tracker

# --- Notification components ---
# NotificationComponents use this::class.java.simpleName as preference keys stored in Room.
# The app's R8 pass must preserve these class names.
-keepnames class * extends com.adsamcik.tracker.tracker.notification.TrackerNotificationComponent

# --- Collection trigger components ---
# TrackerTimerManager uses ::class.java.simpleName as preference keys.
-keepnames class * extends com.adsamcik.tracker.tracker.component.CollectionTriggerComponent

# --- Tracker API surface ---
# Keep tracker API classes used by the app module
-keep class com.adsamcik.tracker.tracker.api.** { *; }

# --- Hilt entry points accessed via EntryPoints.get() ---
-keep class * extends dagger.hilt.internal.GeneratedComponent { *; }
