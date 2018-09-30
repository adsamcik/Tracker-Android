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

-dontwarn okhttp3.**
-dontwarn okio.**

# Platform calls Class.forName on types which do not exist on Android to determine platform.
-dontnote retrofit2.Platform
# Platform used when running on Java 8 VMs. Will not be used at runtime.
-dontwarn retrofit2.Platform$Java8
# Retain generic type information for use by reflection by converters and adapters.
-keepattributes Signature
# Retain declared checked exceptions for use by a Proxy instance.
-keepattributes Exceptions

-keep public class com.adsamcik.signalcollector.data.** {
  public private <fields>;
  public <methods>;
}

-keep public class com.adsamcik.signalcollector.signin.User {
	public private <fields>;
	public <methods>;
}

-keep public class com.adsamcik.signalcollector.signin.UserData {
	public private <fields>;
	public <methods>;
}

-keep public class com.adsamcik.signalcollector.signin.NetworkInfo {
	public private <fields>;
	public <methods>;
}


-keep public class com.adsamcik.signalcollector.signin.NetworkPreferences {
	public private <fields>;
	public <methods>;
}

-keep public class com.adsamcik.signalcollector.signin.UserJson {
	public private <fields>;
	public <methods>;
}


-keep class kotlin.Metadata { *; }

# Crashlytics
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception

-keep class com.crashlytics.** { *; }
-dontwarn com.crashlytics.**

-assumenosideeffects class android.util.Log {
    public static *** d(...);
}

-keep public class com.adsamcik.signalcollector.network.JwtData { *; }



# JSR 305 annotations are for embedding nullability information.
-dontwarn javax.annotation.**

-keepclasseswithmembers class * {
    @com.squareup.moshi.* <methods>;
}

-keep @com.squareup.moshi.JsonQualifier interface *

# Enum field names are used by the integrated EnumJsonAdapter.
# Annotate enums with @JsonClass(generateAdapter = false) to use them with Moshi.
-keepclassmembernames @com.squareup.moshi.JsonClass class * extends java.lang.Enum {
    <fields>;
}

# The name of @JsonClass types is used to look up the generated adapter.
-keepnames @com.squareup.moshi.JsonClass class *

# Retain generated JsonAdapters if annotated type is retained.
-if @com.squareup.moshi.JsonClass class *
-keep class <1>JsonAdapter {
    <init>(...);
    <fields>;
}
