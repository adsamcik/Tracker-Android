# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-keep public class com.adsamcik.tracker.*

# Keep classes from shared modules referenced by tracker (prevent R8 missing class errors)
-keep class com.adsamcik.tracker.logger.** { *; }
-keep class com.adsamcik.tracker.shared.base.** { *; }
-keep class com.adsamcik.tracker.shared.preferences.** { *; }
-keep class com.adsamcik.tracker.shared.utils.** { *; }

# Ensure specific classes referenced via reflection or in generic code are retained
-keep class com.adsamcik.tracker.logger.LogData { *; }
-keep class com.adsamcik.tracker.logger.Logger { *; }
-keep class com.adsamcik.tracker.logger.Reporter { *; }
-keep class com.adsamcik.tracker.shared.base.database.PreferenceDatabase$Companion { *; }
-keep class com.adsamcik.tracker.shared.base.database.dao.NotificationPreferenceDao { *; }
-keep class com.adsamcik.tracker.shared.base.database.data.NotificationPreference { *; }
-keep class com.adsamcik.tracker.shared.base.misc.NonNullLiveData { *; }
-keep class com.adsamcik.tracker.shared.base.misc.NonNullLiveMutableData { *; }
-keep class com.adsamcik.tracker.shared.base.service.CoreService { *; }
-keep class com.adsamcik.tracker.shared.preferences.MutablePreferences { *; }
-keep class com.adsamcik.tracker.shared.preferences.Preferences { *; }
-keep class com.adsamcik.tracker.shared.preferences.Preferences$Companion { *; }
-keep class com.adsamcik.tracker.shared.utils.activity.ManageActivity { *; }
-keep class com.adsamcik.tracker.shared.utils.activity.ManageActivity$EditDataInstance { *; }
-keep class com.adsamcik.tracker.shared.utils.module.TrackerUpdateReceiver { *; }
-keep class com.adsamcik.tracker.shared.utils.style.marker.IViewChange { *; }
