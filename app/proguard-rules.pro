# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in D:\Stuff\AndroidSDK/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# R2 round-6 perf review (P3): the previous catch-all `-keep class com.adsamcik.tracker.**`
# disabled tree-shaking + obfuscation for the entire app and ballooned the release APK.
# It has been removed in favour of the narrow, explicit keep rules below. The components
# that ACTUALLY require keeping are covered by:
#
#   * AndroidManifest entries (Application, Activities, Receivers, Services, ContentProviders)
#     — aapt auto-generates `-keep` rules for every manifest-declared class. This covers
#     `MainActivityCompose`, `BootReceiver`, the Glance widget receivers, etc.
#   * Hilt's bundled R8 rules (see hilt-android AAR `proguard.txt`) cover generated
#     components and `@HiltAndroidApp` / `@AndroidEntryPoint` / `@HiltViewModel`
#     classes, plus `@EntryPoint` / `@InstallIn` interfaces with `allowobfuscation,
#     allowshrinking` — class literals get rewritten alongside obfuscation.
#   * Moshi + kotlinx-serialization rules (below) already keep `@JsonClass`-generated
#     adapters and `@Serializable` companions / serializers.
#   * Navigation routes are explicitly kept (see kotlinx-serialization section).
#
# The only Tracker-Android reflection paths we found in `app/src/main` are safe under
# obfuscation: `ModuleInitializerCoordinator` reads `KClass.qualifiedName` purely for
# deterministic sorting; the value is never used as a class lookup key. If a future
# change introduces reflection-by-name (e.g. `Class.forName`, `getDeclaredMethod`), add
# a NARROW keep next to it, not a global catch-all. The `release_nominify` build variant
# exists for debugging suspected R8 stripping regressions without touching this file.
#
# As a safety net for any `@dagger.hilt.EntryPoint` interfaces declared on `app/`
# classes (e.g. `BootReceiver.BootReceiverEntryPoint`, `TrackerWidgetEntryPoint`),
# keep them explicitly — Hilt's bundled rules apply globally, this just makes the
# contract obvious at the app level. `allowshrinking` keeps R8 free to remove unused
# entry points; `allowobfuscation` keeps it free to rename them.
-keep,allowshrinking,allowobfuscation @dagger.hilt.EntryPoint interface com.adsamcik.tracker.** { *; }

# Keep stack traces useful for local Tracebox diagnostics (no remote crash reporter).
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception

# JSR 305 annotations are for embedding nullability information.
-dontwarn javax.annotation.**

-keepclasseswithmembers class * {
    @com.squareup.moshi.* <methods>;
}

-keep @com.squareup.moshi.JsonQualifier interface * { *; }

# Enum field names are used by the integrated EnumJsonAdapter.
# Annotate enums with @JsonClass(generateAdapter = false) to use them with Moshi.
-keepclassmembernames @com.squareup.moshi.JsonClass class * extends java.lang.Enum {
    <fields>;
}

# The name of @JsonClass types is used to look up the generated adapter.
-keepnames @com.squareup.moshi.JsonClass class * { *; }

# Retain generated JsonAdapters if annotated type is retained.
-if @com.squareup.moshi.JsonClass class *
-keep class <1>JsonAdapter {
    <init>(...);
    <fields>;
}

# New errors, they don't seem to cause any problems
-dontwarn edu.umd.cs.findbugs.annotations.Nullable
-dontwarn java.lang.invoke.StringConcatFactory

# kotlinx.serialization - Navigation routes use @Serializable
-keepattributes RuntimeVisibleAnnotations
-keep class kotlinx.serialization.** { *; }
-keepclassmembers @kotlinx.serialization.Serializable class * {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.adsamcik.tracker.app.ui.navigation.** { *; }

# Relocated from :stats:api consumer-rules.pro (the AGP KMP library plugin drops consumerProguardFiles).
-keep class com.adsamcik.tracker.stats.api.** { *; }
