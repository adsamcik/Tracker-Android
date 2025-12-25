-keep public class com.adsamcik.tracker.*

# Keep all extension functions in this module (used by dependent modules)
-keep class com.adsamcik.tracker.shared.base.extension.** { *; }

-dontwarn java.lang.invoke.StringConcatFactory