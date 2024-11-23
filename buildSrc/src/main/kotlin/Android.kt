import org.gradle.api.JavaVersion

/**
 * Android specific build properties
 */
object Android {
	const val MIN_VERSION: Int = 24
	const val COMPILE_VERSION: Int = 35
	const val TARGET_VERSION: Int = 35

	const val BUILD_TOOLS_VERSION: String = "35.0.0"

	const val JAVA_VERSION: Int = 17
	val javaTarget: JavaVersion = JavaVersion.toVersion(JAVA_VERSION)
}
