import org.gradle.api.JavaVersion

/**
 * Centralized Android configuration constants.
 * Used across all module build.gradle.kts files.
 */
object Android {
    const val COMPILE_VERSION = 37
    const val TARGET_VERSION = 37
    const val MIN_VERSION = 26
    const val BUILD_TOOLS_VERSION = "37.0.0-rc2"
    
    const val JAVA_VERSION = 17
    val javaTarget: JavaVersion = JavaVersion.VERSION_17
}
