import org.gradle.api.JavaVersion

/**
 * Android specific build properties
 */
object Android {
	const val min: Int = 23
	const val compile: Int = 31
	const val target: Int = 31

	const val buildTools: String = "31.0.0"

	val javaTarget: JavaVersion = JavaVersion.VERSION_1_8
	const val jvmTarget: String = "1.8"
}
