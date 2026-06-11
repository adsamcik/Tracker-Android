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


# Export automation depends on shared Clock abstraction provided by :sbase. The
# class is packaged via module dependency, but R8 doesn't see it when shrinking
# the standalone impexp release artifact, so suppress the warning.

# Keep export automation scaffolding even if the release variant is not yet wired
# from the app module. This allows unit tests to reference the classes while we
# iterate on the feature.
-keep class com.adsamcik.tracker.impexp.exporter.automation.** { *; }
-dontwarn com.adsamcik.tracker.shared.base.time.Clock
