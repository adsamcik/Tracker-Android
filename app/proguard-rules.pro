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

-keep class com.adsamcik.tracker.**

# Crashlytics
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception

-keep class com.crashlytics.** { *; }
-dontwarn com.crashlytics.**

-assumenosideeffects class android.util.Log {
    public static *** d(...);
}


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

-keep class javax.xml.stream.**  { *; }
-keep class io.jenetics.jpx.** { *; }
-keep class com.bea.xml.stream.** { *; }

# New errors, they don't seem to cause any problems
-dontwarn edu.umd.cs.findbugs.annotations.Nullable
-dontwarn javax.xml.transform.stax.StAXResult
-dontwarn javax.xml.transform.stax.StAXSource
-dontwarn java.lang.invoke.StringConcatFactory
